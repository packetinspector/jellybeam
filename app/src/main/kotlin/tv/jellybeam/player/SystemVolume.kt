package tv.jellybeam.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import uniffi.jellybeam_core.VolumeFfi
import kotlin.math.roundToInt

/**
 * docs/31 §4.4: remote volume is the system music stream, since a passthrough output has no
 * player gain; safe from any thread, as [AudioManager] is.
 */
internal class SystemVolume(private val audio: AudioManager) {
    constructor(context: Context) : this(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)

    /** docs/31 §4.4: a fixed-volume device (HDMI-CEC) neither advertises nor applies volume commands. */
    val adjustable: Boolean get() = !audio.isVolumeFixed

    fun apply(op: VolumeOp) {
        when (op) {
            is VolumeOp.Set -> audio.setStreamVolume(STREAM, volumeIndex(op.percent, min(), max()), AudioManager.FLAG_SHOW_UI)
            VolumeOp.Up -> adjust(AudioManager.ADJUST_RAISE)
            VolumeOp.Down -> adjust(AudioManager.ADJUST_LOWER)
            VolumeOp.Mute -> adjust(AudioManager.ADJUST_MUTE)
            VolumeOp.Unmute -> adjust(AudioManager.ADJUST_UNMUTE)
            VolumeOp.ToggleMute -> adjust(AudioManager.ADJUST_TOGGLE_MUTE)
        }
    }

    fun read(): VolumeFfi = VolumeFfi(volumePercent(audio.getStreamVolume(STREAM), min(), max()).toUByte(), audio.isStreamMute(STREAM))

    private fun adjust(direction: Int) = audio.adjustStreamVolume(STREAM, direction, AudioManager.FLAG_SHOW_UI)

    private fun min(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(STREAM) else 0

    private fun max(): Int = audio.getStreamMaxVolume(STREAM)

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
    }
}

/** docs/31 §4.4: the stream index proportional to [percent] (0-100) on the [min]..[max] scale. */
internal fun volumeIndex(percent: Int, min: Int, max: Int): Int =
    min + (percent.coerceIn(0, 100) * (max - min) / 100.0).roundToInt()

/** docs/31 §4.4: stream [index] on the [min]..[max] scale as 0-100; a scale with one step reads full. */
internal fun volumePercent(index: Int, min: Int, max: Int): Int =
    if (max <= min) 100 else ((index - min).coerceIn(0, max - min) * 100.0 / (max - min)).roundToInt()
