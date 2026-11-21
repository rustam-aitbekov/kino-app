package kg.home.kino.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for oc.kg's internal JSON API (api.php, the same one the website uses).
 *
 * Request format: api.php?format=json&action[0]=Video.getMovie&movie_id[0]=24030
 * Values are encoded like JavaScript's escape() (Cyrillic as %uXXXX), as the site's own
 * JsHttpRequest library does. They are sent in a POST body: Android 6's URL parser rejects
 * %uXXXX in a URL ("Invalid % sequence"), which broke searching in Russian.
 */
object OcApi {
    const val BASE = "https://oc.kg"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 6.0.1; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0 Safari/537.36"

    // ---------------------------------------------------------------- public API

    suspend fun bestsellers(): List<Category> {
        val r = call("Video.getBestsellers") as JSONObject
        return r.optJSONArray("bestsellers").objects().map { c ->
            Category(c.optString("name"), c.optJSONArray("movies").objects().map(::movieShort))
        }.filter { it.movies.isNotEmpty() }
    }

    suspend fun popular(): List<MovieShort> {
        val r = call("Video.getPopMovies") as JSONObject
        return r.optJSONArray("movies").objects().map(::movieShort)
    }

    /** Newest first by default. */
    /**
     * One page of the catalogue, newest first by default. Filters are optional and combine:
     * genre, country, or a selection ("Подборка"). [order] is the site's sort code (see CatalogFragment).
     */
    suspend fun catalog(
        offset: Int,
        size: Int,
        genreId: String? = null,
        order: String? = null,
        selectionId: String? = null,
        countryId: String? = null,
    ): CatalogPage {
        val r = call(
            "Video.getCatalog",
            "offset" to offset, "size" to size, "genre" to genreId, "selection" to selectionId,
            "country" to countryId, "order" to order,
        ) as JSONObject
        return CatalogPage(r.optString("total").toIntOrNull() ?: 0, r.optJSONArray("movies").objects().map(::movieShort))
    }

    suspend fun countries(): List<Genre> {
        val r = call("Video.getCountries") as JSONObject
        return r.optJSONArray("countries").objects()
            .map { Genre(it.optString("id"), it.optString("name"), it.optString("count").toIntOrNull() ?: 0) }
            .filter { it.count > 0 }
    }

    @Volatile
    private var selectionsCache: List<Selection>? = null

