package kg.home.kino.ui.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kg.home.kino.R
import kg.home.kino.data.OcApi
import kg.home.kino.data.TsApi
import kg.home.kino.ui.common.MovieCardPresenter
import kg.home.kino.ui.common.Nav
import kg.home.kino.ui.common.SpacedGridPresenter
import kg.home.kino.ui.common.hideBuiltInTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search with its own on-screen keyboard - the TV's system keyboard has only English.
 * Russian (А-Я), English (A-Z) and digits pages, Пробел / ⌫ / Очистить; results update as you
 * type. The remote's number keys and a hardware keyboard type too.
 */
class SearchActivity : FragmentActivity() {

    private enum class Page { RU, EN, DIGITS }

    private val series get() = intent.getBooleanExtra(EXTRA_SERIES, false)

    private lateinit var queryBox: TextView
    private lateinit var status: TextView
    private lateinit var keyboard: GridLayout
    private lateinit var results: SearchResultsFragment

    private val query = StringBuilder()
    private var page = Page.RU
    private var lastLettersPage = Page.RU
    private var job: Job? = null
    private var lastSearched = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        queryBox = findViewById(R.id.query_box)
        status = findViewById(R.id.search_status)
        keyboard = findViewById(R.id.keyboard)
        queryBox.hint = getString(if (series) R.string.search_hint_series else R.string.search_hint)

        results = supportFragmentManager.findFragmentById(R.id.search_results) as? SearchResultsFragment
            ?: SearchResultsFragment.newInstance(series).also {
                supportFragmentManager.beginTransaction().replace(R.id.search_results, it).commitNow()
            }

