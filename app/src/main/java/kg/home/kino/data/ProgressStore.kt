package kg.home.kino.data

import android.content.Context
import org.json.JSONObject

/** Remembers where each film was stopped (local to the TV, no account needed). */
class ProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun get(movieId: String): Progress? = prefs.getString(key(movieId), null)?.let(::parse)

    /**
     * [force] stores the entry as is - used for series to point "continue watching" at the next
     * episode (position 0) once the previous one has finished.
     */
    fun save(p: Progress, force: Boolean = false) {
        if (!force) {
            // Finished (or almost): drop it from "continue watching".
            if (p.durationMs > 0 && p.positionMs > p.durationMs * 0.95) {
                remove(p.movieId)
                return
            }
            if (p.positionMs < 30_000) return   // barely started - don't clutter the row
        }
        val o = JSONObject()
            .put("movieId", p.movieId).put("fileId", p.fileId).put("name", p.name)
            .put("poster", p.poster ?: "").put("pos", p.positionMs).put("dur", p.durationMs)
            .put("ts", p.updatedAt)
        prefs.edit().putString(key(p.movieId), o.toString()).apply()
    }

    fun remove(movieId: String) = prefs.edit().remove(key(movieId)).apply()

    fun all(): List<Progress> = prefs.all.values.mapNotNull { (it as? String)?.let(::parse) }
        .sortedByDescending { it.updatedAt }

    /** Films (OC.KG) only. */
    fun movies(): List<Progress> = all().filterNot { it.isSeries }

    /** Series (TS.KG) only. */
    fun series(): List<Progress> = all().filter { it.isSeries }

    private fun key(movieId: String) = "m_$movieId"

    private fun parse(s: String): Progress? = try {
        val o = JSONObject(s)
        Progress(
            movieId = o.getString("movieId"),
            fileId = o.optString("fileId"),
            name = o.optString("name"),
            poster = o.optString("poster").ifBlank { null },
            positionMs = o.optLong("pos"),
            durationMs = o.optLong("dur"),
            updatedAt = o.optLong("ts"),
        )
    } catch (e: Exception) {
        null
    }
}
