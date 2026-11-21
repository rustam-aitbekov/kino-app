package kg.home.kino.ui.ts

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kg.home.kino.R
import kg.home.kino.data.TsApi
import kg.home.kino.data.TsFilter
import kg.home.kino.data.TsFilterOptions
import kg.home.kino.data.TsOption
import kg.home.kino.ui.grid.CatalogFilter
import kg.home.kino.ui.grid.CatalogGridFragment
import kotlinx.coroutines.launch

/**
 * TS.KG Каталог tab: all shows as a poster grid with the site's catalogue menus as buttons -
 * Сортировка, Категория, Жанр, Страна, Год.
 */
class TsCatalogFragment : Fragment() {

    private var filter = TsFilter()
    private var categoryName: String? = null
    private var genreName: String? = null
    private var countryName: String? = null
    private var yearName: String? = null

    private lateinit var orderButton: TextView
    private lateinit var categoryButton: TextView
    private lateinit var genreButton: TextView
    private lateinit var countryButton: TextView
    private lateinit var yearButton: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_ts_catalog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        orderButton = view.findViewById(R.id.filter_order)
        categoryButton = view.findViewById(R.id.filter_category)
        genreButton = view.findViewById(R.id.filter_genre)
        countryButton = view.findViewById(R.id.filter_country)
        yearButton = view.findViewById(R.id.filter_year)

        orderButton.setOnClickListener { chooseOrder() }
        categoryButton.setOnClickListener {
            withOptions { o ->
                pick(R.string.filter_category_title, o.categories, filter.categoryId) { opt ->
                    categoryName = opt?.name
                    apply(filter.copy(categoryId = opt?.id))
                }
            }
        }
        genreButton.setOnClickListener {
            withOptions { o ->
                pick(R.string.filter_genre_title, o.genres, filter.genreId) { opt ->
                    genreName = opt?.name
                    apply(filter.copy(genreId = opt?.id))
                }
            }
        }
        countryButton.setOnClickListener {
            withOptions { o ->
                pick(R.string.filter_country_title, o.countries, filter.countryCode) { opt ->
                    countryName = opt?.name
                    apply(filter.copy(countryCode = opt?.id))
                }
            }
        }
        yearButton.setOnClickListener {
            withOptions { o ->
                pick(R.string.filter_year_title, o.years, filter.year) { opt ->
                    yearName = opt?.name
                    apply(filter.copy(year = opt?.id))
                }
            }
        }
        updateButtons()

        if (childFragmentManager.findFragmentById(R.id.catalog_grid) == null) {
            val grid = CatalogGridFragment.newInstance(
                CatalogFilter(), title = null, tsUrl = TsApi.catalogUrl(filter), embedded = true,
            )
            childFragmentManager.beginTransaction().replace(R.id.catalog_grid, grid).commitNow()
        }
    }

    private fun apply(f: TsFilter) {
        updateButtons()
        if (f == filter) return
        filter = f
        updateButtons()
        val grid = childFragmentManager.findFragmentById(R.id.catalog_grid) as? CatalogGridFragment
        grid?.setTsUrl(TsApi.catalogUrl(f))
    }

    private fun updateButtons() {
        val all = getString(R.string.filter_all_genres)
        val order = ORDERS.firstOrNull { it.first == (filter.sortBy ?: DEFAULT_ORDER) }?.second ?: ORDERS[0].second
        orderButton.text = getString(R.string.filter_order, order)
        categoryButton.text = getString(R.string.filter_category, categoryName ?: all)
        genreButton.text = getString(R.string.filter_genre, genreName ?: all)
        countryButton.text = getString(R.string.filter_country, countryName ?: all)
        yearButton.text = getString(R.string.filter_year, yearName ?: all)
    }

    private fun chooseOrder() {
        val current = ORDERS.indexOfFirst { it.first == (filter.sortBy ?: DEFAULT_ORDER) }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.filter_order_title)
            .setSingleChoiceItems(ORDERS.map { it.second }.toTypedArray(), current) { d, which ->
                d.dismiss()
                apply(filter.copy(sortBy = ORDERS[which].first.takeIf { it != DEFAULT_ORDER }))
            }
            .show()
    }

    private fun withOptions(show: (TsFilterOptions) -> Unit) {
        lifecycleScope.launch {
            try {
                show(TsApi.filterOptions())
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** List dialog: "Все" first, then the options in the site's order. */
    private fun pick(titleRes: Int, list: List<TsOption>, currentId: String?, onPick: (TsOption?) -> Unit) {
        val labels = listOf(getString(R.string.filter_all)) + list.map { it.name }
        val checked = list.indexOfFirst { it.id == currentId } + 1
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                onPick(if (which == 0) null else list[which - 1])
            }
            .show()
    }

    companion object {
        private const val DEFAULT_ORDER = "updated"

        /** The site's sort buttons (sortBy code to label). */
        private val ORDERS = listOf(
            "updated" to "новые серии",
            "new" to "новые сериалы",
            "a" to "А–Я",
            "z" to "Я–А",
        )
    }
}
