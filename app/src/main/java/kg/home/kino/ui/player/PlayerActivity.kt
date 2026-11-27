package kg.home.kino.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import kg.home.kino.R
import kg.home.kino.data.MovieDetails
import kg.home.kino.data.OcApi
import kg.home.kino.data.Progress
import kg.home.kino.data.ProgressStore
import kg.home.kino.data.TS_PROGRESS_PREFIX
import kg.home.kino.data.TsApi
import kg.home.kino.data.TsEpisode
import kg.home.kino.data.TsShowDetails
import kg.home.kino.data.TsStream
import kg.home.kino.data.VideoFile
import kotlinx.coroutines.launch

/**
 * Native full-screen player (ExoPlayer). Plays films straight from oc.kg's video server, and
 * TS.KG episodes from ts.kg's HLS servers - no website, no advert videos. For series it goes
 * on to the next episode by itself, like "Смотреть все серии" on the site.
 *
 * Remote: OK = pause/play, ←/→ = -10s/+10s, Back = hide controls / exit, ⏭ = next episode.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var statusBox: View
    private lateinit var statusText: TextView
    private lateinit var nextButton: TextView
    private lateinit var subsButton: TextView
    private lateinit var progressStore: ProgressStore

    private var player: ExoPlayer? = null

    // OC.KG film
    private var movie: MovieDetails? = null
    private var file: VideoFile? = null

    // TS.KG series
    private var show: TsShowDetails? = null
    private var episodes: List<TsEpisode> = emptyList()
    private var episodeIndex = -1
    private var stream: TsStream? = null
    /** False while the next episode's link is being fetched - nothing to save then. */
    private var episodeReady = false
    private var subsOn = false

    private val isSeries get() = intent.hasExtra(EXTRA_TS_SLUG)

    private val handler = Handler(Looper.getMainLooper())

    /** Time of the last automatic recovery from a playback error (see onPlayerError). */
    private var lastRetryAt = 0L
    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        statusBox = findViewById(R.id.status_box)
        statusText = findViewById(R.id.status_text)
        nextButton = playerView.findViewById(R.id.player_next)
        subsButton = playerView.findViewById(R.id.player_subs)
        progressStore = ProgressStore(this)
        subsOn = getSharedPreferences("player", MODE_PRIVATE).getBoolean("subs", false)

        // Controls hide after 2.5 s (was 4 s), all at once: the default hide animation only knows
        // media3's standard ids, so our title and buttons would disappear at different moments.
        playerView.controllerShowTimeoutMs = 2500
        playerView.setControllerAnimationEnabled(false)
        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.setKeyTimeIncrement(10_000)
        nextButton.setOnClickListener { playNext() }
        subsButton.setOnClickListener { toggleSubtitles() }

        showStatus(getString(R.string.player_resolving), loading = true)
        val resume = intent.getBooleanExtra(EXTRA_RESUME, false)
        if (isSeries) {
            startSeries(intent.getStringExtra(EXTRA_TS_SLUG)!!, intent.getStringExtra(EXTRA_FILE), resume)
        } else {
            startMovie(intent.getStringExtra(EXTRA_MOVIE)!!, intent.getStringExtra(EXTRA_FILE), resume)
        }
    }

    // ---------------------------------------------------------------- OC.KG

    private fun startMovie(movieId: String, fileId: String?, resume: Boolean) {
        lifecycleScope.launch {
            try {
                val m = OcApi.movie(movieId)
                val f = m.files.firstOrNull { it.id == fileId } ?: m.files.firstOrNull()
                    ?: throw IllegalStateException(getString(R.string.no_files))
                movie = m
                file = f
                showTitle(if (m.files.size > 1) "${m.name} — ${f.name}" else m.name)

                val url = OcApi.streamUrl(f)
                val startAt = if (resume) progressStore.get(m.id)?.takeIf { it.fileId == f.id }?.positionMs ?: 0L else 0L
                play(MediaItem.fromUri(url), startAt)
            } catch (e: Exception) {
                showStatus(getString(R.string.player_error, e.message ?: e.javaClass.simpleName), loading = false)
            }
        }
    }

    // ---------------------------------------------------------------- TS.KG

    private fun startSeries(slug: String, episodeId: String?, resume: Boolean) {
        lifecycleScope.launch {
            try {
                val s = TsApi.show(slug)
                if (s.episodes.isEmpty()) throw IllegalStateException(getString(R.string.ts_no_episodes))
                show = s
                episodes = s.episodes
                val index = episodes.indexOfFirst { it.id == episodeId }.coerceAtLeast(0)
                val startAt = if (resume) {
                    progressStore.get(TS_PROGRESS_PREFIX + slug)?.takeIf { it.fileId == episodes[index].id }?.positionMs ?: 0L
                } else 0L
                playEpisode(index, startAt)
            } catch (e: Exception) {
                showStatus(getString(R.string.player_error, e.message ?: e.javaClass.simpleName), loading = false)
            }
        }
    }

    private fun playEpisode(index: Int, startAtMs: Long) {
        val s = show ?: return
        val e = episodes.getOrNull(index) ?: return
        episodeReady = false
        player?.stop()
        showStatus(getString(R.string.player_resolving), loading = true)
        lifecycleScope.launch {
            try {
                val st = TsApi.episode(e.id)
                stream = st
                episodeIndex = index
                showTitle(if (episodes.size > 1) "${s.name} — ${e.label}${e.name?.let { ": $it" } ?: ""}" else s.name)
                nextButton.visibility = if (index < episodes.size - 1) View.VISIBLE else View.GONE
                subsButton.visibility = if (st.subtitles != null) View.VISIBLE else View.GONE
                updateSubsButton()

                val item = MediaItem.Builder()
                    .setUri(st.url)
                    .apply {
                        val hls = st.mimeType?.contains("mpegurl", ignoreCase = true) == true || st.url.contains(".m3u8")
                        if (hls) setMimeType(MimeTypes.APPLICATION_M3U8)
                        st.subtitles?.let { sub ->
                            setSubtitleConfigurations(
                                listOf(
                                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                                        .setMimeType(MimeTypes.TEXT_VTT)
                                        .setLanguage("ru")
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                )
                            )
                        }
                    }
                    .build()
                play(item, startAtMs)
                episodeReady = true
                // Remember the episode right away, so "continue" points here even if stopped early.
                if (startAtMs == 0L && index > 0) saveSeriesProgress(e, 0L, st.durationSeconds * 1000, force = true)
            } catch (ex: Exception) {
                showStatus(getString(R.string.player_error, ex.message ?: ex.javaClass.simpleName), loading = false)
            }
        }
    }

    private fun playNext() {
        if (!isSeries || episodeIndex < 0 || episodeIndex >= episodes.size - 1) return
        playEpisode(episodeIndex + 1, 0L)
    }

    private fun toggleSubtitles() {
        subsOn = !subsOn
        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("subs", subsOn).apply()
        applySubtitleSetting()
        updateSubsButton()
    }

    private fun updateSubsButton() {
        subsButton.setText(if (subsOn) R.string.player_subs_on else R.string.player_subs_off)
    }

    private fun applySubtitleSetting() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
            .setPreferredTextLanguage("ru")
            .build()
    }

    // ---------------------------------------------------------------- playback

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val referer = if (isSeries) "${TsApi.BASE}/" else "${OcApi.BASE}/"
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(OcApi.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to referer))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)

        val p = ExoPlayer.Builder(this, SafeRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player = p
        playerView.player = p
        applySubtitleSetting()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> hideStatus()
                    Player.STATE_ENDED -> onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Decoder / network hiccups: retry once from the same position before giving up.
                val now = System.currentTimeMillis()
                if (now - lastRetryAt > 30_000) {
                    lastRetryAt = now
                    p.prepare()
                    return
                }
                showStatus(getString(R.string.player_error, error.errorCodeName), loading = false)
            }
        })
        handler.postDelayed(saver, 10_000)
        return p
    }

    private fun play(item: MediaItem, startAtMs: Long) {
        val p = ensurePlayer()
        p.setMediaItem(item)
        if (startAtMs > 0) p.seekTo(startAtMs)
        p.playWhenReady = true
        p.prepare()
    }

    private fun onEnded() {
        if (isSeries) {
            val s = show ?: return finish()
            if (!episodeReady) return
            episodeReady = false
            if (episodeIndex < episodes.size - 1) {
                playEpisode(episodeIndex + 1, 0L)
            } else {
                progressStore.remove(TS_PROGRESS_PREFIX + s.slug)
                finish()
            }
        } else {
            movie?.let { progressStore.remove(it.id) }
            finish()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)
        val controlsVisible = playerView.isControllerFullyVisible
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> if (isSeries) {
                    playNext()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!controlsVisible) {
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) p.seekBack() else p.seekForward()
                    playerView.showController()
                    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> if (!controlsVisible) {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController()
                    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!controlsVisible) {
                    playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> if (controlsVisible) {
                    playerView.hideController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- progress

    private fun saveProgress() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE) return
        if (isSeries) {
            if (!episodeReady) return
            val e = episodes.getOrNull(episodeIndex) ?: return
            val duration = p.duration.takeIf { it > 0 } ?: ((stream?.durationSeconds ?: 0L) * 1000)
            val next = episodes.getOrNull(episodeIndex + 1)
            if (duration > 0 && p.currentPosition > duration * 0.95 && next != null) {
                // Credits of an episode: "continue" should start the next one.
                saveSeriesProgress(next, 0L, 0L, force = true)
            } else {
                saveSeriesProgress(e, p.currentPosition, duration, force = false)
            }
            return
        }
        val m = movie ?: return
        val f = file ?: return
        val duration = p.duration.takeIf { it > 0 } ?: (f.durationSeconds * 1000)
        progressStore.save(
            Progress(
                movieId = m.id,
                fileId = f.id,
                name = m.name,
                poster = m.posterThumb,
                positionMs = p.currentPosition,
                durationMs = duration,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun saveSeriesProgress(e: TsEpisode, positionMs: Long, durationMs: Long, force: Boolean) {
        val s = show ?: return
        val name = if (episodes.size > 1) "${s.name} · ${e.season}x${e.number}" else s.name
        progressStore.save(
            Progress(
                movieId = TS_PROGRESS_PREFIX + s.slug,
                fileId = e.id,
                name = name,
                poster = s.poster,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            ),
            force = force,
        )
    }

    // ---------------------------------------------------------------- UI

    private fun showTitle(text: String) {
        playerView.findViewById<TextView>(R.id.player_title)?.text = text
    }

    /**
     * Our own status box ("Подготовка видео…" / errors). While it is up, the player's built-in
     * buffering spinner is turned off - otherwise two spinners show during loading.
     */
    private fun showStatus(text: String, loading: Boolean) {
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        statusBox.visibility = View.VISIBLE
        statusText.text = text
        findViewById<View>(R.id.status_progress).visibility = if (loading) View.VISIBLE else View.GONE
    }

    /** Playback started: our box goes, the player's spinner handles any later re-buffering. */
    private fun hideStatus() {
        statusBox.visibility = View.GONE
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MOVIE = "movie"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_RESUME = "resume"
        private const val EXTRA_TS_SLUG = "ts_slug"

        /** OC.KG film. */
        fun intent(context: Context, movieId: String, fileId: String?, resume: Boolean) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_MOVIE, movieId)
                .putExtra(EXTRA_FILE, fileId)
                .putExtra(EXTRA_RESUME, resume)

        /** TS.KG episode; the following episodes of the show play after it. */
        fun tsIntent(context: Context, slug: String, episodeId: String?, resume: Boolean) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_TS_SLUG, slug)
                .putExtra(EXTRA_FILE, episodeId)
                .putExtra(EXTRA_RESUME, resume)
    }
}
