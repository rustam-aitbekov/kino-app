package kg.home.kino.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for www.ts.kg (TV series). The site has no public API, so lists and show pages are
 * read from the HTML. Playback uses the same JSON endpoint as the site's own player:
 * /show/episode/episode.json?episode=ID, which returns an HLS link on the video server
 * (no advert videos). The JSON endpoints answer only with "X-Requested-With: XMLHttpRequest".
 */
object TsApi {
    const val BASE = "https://www.ts.kg"

    /** Categories shown on the home screen, in a TV-friendly order. */
    val categories = listOf(
        TsCategory(null, "Все сериалы"),
        TsCategory("tv_series", "Зарубежные"),
        TsCategory("russian_tv_series", "Российские"),
        TsCategory("turkish_tv_series", "Турецкие"),
        TsCategory("korean", "Корейские"),
        TsCategory("anime", "Аниме"),
        TsCategory("cartoon_for_kids", "Мультфильмы"),
        TsCategory("tv_series_for_kids", "Детские сериалы"),
        TsCategory("european", "Европейские"),
        TsCategory("british_tv_series", "Британские"),
        TsCategory("ukrainian_tv_series", "Украинские"),
        TsCategory("cartoon_tv_series", "Мульты для взрослых"),
        TsCategory("japanese", "Японские"),
        TsCategory("chinese", "Китайские"),
        TsCategory("thai", "Тайские"),
        TsCategory("indian", "Индийские"),
        TsCategory("kyrgyz_tv_series", "Кыргызские"),
        TsCategory("other", "Другие азиатские"),
        TsCategory("tv_show", "ТВ-шоу"),
        TsCategory("russian_tv_show", "Российские ТВ-шоу"),
        TsCategory("sport", "Спорт"),
        TsCategory("full", "Полный метр"),
        TsCategory("films", "Франшизы"),
        TsCategory("anime_full", "Аниме-фильмы"),
        TsCategory("netflix_movies", "Netflix"),
        TsCategory("hbo", "HBO"),
        TsCategory("prime_video", "Prime Video"),
        TsCategory("apple_tv", "Apple TV"),
        TsCategory("disney_plus", "Disney+"),
    )

    data class Home(val latest: List<TsShow>, val newShows: List<TsShow>, val popular: List<TsShow>)

    // ---------------------------------------------------------------- public API

    suspend fun home(): Home = withContext(Dispatchers.IO) {
        val html = get("$BASE/")
        Home(
            latest = parseNews(section(html, "id=\"index-page-news-list\"", "id=\"app-index-shows-new\"")),
            newShows = parseCards(section(html, "id=\"app-index-shows-new-m\"", "role=\"tabpanel\"")),
            popular = parseCards(section(html, "id=\"app-index-shows-top-m\"", "<footer")),
        )
    }

    /** First page of a category (or of all shows when [category] has no slug). */
    fun firstPageUrl(category: TsCategory): String =
        if (category.slug == null) "$BASE/show?sortBy=updated" else "$BASE/category/${category.slug}"

    /** First page of the catalogue (/show) with the given filters. */
    fun catalogUrl(f: TsFilter): String {
        val q = listOfNotNull(
            f.categoryId?.let { "categoryId=$it" },
            f.genreId?.let { "genreId=$it" },
            f.countryCode?.let { "countryCode=" + URLEncoder.encode(it, "UTF-8") },
            f.year?.let { "year=$it" },
            "sortBy=" + (f.sortBy ?: "updated"),
        ).joinToString("&")
        return "$BASE/show?$q"
    }

    fun selectionUrl(s: TsSelection) = "$BASE/selection/${s.slug}"

    @Volatile
    private var optionsCache: TsFilterOptions? = null

