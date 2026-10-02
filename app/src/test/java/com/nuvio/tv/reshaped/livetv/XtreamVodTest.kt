package com.nuvio.tv.reshaped.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.Reader

class XtreamVodTest {
    private val account = requireNotNull(XtreamVodAccount.from(LiveTvXtreamSettings("https://panel.example/iptv", "user /+", "p&/?#")))
    private val movie = XtreamVodItem("42", XtreamVodKind.Movie, "Dune (2021) HD", tmdbId = "438631", year = 2021, extension = "mkv")

    @Test
    fun `credentials are encoded once and server subpaths are preserved`() {
        val api = account.apiUrl("get_vod_info", "vod_id" to "42").toHttpUrl()
        assertEquals("/iptv/player_api.php", api.encodedPath)
        assertEquals("user /+", api.queryParameter("username"))
        assertEquals("p&/?#", api.queryParameter("password"))
        val playback = requireNotNull(account.playbackUrl(XtreamVodKind.Movie, "42", "mkv")).toHttpUrl()
        assertEquals(listOf("iptv", "movie", "user /+", "p&/?#", "42.mkv"), playback.pathSegments)
        assertFalse(account.toString().contains("p&/?#"))
        assertFalse(account.toString().contains("user /+"))
    }

    @Test
    fun `M3U get php links reuse the same decoded login`() {
        val fromM3u =
            requireNotNull(
                XtreamVodAccount.fromPlaylistUrl(
                    "https://panel.example/iptv/get.php?username=user%20%2F%2B&password=p%26%2F%3F%23&type=m3u_plus&output=ts",
                ),
            )
        assertEquals(account.apiUrl("get_series"), fromM3u.apiUrl("get_series"))
        assertNull(XtreamVodAccount.fromPlaylistUrl("https://panel.example/list.m3u?username=u&password=p"))
        assertNull(XtreamVodAccount.fromPlaylistUrl("file:///list.m3u"))
        assertNull(XtreamVodAccount.fromPlaylistUrl("https://panel.example/get.php?username=u&username=v&password=p"))
        assertNull(XtreamVodAccount.fromPlaylistUrl("https://panel.example/get.php?username=u"))
        assertNull(XtreamVodAccount.fromPlaylistUrl("https://u:p@panel.example/get.php?username=u&password=p"))
        assertNull(XtreamVodAccount.from(LiveTvSource("s", LiveTvSourceType.Stalker)))
    }

    @Test
    fun `API suffix and query on an explicit server URL do not duplicate the endpoint`() {
        val explicit = requireNotNull(XtreamVodAccount.from(LiveTvXtreamSettings("https://panel.example/player_api.php?old=1", "u", "p")))
        val api = explicit.apiUrl("get_series").toHttpUrl()
        assertEquals("/player_api.php", api.encodedPath)
        assertNull(api.queryParameter("old"))
        assertNull(XtreamVodAccount.from(LiveTvXtreamSettings("file:///tmp/a", "u", "p")))
    }

    @Test
    fun `unknown containers and invalid IDs never generate guessed live URLs`() {
        assertNull(account.playbackUrl(XtreamVodKind.Movie, "42", null))
        assertNull(account.playbackUrl(XtreamVodKind.Movie, "42", "../ts"))
        assertNull(account.playbackUrl(XtreamVodKind.Movie, "../42", "mp4"))
        assertNull(account.playbackUrl(XtreamVodKind.Movie, "0", "mp4"))
        val dotAccount = requireNotNull(XtreamVodAccount.from(LiveTvXtreamSettings("https://panel.example", "u", "..")))
        assertNull(dotAccount.playbackUrl(XtreamVodKind.Movie, "42", "mp4"))
        assertTrue(requireNotNull(account.playbackUrl(XtreamVodKind.Series, "99", "MKV")).endsWith("/99.mkv"))
    }

    @Test
    fun `movie catalogs accept numeric and string IDs but skip malformed and duplicate entries`() {
        val items =
            readXtreamVodCatalog(
                """[
                {"stream_id":42,"name":"Dune (2021) HD","tmdb_id":"438631","container_extension":"mkv","unknown":{"large":[1,2,3]}},
                {"stream_id":"42","name":"duplicate"},
                {"stream_id":"43","name":"Other","tmdb":0,"release_date":"2024-01-02"},
                {"stream_id":0,"name":"invalid"}, {"stream_id":44,"name":null}, null
            ]""".reader(),
                XtreamVodKind.Movie,
            )
        assertEquals(listOf("42", "43"), items.map { it.id })
        assertEquals(2021, items[0].year)
        assertEquals("438631", items[0].tmdbId)
        assertEquals(2024, items[1].year)
        assertNull(items[1].tmdbId)
    }

