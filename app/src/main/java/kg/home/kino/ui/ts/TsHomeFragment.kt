package kg.home.kino.ui.ts

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import kg.home.kino.R
import kg.home.kino.data.ProgressStore
import kg.home.kino.data.TsApi
import kg.home.kino.ui.common.GenreCardPresenter
import kg.home.kino.ui.common.MovieCardPresenter
import kg.home.kino.ui.common.Nav
import kg.home.kino.ui.common.SpacedListRowPresenter
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * TS.KG Главная tab:
 *   Продолжить просмотр (local) · Новые серии · Новые сериалы · Популярные · Подборки · Категории
 */
class TsHomeFragment : RowsSupportFragment() {

    private val rows = ArrayObjectAdapter(SpacedListRowPresenter())
    private val cards = MovieCardPresenter(wide = true)
    private var continueRow: ListRow? = null
    private lateinit var progress: ProgressStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progress = ProgressStore(requireContext())
        adapter = rows
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> Nav.open(requireContext(), item) }
        load()
    }

    override fun onResume() {
        super.onResume()
        refreshContinueRow()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshContinueRow()
    }

    private fun load() {
        lifecycleScope.launch {
            val selections = async { runCatching { TsApi.selections() }.getOrDefault(emptyList()) }
            try {
                val home = TsApi.home()
                addRow(getString(R.string.ts_row_latest), home.latest)
                addRow(getString(R.string.ts_row_new), home.newShows)
                addRow(getString(R.string.ts_row_popular), home.popular)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
            }
            addRow(getString(R.string.row_selections), selections.await())
            // Categories are a fixed list - shown even if the site failed to load.
            val cats = ArrayObjectAdapter(GenreCardPresenter()).apply { addAll(0, TsApi.categories) }
            rows.add(ListRow(HeaderItem(getString(R.string.ts_row_categories)), cats))
        }
    }

    private fun addRow(title: String, items: List<Any>) {
        if (items.isEmpty()) return
        val a = ArrayObjectAdapter(cards)
        a.addAll(0, items)
        rows.add(ListRow(HeaderItem(title), a))
    }

    /** "Продолжить просмотр" is always the first row when there is something to continue. */
    private fun refreshContinueRow() {
        val items = progress.series()
        val existing = continueRow
        if (items.isEmpty()) {
            if (existing != null) rows.remove(existing)
            continueRow = null
            return
        }
        val a = ArrayObjectAdapter(cards).apply { addAll(0, items) }
        val row = ListRow(HeaderItem(getString(R.string.row_continue)), a)
        if (existing != null) rows.replace(rows.indexOf(existing), row) else rows.add(0, row)
        continueRow = row
    }
}
