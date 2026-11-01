package com.ctify.player.player.fx

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

class NierFeedTest {
    @Test
    fun sineShowsUpInItsBinOnly() {
        val wave = ByteArray(NierFeed.SIZE)
        val fft = ByteArray(NierFeed.SIZE)
        val frames = 44100
        val pcm = ShortArray(frames * 2) { (sin(2 * PI * 16 * (it / 2) / NierFeed.SIZE) * 32000).toInt().toShort() }
        NierFeed.push(pcm, frames, 44100)
        assertTrue(NierFeed.live(wave, fft, 1f))
        fun mag(k: Int) = hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())
        assertTrue("bin 16 = ${mag(16)}", mag(16) > 60)
        assertTrue("bin 100 = ${mag(100)}", mag(100) < 3)
        assertTrue(wave.any { (it.toInt() and 0xFF) > 210 } && wave.any { (it.toInt() and 0xFF) < 45 })
    }
}
