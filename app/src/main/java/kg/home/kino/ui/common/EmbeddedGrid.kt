package kg.home.kino.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.BrowseFrameLayout
import androidx.leanback.widget.TitleView

/**
 * For a VerticalGridSupportFragment shown inside a screen that has its own top bar (the OC.KG
 * tabs): removes the grid's built-in title/search bar and lets Up from the first row move focus
 * to the views above the grid. Call from onStart() after super.onStart().
 *
 * The title view itself has to stay (leanback needs it), so it is collapsed instead.
 */
fun VerticalGridSupportFragment.hideBuiltInTitle() {
    titleView?.let { tv ->
        tv.layoutParams = tv.layoutParams.apply { height = 0 }
        (tv as? TitleView)?.searchAffordanceView?.isFocusable = false
        (tv as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        tv.visibility = View.GONE
    }
    // leanback routes Up from the first row to its title bar; drop that so normal focus search
    // finds the filter buttons / tabs above.
    view?.findViewById<BrowseFrameLayout>(androidx.leanback.R.id.grid_frame)?.onFocusSearchListener = null
    // Without the title bar the grid's default top gap is too big under our own top bar.
    view?.findViewById<View>(androidx.leanback.R.id.browse_grid)?.let { g ->
        val top = (12 * g.resources.displayMetrics.density).toInt()
        g.setPadding(g.paddingLeft, top, g.paddingRight, g.paddingBottom)
    }
}