    @Test
    fun `large catalogs skip unknown nested metadata without losing entries`() {
        val payload =
            (1..10_000).joinToString(prefix = "[", postfix = "]") { id ->
                """{"stream_id":$id,"name":"Movie $id","unused":{"nested":[{"x":null}]}}"""
            }
        val items = readXtreamVodCatalog(payload.reader(), XtreamVodKind.Movie)
        assertEquals(10_000, items.size)
        assertEquals("10000", items.last().id)
        assertTrue(readXtreamVodCatalog("[]".reader(), XtreamVodKind.Movie).isEmpty())
    }

    @Test
    fun `series catalogs use series IDs and releaseDate`() {
        val series =
            readXtreamVodCatalog(
                """[{"series_id":"12","stream_id":"99","name":"Series","tmdb":"123","releaseDate":"2020-02-03"}]""".reader(),
                XtreamVodKind.Series,
            ).single()
        assertEquals("12", series.id)
        assertEquals("123", series.tmdbId)
        assertEquals(2020, series.year)
    }

    @Test
    fun `authentication objects are errors rather than empty catalogs`() {
        assertThrows(IOException::class.java) {
            readXtreamVodCatalog("""{"user_info":{"auth":0}}""".reader(), XtreamVodKind.Movie)
        }
        assertThrows(Exception::class.java) { readXtreamVodCatalog("[{".reader(), XtreamVodKind.Movie) }
    }

    @Test
    fun `TMDB identity takes precedence over title and media namespaces stay separate`() {
        assertTrue(movie.matches(XtreamVodQuery(XtreamVodKind.Movie, tmdbId = "438631")))
        assertFalse(movie.matches(XtreamVodQuery(XtreamVodKind.Movie, "999", setOf("Dune"), 2021)))
        assertFalse(movie.matches(XtreamVodQuery(XtreamVodKind.Series, tmdbId = "438631")))
    }

    @Test
    fun `title fallback requires exact normalized title and matching year`() {
        val noId = movie.copy(tmdbId = null)
        assertTrue(noId.matches(XtreamVodQuery(XtreamVodKind.Movie, titles = setOf("DUNE"), year = 2021)))
        assertFalse(noId.matches(XtreamVodQuery(XtreamVodKind.Movie, titles = setOf("Dune"), year = 1984)))
        assertFalse(noId.matches(XtreamVodQuery(XtreamVodKind.Movie, titles = setOf("Dune"))))
        assertFalse(noId.matches(XtreamVodQuery(XtreamVodKind.Movie, titles = setOf("Dune Part Two"), year = 2021)))
        assertEquals("amélie", vodTitleKey("Amélie (2001) [FHD]"))
        assertEquals("se7en", vodTitleKey("Se7en"))
    }

    @Test
    fun `movie info uses the panel ID not direct source and carries explicit subtitles only`() {
        val video =
            readXtreamVodMovie(
                """{"movie_data":{"stream_id":42,"name":"Dune","container_extension":"mp4","direct_source":"https://upstream.invalid/a"},
                "info":{"subtitles":[
                    {"id":"tr","url":"https://subs.example/tr.vtt","lang":"tur"},
                    {"id":"en","file":"https://subs.example/en.srt","language":"eng"},
                    {"url":"file:///etc/passwd","lang":"eng"},
                    {"url":"javascript:alert(1)","lang":"eng"},
                    {"url":"https://subs.example/missing-language.srt"}
                ]}}""".reader(),
                movie,
            )
        val source = LiveTvSource("source1", LiveTvSourceType.Xtream, url = "https://panel.example", name = "My IPTV")
        val stream = requireNotNull(video.toStream(account, source))
        assertTrue(requireNotNull(stream.url).contains("/movie/"))
        assertTrue(requireNotNull(stream.url).endsWith("/42.mp4"))
        assertEquals("IPTV · My IPTV", stream.addonName)
        assertEquals(listOf("tur", "eng"), stream.subtitles.map { it.lang })
        assertTrue(stream.subtitles.all { it.isStreamProvided && it.headers == null })
        assertEquals("iptv-source1", stream.behaviorHints?.bingeGroup)
    }

