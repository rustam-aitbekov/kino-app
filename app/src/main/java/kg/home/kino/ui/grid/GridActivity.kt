package kg.home.kino.ui.grid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kg.home.kino.R
import kg.home.kino.data.OcApi
import kg.home.kino.data.Selection
import kg.home.kino.data.TsApi
import kg.home.kino.data.TsCategory
import kg.home.kino.data.TsSelection
import kg.home.kino.ui.common.MovieCardPresenter
import kg.home.kino.ui.common.Nav
import kg.home.kino.ui.common.SpacedGridPresenter
import kg.home.kino.ui.common.hideBuiltInTitle
import kotlinx.coroutines.launch

/**
 * Full-screen poster grid: all films / one genre / one selection (OC.KG), or one TS.KG category.
 * Loads more while you scroll.
 */
class GridActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val f = CatalogGridFragment.newInstance(
                CatalogFilter(
                    genreId = intent.getStringExtra(EXTRA_GENRE),
                    selectionId = intent.getStringExtra(EXTRA_SELECTION),
                ),
                title = intent.getStringExtra(EXTRA_TITLE),
                tsUrl = intent.getStringExtra(EXTRA_TS_URL),
            )
            supportFragmentManager.beginTransaction().replace(android.R.id.content, f).commit()
        }
    }

    companion object {
        private const val EXTRA_GENRE = "genre"
        private const val EXTRA_SELECTION = "selection"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TS_URL = "ts_url"

        fun intent(context: Context, genreId: String?, title: String?) = Intent(context, GridActivity::class.java)
            .putExtra(EXTRA_GENRE, genreId).putExtra(EXTRA_TITLE, title)

        fun selectionIntent(context: Context, s: Selection) = Intent(context, GridActivity::class.java)
            .putExtra(EXTRA_SELECTION, s.id).putExtra(EXTRA_TITLE, s.name)

        fun tsIntent(context: Context, category: TsCategory) = Intent(context, GridActivity::class.java)
            .putExtra(EXTRA_TITLE, category.name).putExtra(EXTRA_TS_URL, TsApi.firstPageUrl(category))

        fun tsSelectionIntent(context: Context, s: TsSelection) = Intent(context, GridActivity::class.java)
            .putExtra(EXTRA_TITLE, s.name).putExtra(EXTRA_TS_URL, TsApi.selectionUrl(s))
    }
}

/** OC.KG catalogue filters; null = not filtered. [order] is the site's sort code. */
data class CatalogFilter(
    val genreId: String? = null,
    val countryId: String? = null,
    val selectionId: String? = null,
    val order: String? = null,
) : java.io.Serializable

class CatalogGridFragment : VerticalGridSupportFragment() {
    private lateinit var items: ArrayObjectAdapter
    private var filter = CatalogFilter()
    private var total = Int.MAX_VALUE
    private var loading = false
    /** Bumped on every filter change so pages of the old list are thrown away. */
    private var generation = 0

    /** TS.KG mode: URL of the next page to load, null once everything is loaded. */
    private var series = false
    private var tsNext: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        @Suppress("DEPRECATION")
        filter = args.getSerializable(ARG_FILTER) as? CatalogFilter ?: CatalogFilter()
        tsNext = args.getString(ARG_TS_URL)
        series = tsNext != null
        title = args.getString(ARG_TITLE) ?: getString(R.string.all_movies)

        val brand = ContextCompat.getColor(requireContext(), if (series) R.color.ts_brand else R.color.brand)
        items = ArrayObjectAdapter(MovieCardPresenter(wide = series))
        gridPresenter = if (series) SpacedGridPresenter(columns = 5)
        else VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM, false).apply { numberOfColumns = 6 }
        adapter = items
        setOnSearchClickedListener { Nav.search(requireContext(), series) }
        searchAffordanceColor = brand

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> Nav.open(requireContext(), item) }
        setOnItemViewSelectedListener(OnItemViewSelectedListener { _, item, _, _ ->
            val pos = items.indexOf(item)
            if (pos >= items.size() - 12) loadMore()
        })
        loadMore()
    }

    /** Inside the OC.KG home screen the top bar already has the title and search - hide the grid's own. */
    override fun onStart() {
        super.onStart()
        if (arguments?.getBoolean(ARG_EMBEDDED) == true) hideBuiltInTitle()
    }

    /** Shows a different slice of the catalogue (used by the Каталог tab's filter buttons). */
    fun setFilter(f: CatalogFilter) {
        if (f == filter) return
        filter = f
        generation++
        items.clear()
        total = Int.MAX_VALUE
        loading = false
        loadMore()
    }

    /** TS.KG: shows a different listing (the Каталог tab's filters build a new /show URL). */
    fun setTsUrl(url: String) {
        generation++
        items.clear()
        tsNext = url
        loading = false
        loadMore()
    }

    private fun loadMore() {
        if (loading) return
        if (series && tsNext == null) return
        if (!series && items.size() >= total) return
        loading = true
        val gen = generation
        lifecycleScope.launch {
            try {
                if (series) {
                    val page = TsApi.page(tsNext!!)
                    if (gen != generation) return@launch
                    // Pages can overlap when the site updates while scrolling - skip repeats.
                    val seen = (0 until items.size()).map { items.get(it) }.toHashSet()
                    items.addAll(items.size(), page.shows.filter { it !in seen })
                    tsNext = page.nextUrl.takeIf { page.shows.isNotEmpty() }
                } else {
                    val f = filter
                    val page = OcApi.catalog(items.size(), PAGE, f.genreId, f.order, f.selectionId, f.countryId)
                    if (gen != generation) return@launch
                    total = page.total
                    items.addAll(items.size(), page.movies)
                    if (page.movies.isEmpty()) total = items.size()
                }
            } catch (e: Exception) {
                if (gen == generation) {
                    Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    companion object {
        private const val PAGE = 48
        private const val ARG_FILTER = "filter"
        private const val ARG_TITLE = "title"
        private const val ARG_TS_URL = "ts_url"
        private const val ARG_EMBEDDED = "embedded"

        fun newInstance(filter: CatalogFilter, title: String?, tsUrl: String? = null, embedded: Boolean = false) =
            CatalogGridFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_FILTER, filter)
                    putString(ARG_TITLE, title)
                    putString(ARG_TS_URL, tsUrl)
                    putBoolean(ARG_EMBEDDED, embedded)
                }
            }
    }
}
