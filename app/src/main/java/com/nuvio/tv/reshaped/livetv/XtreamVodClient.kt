package com.nuvio.tv.reshaped.livetv

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.Reader

internal interface XtreamVodTransport {
    suspend fun <T> read(
        url: String,
        parse: (Reader) -> T,
    ): T
}

private object LiveTvVodTransport : XtreamVodTransport {
    override suspend fun <T> read(
        url: String,
        parse: (Reader) -> T,
    ): T =
        LiveTvHttp.stream(url, LIVE_TV_PLAYLIST_HEADERS) { input ->
            input.bufferedReader(Charsets.UTF_8).use(parse)
        }
}

/** No account storage or cache here: callers supply the active profile's saved source snapshot. */
internal class XtreamVodClient(
    private val transport: XtreamVodTransport = LiveTvVodTransport,
) {
    suspend fun catalog(
        account: XtreamVodAccount,
        kind: XtreamVodKind,
    ): List<XtreamVodItem> =
        request(account.apiUrl(if (kind == XtreamVodKind.Movie) "get_vod_streams" else "get_series")) {
            readXtreamVodCatalog(it, kind)
        }

    suspend fun movie(
        account: XtreamVodAccount,
        item: XtreamVodItem,
    ): XtreamVodVideo {
        require(item.kind == XtreamVodKind.Movie && item.id.toLongOrNull()?.let { it > 0 } == true)
        return request(account.apiUrl("get_vod_info", "vod_id" to item.id)) { readXtreamVodMovie(it, item) }
    }

    suspend fun episode(
        account: XtreamVodAccount,
        item: XtreamVodItem,
        season: Int,
        episode: Int,
    ): List<XtreamVodVideo> {
        require(item.kind == XtreamVodKind.Series && item.id.toLongOrNull()?.let { it > 0 } == true)
        require(season >= 0 && episode > 0)
        return request(account.apiUrl("get_series_info", "series_id" to item.id)) {
            readXtreamVodEpisode(it, season, episode)
        }
    }

    private suspend fun <T> request(
        url: String,
        parse: (Reader) -> T,
    ): T =
        try {
            transport.read(url, parse)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Transport/parser errors may contain the request URL or panel payload, both carrying secrets.
            throw IOException("Xtream VOD request failed")
        }
}
