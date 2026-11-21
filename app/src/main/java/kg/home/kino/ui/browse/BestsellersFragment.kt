package kg.home.kino.ui.browse

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import kg.home.kino.R
import kg.home.kino.data.MovieShort
import kg.home.kino.data.OcApi
import kg.home.kino.data.ProgressStore
import kg.home.kino.ui.common.MovieCardPresenter
import kg.home.kino.ui.common.Nav
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Бестселлеры tab - horizontal rows, like the site's bestsellers page:
 *   Продолжить просмотр (local) · Популярное сейчас · Новинки · Подборки · bestseller categories
 */
class BestsellersFragment : RowsSupportFragment() {

    private val rows = ArrayObjectAdapter(ListRowPresenter())
    private val movieCards = MovieCardPresenter()
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
            try {
                val popular = async { OcApi.popular() }
                val newest = async { OcApi.catalog(0, 30) }
                val best = async { OcApi.bestsellers() }
                val selections = async { runCatching { OcApi.selections() }.getOrDefault(emptyList()) }

                // Popular and bestseller lists come without oc.kg's own rating - filled in below.
                val needRating = ArrayList<ArrayObjectAdapter>()
                addRow(getString(R.string.row_popular), popular.await())?.let { needRating += it }
                addRow(getString(R.string.row_new), newest.await().movies)
                addRow(getString(R.string.row_selections), selections.await())
                best.await().forEach { c -> addRow(c.name, c.movies)?.let { needRating += it } }

                fillLocalRatings(needRating)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addRow(title: String, items: List<Any>): ArrayObjectAdapter? {
        if (items.isEmpty()) return null
        val a = ArrayObjectAdapter(movieCards)
        a.addAll(0, items)
        rows.add(ListRow(HeaderItem(title), a))
        return a
    }

    /** Adds the OC rating to the cards in [adapters] (best effort - the cards are already shown). */
    private suspend fun fillLocalRatings(adapters: List<ArrayObjectAdapter>) {
        val ids = adapters.flatMap { a -> (0 until a.size()).mapNotNull { (a.get(it) as? MovieShort)?.id } }
        val ratings = try {
            OcApi.localRatings(ids)
        } catch (e: Exception) {
            return
        }
        for (a in adapters) {
            for (i in 0 until a.size()) {
                val m = a.get(i) as? MovieShort ?: continue
                val r = ratings[m.id] ?: continue
                if (m.ratingOc == null) a.replace(i, m.copy(ratingOc = r))
            }
        }
    }

    /** "Продолжить просмотр" is always the first row when there is something to continue. */
    private fun refreshContinueRow() {
        val items = progress.movies()
        val existing = continueRow
        if (items.isEmpty()) {
            if (existing != null) rows.remove(existing)
            continueRow = null
            return
        }
        val a = ArrayObjectAdapter(movieCards).apply { addAll(0, items) }
        val row = ListRow(HeaderItem(getString(R.string.row_continue)), a)
        if (existing != null) rows.replace(rows.indexOf(existing), row) else rows.add(0, row)
        continueRow = row
    }
}
