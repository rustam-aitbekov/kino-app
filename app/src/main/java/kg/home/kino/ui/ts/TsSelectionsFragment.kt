package kg.home.kino.ui.ts

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import kg.home.kino.R
import kg.home.kino.data.TsApi
import kg.home.kino.ui.common.MovieCardPresenter
import kg.home.kino.ui.common.Nav
import kg.home.kino.ui.common.SpacedGridPresenter
import kg.home.kino.ui.common.hideBuiltInTitle
import kotlinx.coroutines.launch

/** TS.KG Подборки tab: all the site's selections as picture tiles; each opens its shows. */
class TsSelectionsFragment : VerticalGridSupportFragment() {
    private val items = ArrayObjectAdapter(MovieCardPresenter(wide = true))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gridPresenter = SpacedGridPresenter(columns = 5)
        adapter = items
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> Nav.open(requireContext(), item) }
        lifecycleScope.launch {
            try {
                items.addAll(0, TsApi.selections())
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** The top bar is the title - hide the grid's own title bar. */
    override fun onStart() {
        super.onStart()
        hideBuiltInTitle()
    }
}
