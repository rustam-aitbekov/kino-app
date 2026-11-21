package kg.home.kino.data

import java.io.Serializable

/** A film as it appears in lists (rows, grids, search). */
data class MovieShort(
    val id: String,
    val name: String,
    val internationalName: String?,
    val year: String?,
    /** Poster at 200x300 (absolute URL), or null. */
    val poster: String?,
    val description: String? = null,
    val ratingImdb: String? = null,
    val ratingKp: String? = null,
    /** oc.kg's own visitor rating. */
    val ratingOc: String? = null,
    /** The size the API returned - not every film has a 200x300 version. */
    val posterFallback: String? = null,
) : Serializable

data class Category(val name: String, val movies: List<MovieShort>)

data class Genre(val id: String, val name: String, val count: Int)

/** Special tile in the genres row that opens the full catalogue. */
object AllMoviesTile

/** A "Подборка" - a themed TOP list on oc.kg (catalogue filtered by selection id). */
data class Selection(val id: String, val name: String, val image: String?) : Serializable

data class Person(
    val id: String,
    val name: String,
    val photo: String?,
    val role: String?,
    val character: String?,
)

/** One playable file of a film (films usually have one; collections/series have several). */
data class VideoFile(
    val id: String,
    val name: String,
    /** Relative player page link, e.g. "pl.php?player=ftp&...". */
    val license: String?,
    /** Server path, e.g. "/home/video/video/a/25/Film.mp4" (fallback for building the stream URL). */
    val path: String?,
    val durationSeconds: Long,
    val durationText: String?,
    val videoInfo: String?,
    val audioInfo: String?,
)

data class MovieDetails(
    val id: String,
    val name: String,
    val internationalName: String?,
    val year: String?,
    val description: String?,
    val genres: List<String>,
    val countries: List<String>,
    val directors: List<String>,
    val quality: String?,
    val translation: List<String>,
    val ratingImdb: String?,
    val ratingKp: String?,
    val ratingOc: String?,
    val posterThumb: String?,
    val posterOriginal: String?,
    val frames: List<String>,
    val files: List<VideoFile>,
    val persons: List<Person>,
    val similar: List<MovieShort>,
) {
    fun toShort() = MovieShort(id, name, internationalName, year, posterThumb, description, ratingImdb, ratingKp, ratingOc)
}

data class CatalogPage(val total: Int, val movies: List<MovieShort>)

data class SearchResult(val movies: List<MovieShort>)

/** Saved watch position for the "continue watching" row. */
data class Progress(
    val movieId: String,
    val fileId: String,
    val name: String,
    val poster: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    /** TS.KG entry: movieId is "ts:<show slug>", fileId is the episode id. */
    val isSeries: Boolean get() = movieId.startsWith(TS_PROGRESS_PREFIX)
    val showSlug: String get() = movieId.removePrefix(TS_PROGRESS_PREFIX)
}
