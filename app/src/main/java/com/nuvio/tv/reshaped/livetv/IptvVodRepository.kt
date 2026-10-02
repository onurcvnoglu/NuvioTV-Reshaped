package com.nuvio.tv.reshaped.livetv

import android.content.Context
import com.nuvio.tv.core.plugin.resolvePluginSeasonEpisode
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A request-owned snapshot. Only the fingerprint, never account credentials, enters cache keys. */
internal class IptvVodSource(
    val source: LiveTvSource,
    val account: XtreamVodAccount,
    val fingerprint: String,
) {
    val providerName: String get() = "IPTV · ${source.label}"
    override fun toString(): String = "IptvVodSource(${source.id}, credentials=redacted)"
}

internal class IptvVodRepository(
    private val context: Context,
    private val tmdbService: TmdbService,
    private val client: XtreamVodClient = XtreamVodClient(),
) {
    private data class CatalogKey(val sourceFingerprint: String, val kind: XtreamVodKind)
    private data class Catalog(val items: List<XtreamVodItem>, val savedAtMs: Long)
    private val catalogs = LinkedHashMap<CatalogKey, Catalog>(8, 0.75f, true)
    private val cacheMutex = Mutex()
    // Coalesces catalog misses without holding the cache-state lock during network IO.
    private val catalogLoadMutex = Mutex()
    private val sourcePermits = Semaphore(2)
    private var allowedSources = emptySet<String>()
    private var generation = 0L

    suspend fun captureSources(profileId: Int, forceRefresh: Boolean): List<IptvVodSource> {
        val sources = withContext(Dispatchers.IO) {
            LiveTvStorage(context, profileId).sources().mapNotNull { source ->
                XtreamVodAccount.from(source)?.let { account ->
                    IptvVodSource(source, account, fingerprint(profileId, source, account))
                }
            }
        }
        cacheMutex.withLock {
            val current = sources.mapTo(HashSet()) { it.fingerprint }
            if (forceRefresh || current != allowedSources) {
                generation++
                catalogs.entries.removeAll { forceRefresh || it.key.sourceFingerprint !in current }
                allowedSources = current
            }
            pruneCatalogs()
        }
        return sources
    }

    suspend fun stream(
        profileId: Int,
        sources: List<IptvVodSource>,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        isActiveProfile: () -> Boolean,
        onResult: suspend (AddonStreams) -> Unit,
        onFailure: (String) -> Unit,
    ) = coroutineScope {
        val kind = kindFor(type) ?: return@coroutineScope
        if (sources.isEmpty() || !isActiveProfile()) return@coroutineScope
        val canonicalId = videoId.removePrefix("tv:")
        val idParts = canonicalId.removePrefix("tmdb:").removePrefix("series:").split(':')
        val hasEpisodeCoordinates = idParts.size == 3 &&
            (idParts.first().toLongOrNull() != null || idParts.first().matches(Regex("tt\\d+")))
        val (requestedSeason, requestedEpisode) = resolvePluginSeasonEpisode(
            canonicalId,
            season ?: if (hasEpisodeCoordinates) idParts[1].toIntOrNull() else null,
            episode ?: if (hasEpisodeCoordinates) idParts[2].toIntOrNull() else null,
        )
        if (kind == XtreamVodKind.Series && (requestedSeason == null || requestedSeason < 0 || requestedEpisode == null || requestedEpisode <= 0)) {
            return@coroutineScope
        }
        val tmdbId = tmdbService.ensureTmdbId(canonicalId, type)?.toLongOrNull()?.takeIf { it > 0 }?.toString()
            ?: return@coroutineScope
        val idQuery = XtreamVodQuery(kind = kind, tmdbId = tmdbId)
        val titleQuery = async(start = CoroutineStart.LAZY) {
            val identity = tmdbService.fetchMediaIdentity(tmdbId, type)
            idQuery.copy(titles = identity?.titles.orEmpty(), year = identity?.year)
        }
        try {
            sources.map { source ->
                launch {
                    try {
                        sourcePermits.withPermit {
                            if (!isActiveProfile()) return@withPermit
                            val completed = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                                val items = catalog(source, kind)
                                val identified = items.filter { it.matches(idQuery) }
                                val matches = if (identified.isNotEmpty()) identified else {
                                    val fallback = titleQuery.await()
                                    items.filter { it.matches(fallback) }
                                }
                                val streams = resolveStreams(source, matches.take(MAX_VARIANTS), tmdbId, requestedSeason, requestedEpisode)
                                // A source removed or edited while loading must not publish stale account URLs.
                                if (streams.isNotEmpty() && isActiveProfile() && stillConfigured(profileId, source)) {
                                    onResult(AddonStreams(source.providerName, null, streams))
                                }
                                true
                            }
                            if (completed == null) onFailure(source.providerName)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        onFailure(source.providerName)
                    }
                }
            }.joinAll()
        } finally {
            // An unused LAZY deferred is still a child of this scope.
            titleQuery.cancel()
        }
    }

    private suspend fun resolveStreams(
        source: IptvVodSource,
        matches: List<XtreamVodItem>,
        tmdbId: String,
        season: Int?,
        episode: Int?,
    ): List<Stream> {
        val results = matches.map { item ->
            runCatching {
                val videos = when (item.kind) {
                    XtreamVodKind.Movie -> listOf(client.movie(source.account, item))
                    XtreamVodKind.Series -> client.episode(source.account, item, requireNotNull(season), requireNotNull(episode))
                }
                videos.filter { it.tmdbId == null || it.tmdbId == tmdbId }.mapNotNull { it.toStream(source.account, source.source) }
            }.onFailure { if (it is CancellationException) throw it }
        }
        val streams = results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.url }
        if (streams.isEmpty() && results.any { it.isFailure }) throw IOException("Xtream VOD request failed")
        return streams
    }

    private suspend fun catalog(source: IptvVodSource, kind: XtreamVodKind): List<XtreamVodItem> {
        val key = CatalogKey(source.fingerprint, kind)
        cacheMutex.withLock {
            pruneCatalogs()
            catalogs[key]?.let { return it.items }
        }
        return catalogLoadMutex.withLock {
            val loadGeneration = cacheMutex.withLock {
                pruneCatalogs()
                catalogs[key]?.let { return it.items }
                generation
            }
            val items = client.catalog(source.account, kind)
            cacheMutex.withLock {
                if (generation == loadGeneration && source.fingerprint in allowedSources && items.size <= MAX_CACHED_ITEMS) {
                    catalogs[key] = Catalog(items, nowMs())
                    pruneCatalogs()
                }
            }
            items
        }
    }

    /** Called only under cacheMutex; both retained entry count and total item count are bounded. */
    private fun pruneCatalogs() {
        val now = nowMs()
        catalogs.entries.removeAll { now - it.value.savedAtMs >= CATALOG_TTL_MS }
        while (catalogs.size > MAX_CATALOGS || catalogs.values.sumOf { it.items.size } > MAX_CACHED_ITEMS) {
            val iterator = catalogs.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private suspend fun stillConfigured(profileId: Int, source: IptvVodSource): Boolean = withContext(Dispatchers.IO) {
        LiveTvStorage(context, profileId).sources().any { current ->
            current.id == source.source.id && XtreamVodAccount.from(current)?.let {
                fingerprint(profileId, current, it) == source.fingerprint
            } == true
        }
    }

    private fun fingerprint(profileId: Int, source: LiveTvSource, account: XtreamVodAccount): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(profileId.toString(), source.id, source.label, account.apiUrl("get_vod_streams")).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        fun supports(type: String): Boolean = kindFor(type) != null
        private fun kindFor(type: String): XtreamVodKind? = when (type.lowercase(java.util.Locale.ROOT)) {
            "movie", "film" -> XtreamVodKind.Movie
            "series", "tv", "show", "tvshow" -> XtreamVodKind.Series
            else -> null
        }
        private const val CATALOG_TTL_MS = 15 * 60 * 1_000L
        private const val SOURCE_TIMEOUT_MS = 90_000L
        private const val MAX_CATALOGS = 6
        private const val MAX_CACHED_ITEMS = 50_000
        private const val MAX_VARIANTS = 8
    }
}
