package kg.home.kino.ui.details

import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import kg.home.kino.data.MovieDetails
import kg.home.kino.ui.common.ratingsLine

/** Text block next to the poster: title, year · genres · country, ratings, description. */
class DescriptionPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(vh: ViewHolder, item: Any?) {
        val m = item as MovieDetails
        vh.title.text = m.name

        val line1 = listOfNotNull(
            m.year,
            m.genres.take(3).joinToString(", ").ifBlank { null },
            m.countries.take(2).joinToString(", ").ifBlank { null },
        ).joinToString("  ·  ")
        val ratings = listOfNotNull(
            ratingsLine(m.ratingImdb, m.ratingKp, m.ratingOc, long = true),
            m.quality,
            m.files.firstOrNull()?.durationText,
        ).joinToString("  ·  ")
        // The subtitle view is single-line, so ratings go at the top of the body instead.
        vh.subtitle.text = line1

        val credits = buildString {
            if (m.directors.isNotEmpty()) append("Режиссёр: ").append(m.directors.joinToString(", ")).append('\n')
            if (!m.internationalName.isNullOrBlank()) append(m.internationalName).append('\n')
            if (m.translation.isNotEmpty()) append("Перевод: ").append(m.translation.joinToString(", ")).append('\n')
        }
        vh.body.text = listOfNotNull(
            ratings.ifBlank { null },
            m.description,
            credits.trim().ifBlank { null },
        ).joinToString("\n\n")
    }
}
