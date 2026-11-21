package kg.home.kino.data

import java.io.Serializable

/** A TS.KG show as it appears in lists (rows, grids, search). Posters are 256x204 (5:4). */
data class TsShow(
    /** URL slug, e.g. "mobland" for https://www.ts.kg/show/mobland. */
    val slug: String,
    val name: String,
    val poster: String?,
    /** Short second line: "2025, Драма, Криминал" or "2 сезон, 10 серия". */
    val info: String? = null,
    /** Voice-over label if the site shows one on the card ("Дубляж"). */
    val voice: String? = null,
    /** Visitors' 👍 / 👎 votes, when the listing shows them (news and search results don't). */
    val likes: Int? = null,
    val dislikes: Int? = null,
) : Serializable

/** A TS.KG category (Турецкие, Аниме, ...) or, with slug == null, all shows. */
data class TsCategory(val slug: String?, val name: String)

data class TsEpisode(
    /** Episode id used by /show/episode/episode.json. */
    val id: String,
    val season: String,
    /** Number as the site shows it ("10", "10-11", "Спецвыпуск"). */
    val number: String,
    val name: String?,
    val duration: String?,
) {
    /** "S2E10" style label, readable in Russian: "2 сезон, 10 серия". */
    val label: String get() = "$season сезон, $number серия"
}

data class TsSeason(val number: String, val episodes: List<TsEpisode>)

/** Another voice-over of the same show; each one is a separate show page on the site. */
data class TsVoice(val slug: String?, val name: String, val active: Boolean)

data class TsShowDetails(
    val id: String,
    val slug: String,
    val name: String,
    val originalName: String?,
    val year: String?,
    val voice: String?,
    val description: String?,
    val category: String?,
    val genres: List<String>,
    val countries: List<String>,
    val poster: String?,
    val posterLarge: String?,
    val frames: List<String>,
    val seasons: List<TsSeason>,
    val voices: List<TsVoice>,
    /** Visitors' 👍 / 👎 votes on the show page (null if the page has none). */
    val likes: Int? = null,
    val dislikes: Int? = null,
) {
    val episodes: List<TsEpisode> get() = seasons.flatMap { it.episodes }
    fun toShort() = TsShow(slug, name, poster, listOfNotNull(year, genres.firstOrNull()).joinToString(", "))
}

/** What the player needs for one episode. */
data class TsStream(
    val url: String,
    val mimeType: String?,
    val subtitles: String?,
    val durationSeconds: Long,
)

data class TsPage(val shows: List<TsShow>, val nextUrl: String?)

/** A "Подборка" on ts.kg (/selection/<slug>). Pictures are 290x250. */
data class TsSelection(val slug: String, val name: String, val image: String?) : Serializable

/** One choice in a catalogue filter menu (id as the site's /show form expects it). */
data class TsOption(val id: String, val name: String)

/** The site's catalogue menus, read from the /show page. */
data class TsFilterOptions(
    val categories: List<TsOption>,
    val genres: List<TsOption>,
    val countries: List<TsOption>,
    val years: List<TsOption>,
)

/** Catalogue filters; null = all. [sortBy] is the site's code: updated / new / a / z. */
data class TsFilter(
    val categoryId: String? = null,
    val genreId: String? = null,
    val countryCode: String? = null,
    val year: String? = null,
    val sortBy: String? = null,
) : Serializable

/** Progress entries for TS.KG are stored with this prefix on the show slug. */
const val TS_PROGRESS_PREFIX = "ts:"
