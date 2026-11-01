package com.ctify.player.player.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The processed audio as Nier Visualizer's renderers read it: [SIZE] bytes of unsigned 8-bit wave
 * and the matching FFT in Android Visualizer layout. Written by the audio thread, read on the UI thread.
 */
object NierFeed {
    const val SIZE = 512
    private const val MASK = (1 shl 17) - 1
    private val ring = FloatArray(MASK + 1)
    @Volatile private var written = 0L
    @Volatile private var rate = 44100
    @Volatile private var pushedAt = 0L
    private val buf = FloatArray(SIZE)
    private var peak = 0f
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private val hann = FloatArray(SIZE) { (0.5 - 0.5 * cos(2.0 * PI * it / (SIZE - 1))).toFloat() }

    fun push(stereo: ShortArray, frames: Int, sampleRate: Int) {
        rate = sampleRate
        var w = written
        for (i in 0 until frames) {
            ring[(w and MASK.toLong()).toInt()] = (stereo[i * 2] + stereo[i * 2 + 1]) / 65536f
            w++
        }
        written = w
        pushedAt = System.nanoTime()
    }

    fun clear() { ring.fill(0f) }

    /** The window being heard now; false until enough audio went through. */
    fun live(wave: ByteArray, fft: ByteArray, gain: Float): Boolean {
        // shortcut: fixed guess of ExoPlayer's AudioTrack buffer, read the real playout position if it drifts on some phones
        // audio comes in chunks of a few hundred ms, so walk through the last one with the clock
        val w = written
        val end = minOf(w, w - rate * 3 / 10 + (System.nanoTime() - pushedAt) * rate / 1_000_000_000L)
        if (end < SIZE) return false
        for (i in 0 until SIZE) buf[i] = ring[((end - SIZE + i) and MASK.toLong()).toInt()]
        // slow auto-gain, so quiet masters fill the picture like loud ones (at most 4x)
        var top = 0f
        for (v in buf) top = maxOf(top, abs(v))
        peak = maxOf(top, peak * 0.97f, 0.01f)
        val auto = (0.8f / peak).coerceAtMost(4f)
        for (i in 0 until SIZE) buf[i] *= auto
        encode(wave, fft, gain)
        return true
    }

    /** A 120 bpm kick over a slow chord at [t] seconds, for previews and pauses. */
    fun fake(t: Float, wave: ByteArray, fft: ByteArray, gain: Float) {
        val k = sin(t * 2 * PI).coerceAtLeast(0.0)
        val kick = k * k * k * k
        for (i in 0 until SIZE) {
            val s = i / 44100.0 + t
            buf[i] = (0.5 * kick * sin(2 * PI * 60 * s) + 0.12 * sin(2 * PI * 220 * s) +
                0.08 * sin(2 * PI * 330 * s + t) + 0.05 * sin(2 * PI * 1760 * s * (1 + 0.1 * sin(t.toDouble())))).toFloat()
        }
        encode(wave, fft, gain)
    }

    private fun encode(wave: ByteArray, fft: ByteArray, gain: Float) {
        for (i in 0 until SIZE) {
            wave[i] = (buf[i] * gain * 128 + 128).toInt().coerceIn(0, 255).toByte()
            re[i] = buf[i] * hann[i]
            im[i] = 0f
        }
        fft(re, im)
        // a full-scale sine lands near 127 like Android's own Visualizer
        val scale = gain * 4f * 128f / SIZE
        fun b(v: Float) = (v * scale).toInt().coerceIn(-128, 127).toByte()
        fft[0] = b(re[0]); fft[1] = b(re[SIZE / 2])
        for (k in 1 until SIZE / 2) { fft[2 * k] = b(re[k]); fft[2 * k + 1] = b(im[k]) }
    }
}
