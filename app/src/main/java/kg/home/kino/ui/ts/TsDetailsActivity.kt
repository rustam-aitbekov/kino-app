package kg.home.kino.ui.ts

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
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
import kg.home.kino.data.Progress
import kg.home.kino.data.ProgressStore
import kg.home.kino.data.TS_PROGRESS_PREFIX
import kg.home.kino.data.TsApi
import kg.home.kino.data.TsEpisode
import kg.home.kino.data.TsShowDetails
import kg.home.kino.ui.common.EpisodeCardPresenter
import kg.home.kino.ui.common.EpisodeItem
import kg.home.kino.ui.common.FrameCardPresenter
import kg.home.kino.ui.common.Nav
import kg.home.kino.ui.common.formatTime
import kg.home.kino.ui.player.PlayerActivity
import kotlinx.coroutines.launch

class TsDetailsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, TsShowDetailsFragment.newInstance(intent.getStringExtra(EXTRA_SLUG)!!))
                .commit()
        }
    }

    companion object {
        private const val EXTRA_SLUG = "slug"
        fun intent(context: Context, slug: String) =
            Intent(context, TsDetailsActivity::class.java).putExtra(EXTRA_SLUG, slug)
    }
}

/** Text block next to the poster: title, year · category · genres, voice-over, description. */
class TsDescriptionPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(vh: ViewHolder, item: Any?) {
        val s = item as TsShowDetails
        vh.title.text = s.name
        vh.subtitle.text = listOfNotNull(
            s.year,
            s.countries.firstOrNull(),
            s.genres.take(3).joinToString(", ").ifBlank { null },
        ).joinToString("  ·  ")

        val episodes = s.episodes.size
        val facts = listOfNotNull(
            s.originalName,
            s.category,
            if (s.seasons.size > 1) "Сезонов: ${s.seasons.size}" else null,
            if (episodes > 1) "Серий: $episodes" else s.episodes.firstOrNull()?.duration,
            s.voice?.let { "Озвучка: $it" },
        ).joinToString("  ·  ")
        vh.body.text = listOfNotNull(facts.ifBlank { null }, s.description).joinToString("\n\n")
    }
}

/**
 * A TS.KG show: overview (Смотреть / Продолжить / Озвучка), one row of episodes per season,
 * then frames. Clicking an episode plays it; the player continues with the next ones.
 */
class TsShowDetailsFragment : DetailsSupportFragment() {

