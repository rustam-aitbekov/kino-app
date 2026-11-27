package kg.home.kino.ui.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * The TV's MediaTek H.264 decoder fails `MediaCodec.setOutputSurface` (CodecException 0x80000000 →
 * ERROR_CODE_FAILED_RUNTIME_CHECK) when the video surface changes mid-playback, e.g. when a system
 * popup appears or the app goes to the background and back. Telling ExoPlayer the codec needs the
 * "set output surface workaround" makes it release and re-create the decoder instead.
 */
@OptIn(UnstableApi::class)
class SafeRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(object : MediaCodecVideoRenderer(
            context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
            enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
        ) {
            override fun codecNeedsSetOutputSurfaceWorkaround(name: String) = true
        })
    }
}
