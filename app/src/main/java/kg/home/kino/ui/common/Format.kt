package kg.home.kino.ui.common

import java.util.Locale

/** 3723000 -> "1:02:03", 125000 -> "2:05". */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "7.31" -> "7.3", "9.3113" -> "9.3"; null for missing or zero ratings. */
fun formatRating(value: String?): String? =
    value?.toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }

/** "IMDb 7.1 · КП 7.3 · OC 9.3" - only the ratings the film has. [long] spells out the sources. */
fun ratingsLine(imdb: String?, kp: String?, oc: String?, long: Boolean = false): String? = listOfNotNull(
    formatRating(imdb)?.let { "IMDb $it" },
    formatRating(kp)?.let { if (long) "КиноПоиск $it" else "КП $it" },
    formatRating(oc)?.let { if (long) "OC.KG $it" else "OC $it" },
).joinToString(if (long) "  ·  " else " · ").ifBlank { null }
