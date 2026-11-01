package com.ctify.player.ui.visualizer

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualizerLookTest {
    @Test
    fun defaultsAreAccentAndGainIsClamped() {
        assertEquals(VizSettings(VizColor.ACCENT, 1f, glow = false), VizSettings())
        assertEquals(2f, VizSettings(gain = 9f).clamped().gain)
        assertEquals(0.5f, VizSettings(gain = 0f).clamped().gain)
    }
}