    @Test
    fun `movie ID mismatch is rejected and embedded subtitles need no guessed sidecar`() {
        assertThrows(IOException::class.java) {
            readXtreamVodMovie("""{"movie_data":{"stream_id":99}}""".reader(), movie)
        }
        val video = readXtreamVodMovie("""{"movie_data":{"stream_id":42},"info":{"subtitles":null}}""".reader(), movie)
        assertEquals("mkv", video.extension)
        assertTrue(video.subtitles.isEmpty())
    }

    @Test
    fun `episode selection uses episode numbers and rejects contradictory season metadata`() {
        val videos =
            readXtreamVodEpisode(
                """{"episodes":{"1":[
                {"id":"100","episode_num":2,"season":1,"title":"Second","container_extension":"mkv","info":{"tmdb_id":555}},
                {"id":"101","episode_num":1,"season":1,"title":"First","container_extension":"mp4"},
                {"id":"102","episode_num":2,"season":2,"title":"Wrong season","container_extension":"mp4"},
                {"id":"100","episode_num":2,"title":"Duplicate","container_extension":"mkv"}
            ],"2":[{"id":"200","episode_num":2,"season":2,"container_extension":"mkv"}]}}""".reader(),
                1,
                2,
            )
        assertEquals(listOf("100"), videos.map { it.id })
        assertTrue(
            requireNotNull(account.playbackUrl(videos.single().kind, videos.single().id, videos.single().extension)).endsWith("/100.mkv"),
        )
        assertEquals(1, videos.single().season)
        assertEquals(2, videos.single().episode)
    }

    @Test
    fun `missing episodes and season zero specials are handled explicitly`() {
        assertTrue(readXtreamVodEpisode("""{"episodes":{}}""".reader(), 1, 1).isEmpty())
        assertTrue(readXtreamVodEpisode("""{"episodes":[]}""".reader(), 1, 1).isEmpty())
        val special =
            readXtreamVodEpisode(
                """{"episodes":{"0":[{"id":1,"episode_num":1,"container_extension":"mkv"}]}}""".reader(),
                0,
                1,
            ).single()
        assertEquals(0, special.season)
        assertThrows(IllegalArgumentException::class.java) { readXtreamVodEpisode("{}".reader(), 1, 0) }
    }

    @Test
    fun `client issues only the intended API actions`() =
        runTest {
            val transport =
                FixtureTransport(
                    mutableListOf(
                        """[{"stream_id":42,"name":"Dune","container_extension":"mkv"}]""",
                        """[{"series_id":12,"name":"Show"}]""",
                        """{"movie_data":{"stream_id":42}}""",
                        """{"episodes":{"1":[{"id":90,"episode_num":2,"container_extension":"mkv"}]}}""",
                    ),
                )
            val client = XtreamVodClient(transport)
            client.catalog(account, XtreamVodKind.Movie)
            val series = client.catalog(account, XtreamVodKind.Series).single()
            client.movie(account, movie)
            client.episode(account, series, 1, 2)
            assertEquals(
                listOf("get_vod_streams", "get_series", "get_vod_info", "get_series_info"),
                transport.urls.map {
                    it.toHttpUrl().queryParameter("action")
                },
            )
            assertEquals("42", transport.urls[2].toHttpUrl().queryParameter("vod_id"))
            assertEquals("12", transport.urls[3].toHttpUrl().queryParameter("series_id"))
        }

    @Test
    fun `client sanitizes credential bearing errors without swallowing cancellation`() =
        runTest {
            val failed =
                XtreamVodClient(
                    object : XtreamVodTransport {
                        override suspend fun <T> read(
                            url: String,
                            parse: (Reader) -> T,
                        ): T = throw IOException(url)
                    },
                )
            try {
                failed.catalog(account, XtreamVodKind.Movie)
                fail("Expected request failure")
            } catch (error: IOException) {
                assertEquals("Xtream VOD request failed", error.message)
                assertNull(error.cause)
            }
            val cancelled =
                XtreamVodClient(
                    object : XtreamVodTransport {
                        override suspend fun <T> read(
                            url: String,
                            parse: (Reader) -> T,
                        ): T = throw CancellationException("cancelled")
                    },
                )
            try {
                cancelled.catalog(account, XtreamVodKind.Movie)
                fail("Expected cancellation")
            } catch (error: CancellationException) {
                assertEquals("cancelled", error.message)
            }
        }

    private class FixtureTransport(
        private val responses: MutableList<String>,
    ) : XtreamVodTransport {
        val urls = mutableListOf<String>()

        override suspend fun <T> read(
            url: String,
            parse: (Reader) -> T,
        ): T {
            urls += url
            return parse(responses.removeAt(0).reader())
        }
    }
}
