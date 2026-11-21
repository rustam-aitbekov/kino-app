package kg.home.kino.ui.browse

import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import kg.home.kino.R
import kg.home.kino.data.OcApi
import kg.home.kino.ui.common.MovieCardPresenter
import kg.home.kino.ui.common.Nav
import kg.home.kino.ui.common.hideBuiltInTitle
import kotlinx.coroutines.launch

/** Подборки tab: all the site's themed TOP lists as poster tiles; each opens its films. */
class SelectionsFragment : VerticalGridSupportFragment() {
    private val items = ArrayObjectAdapter(MovieCardPresenter())

    /** The top bar is the title - hide the grid's own title bar. */
    override fun onStart() {
        super.onStart()
        hideBuiltInTitle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM, false).apply { numberOfColumns = 6 }
        adapter = items
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> Nav.open(requireContext(), item) }
        lifecycleScope.launch {
            try {
                items.addAll(0, OcApi.selections())
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
