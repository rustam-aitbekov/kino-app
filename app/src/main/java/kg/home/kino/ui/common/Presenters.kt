package kg.home.kino.ui.common

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kg.home.kino.R
import kg.home.kino.data.AllMoviesTile
import kg.home.kino.data.Comment
import kg.home.kino.data.Genre
import kg.home.kino.data.MovieShort
import kg.home.kino.data.Person
import kg.home.kino.data.Progress
import kg.home.kino.data.Selection
import kg.home.kino.data.TsCategory
import kg.home.kino.data.TsSelection
import kg.home.kino.data.TsEpisode
import kg.home.kino.data.TsShow

private fun dp(parent: ViewGroup, v: Int) = (v * parent.resources.displayMetrics.density).toInt()

/**
 * Poster cards for films, TS.KG shows and "continue watching" items.
 * [wide] = TS.KG poster shape (5:4) instead of the 2:3 film poster.
 */
class MovieCardPresenter(private val wide: Boolean = false) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            if (wide) setMainImageDimensions(dp(parent, 160), dp(parent, 128))
            else setMainImageDimensions(dp(parent, 134), dp(parent, 200))
            setMainImageScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            // Films: year on the first line, the three ratings on the second.
            // TS.KG: one line, so every card in a row has the same height.
            findViewById<TextView>(androidx.leanback.R.id.content_text)?.apply {
                isSingleLine = false
                maxLines = if (wide) 1 else 2
            }
            // TS.KG posters are transparent PNGs with the show title printed on top - often in
            // black. Put them on a light backing with a small margin so every title is readable.
            if (wide) {
                val p = dp(parent, 8)
                mainImageView.setPadding(p, p, p, p)
                mainImageView.setBackgroundColor(ContextCompat.getColor(context, R.color.poster_backing))
            }
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = viewHolder.view as ImageCardView
        val poster: String?
        var fallback: String? = null
        when (item) {
            is MovieShort -> {
                card.titleText = item.name
                // Narrow card: "2026 · IMDb 7.1" / "КП 7.3 · OC 9.3"
                val line1 = listOfNotNull(item.year, formatRating(item.ratingImdb)?.let { "IMDb $it" }).joinToString(" · ")
                card.contentText = listOfNotNull(line1.ifBlank { null }, ratingsLine(null, item.ratingKp, item.ratingOc))
                    .joinToString("\n")
                poster = item.poster
                fallback = item.posterFallback
            }
            is Selection -> {
                card.titleText = item.name
                card.contentText = null
                poster = item.image
            }
            is TsSelection -> {
                card.titleText = item.name
                card.contentText = null
                poster = item.image
            }
            is TsShow -> {
                card.titleText = item.name
                card.contentText = listOfNotNull(item.info, item.voice).joinToString(" · ")
                poster = item.poster
            }
            is Progress -> {
                card.titleText = item.name
                card.contentText = if (item.durationMs <= 0 || item.positionMs <= 0) {
                    "Следующая серия"
                } else {
                    val leftMin = ((item.durationMs - item.positionMs) / 60_000).coerceAtLeast(1)
                    "${(item.fraction * 100).toInt()}% · осталось $leftMin мин"
                }
                poster = item.poster
            }
            else -> return
        }
        val placeholder = ColorDrawable(ContextCompat.getColor(card.context, R.color.surface))
        if (poster != null) {
            val glide = Glide.with(card.context)
            glide.load(poster)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(placeholder)
                .error(
                    if (fallback != null && fallback != poster) glide.load(fallback).centerCrop().error(placeholder)
                    else glide.load(placeholder)
                )
                .centerCrop()
                .into(card.mainImageView)
        } else {
            card.mainImage = placeholder
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        Glide.with(card.context.applicationContext).clear(card.mainImageView)
        card.mainImage = null
    }
}