    /** Category / genre / country / year menus of the site's catalogue form. */
    suspend fun filterOptions(): TsFilterOptions = optionsCache ?: withContext(Dispatchers.IO) {
        val html = get("$BASE/show")
        fun options(name: String): List<TsOption> {
            val select = Regex("""<select[^>]*name="$name"[^>]*>(.*?)</select>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return emptyList()
            return Regex("""<option value="([^"]*)"[^>]*>([^<]*)</option>""").findAll(select)
                .map { TsOption(it.groupValues[1], text(it.groupValues[2]).let(::stripFlag)) }
                .filter { it.id.isNotEmpty() && it.id != "0" && it.name.isNotEmpty() }
                .distinctBy { it.id }
                .toList()
        }
        TsFilterOptions(
            // "Новости" and "Тестовая" are site-internal categories.
            categories = options("categoryId").filter { it.id != "45" && it.id != "36" },
            genres = options("genreId"),
            countries = options("countryCode"),
            years = options("year"),
        ).also { optionsCache = it }
    }

    @Volatile
    private var selectionsCache: List<TsSelection>? = null

    /** All "Подборки" (the /selection list has a few pages). */
    suspend fun selections(): List<TsSelection> = selectionsCache ?: withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, TsSelection>()
        val re = Regex(
            """<a href="/selection/([^"/?]+)">\s*<img src="([^"]+)"[^>]*?title="([^"]*)"""",
        )
        var page = 1
        while (page <= 10) {
            val html = get(if (page == 1) "$BASE/selection" else "$BASE/selection?page=$page")
            val before = out.size
            for (m in re.findAll(html)) {
                val slug = m.groupValues[1]
                out.getOrPut(slug) { TsSelection(slug, decode(m.groupValues[3]), absolute(m.groupValues[2])) }
            }
            if (out.size == before || !html.contains("/selection?page=${page + 1}")) break
            page++
        }
        out.values.toList().also { if (it.isNotEmpty()) selectionsCache = it }
    }

    /** "🇦🇺 Австралия" -> "Австралия" (flag emoji don't render on the TV). */
    private fun stripFlag(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp !in 0x1F1E6..0x1F1FF) sb.appendCodePoint(cp)   // regional-indicator letters
            i += Character.charCount(cp)
        }
        return sb.toString().trim()
    }

    /** One page (about 50 shows) of a listing; follow [TsPage.nextUrl] for more. */
    suspend fun page(url: String): TsPage = withContext(Dispatchers.IO) {
        val html = get(url)
        val next = Regex("""class="next-page" href="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.let { absolute(decode(it)) }
        // Most listings mark the grid with id="shows"; some selection pages only with the class.
        val grid = section(html, "id=\"shows\"", "<footer")
            .ifEmpty { section(html, "class=\"app-shows-container\"", "<footer") }
            .ifEmpty { html }
        TsPage(parseCards(grid), next)
    }

    suspend fun search(query: String): List<TsShow> = withContext(Dispatchers.IO) {
        val json = get("$BASE/shows/search/" + URLEncoder.encode(query, "UTF-8").replace("+", "%20"), xhr = true)
        val arr = org.json.JSONArray(json)
        arr.objects().mapNotNull { o ->
            val slug = slugOf(o.optString("url")) ?: return@mapNotNull null
            // "Гангстерленд (MobLand)" -> title + original name as the second line
            val full = o.optString("name")
            val m = Regex("""^(.*?)\s*\(([^()]*)\)$""").find(full)
            TsShow(
                slug = slug,
                name = m?.groupValues?.get(1)?.ifBlank { null } ?: full,
                poster = posterUrl(slug),
                info = m?.groupValues?.get(2),
            )
        }
    }

    suspend fun show(slug: String): TsShowDetails = withContext(Dispatchers.IO) {
        val html = get("$BASE/show/$slug")
        val title = Regex("""id="h-show-title" data-title="([^"]*)" data-id="(\d+)"""").find(html)
            ?: throw IOException("сериал не найден")
        val header = section(html, "<div class=\"app-h\">", "<ul class=\"app-show-tags\">")
        val tags = section(html, "<ul class=\"app-show-tags\">", "<div class=\"app-show\">")
        val shots = section(html, "id=\"show-shots\"", "id=\"show-shots-mobile\"")

        TsShowDetails(
            id = title.groupValues[2],
            slug = slug,
            name = decode(title.groupValues[1]),
            originalName = Regex("""<div class="app-h2">([^<]*)</div>""").find(header)?.groupValues?.get(1)
                ?.let(::decode)?.ifBlank { null },
            year = Regex("""<div class="app-h3">\s*(\d{4})""").find(header)?.groupValues?.get(1),
            voice = Regex("""<div class="text-info">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(header)
                ?.groupValues?.get(1)?.let(::text)?.ifBlank { null },
            description = Regex("""<div class="app-show-info">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)
                ?.groupValues?.get(1)?.let(::paragraphs)?.ifBlank { null },
            category = Regex("""href="/category/[^"]+"><strong>([^<]+)</strong>""").find(tags)?.groupValues?.get(1)
                ?.let(::decode),
            genres = Regex("""href="/genre/[^"]+">([^<]+)</a>""").findAll(tags).map { decode(it.groupValues[1]) }.toList(),
            countries = Regex("""class="app-show-tags-flag"[^>]*title="([^"]+)"""").findAll(tags)
                .map { decode(it.groupValues[1]) }.toList(),
            poster = posterUrl(slug),
            posterLarge = "$BASE/posters2/$slug.png",
            frames = Regex("""<a href="(/shots2/[^"]+)"""").findAll(shots).map { absolute(it.groupValues[1]) }.toList(),
            seasons = parseSeasons(html),
            voices = parseVoices(html),
        )
    }

    suspend fun episode(id: String): TsStream = withContext(Dispatchers.IO) {
        val o = JSONObject(get("$BASE/show/episode/episode.json?episode=$id", xhr = true))
        val video = o.optJSONObject("video") ?: throw IOException("нет видео")
        val url = video.optStringOrNull("url") ?: throw IOException("нет ссылки на видео")
        TsStream(
            url = absolute(url),
            mimeType = video.optStringOrNull("mimetype"),
            subtitles = video.optStringOrNull("subtitles")?.let(::absolute),
            durationSeconds = o.optLong("duration"),
        )
    }

    fun posterUrl(slug: String) = "$BASE/posters/$slug.png"

    // ---------------------------------------------------------------- HTML parsing

    /** Show cards (<div class="app-shows-item-full">) in a piece of a listing page. */
    private fun parseCards(html: String): List<TsShow> {
        val out = LinkedHashMap<String, TsShow>()
        for (block in html.split("<div class=\"app-shows-item-full\">").drop(1)) {
            val slug = Regex("""href="/show/([^"/?]+)""").find(block)?.groupValues?.get(1) ?: continue
            val name = Regex("""class="app-shows-card-title">[^<]*<a [^>]*>([^<]*)</a>""").find(block)?.groupValues?.get(1)
                ?: Regex("""alt="([^"]*)"""").find(block)?.groupValues?.get(1) ?: continue
            val poster = Regex("""<img src="([^"]+)"""").find(block)?.groupValues?.get(1)
            val info = Regex("""class="app-shows-card-tooltip">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(block)
                ?.groupValues?.get(1)?.let(::text)
                // keep it short: year + first two genres
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.joinToString(", ")
            val voice = Regex("""fa-microphone"></span>\s*([^<]+)""").find(block)?.groupValues?.get(1)?.trim()
            out.getOrPut(slug) { TsShow(slug, decode(name), poster?.let(::absolute) ?: posterUrl(slug), info, voice) }
        }
        return out.values.toList()
    }

    /** "Новые серии" list on the home page: show name + "2 сезон, 10 серия". One entry per show. */
    private fun parseNews(html: String): List<TsShow> {
        val out = LinkedHashMap<String, TsShow>()
        val re = Regex(
            """class="news-link app-news-link" href="/show/([^"/]+)[^"]*"[^>]*>([^<]*)</a>\s*<span class="text-muted clearfix"><small>([^<]*)</small>"""
        )
        for (m in re.findAll(html)) {
            val slug = m.groupValues[1]
            val info = decode(m.groupValues[3]).substringBefore(" — ").trim()
            out.getOrPut(slug) { TsShow(slug, decode(m.groupValues[2]).trim(), posterUrl(slug), info) }
        }
        return out.values.toList()
    }

    private fun parseSeasons(html: String): List<TsSeason> {
        val full = section(html, "class=\"app-show-seasons-section-full\"", "<footer")
        val epRe = Regex(
            """<a href="/show/[^"]+" class="text-primary" id="app-show-episode-title-(\d+)"><strong class="app-show-episode-title">([^<]*)</strong>([^<]*)</a>(?:\s*<small class="text-muted">\|\s*([^<]*)</small>)?"""
        )
        return full.split("data-season=\"").drop(1).mapNotNull { chunk ->
            val season = decode(chunk.substringBefore('"'))
            val episodes = epRe.findAll(chunk).map { m ->
                TsEpisode(
                    id = m.groupValues[1],
                    season = season,
                    number = decode(m.groupValues[2]).trim(),
                    name = decode(m.groupValues[3]).trim().removePrefix(":").trim().ifBlank { null },
                    duration = m.groupValues[4].trim().ifBlank { null },
                )
            }.toList()
            if (episodes.isEmpty()) null else TsSeason(season, episodes)
        }
    }

    private fun parseVoices(html: String): List<TsVoice> {
        val box = section(html, "Выбрать озвучку", "</div>")
        if (box.isEmpty()) return emptyList()
        val re = Regex("""<a href="/show/([^"/]+)" class="btn[^"]*">(.*?)</a>|<button[^>]*active[^>]*>(.*?)</button>""",
            RegexOption.DOT_MATCHES_ALL)
        return re.findAll(box).map { m ->
            if (m.groupValues[1].isNotEmpty()) TsVoice(m.groupValues[1], text(m.groupValues[2]), false)
            else TsVoice(null, text(m.groupValues[3]), true)
        }.filter { it.name.isNotBlank() }.toList()
    }

    /** The part of [html] from [start] up to the next [end] (or the end), or "" if [start] is missing. */
    private fun section(html: String, start: String, end: String): String {
        val i = html.indexOf(start)
        if (i < 0) return ""
        val j = html.indexOf(end, i + start.length)
        return if (j < 0) html.substring(i) else html.substring(i, j)
    }

    private fun slugOf(url: String): String? =
        Regex("""/show/([^/?"]+)""").find(url)?.groupValues?.get(1)

    /** Tags removed, entities decoded, whitespace collapsed. */
    private fun text(s: String) = decode(s.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    /** Like [text] but keeps paragraph breaks. */
    private fun paragraphs(s: String) = s.split(Regex("</p>|<br\\s*/?>"))
        .map(::text).filter { it.isNotEmpty() }.joinToString("\n\n")

    private fun decode(s: String): String {
        var r = s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#039;", "'").replace("&apos;", "'")
            .replace("&laquo;", "«").replace("&raquo;", "»").replace("&hellip;", "…").replace("&mdash;", "—")
            .replace("&ndash;", "–").replace("&lt;", "<").replace("&gt;", ">")
        r = Regex("&#(\\d+);").replace(r) { m -> m.groupValues[1].toIntOrNull()?.let { Char(it).toString() } ?: m.value }
        // The site prefixes some voice names with an invisible braille blank (U+2800).
        return r.replace("&amp;", "&").replace("⠀", "")
    }

    fun absolute(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> BASE + path
        else -> "$BASE/$path"
    }

    private fun get(url: String, xhr: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", OcApi.USER_AGENT)
            c.setRequestProperty("Referer", "$BASE/")
            c.setRequestProperty("Accept-Language", "ru")
            if (xhr) c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