    /**
     * "Подборки" (TOP-20 lists). The API only returns ids, so the list is read from the template
     * the website itself uses to draw that page (id, picture, title), embedded in the home page.
     */
    suspend fun selections(): List<Selection> = selectionsCache ?: withContext(Dispatchers.IO) {
        val html = get("$BASE/")
        val start = html.indexOf("TEMPLATES.SELECTION")
        if (start < 0) throw IOException("подборки не найдены")
        val end = html.indexOf("\";", start).let { if (it < 0) html.length else it }
        val tpl = html.substring(start, end).replace("\\\"", "\"").replace("\\n", "\n").replace("\\/", "/")
        tpl.split("class=\"catalog-item\"").drop(1).mapNotNull { block ->
            val id = Regex("""selection/(\d+)/""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val img = Regex("""<img src="([^"]+)"""").find(block)?.groupValues?.get(1)
            val name = Regex("""class="catalog-name"><a [^>]*>([^<]+)</a>""").find(block)?.groupValues?.get(1)
                ?: Regex("""alt="([^"]+)"""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            Selection(id, cleanText(name), img?.let(::absolute))
        }.distinctBy { it.id }.also { if (it.isNotEmpty()) selectionsCache = it }
    }

    suspend fun genres(): List<Genre> {
        val r = call("Video.getGenres") as JSONObject
        return r.optJSONArray("genres").objects()
            .map { Genre(it.optString("id"), it.optString("name"), it.optString("count").toIntOrNull() ?: 0) }
            .filter { it.count > 0 }
    }

    suspend fun search(query: String): SearchResult {
        val r = call("Video.search", "query" to query) as JSONObject
        return SearchResult(r.optJSONArray("movies").objects().map(::movieShort))
    }

    suspend fun movie(id: String): MovieDetails {
        val r = call("Video.getMovie", "movie_id" to id) as JSONObject
        val m = r.getJSONObject("movie")
        val covers = m.optJSONArray("covers").objects()
        val files = flattenFiles(m.optJSONArray("files"))
        val frames = m.optJSONArray("files").objects()
            .flatMap { f -> f.optJSONArray("frames").strings() }
            .map(::absolute)
        return MovieDetails(
            id = m.optString("movie_id"),
            name = m.optString("name"),
            internationalName = m.optStringOrNull("international_name"),
            year = m.optStringOrNull("year"),
            description = m.optStringOrNull("description")?.let(::cleanText),
            genres = m.optJSONArray("genres").strings(),
            countries = m.optJSONArray("countries").strings(),
            directors = m.optJSONArray("directors").strings(),
            quality = m.optStringOrNull("quality"),
            translation = m.optJSONArray("translation").strings(),
            ratingImdb = m.optStringOrNull("rating_imdb_value"),
            ratingKp = m.optStringOrNull("rating_kinopoisk_value"),
            ratingOc = m.optStringOrNull("rating_local_value"),
            posterThumb = covers.firstOrNull()?.optStringOrNull("thumbnail")?.let(::poster),
            posterOriginal = covers.firstOrNull()?.optStringOrNull("original")?.let(::absolute),
            frames = frames,
            files = files,
            persons = m.optJSONArray("persones").objects().map { p ->
                val role = p.optJSONArray("roles").objects().firstOrNull()
                Person(
                    id = p.optString("person_id"),
                    name = p.optString("name"),
                    photo = p.optStringOrNull("photo")?.let(::absolute),
                    role = role?.optStringOrNull("role"),
                    character = role?.optStringOrNull("character"),
                )
            },
            similar = m.optJSONArray("other_movies").objects().map(::movieShort),
        )
    }

    /**
     * oc.kg's own rating for each film id (null = nobody has rated it yet). The popular and
     * bestseller lists don't include it, so it's read from the films themselves - batched,
     * [CHUNK] films per request (the API accepts several actions in one call).
     */
    suspend fun localRatings(ids: Collection<String>): Map<String, String?> = withContext(Dispatchers.IO) {
        val out = HashMap<String, String?>()
        for (chunk in ids.distinct().chunked(CHUNK)) {
            val url = StringBuilder("$BASE/api.php?format=json")
            chunk.forEachIndexed { i, id ->
                url.append("&action%5B$i%5D=Video.getMovie&movie_id%5B$i%5D=").append(jsEscape(id))
            }
            val results = JSONObject(get(url.toString())).getJSONArray("json")
            for (r in results.objects()) {
                if (r.optInt("status") != 200) continue
                val m = r.optJSONObject("response")?.optJSONObject("movie") ?: continue
                out[m.optString("movie_id")] = m.optStringOrNull("rating_local_value")
            }
        }
        out
    }

    private const val CHUNK = 20

    /**
     * Finds the direct video URL for a file. The player page (pl.php) lists the advert videos
     * (/background/...) and the film itself; we take the film. Falls back to building the URL
     * from the server path if the page can't be read.
     */
    suspend fun streamUrl(file: VideoFile): String = withContext(Dispatchers.IO) {
        file.license?.let { license ->
            try {
                val html = get(absolute(license))
                Regex("""src\s*:\s*["']([^"']+)["']""").findAll(html)
                    .map { it.groupValues[1] }
                    .firstOrNull { !it.contains("/background/") && (it.contains(".mp4") || it.contains(".m3u8") || it.contains("/video/")) }
                    ?.let { return@withContext absolute(it) }
            } catch (e: IOException) {
                // fall through to the path-based URL
            }
        }
        val path = file.path ?: throw IOException("no video link")
        "https://p1.oc.kg:8082" + path.removePrefix("/home/video") + "?an=1"
    }

    // ---------------------------------------------------------------- helpers

    /** Absolute URL for a site-relative path. */
    fun absolute(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> BASE + path
        else -> "$BASE/$path"
    }

    /**
     * The site keeps a 200px-wide thumbnail of every poster at the poster's own aspect ratio
     * (e.g. _120x180 -> _200x300, _120x171 -> _200x285). That's the sharpest list-size image.
     */
    fun poster(path: String): String {
        val m = Regex("""_(\d+)x(\d+)\.jpg$""").find(path) ?: return absolute(path)
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        if (w <= 0) return absolute(path)
        val h200 = Math.round(h * 200.0 / w).toInt()
        return absolute(path.substring(0, m.range.first) + "_200x$h200.jpg")
    }

    private fun movieShort(o: JSONObject) = MovieShort(
        id = o.optString("movie_id"),
        name = o.optString("name"),
        internationalName = o.optStringOrNull("international_name"),
        year = o.optStringOrNull("year"),
        poster = o.optStringOrNull("cover")?.let(::poster),
        description = o.optStringOrNull("description")?.let(::cleanText),
        ratingImdb = o.optStringOrNull("rating_imdb_value"),
        ratingKp = o.optStringOrNull("rating_kinopoisk_value"),
        ratingOc = o.optStringOrNull("rating_local_value"),
        // The exact cover the API returned always exists (small, but better than nothing).
        posterFallback = o.optStringOrNull("cover")?.let(::absolute),
    )

    private fun flattenFiles(arr: JSONArray?): List<VideoFile> {
        val out = ArrayList<VideoFile>()
        fun walk(a: JSONArray?) {
            for (f in a.objects()) {
                if (!f.optBoolean("is_dir") && f.optBoolean("active", true)) {
                    val meta = f.optJSONObject("metainfo")
                    out += VideoFile(
                        id = f.optString("file_id"),
                        name = f.optString("name"),
                        license = f.optJSONObject("links")?.optStringOrNull("license"),
                        path = f.optStringOrNull("path"),
                        durationSeconds = meta?.optLong("playtime_seconds") ?: 0L,
                        durationText = meta?.optStringOrNull("playtime"),
                        videoInfo = meta?.optJSONObject("video")?.optStringOrNull("label"),
                        audioInfo = meta?.optJSONArray("audio").objects().joinToString(", ") { it.optString("label") }
                            .ifBlank { null },
                    )
                }
                walk(f.optJSONArray("childs"))
            }
        }
        walk(arr)
        return out.filter { it.license != null || it.path != null }
    }

    private fun cleanText(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ")
        .replace("&quot;", "\"").replace("&laquo;", "«").replace("&raquo;", "»").replace("&hellip;", "…")
        .replace("&amp;", "&").trim()

    private suspend fun call(action: String, vararg params: Pair<String, Any?>): Any = withContext(Dispatchers.IO) {
        val body = StringBuilder("format=json&action%5B0%5D=").append(jsEscape(action))
        for ((k, v) in params) {
            if (v == null) continue
            body.append('&').append(jsEscape(k)).append("%5B0%5D=").append(jsEscape(v.toString()))
        }
        val root = JSONObject(post("$BASE/api.php", body.toString()))
        val r = root.getJSONArray("json").getJSONObject(0)
        if (r.optInt("status") != 200) throw IOException(r.optString("message", "API error"))
        r.get("response")
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Referer", "$BASE/")
            c.setRequestProperty("Accept-Language", "ru")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val bytes = body.toByteArray(Charsets.US_ASCII)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Referer", "$BASE/")
            c.setRequestProperty("Accept-Language", "ru")
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Same as JavaScript's escape() + "+" -> "%2B" (what the site's JsHttpRequest library sends). */
    fun jsEscape(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "@*_-./" -> sb.append(ch)
                ch.code < 256 -> sb.append('%').append(String.format("%02X", ch.code))
                else -> sb.append("%u").append(String.format("%04X", ch.code))
            }
        }
        return sb.toString()
    }
}

// ---------------------------------------------------------------- org.json helpers

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() && it != "null" } }
}

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() && it != "null" }
}
