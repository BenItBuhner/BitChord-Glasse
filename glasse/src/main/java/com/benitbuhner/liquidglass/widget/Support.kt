package com.benitbuhner.liquidglass.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.benitbuhner.liquidglass.GlassMotion
import com.benitbuhner.liquidglass.GlassTheme
import com.benitbuhner.liquidglass.LiquidGlassLayout
import com.benitbuhner.liquidglass.SpringOptions
import com.benitbuhner.liquidglass.SpringSim

/*
 * Shared plumbing for the View-system widgets: a Choreographer frame loop, springs that redraw their view,
 * and the theme of the hosting LiquidGlassLayout.
 */

/** Calls [tick] every display frame with dt in seconds (already scaled by [GlassMotion.timeScale]) until it returns false. */
class GlassFrameLoop(private val tick: Tick) {
    fun interface Tick { fun onFrame(dt: Double): Boolean }

    private var last = 0L
    var isRunning = false; private set
    private val cb = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!isRunning) return
            val dt = GlassMotion.frame(if (last == 0L) 1.0 / 60 else (now - last) / 1e9)
            last = now
            if (tick.onFrame(dt * GlassMotion.timeScale)) Choreographer.getInstance().postFrameCallback(this)
            else { isRunning = false; last = 0L }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true; last = 0L
        Choreographer.getInstance().postFrameCallback(cb)
    }

    fun stop() { isRunning = false; last = 0L; Choreographer.getInstance().removeFrameCallback(cb) }
}

/** A set of springs that step together and invalidate [view] each frame while any of them moves. */
internal class ViewSprings(private val view: View, private val onFrame: (() -> Unit)? = null) {
    private val all = ArrayList<SpringSim>()
    private val loop = GlassFrameLoop { dt ->
        var busy = false
        for (s in all) { if (!s.settled) { s.step(dt.toFloat()); busy = busy || !s.settled } }
        onFrame?.invoke()
        view.invalidate()
        busy
    }

    fun spring(initial: Float, opts: SpringOptions) = SpringSim(initial, opts).also { all += it }

    /** animate [s] to [target] (optionally with a new spring / initial velocity) */
    fun to(s: SpringSim, target: Float, opts: SpringOptions? = null, velocity: Float? = null) {
        if (opts != null) s.opts = opts
        if (velocity != null) s.velocity = velocity
        s.target = target
        if (!s.settled) loop.start()
        view.invalidate()
    }

    fun kick() = loop.start()
    fun stop() = loop.stop()
}

internal fun View.dp(v: Float) = v * resources.displayMetrics.density
internal fun Context.dp(v: Float) = v * resources.displayMetrics.density

/** The theme of the LiquidGlassLayout around [v] (or the system light/dark default). */
internal fun themeOf(v: View): GlassTheme {
    LiquidGlassLayout.of(v)?.let { return it.glassTheme }
    val night = (v.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    return GlassTheme(scheme = if (night) com.benitbuhner.liquidglass.GlassScheme.Dark else com.benitbuhner.liquidglass.GlassScheme.Light)
}

internal fun Color.argb() = toArgb()

internal fun Paint.text(sizePx: Float, color: Int, bold: Boolean = false): Paint {
    textSize = sizePx; this.color = color
    typeface = if (bold) android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD) else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    isAntiAlias = true
    return this
}

/** draw [d] centred at (cx, cy), [size] px square, tinted [color] */
internal fun Canvas.icon(d: Drawable?, cx: Float, cy: Float, size: Float, color: Int, alpha: Float = 1f) {
    if (d == null || alpha <= 0.003f) return
    val h = size / 2
    d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
    d.setTint(color)
    d.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
    d.draw(this)
}

internal fun mulAlpha(argb: Int, a: Float): Int {
    val al = ((argb ushr 24) * a.coerceIn(0f, 1f)).toInt()
    return (al shl 24) or (argb and 0xFFFFFF)
}
