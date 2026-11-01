package com.ctify.player.ui.visualizer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

enum class VizColor(val label: String) {
    WHITE("White"), ACCENT("Theme accent"), GRADIENT("Two-color gradient"), OIL("Oil"),
}

/** One look shared by every visualizer. */
data class VizSettings(
    val color: VizColor = VizColor.ACCENT,
    val gain: Float = 1f,
    val glow: Boolean = false,
) {
    fun clamped() = copy(gain = gain.coerceIn(0.5f, 2f))
}

/** The saved [VizSettings], in the same "ctify-ui" prefs as the chosen visualizer. */
object VisualizerLook {
    private const val PREFS = "ctify-ui"
    val settings = MutableStateFlow(VizSettings())
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings.value = VizSettings(
            color = runCatching { VizColor.valueOf(p.getString("viz_color", null)!!) }.getOrDefault(VizColor.ACCENT),
            gain = p.getFloat("viz_gain", 1f),
            glow = p.getBoolean("viz_glow", false),
        ).clamped()
    }

    /** Applies at once; call [save] when the change is done (sliders save on release). */
    fun update(s: VizSettings) { settings.value = s.clamped() }

    fun save(ctx: Context) {
        val s = settings.value
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("viz_color", s.color.name)
            .putFloat("viz_gain", s.gain)
            .putBoolean("viz_glow", s.glow)
            .apply()
    }
}
