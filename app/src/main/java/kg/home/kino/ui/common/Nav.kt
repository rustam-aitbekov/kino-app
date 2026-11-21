package kg.home.kino.ui.common

import android.content.Context
import kg.home.kino.data.AllMoviesTile
import kg.home.kino.data.Genre
import kg.home.kino.data.MovieShort
import kg.home.kino.data.Progress
import kg.home.kino.data.Selection
import kg.home.kino.data.TsCategory
import kg.home.kino.data.TsSelection
import kg.home.kino.data.TsShow
import kg.home.kino.ui.details.DetailsActivity
import kg.home.kino.ui.grid.GridActivity
import kg.home.kino.ui.player.PlayerActivity
import kg.home.kino.ui.search.SearchActivity
import kg.home.kino.ui.ts.TsDetailsActivity

/** Opens the right screen for whatever card was clicked. */
object Nav {
    fun open(context: Context, item: Any?) {
        when (item) {
            is MovieShort -> context.startActivity(DetailsActivity.intent(context, item.id))
            is TsShow -> context.startActivity(TsDetailsActivity.intent(context, item.slug))
            is Progress -> context.startActivity(
                if (item.isSeries) PlayerActivity.tsIntent(context, item.showSlug, item.fileId, resume = true)
                else PlayerActivity.intent(context, item.movieId, item.fileId, resume = true)
            )
            is Genre -> context.startActivity(GridActivity.intent(context, item.id, item.name))
            is AllMoviesTile -> context.startActivity(GridActivity.intent(context, null, null))
            is Selection -> context.startActivity(GridActivity.selectionIntent(context, item))
            is TsCategory -> context.startActivity(GridActivity.tsIntent(context, item))
            is TsSelection -> context.startActivity(GridActivity.tsSelectionIntent(context, item))
        }
    }

    fun search(context: Context, series: Boolean = false) =
        context.startActivity(SearchActivity.intent(context, series))
}
