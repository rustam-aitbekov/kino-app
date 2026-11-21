package kg.home.kino.ui.details

import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnActionClickedListener
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.SparseArrayObjectAdapter
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kg.home.kino.R
import kg.home.kino.data.MovieDetails
import kg.home.kino.data.OcApi
import kg.home.kino.data.ProgressStore
import kg.home.kino.data.VideoFile
import kg.home.kino.ui.common.FrameCardPresenter
import kg.home.kino.ui.common.MovieCardPresenter
import kg.home.kino.ui.common.Nav
import kg.home.kino.ui.common.PersonCardPresenter
import kg.home.kino.ui.common.formatTime
import kg.home.kino.ui.player.PlayerActivity
import kotlinx.coroutines.launch

class MovieDetailsFragment : DetailsSupportFragment() {

    private lateinit var movieId: String
    private var movie: MovieDetails? = null
    private lateinit var progressStore: ProgressStore
    private val overviewPresenter = FullWidthDetailsOverviewRowPresenter(DescriptionPresenter()).apply {
        initialState = FullWidthDetailsOverviewRowPresenter.STATE_HALF
        onActionClickedListener = OnActionClickedListener { onAction(it) }
    }
    private val rowsAdapter = ArrayObjectAdapter(
        ClassPresenterSelector()
            .addClassPresenter(DetailsOverviewRow::class.java, overviewPresenter)
            .addClassPresenter(ListRow::class.java, ListRowPresenter())
    )
    private val actions = SparseArrayObjectAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        movieId = requireArguments().getString(ARG_ID)!!
        progressStore = ProgressStore(requireContext())
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ -> Nav.open(requireContext(), item) }

        val ctx = requireContext()
        overviewPresenter.backgroundColor = ContextCompat.getColor(ctx, R.color.surface)
        overviewPresenter.actionsBackgroundColor = ContextCompat.getColor(ctx, R.color.brand_dark)

        BackgroundManager.getInstance(requireActivity()).apply {
            if (!isAttached) attach(requireActivity().window)
            color = ContextCompat.getColor(ctx, R.color.background)
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        movie?.let { updateActions(it) }   // progress may have changed after watching
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val m = OcApi.movie(movieId)
                movie = m
                show(m)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun show(m: MovieDetails) {
        val row = DetailsOverviewRow(m)
        row.actionsAdapter = actions
        updateActions(m)
        rowsAdapter.add(row)

        // Poster: the 200x300 thumbnail first (fast), then the full-size original.
        loadPoster(row, m.posterThumb)
        loadPoster(row, m.posterOriginal)
        // Backdrop: a frame from the film.
        m.frames.firstOrNull()?.let(::loadBackdrop)

        if (m.frames.isNotEmpty()) {
            val a = ArrayObjectAdapter(FrameCardPresenter()).apply { addAll(0, m.frames) }
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.row_frames)), a))
        }
        val cast = m.persons.filter { it.role != "режиссер" }.ifEmpty { m.persons }
        if (cast.isNotEmpty()) {
            val a = ArrayObjectAdapter(PersonCardPresenter()).apply { addAll(0, cast) }
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.row_cast)), a))
        }
        if (m.similar.isNotEmpty()) {
            val a = ArrayObjectAdapter(MovieCardPresenter()).apply { addAll(0, m.similar) }
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.row_similar)), a))
        }
    }

    private fun updateActions(m: MovieDetails) {
        actions.clear()
        if (m.files.isEmpty()) {
            actions.set(ACTION_PLAY, Action(ACTION_PLAY.toLong(), getString(R.string.no_files)))
            return
        }
        val p = progressStore.get(m.id)
        if (p != null) {
            actions.set(ACTION_RESUME, Action(ACTION_RESUME.toLong(), getString(R.string.action_resume, formatTime(p.positionMs))))
            actions.set(ACTION_RESTART, Action(ACTION_RESTART.toLong(), getString(R.string.action_restart)))
        } else {
            actions.set(ACTION_PLAY, Action(ACTION_PLAY.toLong(), getString(R.string.action_play)))
        }
        if (m.files.size > 1) {
            actions.set(ACTION_FILES, Action(ACTION_FILES.toLong(), getString(R.string.action_files, m.files.size)))
        }
    }

    private fun onAction(action: Action) {
        val m = movie ?: return
        if (m.files.isEmpty()) return
        when (action.id.toInt()) {
            ACTION_PLAY -> play(m, m.files.first(), resume = false)
            ACTION_RESUME -> {
                val p = progressStore.get(m.id)
                val file = m.files.firstOrNull { it.id == p?.fileId } ?: m.files.first()
                play(m, file, resume = true)
            }
            ACTION_RESTART -> {
                val p = progressStore.get(m.id)
                val file = m.files.firstOrNull { it.id == p?.fileId } ?: m.files.first()
                play(m, file, resume = false)
            }
            ACTION_FILES -> chooseFile(m)
        }
    }

    private fun chooseFile(m: MovieDetails) {
        val names = m.files.map { f -> listOfNotNull(f.name, f.durationText).joinToString("  ·  ") }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.choose_file)
            .setItems(names) { _, which -> play(m, m.files[which], resume = false) }
            .show()
    }

    private fun play(m: MovieDetails, file: VideoFile, resume: Boolean) {
        startActivity(PlayerActivity.intent(requireContext(), m.id, file.id, resume))
    }

    private fun loadPoster(row: DetailsOverviewRow, url: String?) {
        url ?: return
        Glide.with(this).load(url).centerCrop().into(object : CustomTarget<Drawable>(
            (200 * resources.displayMetrics.density).toInt(), (300 * resources.displayMetrics.density).toInt()
        ) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                row.imageDrawable = resource
            }
            override fun onLoadCleared(placeholder: Drawable?) {}
        })
    }

    private fun loadBackdrop(url: String) {
        val bm = BackgroundManager.getInstance(requireActivity())
        Glide.with(this).load(url).centerCrop().into(object : CustomTarget<Drawable>(
            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
        ) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                resource.alpha = 90   // dimmed so text stays readable
                bm.drawable = resource
            }
            override fun onLoadCleared(placeholder: Drawable?) {}
        })
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ACTION_PLAY = 1
        private const val ACTION_RESUME = 2
        private const val ACTION_RESTART = 3
        private const val ACTION_FILES = 4

        fun newInstance(movieId: String) = MovieDetailsFragment().apply {
            arguments = Bundle().apply { putString(ARG_ID, movieId) }
        }
    }
}