    private lateinit var slug: String
    private var show: TsShowDetails? = null
    private lateinit var progressStore: ProgressStore
    private val overviewPresenter = FullWidthDetailsOverviewRowPresenter(TsDescriptionPresenter()).apply {
        initialState = FullWidthDetailsOverviewRowPresenter.STATE_HALF
        onActionClickedListener = OnActionClickedListener { onAction(it) }
    }
    private val rowsAdapter = ArrayObjectAdapter(
        ClassPresenterSelector()
            .addClassPresenter(DetailsOverviewRow::class.java, overviewPresenter)
            .addClassPresenter(ListRow::class.java, ListRowPresenter())
    )
    private val actions = SparseArrayObjectAdapter()
    private val seasonAdapters = ArrayList<ArrayObjectAdapter>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slug = requireArguments().getString(ARG_SLUG)!!
        progressStore = ProgressStore(requireContext())
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is EpisodeItem) playEpisode(item.episode) else Nav.open(requireContext(), item)
        }

        val ctx = requireContext()
        overviewPresenter.backgroundColor = ContextCompat.getColor(ctx, R.color.surface)
        overviewPresenter.actionsBackgroundColor = ContextCompat.getColor(ctx, R.color.ts_brand_dark)

        BackgroundManager.getInstance(requireActivity()).apply {
            if (!isAttached) attach(requireActivity().window)
            color = ContextCompat.getColor(ctx, R.color.background)
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        // Progress may have changed after watching.
        show?.let {
            updateActions(it)
            fillSeasons(it)
        }
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                val s = TsApi.show(slug)
                show = s
                display(s)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.load_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun display(s: TsShowDetails) {
        val row = DetailsOverviewRow(s)
        row.actionsAdapter = actions
        updateActions(s)
        rowsAdapter.add(row)

        loadPoster(row, s.poster)
        loadPoster(row, s.posterLarge)
        s.frames.firstOrNull()?.let(::loadBackdrop)

        seasonAdapters.clear()
        for (season in s.seasons) {
            val a = ArrayObjectAdapter(EpisodeCardPresenter())
            seasonAdapters += a
            val header = if (s.seasons.size == 1 && s.episodes.size == 1) s.name else getString(R.string.ts_season, season.number)
            rowsAdapter.add(ListRow(HeaderItem(header), a))
        }
        fillSeasons(s)

        if (s.frames.isNotEmpty()) {
            val a = ArrayObjectAdapter(FrameCardPresenter()).apply { addAll(0, s.frames) }
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.row_frames)), a))
        }
    }

    /** (Re)fills the episode rows, marking the episode "continue watching" points at. */
    private fun fillSeasons(s: TsShowDetails) {
        val p = progress()
        s.seasons.forEachIndexed { i, season ->
            val a = seasonAdapters.getOrNull(i) ?: return@forEachIndexed
            val items = season.episodes.map { e ->
                val note = if (p != null && p.fileId == e.id) {
                    if (p.positionMs > 0) "${getString(R.string.ts_continue_here)} с ${formatTime(p.positionMs)}"
                    else getString(R.string.ts_continue_here)
                } else null
                EpisodeItem(e, note)
            }
            a.setItems(items, null)
        }
    }

    private fun progress(): Progress? = progressStore.get(TS_PROGRESS_PREFIX + slug)

    private fun updateActions(s: TsShowDetails) {
        actions.clear()
        val episodes = s.episodes
        if (episodes.isEmpty()) {
            actions.set(ACTION_PLAY, Action(ACTION_PLAY.toLong(), getString(R.string.ts_no_episodes)))
        } else {
            val p = progress()
            val current = p?.let { pr -> episodes.firstOrNull { it.id == pr.fileId } }
            if (current != null) {
                val label = if (episodes.size > 1) current.label else formatTime(p.positionMs)
                actions.set(ACTION_CONTINUE, Action(ACTION_CONTINUE.toLong(), getString(R.string.ts_action_continue, label)))
                actions.set(
                    ACTION_PLAY,
                    Action(ACTION_PLAY.toLong(), getString(if (episodes.size > 1) R.string.ts_action_play else R.string.action_restart))
                )
            } else {
                actions.set(ACTION_PLAY, Action(ACTION_PLAY.toLong(), getString(R.string.action_play)))
            }
        }
        if (s.voices.size > 1) {
            val active = s.voices.firstOrNull { it.active }?.name ?: s.voice ?: ""
            actions.set(ACTION_VOICE, Action(ACTION_VOICE.toLong(), getString(R.string.ts_action_voice, active)))
        }
    }

    private fun onAction(action: Action) {
        val s = show ?: return
        when (action.id.toInt()) {
            ACTION_PLAY -> s.episodes.firstOrNull()?.let { play(it, resume = false) }
            ACTION_CONTINUE -> {
                val p = progress()
                val e = s.episodes.firstOrNull { it.id == p?.fileId } ?: s.episodes.firstOrNull() ?: return
                play(e, resume = true)
            }
            ACTION_VOICE -> chooseVoice(s)
        }
    }

    /** Clicked on an episode card: resume it if that's where we stopped, otherwise start it. */
    private fun playEpisode(e: TsEpisode) {
        play(e, resume = progress()?.fileId == e.id)
    }

    private fun play(e: TsEpisode, resume: Boolean) {
        startActivity(PlayerActivity.tsIntent(requireContext(), slug, e.id, resume))
    }

    /** Each voice-over is a separate show on the site; switching opens that show instead. */
    private fun chooseVoice(s: TsShowDetails) {
        val voices = s.voices
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ts_choose_voice)
            .setSingleChoiceItems(voices.map { it.name }.toTypedArray(), voices.indexOfFirst { it.active }) { d, which ->
                d.dismiss()
                val other = voices[which].slug ?: return@setSingleChoiceItems
                startActivity(TsDetailsActivity.intent(requireContext(), other))
                requireActivity().finish()
            }
            .show()
    }

    private fun loadPoster(row: DetailsOverviewRow, url: String?) {
        url ?: return
        val d = resources.displayMetrics.density
        Glide.with(this).load(url).centerCrop().into(object : CustomTarget<Drawable>((256 * d).toInt(), (204 * d).toInt()) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                // Same light backing as the cards: the poster's title text can be black.
                val pad = (10 * d).toInt()
                row.imageDrawable = LayerDrawable(
                    arrayOf(
                        ColorDrawable(ContextCompat.getColor(requireContext(), R.color.poster_backing)),
                        InsetDrawable(resource, pad),
                    )
                )
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
        private const val ARG_SLUG = "slug"
        private const val ACTION_PLAY = 1
        private const val ACTION_CONTINUE = 2
        private const val ACTION_VOICE = 3

        fun newInstance(slug: String) = TsShowDetailsFragment().apply {
            arguments = Bundle().apply { putString(ARG_SLUG, slug) }
        }
    }
}
