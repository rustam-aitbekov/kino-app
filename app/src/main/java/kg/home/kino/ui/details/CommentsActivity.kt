package kg.home.kino.ui.details

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kg.home.kino.R
import kg.home.kino.data.Comment
import kg.home.kino.data.OcApi
import kg.home.kino.ui.common.commentHeader
import kotlinx.coroutines.launch

/**
 * All comments of an oc.kg film, full text, one under another (newest first).
 * Opened from the film page's "Комментарии (N)" button. Up/Down move between comments
 * (a long one scrolls through), Back returns to the film.
 */
class CommentsActivity : FragmentActivity() {

    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Comfortable reading width in the middle of the screen.
            setPadding(dp(150), dp(27), dp(150), dp(40))
        }
        val title = TextView(this).apply {
            text = getString(R.string.comments_title, intent.getStringExtra(EXTRA_TITLE) ?: "")
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 28f
        }
        subtitle = TextView(this).apply {
            setText(R.string.loading)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 16f
            setPadding(0, dp(4), 0, dp(12))
        }
        list.addView(title)
        list.addView(subtitle)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.background))
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val comments = OcApi.comments(intent.getStringExtra(EXTRA_MOVIE)!!)
                subtitle.text = resources.getQuantityString(R.plurals.comments_count, comments.size, comments.size)
                comments.forEach(::addComment)
                list.getChildAt(2)?.requestFocus()
            } catch (e: Exception) {
                subtitle.text = getString(R.string.load_error, e.message)
            }
        }
    }

    private fun addComment(c: Comment) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val head = commentHeader(c)
        val text = SpannableStringBuilder(head).append('\n').append(c.text)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, head.length, 0)
        text.setSpan(RelativeSizeSpan(0.85f), 0, head.length, 0)
        text.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_secondary)), 0, head.length, 0)

        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 19f
            setLineSpacing(0f, 1.15f)
            setBackgroundResource(R.drawable.comment_bg)
            setPadding(dp(20), dp(14), dp(20), dp(16))
            // Focusable so the remote moves comment by comment and the list scrolls with it.
            isFocusable = true
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
        list.addView(tv, lp)
    }

    companion object {
        private const val EXTRA_MOVIE = "movie"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, movieId: String, title: String) =
            Intent(context, CommentsActivity::class.java)
                .putExtra(EXTRA_MOVIE, movieId)
                .putExtra(EXTRA_TITLE, title)
    }
}
