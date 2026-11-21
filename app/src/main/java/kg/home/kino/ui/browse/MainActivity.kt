package kg.home.kino.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import kg.home.kino.R
import kg.home.kino.ui.common.Nav
import kg.home.kino.ui.ts.TsCatalogFragment
import kg.home.kino.ui.ts.TsHomeFragment
import kg.home.kino.ui.ts.TsSelectionsFragment

/**
 * Home screen for both sites, laid out like oc.kg: a top bar with three tabs and Поиск on the
 * right, the chosen tab's content below.
 *   OC.KG (films):   Бестселлеры · Каталог · Подборки
 *   TS.KG (series):  Главная · Каталог · Подборки   (blue)
 *
 * Remote: moving along the tabs switches the content; Down goes into it, Up from the top of the
 * content comes back to the current tab; Back from the content returns to the tabs first.
 */
class MainActivity : FragmentActivity() {

    private val series get() = intent.getBooleanExtra(EXTRA_SERIES, false)
    private lateinit var topBar: View
    private lateinit var tabs: List<View>
    private var current = -1

    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
        val i = tabs.indexOf(new)
        if (new == null || !inTopBar(new)) return@OnGlobalFocusChangeListener
        if (old != null && !inTopBar(old) && current >= 0 && i != current) {
            // Coming up from the content: land on the current tab, not whichever is nearest.
            tabs[current].requestFocus()
            return@OnGlobalFocusChangeListener
        }
        if (i >= 0) select(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oc_main)
        topBar = findViewById(R.id.top_bar)
        tabs = listOf(
            findViewById(R.id.tab_bestsellers),
            findViewById(R.id.tab_catalog),
            findViewById(R.id.tab_selections),
        )
        val search = findViewById<View>(R.id.top_search)
        if (series) {
            findViewById<TextView>(R.id.logo).apply {
                setText(R.string.ts_browse_title)
                setTextColor(ContextCompat.getColor(context, R.color.ts_brand))
            }
            (tabs[0] as TextView).setText(R.string.tab_home)
            (tabs + search).forEach { it.setBackgroundResource(R.drawable.top_tab_bg_ts) }
        }
        tabs.forEachIndexed { i, tab -> tab.setOnClickListener { select(i); focusContent() } }
        search.setOnClickListener { Nav.search(this, series) }

        select(savedInstanceState?.getInt(STATE_TAB) ?: 0)
        tabs[current].requestFocus()
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, current)
    }

    /** Shows tab [i]'s fragment (created once, then kept so its scroll position survives). */
    private fun select(i: Int) {
        if (i == current) return
        current = i
        tabs.forEachIndexed { j, t -> t.isSelected = j == i }
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        TAGS.forEachIndexed { j, tag ->
            val f = fm.findFragmentByTag(tag)
            if (j == i) {
                if (f == null) tx.add(R.id.content, create(j), tag) else tx.show(f)
            } else if (f != null) {
                tx.hide(f)
            }
        }
        tx.commitNowAllowingStateLoss()
    }

    private fun create(i: Int): Fragment = if (series) {
        when (i) {
            0 -> TsHomeFragment()
            1 -> TsCatalogFragment()
            else -> TsSelectionsFragment()
        }
    } else {
        when (i) {
            0 -> BestsellersFragment()
            1 -> CatalogFragment()
            else -> SelectionsFragment()
        }
    }

    private fun focusContent() {
        supportFragmentManager.findFragmentByTag(TAGS[current])?.view?.requestFocus()
    }

    private fun inTopBar(v: View): Boolean {
        var p: Any? = v
        while (p is View) {
            if (p === topBar) return true
            p = p.parent
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Back inside the content goes up to the tabs first; Back on the tabs leaves.
        if (keyCode == KeyEvent.KEYCODE_BACK && current >= 0) {
            val focused = currentFocus
            if (focused != null && !inTopBar(focused)) {
                tabs[current].requestFocus()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val STATE_TAB = "tab"
        private const val EXTRA_SERIES = "series"

        fun intent(context: Context, series: Boolean) =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SERIES, series)
        private val TAGS = listOf("bestsellers", "catalog", "selections")
    }
}
