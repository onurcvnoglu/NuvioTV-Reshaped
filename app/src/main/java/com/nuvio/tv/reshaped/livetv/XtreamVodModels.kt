package com.nuvio.tv.reshaped.livetv

import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Subtitle
import java.text.Normalizer
import java.util.Locale

internal const val IPTV_VOD_BINGE_GROUP_PREFIX = "iptv-"

internal enum class XtreamVodKind { Movie, Series }

/** A compact catalog entry; series IDs identify a series, never a playable episode. */
internal data class XtreamVodItem(
    val id: String,
    val kind: XtreamVodKind,
    val title: String,
    val tmdbId: String? = null,
    val year: Int? = null,
    val extension: String? = null,
)

internal data class XtreamVodQuery(
    val kind: XtreamVodKind,
    val tmdbId: String? = null,
    val titles: Set<String> = emptySet(),
    val year: Int? = null,
)

/** IDs are authoritative. Title fallback needs a year to avoid remakes and similarly named shows. */
internal fun XtreamVodItem.matches(query: XtreamVodQuery): Boolean {
    if (kind != query.kind) return false
    if (tmdbId != null && query.tmdbId != null) return tmdbId == query.tmdbId
    if (query.year == null || year != query.year) return false
    val key = vodTitleKey(title)
    return key.isNotEmpty() && query.titles.any { vodTitleKey(it) == key }
}

private val trailingQuality = Regex("""\s*(?:\[|\()?\b(?:4K|UHD|FHD|HD|SD|1080p|720p|2160p)\b(?:\]|\))?\s*$""", RegexOption.IGNORE_CASE)
private val trailingYear = Regex("""\s*[\[(]((?:19|20)\d{2})[\])]\s*$""")

internal fun vodTitleYear(title: String): Int? =
    trailingYear
        .find(titleWithoutQuality(title))
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

private fun titleWithoutQuality(title: String): String {
    var clean = title.trim()
    while (true) {
        val next = clean.replace(trailingQuality, "").trim()
        if (next == clean) return clean
        clean = next
    }
}

internal fun vodTitleKey(title: String): String =
    Normalizer
        .normalize(
            titleWithoutQuality(title).replace(trailingYear, ""),
            Normalizer.Form.NFC,
        ).lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

internal data class XtreamVodSubtitle(
    val id: String,
    val url: String,
    val language: String,
)

internal data class XtreamVodVideo(
    val id: String,
    val kind: XtreamVodKind,
    val title: String,
    val extension: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val subtitles: List<XtreamVodSubtitle> = emptyList(),
    /** Movie/show identity from details, never the episode's own TMDB ID. */
    val tmdbId: String? = null,
) {
    fun toStream(
        account: XtreamVodAccount,
        source: LiveTvSource,
    ): Stream? {
        val url = account.playbackUrl(kind, id, extension) ?: return null
        val providerName = "IPTV · ${source.label}"
        return Stream(
            name = providerName,
            title = title,
            description = title,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints =
                StreamBehaviorHints(
                    notWebReady = true,
                    // The source ID, not its credentials, groups episodes for next-episode playback.
                    bingeGroup = "$IPTV_VOD_BINGE_GROUP_PREFIX${source.id}",
                    countryWhitelist = null,
                    proxyHeaders = ProxyHeaders(request = LIVE_TV_STREAM_HEADERS, response = null),
                ),
            addonName = providerName,
            addonLogo = null,
            subtitles =
                subtitles.distinctBy { it.url to it.language }.mapIndexed { index, subtitle ->
                    Subtitle(
                        id = "iptv-${source.id}-$id-$index",
                        url = subtitle.url,
                        lang = subtitle.language,
                        addonName = providerName,
                        addonLogo = null,
                        isStreamProvided = true,
                        // Account authentication stays in the playback URL; never forward it to subtitle hosts.
                        headers = null,
                    )
                },
        )
    }
}