        buildKeyboard(focusTag = null)
        keyboard.getChildAt(0)?.requestFocus()
        updateQuery()
    }

    // ---------------------------------------------------------------- keyboard

    private fun rowsFor(p: Page): List<String> = when (p) {
        Page.RU -> listOf("АБВГДЕЁ", "ЖЗИЙКЛМ", "НОПРСТУ", "ФХЦЧШЩЪ", "ЫЬЭЮЯ-.")
        Page.EN -> listOf("ABCDEFG", "HIJKLMN", "OPQRSTU", "VWXYZ-'", ".,:!?&+")
        Page.DIGITS -> listOf("1234567", "890.,:-", "!?&'()+", "№%«»/#@", "")
    }

    /** (Re)builds the keys for the current page; [focusTag] = function key to keep focused. */
    private fun buildKeyboard(focusTag: String?) {
        keyboard.removeAllViews()
        for (row in rowsFor(page)) {
            for (ch in row) addKey(ch.toString(), span = 1) { type(ch.lowercaseChar().toString()) }
        }
        addKey(getString(R.string.key_space), span = 4, tag = "space") { type(" ") }
        addKey("⌫", span = 3, tag = "del") { backspace() }
        val langLabel = when (page) {
            Page.RU -> getString(R.string.key_en)
            Page.EN -> getString(R.string.key_ru)
            Page.DIGITS -> getString(if (lastLettersPage == Page.RU) R.string.key_ru else R.string.key_en)
        }
        addKey(langLabel, span = 2, tag = "lang") {
            page = when (page) {
                Page.RU -> Page.EN
                Page.EN -> Page.RU
                Page.DIGITS -> lastLettersPage
            }
            lastLettersPage = page
            buildKeyboard("lang")
        }
        addKey(getString(R.string.key_digits), span = 2, tag = "digits", selected = page == Page.DIGITS) {
            page = if (page == Page.DIGITS) lastLettersPage else Page.DIGITS
            buildKeyboard("digits")
        }
        addKey(getString(R.string.key_clear), span = 3, tag = "clear") { clear() }
        focusTag?.let { keyboard.findViewWithTag<View>(it)?.requestFocus() }
    }

    private fun addKey(label: String, span: Int, tag: String? = null, selected: Boolean = false, onClick: () -> Unit) {
        val d = resources.displayMetrics.density
        val unit = (KEY_W * d).toInt()
        val margin = (KEY_MARGIN * d).toInt()
        val key = TextView(this).apply {
            text = label
            this.tag = tag
            gravity = Gravity.CENTER
            textSize = if (span == 1) 20f else 17f
            setTextColor(ContextCompat.getColorStateList(context, R.color.key_text))
            setBackgroundResource(R.drawable.key_bg)
            isFocusable = true
            isClickable = true
            isSelected = selected
            setOnClickListener { onClick() }
        }
        val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, span)).apply {
            width = unit * span + margin * 2 * (span - 1)
            height = (KEY_H * d).toInt()
            setMargins(margin, margin, margin, margin)
        }
        keyboard.addView(key, lp)
    }

    private fun type(s: String) {
        if (s == " " && (query.isEmpty() || query.endsWith(" "))) return
        if (query.length >= 60) return
        query.append(s)
        updateQuery()
    }

    private fun backspace() {
        if (query.isNotEmpty()) query.setLength(query.length - 1)
        updateQuery()
    }

    private fun clear() {
        query.setLength(0)
        updateQuery()
    }

    private fun updateQuery() {
        if (query.isEmpty()) {
            queryBox.text = null
        } else {
            val caret = "▏"
            val text = SpannableStringBuilder(query).append(caret)
            text.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, if (series) R.color.ts_brand else R.color.brand)),
                text.length - caret.length, text.length, 0
            )
            queryBox.text = text
        }
        scheduleSearch()
    }

    // ---------------------------------------------------------------- search

    private fun scheduleSearch() {
        val q = query.toString().trim()
        if (q == lastSearched) return
        job?.cancel()
        if (q.length < 2) {
            lastSearched = q
            results.show(emptyList())
            status.text = if (q.isEmpty()) "" else getString(R.string.search_type_more)
            return
        }
        job = lifecycleScope.launch {
            delay(500)
            lastSearched = q
            status.text = getString(R.string.search_searching)
            try {
                val found: List<Any> = if (series) TsApi.search(q) else OcApi.search(q).movies
                results.show(found)
                status.text = if (found.isEmpty()) getString(R.string.search_empty) else getString(R.string.search_results)
            } catch (e: Exception) {
                results.show(emptyList())
                status.text = getString(R.string.load_error, e.message)
            }
        }
    }

    // ---------------------------------------------------------------- hardware keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> { backspace(); return true }
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    type((event.keyCode - KeyEvent.KEYCODE_0).toString()); return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    // Back from the results returns to the keyboard first.
                    if (keyboard.findFocus() == null && results.view?.findFocus() != null) {
                        keyboard.getChildAt(0)?.requestFocus()
                        return true
                    }
                }
                else -> {
                    // A connected hardware keyboard types straight into the query.
                    val c = event.unicodeChar
                    if (c != 0 && !Character.isISOControl(c)) {
                        type(String(Character.toChars(c)).lowercase())
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_SERIES = "series"
        private const val KEY_W = 46
        private const val KEY_H = 46
        private const val KEY_MARGIN = 3

        fun intent(context: Context, series: Boolean) =
            Intent(context, SearchActivity::class.java).putExtra(EXTRA_SERIES, series)
    }
}

/** Results to the right of the keyboard: a poster grid without its own title bar. */
class SearchResultsFragment : VerticalGridSupportFragment() {
    private lateinit var items: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val series = arguments?.getBoolean(ARG_SERIES) == true
        items = ArrayObjectAdapter(MovieCardPresenter(wide = series))
        gridPresenter = if (series) SpacedGridPresenter(columns = 2, zoom = FocusHighlight.ZOOM_FACTOR_SMALL)
        else VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_SMALL, false).apply { numberOfColumns = 3 }
        adapter = items
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> Nav.open(requireContext(), item) }
    }

    override fun onStart() {
        super.onStart()
        hideBuiltInTitle()
    }

    fun show(list: List<Any>) {
        items.setItems(list, null)
    }

    companion object {
        private const val ARG_SERIES = "series"
        fun newInstance(series: Boolean) = SearchResultsFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_SERIES, series) }
        }
    }
}
