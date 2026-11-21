package kg.home.kino.ui.browse

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
import kg.home.kino.data.Genre
import kg.home.kino.data.OcApi
import kg.home.kino.ui.grid.CatalogFilter
import kg.home.kino.ui.grid.CatalogGridFragment
import kotlinx.coroutines.launch

/**
 * Каталог tab: the whole oc.kg catalogue as a poster grid, with the site's three menus as
 * buttons above it - Сортировка (sort order), Жанр and Страна.
 */
class CatalogFragment : Fragment() {

    private var filter = CatalogFilter()
    private var genreName: String? = null
    private var countryName: String? = null

    private lateinit var orderButton: TextView
    private lateinit var genreButton: TextView
    private lateinit var countryButton: TextView

    private var genres: List<Genre>? = null
    private var countries: List<Genre>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_catalog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        orderButton = view.findViewById(R.id.filter_order)
        genreButton = view.findViewById(R.id.filter_genre)
        countryButton = view.findViewById(R.id.filter_country)
        orderButton.setOnClickListener { chooseOrder() }
        genreButton.setOnClickListener { chooseGenre() }
        countryButton.setOnClickListener { chooseCountry() }
        updateButtons()

        if (childFragmentManager.findFragmentById(R.id.catalog_grid) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.catalog_grid, CatalogGridFragment.newInstance(filter, null, embedded = true))
                .commitNow()
        }
    }

    private fun grid() = childFragmentManager.findFragmentById(R.id.catalog_grid) as? CatalogGridFragment

    private fun apply(f: CatalogFilter) {
        filter = f
        updateButtons()
        grid()?.setFilter(f)
    }

    private fun updateButtons() {
        val orderName = ORDERS.firstOrNull { it.first == filter.order }?.second ?: ORDERS.first().second
        orderButton.text = getString(R.string.filter_order, orderName)
        genreButton.text = getString(R.string.filter_genre, genreName ?: getString(R.string.filter_all_genres))
        countryButton.text = getString(R.string.filter_country, countryName ?: getString(R.string.filter_all_countries))
    }

    private fun chooseOrder() {
        val current = ORDERS.indexOfFirst { it.first == filter.order }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.filter_order_title)
            .setSingleChoiceItems(ORDERS.map { it.second }.toTypedArray(), current) { d, which ->
                d.dismiss()
                apply(filter.copy(order = ORDERS[which].first))
            }
            .show()
    }

    private fun chooseGenre() = withList({ genres ?: OcApi.genres().also { genres = it } }) { list ->
        pick(getString(R.string.filter_genre_title), list, filter.genreId) { g ->
            genreName = g?.name
            apply(filter.copy(genreId = g?.id))
        }
    }

    private fun chooseCountry() = withList({ countries ?: OcApi.countries().also { countries = it } }) { list ->
        pick(getString(R.string.filter_country_title), list, filter.countryId) { c ->
            countryName = c?.name
            apply(filter.copy(countryId = c?.id))
        }
    }

    private fun withList(load: suspend () -> List<Genre>, show: (List<Genre>) -> Unit) {
        lifecycleScope.launch {
            try {
                show(load())
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** List dialog: "Все" first, then the most common entries first (as the site sorts them). */
    private fun pick(title: String, list: List<Genre>, currentId: String?, onPick: (Genre?) -> Unit) {
        val sorted = list.sortedByDescending { it.count }
        val names = listOf(getString(R.string.filter_all)) + sorted.map { "${it.name}  (${it.count})" }
        val checked = sorted.indexOfFirst { it.id == currentId } + 1
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), checked) { d, which ->
                d.dismiss()
                onPick(if (which == 0) null else sorted[which - 1])
            }
            .show()
    }

    companion object {
        /** The site's sort menu (order code to label); "Лучшие (мои)" needs an account, so it's left out. */
        private val ORDERS = listOf(
            null to "последние добавленные",
            "1" to "новинки (по году)",
            "2" to "лучшие по IMDb",
            "9" to "лучшие по КиноПоиску",
            "3" to "лучшие по оценкам OC.KG",
            "8" to "в центре внимания",
            "6" to "хиты",
        )
    }
}
