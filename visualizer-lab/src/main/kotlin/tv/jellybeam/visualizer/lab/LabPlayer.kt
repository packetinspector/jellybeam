package tv.jellybeam.visualizer.lab

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import tv.jellybeam.visualizer.tap.PcmTap

/**
 * docs/30 §9 T25–T27: an ExoPlayer whose audio outputs feed a real [PcmTap] the way the app's music
 * output does (consumed bytes on write, flush, guarded position), minus the app's clock guard.
 */
@OptIn(UnstableApi::class)
object LabPlayer {
    /** The newest output's last reported position and when (T25's reference clock). */
    @Volatile
    var lastPositionUs = 0L

    @Volatile
    var lastPositionNanos = 0L

    /** Presentation time of the first frame written since the output started or flushed (T25's media base). */
    @Volatile
    var firstPtsUs = Long.MIN_VALUE

    fun build(context: Context, tap: PcmTap): ExoPlayer {
        lastPositionUs = 0L
        lastPositionNanos = 0L
        firstPtsUs = Long.MIN_VALUE
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioOutputProvider(TapProvider(AudioTrackAudioOutputProvider.Builder(context).build(), tap))
                    .build()
        }
        return ExoPlayer.Builder(context, renderers).build()
    }

    fun item(file: File): MediaItem = MediaItem.fromUri(Uri.fromFile(file))

    private class TapProvider(delegate: AudioOutputProvider, private val tap: PcmTap) : ForwardingAudioOutputProvider(delegate) {
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            val out = TapOutput(super.getAudioOutput(config), tap)
            // Media3 may recreate the output on a seek instead of flushing it; the base restarts either way.
            firstPtsUs = Long.MIN_VALUE
            tap.configure(config.encoding, config.sampleRate, Integer.bitCount(config.channelMask), config.isOffload)
            return out
        }
    }

    private class TapOutput(delegate: AudioOutput, private val tap: PcmTap) : ForwardingAudioOutput(delegate) {
        @Volatile
        private var playing = false

        override fun play() {
            super.play()
            playing = true
        }

        override fun pause() {
            playing = false
            super.pause()
            tap.pause(System.nanoTime())
        }

        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            val start = buffer.position()
            if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = presentationTimeUs
            val ok = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            tap.offer(buffer, start, buffer.position())
            return ok
        }

        override fun flush() {
            super.flush()
            firstPtsUs = Long.MIN_VALUE
            tap.flush()
        }

        override fun getPositionUs(): Long {
            val p = super.getPositionUs()
            if (p >= 0) {
                val now = System.nanoTime()
                lastPositionUs = p
                lastPositionNanos = now
                tap.onPosition(p, now, playing, playbackParameters.speed)
            }
            return p
        }
    }

    /** Writes a mono 16-bit WAV of [seconds] with a 2 ms full-scale click every [periodMs], the first at 0. */
    fun clickWav(file: File, seconds: Int, periodMs: Int = 500, rate: Int = LabSignals.RATE): File {
        val frames = seconds * rate
        val data = ByteArray(frames * 2)
        val period = rate * periodMs / 1000
        val click = rate * 2 / 1000
        for (f in 0 until frames) {
            val v: Int = if (f % period < click) 30_000 else 0
            data[f * 2] = (v and 0xFF).toByte()
            data[f * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            out.write("RIFF".toByteArray())
            out.write(le32(36 + data.size))
            out.write("WAVEfmt ".toByteArray())
            out.write(le32(16))
            out.write(le16(1))
            out.write(le16(1))
            out.write(le32(rate))
            out.write(le32(rate * 2))
            out.write(le16(2))
            out.write(le16(16))
            out.write("data".toByteArray())
            out.write(le32(data.size))
            out.write(data)
        }
        return file
    }
}
