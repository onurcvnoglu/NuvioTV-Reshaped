package com.nuvio.tv.reshaped.livetv

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.Reader

/** Catalogs are streamed one entry at a time; unknown nested fields never build a JSON tree. */
internal fun readXtreamVodCatalog(
    input: Reader,
    kind: XtreamVodKind,
): List<XtreamVodItem> {
    val items = ArrayList<XtreamVodItem>()
    val seen = HashSet<String>()
    JsonReader(input).use { reader ->
        if (reader.peek() != JsonToken.BEGIN_ARRAY) throw IOException("Invalid Xtream VOD catalog")
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            val fields = HashMap<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in CATALOG_FIELDS && reader.peek() in setOf(JsonToken.STRING, JsonToken.NUMBER)) {
                    fields[name] = reader.nextString().trim()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            val id = fields[if (kind == XtreamVodKind.Movie) "stream_id" else "series_id"]?.positiveId() ?: continue
            val title = fields["name"]?.takeIf(String::isNotBlank) ?: continue
            if (!seen.add(id)) continue
            items +=
                XtreamVodItem(
                    id = id,
                    kind = kind,
                    title = title,
                    tmdbId = fields["tmdb_id"]?.positiveId() ?: fields["tmdb"]?.positiveId(),
                    year =
                        listOf(fields["year"], fields["releaseDate"], fields["release_date"], fields["releasedate"])
                            .firstNotNullOfOrNull(::releaseYear) ?: vodTitleYear(title),
                    extension = fields["container_extension"],
                )
        }
        reader.endArray()
        if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Invalid Xtream VOD catalog")
    }
    return items
}

private val CATALOG_FIELDS =
    setOf(
        "stream_id",
        "series_id",
        "name",
        "tmdb_id",
        "tmdb",
        "year",
        "releaseDate",
        "release_date",
        "releasedate",
        "container_extension",
    )

private fun String.positiveId(): String? = toLongOrNull()?.takeIf { it > 0 }?.toString()

private fun releaseYear(value: String?): Int? = value?.take(4)?.toIntOrNull()?.takeIf { it in 1800..2199 }

internal fun readXtreamVodMovie(
    input: Reader,
    item: XtreamVodItem,
): XtreamVodVideo {
    require(item.kind == XtreamVodKind.Movie)
    val root = readDetails(input)
    val data = root.objectOrNull("movie_data") ?: throw IOException("Invalid Xtream movie details")
    val id = data.text("stream_id")?.positiveId() ?: throw IOException("Invalid Xtream movie ID")
    if (id != item.id) throw IOException("Xtream movie ID mismatch")
    return XtreamVodVideo(
        id = id,
        kind = XtreamVodKind.Movie,
        title = data.text("name") ?: item.title,
        extension = data.text("container_extension") ?: item.extension,
        subtitles = readSubtitles(root.objectOrNull("info")) + readSubtitles(data),
        tmdbId = root.objectOrNull("info")?.tmdbId() ?: data.tmdbId(),
    )
}

/** Selects actual season/episode numbers, never an array position or an episode's TMDB ID. */
internal fun readXtreamVodEpisode(
    input: Reader,
    season: Int,
    episode: Int,
): List<XtreamVodVideo> {
    require(season >= 0 && episode > 0)
    val root = readDetails(input)
    val episodes = root["episodes"] ?: throw IOException("Invalid Xtream series details")
    // Empty series are commonly sent as [] rather than {}.
    if (episodes.isJsonArray && episodes.asJsonArray.size() == 0) return emptyList()
    if (!episodes.isJsonObject) throw IOException("Invalid Xtream series episodes")
    val videos = ArrayList<XtreamVodVideo>()
    val seen = HashSet<String>()
    for ((seasonKey, values) in episodes.asJsonObject.entrySet()) {
        if (seasonKey.toIntOrNull() != season || !values.isJsonArray) continue
        for (value in values.asJsonArray) {
            if (!value.isJsonObject) continue
            val data = value.asJsonObject
            val actualSeason = if (data.has("season")) data.text("season")?.toIntOrNull() else seasonKey.toIntOrNull()
            if (actualSeason != season || data.text("episode_num")?.toIntOrNull() != episode) continue
            val id = data.text("id")?.positiveId() ?: continue
            if (!seen.add(id)) continue
            videos +=
                XtreamVodVideo(
                    id = id,
                    kind = XtreamVodKind.Series,
                    title = data.text("title") ?: "S${season}E$episode",
                    extension = data.text("container_extension"),
                    season = season,
                    episode = episode,
                    subtitles = readSubtitles(data.objectOrNull("info")) + readSubtitles(data),
                    tmdbId = root.objectOrNull("info")?.tmdbId(),
                )
        }
    }
    return videos
}

private fun readDetails(input: Reader): JsonObject =
    input.use {
        val value = JsonParser.parseReader(it)
        if (!value.isJsonObject) throw IOException("Invalid Xtream VOD details")
        value.asJsonObject
    }

private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.tmdbId(): String? = text("tmdb_id")?.positiveId() ?: text("tmdb")?.positiveId()

private fun JsonObject.text(name: String): String? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

/** Optional provider extension. No standard Xtream subtitle action or guessed subtitle URL exists. */
private fun readSubtitles(info: JsonObject?): List<XtreamVodSubtitle> {
    val values = info?.get("subtitles")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyList()
    return values
        .mapNotNull { value ->
            if (!value.isJsonObject) return@mapNotNull null
            val subtitle = value.asJsonObject
            val url = (subtitle.text("url") ?: subtitle.text("file"))?.toHttpUrlOrNull() ?: return@mapNotNull null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return@mapNotNull null
            val language = subtitle.text("lang") ?: subtitle.text("language") ?: return@mapNotNull null
            XtreamVodSubtitle(
                id = subtitle.text("id") ?: url.toString(),
                url = url.toString(),
                language = language,
            )
        }.distinctBy { it.url to it.language }
}
