package com.ctify.player.ui.visualizer

import android.content.Context
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.ctify.player.player.PlayerHub
import com.ctify.player.player.fx.NierFeed
import com.ctify.player.ui.reducedMotion
import me.bogerchan.niervisualizer.renderer.IRenderer
import me.bogerchan.niervisualizer.renderer.circle.CircleBarRenderer
import me.bogerchan.niervisualizer.renderer.circle.CircleRenderer
import me.bogerchan.niervisualizer.renderer.circle.CircleSolidRenderer
import me.bogerchan.niervisualizer.renderer.circle.CircleWaveRenderer
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType1Renderer
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType2Renderer
import me.bogerchan.niervisualizer.renderer.columnar.ColumnarType4Renderer
import me.bogerchan.niervisualizer.renderer.line.LineRenderer
import me.bogerchan.niervisualizer.renderer.other.ArcStaticRenderer

/** Nier Visualizer renderers the stage can show instead of a skin; [art] is the cover size in the middle, 0 for none. */
enum class Visualizer(val label: String, val make: () -> IRenderer, val art: Float = 0f) {
    COLUMNS("Columns", { ColumnarType1Renderer() }),
    COLUMNS_SPIN("Spinning columns", { ColumnarType2Renderer() }),
    SPECTRUM("Spectrum", { ColumnarType4Renderer() }),
    LINE("Line", { LineRenderer(false) }),
    CIRCLE("Circle", { CircleRenderer(false) }),
    CIRCLE_BARS("Circle bars", { CircleBarRenderer() }, 0.42f),
    CIRCLE_BURST("Circle burst", { CircleBarRenderer(type = CircleBarRenderer.Type.TYPE_A_AND_TYPE_B) }, 0.42f),
    CIRCLE_WAVE("Circle wave", { CircleWaveRenderer() }, 0.42f),
    PULSE("Pulse", { CircleSolidRenderer() }),
    ARCS("Arcs", { ArcStaticRenderer() }, 0.5f);

    companion object {
        private const val PREFS = "ctify-ui"

        /** The chosen visualizer, or null when a skin is on stage. */
        fun load(ctx: Context): Visualizer? = runCatching {
            valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("visualizer", null)!!)
        }.getOrNull()

        fun save(ctx: Context, v: Visualizer?) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("visualizer", v?.name).apply()
        }
    }
}

/** Under this, still previews animate anyway: live audio when something plays, a made-up beat otherwise. */
val LocalVisualizerDemo = staticCompositionLocalOf { false }

/** One renderer with its own data buffers, so drawing a frame allocates nothing. */
private class Stage(v: Visualizer) {
    val renderer = v.make().also { it.onStart(NierFeed.SIZE) }
    val wave = ByteArray(NierFeed.SIZE)
    val fft = ByteArray(NierFeed.SIZE)
    private val shown = FloatArray(NierFeed.SIZE) { if (renderer.getInputDataType() == IRenderer.DataType.WAVE) 128f else 0f }
    val out = ByteArray(NierFeed.SIZE)
    val area = Rect()
    val start = System.nanoTime()
    var lastFeed = 0L
    var lastDraw = 0L

    /** Eases [out] toward the newest data by frame time, so slow frames still move. */
    fun ease(now: Long) {
        val dt = if (lastDraw == 0L) 1f else (now - lastDraw) / 1e9f
        lastDraw = now
        val k = 1f - kotlin.math.exp(-dt / 0.06f)
        val isWave = renderer.getInputDataType() == IRenderer.DataType.WAVE
        for (i in shown.indices) {
            val target = if (isWave) (wave[i].toInt() and 0xFF).toFloat() else fft[i].toFloat()
            shown[i] += (target - shown[i]) * k
            out[i] = shown[i].toInt().toByte()
        }
    }
}

/**
 * [v] drawn [side] wide from the processed audio, recoloured by the [VisualizerLook] settings.
 * Still or paused views show one frame of a made-up beat.
 */
@Composable
fun VisualizerView(v: Visualizer, art: Painter, playing: Boolean, still: Boolean, side: Dp) {
    val ctx = LocalContext.current
    remember { VisualizerLook.load(ctx) }
    val look by VisualizerLook.settings.collectAsState()
    val reduced = reducedMotion()
    val demo = LocalVisualizerDemo.current && !reduced
    val audio = !reduced && if (demo) PlayerHub.playback.collectAsState().value?.isPlaying == true else playing && !still
    val live = audio || demo
    val tick = remember { mutableLongStateOf(0L) }
    if (live) LaunchedEffect(Unit) { while (true) withFrameNanos { tick.longValue = it } }
    val st = remember(v) { Stage(v) }
    DisposableEffect(st) { onDispose { st.renderer.onStop() } }

    val one = MaterialTheme.colorScheme.primary
    val two = MaterialTheme.colorScheme.tertiary
    // Nier draws in its own colours, this recolours them keeping their alpha
    val tint: Brush = remember(look.color, one, two) {
        when (look.color) {
            VizColor.WHITE -> SolidColor(Color.White)
            VizColor.ACCENT -> SolidColor(one)
            VizColor.GRADIENT -> Brush.linearGradient(listOf(one, two))
            VizColor.OIL -> Brush.sweepGradient(listOf(one, two, one))
        }
    }
    val blur = with(LocalDensity.current) { side.toPx() } * 0.025f
    val glow = remember(look.glow, blur) {
        // shortcut: no glow before Android 12 (no RenderEffect), draw a blurred copy by hand if older phones need it
        if (look.glow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) glowEffect(blur) else null
    }

    Box(Modifier.size(side).semantics { contentDescription = "${v.label} visualizer" }, contentAlignment = Alignment.Center) {
        if (v.art > 0f) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.size(side * v.art).clip(CircleShape))
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                renderEffect = glow
            },
        ) {
            tick.longValue // subscribes the draw to the frame clock while live
            val now = System.nanoTime()
            val t = (now - st.start) / 1e9f
            // new audio every 50 ms like Android's Visualizer, eased in between
            if (!live || now - st.lastFeed > 50_000_000L) {
                st.lastFeed = now
                if (!(audio && NierFeed.live(st.wave, st.fft, look.gain))) NierFeed.fake(if (live) t else 0.1f, st.wave, st.fft, look.gain)
            }
            if (live) st.ease(now)
            st.area.set(0, 0, size.width.toInt(), size.height.toInt())
            val r = st.renderer
            r.calculate(st.area, if (live) st.out else if (r.getInputDataType() == IRenderer.DataType.WAVE) st.wave else st.fft)
            drawIntoCanvas { r.render(it.nativeCanvas) }
            if (look.color == VizColor.OIL) rotate(t * 12f) { drawCircle(tint, size.maxDimension, blendMode = BlendMode.SrcIn) }
            else drawRect(tint, blendMode = BlendMode.SrcIn)
        }
    }
}

/** The drawing with a soft blurred copy of itself underneath. */
@RequiresApi(Build.VERSION_CODES.S)
private fun glowEffect(r: Float) = android.graphics.RenderEffect.createBlendModeEffect(
    android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.DECAL),
    android.graphics.RenderEffect.createOffsetEffect(0f, 0f),
    android.graphics.BlendMode.SRC_OVER,
).asComposeRenderEffect()
