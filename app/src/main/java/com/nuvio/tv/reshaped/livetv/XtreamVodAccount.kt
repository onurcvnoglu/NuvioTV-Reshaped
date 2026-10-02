package com.nuvio.tv.reshaped.livetv

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Reuses a saved Live TV login without putting credentials into diagnostic output. */
internal class XtreamVodAccount private constructor(
    private val baseUrl: HttpUrl,
    private val username: String,
    private val password: String,
) {
    override fun toString(): String = "XtreamVodAccount(${baseUrl.host}, credentials=redacted)"

    fun apiUrl(
        action: String,
        parameter: Pair<String, String>? = null,
    ): String =
        baseUrl
            .newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
            .addQueryParameter("action", action)
            .apply { parameter?.let { addQueryParameter(it.first, it.second) } }
            .build()
            .toString()

    fun playbackUrl(
        kind: XtreamVodKind,
        id: String,
        extension: String?,
    ): String? {
        if (id.toLongOrNull()?.let { it > 0 } != true) return null
        // URL builders normalize dot segments instead of preserving them as account credentials.
        if (username in setOf(".", "..") || password in setOf(".", "..")) return null
        // Never substitute a live TS format for an unknown VOD container.
        val container = extension?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in CONTAINERS } ?: return null
        return baseUrl
            .newBuilder()
            .addPathSegment(if (kind == XtreamVodKind.Movie) "movie" else "series")
            .addPathSegment(username)
            .addPathSegment(password)
            .addPathSegment("$id.$container")
            .build()
            .toString()
    }

    companion object {
        private val CONTAINERS = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m3u8")

        fun from(source: LiveTvSource): XtreamVodAccount? =
            when (source.type) {
                LiveTvSourceType.Xtream -> from(source.xtream)
                LiveTvSourceType.M3u -> fromPlaylistUrl(source.url)
                LiveTvSourceType.Stalker -> null
            }

        fun from(settings: LiveTvXtreamSettings): XtreamVodAccount? {
            if (!settings.isConfigured) return null
            val url = settings.serverUrl.trim().toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val builder = url.newBuilder().query(null).fragment(null)
            if (url.pathSegments.last().equals("player_api.php", ignoreCase = true)) {
                builder.removePathSegment(url.pathSegments.lastIndex)
            }
            return XtreamVodAccount(builder.build().withTrailingSlash(), settings.username, settings.password)
        }

        /** Only conventional Xtream get.php links imply a panel login, not arbitrary M3U files. */
        fun fromPlaylistUrl(value: String): XtreamVodAccount? {
            val url = value.trim().toHttpUrlOrNull() ?: return null
            if (!url.pathSegments.last().equals("get.php", ignoreCase = true)) return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            val username = url.queryParameterValues("username").singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val password = url.queryParameterValues("password").singleOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val base =
                url
                    .newBuilder()
                    .removePathSegment(url.pathSegments.lastIndex)
                    .query(null)
                    .fragment(null)
                    .build()
                    .withTrailingSlash()
            return XtreamVodAccount(base, username, password)
        }

        private fun HttpUrl.withTrailingSlash(): HttpUrl = if (encodedPath.endsWith('/')) this else newBuilder().addPathSegment("").build()
    }
}
