package kg.home.kino.ui.details

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class DetailsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, MovieDetailsFragment.newInstance(intent.getStringExtra(EXTRA_ID)!!))
                .commit()
        }
    }

    companion object {
        private const val EXTRA_ID = "movie_id"
        fun intent(context: Context, movieId: String) =
            Intent(context, DetailsActivity::class.java).putExtra(EXTRA_ID, movieId)
    }
}
