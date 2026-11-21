package kg.home.kino.ui.common

import android.view.ViewGroup
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.VerticalGridPresenter

private fun ViewGroup.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** Horizontal rows with more air between the cards (leanback's default is tight). */
class SpacedListRowPresenter(private val spacingDp: Int = 24) : ListRowPresenter() {
    override fun createRowViewHolder(parent: ViewGroup): RowPresenter.ViewHolder {
        val vh = super.createRowViewHolder(parent) as ListRowPresenter.ViewHolder
        vh.gridView.setItemSpacing(parent.dp(spacingDp))
        return vh
    }
}

/** Poster grid with explicit gaps between columns and rows. */
class SpacedGridPresenter(
    columns: Int,
    private val horizontalDp: Int = 16,
    private val verticalDp: Int = 24,
    zoom: Int = FocusHighlight.ZOOM_FACTOR_MEDIUM,
) : VerticalGridPresenter(zoom, false) {
    init {
        numberOfColumns = columns
    }

    override fun initializeGridViewHolder(vh: ViewHolder) {
        super.initializeGridViewHolder(vh)
        vh.gridView.setHorizontalSpacing(vh.gridView.dp(horizontalDp))
        vh.gridView.setVerticalSpacing(vh.gridView.dp(verticalDp))
    }
}