/** Colored text tiles for genres and the "Все фильмы" tile. */
class GenreCardPresenter : Presenter() {
    private val colors = intArrayOf(
        0xFF8E24AA.toInt(), 0xFF3949AB.toInt(), 0xFF00897B.toInt(), 0xFFF4511E.toInt(),
        0xFF6D4C41.toInt(), 0xFF546E7A.toInt(), 0xFFD81B60.toInt(), 0xFF43A047.toInt(),
    )

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(dp(parent, 200), dp(parent, 100))
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 20f
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(parent, 8), 0, dp(parent, 8), 0)
            // The row already zooms the focused card; just brighten it.
            setOnFocusChangeListener { v, f -> v.alpha = if (f) 1f else 0.85f }
            alpha = 0.85f
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val tv = viewHolder.view as TextView
        when (item) {
            is Genre -> {
                tv.text = item.name
                tv.setBackgroundColor(colors[(item.id.toIntOrNull() ?: item.name.hashCode()).mod(colors.size)])
            }
            is TsCategory -> {
                tv.text = item.name
                tv.setBackgroundColor(
                    if (item.slug == null) ContextCompat.getColor(tv.context, R.color.ts_brand)
                    else colors[item.name.hashCode().mod(colors.size)]
                )
            }
            is AllMoviesTile -> {
                tv.setText(R.string.all_movies)
                tv.setBackgroundColor(ContextCompat.getColor(tv.context, R.color.brand))
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}

/** Wide frame images (stills from the film). */
class FrameCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(dp(parent, 300), dp(parent, 128))
            setMainImageScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
            cardType = ImageCardView.CARD_TYPE_FLAG_IMAGE_ONLY
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = viewHolder.view as ImageCardView
        Glide.with(card.context).load(item as String).centerCrop().into(card.mainImageView)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        Glide.with(card.context.applicationContext).clear(card.mainImageView)
    }
}

/** Cast / crew cards. */
class PersonCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(dp(parent, 110), dp(parent, 150))
            setMainImageScaleType(android.widget.ImageView.ScaleType.CENTER_CROP)
            setInfoAreaBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val p = item as Person
        val card = viewHolder.view as ImageCardView
        card.titleText = p.name
        card.contentText = p.character ?: p.role ?: ""
        val placeholder = ContextCompat.getDrawable(card.context, R.drawable.person_placeholder)
        if (p.photo != null) {
            Glide.with(card.context).load(p.photo).placeholder(placeholder).error(placeholder).centerCrop()
                .into(card.mainImageView)
        } else {
            card.mainImage = placeholder
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        Glide.with(card.context.applicationContext).clear(card.mainImageView)
    }
}

/** An episode card on the TS.KG show screen; [note] marks the episode to continue from. */
data class EpisodeItem(val episode: TsEpisode, val note: String? = null)

/** Text cards for episodes: "Серия 3", its name, and the duration (or the continue note). */
class EpisodeCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(dp(parent, 210), dp(parent, 104))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(parent, 14), dp(parent, 8), dp(parent, 14), dp(parent, 8))
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val e = item as EpisodeItem
        val tv = viewHolder.view as TextView
        val ctx = tv.context
        val title = "Серия ${e.episode.number}"
        val sub = listOfNotNull(e.episode.name, e.note ?: e.episode.duration).joinToString("\n")
        val text = android.text.SpannableStringBuilder(title)
        text.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, 0)
        text.setSpan(android.text.style.RelativeSizeSpan(1.2f), 0, title.length, 0)
        if (sub.isNotEmpty()) {
            val start = text.length + 1
            text.append('\n').append(sub)
            text.setSpan(
                android.text.style.ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.text_secondary)),
                start, text.length, 0
            )
        }
        tv.text = text
        val normal = ContextCompat.getColor(ctx, if (e.note != null) R.color.ts_brand_dark else R.color.surface)
        val focused = ContextCompat.getColor(ctx, R.color.ts_brand)
        tv.setBackgroundColor(normal)
        tv.setOnFocusChangeListener { v, f -> v.setBackgroundColor(if (f) focused else normal) }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}


/** "user → other · 05.01.2013 · +3" */
fun commentHeader(c: Comment): String = buildString {
    append(c.user)
    c.toUser?.let { append(" → ").append(it) }
    c.date?.let { append("  ·  ").append(it) }
    if (c.votes > 0) append("  ·  +").append(c.votes)
}
