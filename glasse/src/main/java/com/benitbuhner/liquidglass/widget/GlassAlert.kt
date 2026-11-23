package com.benitbuhner.liquidglass.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.benitbuhner.liquidglass.AlertStyle
import com.benitbuhner.liquidglass.GlassLooks
import com.benitbuhner.liquidglass.GlassPanel
import com.benitbuhner.liquidglass.LiquidGlassLayout
import com.benitbuhner.liquidglass.SpringOptions
import com.benitbuhner.liquidglass.SpringSim
import com.benitbuhner.liquidglass.smooth01
import kotlin.math.min

/**
 * The iOS 26 alert for the View system (twin of Compose `GlassAlert`, web `<GlassAlert>`): thick glass (r 34,
 * bezel 22, blur 18). Style (default: the current [com.benitbuhner.liquidglass.GlassLook.alertStyle]):
 * Center pops in from 0.86 over a dim; Tap grows out of the source view and returns there; Popup is Mail's card
 * of capsule actions anchored at the source, no dimming. Same springs as the web / RN kits. Back / tap outside
 * cancel.
 *
 * ```java
 * new GlassAlert.Builder(view)
 *     .setTitle("Delete photo?")
 *     .setMessage("This can't be undone.")
 *     .addAction("Cancel", GlassAlert.CANCEL, null)
 *     .addAction("Delete", GlassAlert.DESTRUCTIVE, () -> delete())
 *     .show();
 * ```
 */
class GlassAlert private constructor(private val b: Builder) {
    companion object {
        const val DEFAULT = 0
        const val PROMINENT = 1
        const val DESTRUCTIVE = 2
        const val CANCEL = 3
    }

    internal class Action(val label: CharSequence, val style: Int, val run: Runnable?)

    class Builder(internal val anchor: View) {
        internal var title: CharSequence = ""
        internal var message: CharSequence? = null
        internal val actions = ArrayList<Action>()
        internal var style: AlertStyle? = null
        internal var source: View? = null
        internal var onDismiss: Runnable? = null
        fun setTitle(t: CharSequence) = apply { title = t }
        fun setMessage(m: CharSequence?) = apply { message = m }
        /** [style] is one of [DEFAULT], [PROMINENT], [DESTRUCTIVE], [CANCEL]. */
        fun addAction(label: CharSequence, style: Int, onClick: Runnable?) = apply { actions += Action(label, style, onClick) }
        /** Override the look's alert style. */
        fun setStyle(s: AlertStyle?) = apply { style = s }
        /** The control the alert grows out of (Tap / Popup styles). Defaults to the anchor. */
        fun setSource(v: View?) = apply { source = v }
        fun setOnDismissListener(r: Runnable?) = apply { onDismiss = r }
        fun build() = GlassAlert(this)
        fun show() = build().also { it.show() }
    }

    private var view: AlertView? = null

    fun show() {
        if (view != null) return
        val host = Overlays.hostOf(b.anchor)
        val layer = Overlays.layerOf(host)
        if (b.actions.isEmpty()) b.actions += Action("OK", PROMINENT, null)
        val v = AlertView(b.anchor.context, host, b, this)
        view = v
        layer.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun dismiss() { view?.close() }
    internal fun gone() { view = null; b.onDismiss?.run() }

    /** Keynote-measured source-anchored timing (React Native `ALERT_FROM_TAP`), seconds. */
    private object Tap {
        val SHAPE = SpringOptions(562f, 47.4f, 1f, 5e-5f)
        val SHAPE_CLOSE = SpringOptions(778f, 55.8f, 1f, 5e-5f)
        const val GLASS_IN1 = 0.067f
        const val CONTENT_IN0 = 0.067f; const val CONTENT_IN1 = 0.43f
        const val BLUR_IN1 = 0.267f; const val SIGMA = 8f
        const val GLASS_OUT0 = 0.06f; const val GLASS_OUT1 = 0.17f
        const val CONTENT_OUT1 = 0.1f
    }

    @SuppressLint("ViewConstructor")
    private class AlertView(context: Context, private val host: LiquidGlassLayout, private val b: Builder, private val owner: GlassAlert) : FrameLayout(context) {
        private val mode = b.style ?: GlassLooks.current.alertStyle
        private val src = mode != AlertStyle.Center
        private val sc = SpringSim(0.86f, SpringOptions(420f, 24f, 1f, 5e-5f))
        private val pr = SpringSim(0f, SpringOptions(600f, 49f, 1f, 2e-4f))
        private val dim = SpringSim(0f, SpringOptions(260f, 32f, 1f, 2e-4f))
        private var tm = 0f
        private var visible = true
        private val box = GlassPanel(context)
        private val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val th = themeOf(host)
        private val loop = GlassFrameLoop { dt -> frame(dt.toFloat()) }
        private var back: OnBackPressedCallback? = null
        private val origin by lazy { Overlays.boundsIn(b.source ?: b.anchor, host) }

        init {
            setWillNotDraw(false)
            clipChildren = false
            val d = resources.displayMetrics.density
            val red = if (th.isDark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
            val tint = if (th.isDark) 0.72f else 0.74f
            box.glassStyle = box.glassStyle.copy(bezel = androidx.compose.ui.unit.Dp(22f), thickness = androidx.compose.ui.unit.Dp(30f), blur = androidx.compose.ui.unit.Dp(18f), tintAlpha = tint)
            box.isClickable = true // taps on the box don't dismiss
            fun button(a: Action): View = TextView(context).apply {
                text = a.label; textSize = 17f; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setTextColor(when (a.style) { PROMINENT -> android.graphics.Color.WHITE; DESTRUCTIVE -> red; else -> th.label.argb() })
                background = GradientDrawable().apply {
                    cornerRadius = 24 * d
                    setColor(if (a.style == PROMINENT) th.accent.argb() else if (th.isDark) 0x33FFFFFF else 0x14000000)
                }
                setOnClickListener { if (visible) { a.run?.run(); close() } }
            }
            val bh = (48 * d).toInt()
            if (mode == AlertStyle.Popup) {
                box.setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
                if (b.title.isNotEmpty()) inner.addView(TextView(context).apply {
                    text = b.title; textSize = 13f; gravity = Gravity.CENTER; setTextColor(mulAlpha(th.label.argb(), 0.7f))
                    setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
                })
                b.actions.filter { it.style != CANCEL }.forEach { a ->
                    inner.addView(button(a), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bh).apply { topMargin = (8 * d).toInt() })
                }
            } else {
                box.setPadding((16 * d).toInt(), (22 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
                inner.addView(TextView(context).apply {
                    text = b.title; textSize = 17f; setTextColor(th.label.argb()); setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                })
                b.message?.let { m ->
                    inner.addView(TextView(context).apply {
                        text = m; textSize = 15f; setTextColor(mulAlpha(th.label.argb(), 0.8f)); setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), 0)
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }
                val row = LinearLayout(context).apply { orientation = if (b.actions.size > 2) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
                b.actions.forEachIndexed { i, a ->
                    val lp = if (b.actions.size > 2) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bh).apply { if (i > 0) topMargin = (10 * d).toInt() }
                    else LinearLayout.LayoutParams(0, bh, 1f).apply { if (i > 0) marginStart = (10 * d).toInt() }
                    row.addView(button(a), lp)
                }
                inner.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * d).toInt() })
            }
            box.addView(inner)
            addView(box)
            back = Overlays.onBack(this) { close() }
            start(true)
        }

        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, h)
            val d = resources.displayMetrics.density
            val W = MeasureSpec.getSize(w) / d
            val bw = if (mode == AlertStyle.Popup) min(270f, W - 32f) else min(300f, W - 48f)
            box.measure(MeasureSpec.makeMeasureSpec((bw * d).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(h), MeasureSpec.AT_MOST))
            val look = GlassLooks.current
            val r = if (mode == AlertStyle.Popup) look.concentricRadius(34f, 16f, W, MeasureSpec.getSize(h) / d, Float.MAX_VALUE, 22f)
            else if (look.concentric) (look.hardwareRadius(W, MeasureSpec.getSize(h) / d) - 6f).coerceIn(20f, 46f) else 34f
            box.setCornerRadiusDp(r)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val W = r - l; val H = b - t
            val bw = box.measuredWidth; val bh = box.measuredHeight
            if (mode == AlertStyle.Popup) {
                val o = origin; val d = resources.displayMetrics.density
                val leftSide = o.centerX() < W / 2f; val upper = o.centerY() < H / 2f
                val x = (if (leftSide) o.left else o.right - bw).coerceIn(16 * d, W - 16 * d - bw).toInt()
                val y = if (upper) o.top.toInt() else (o.bottom - bh).toInt()
                box.layout(x, y, x + bw, y + bh)
            } else {
                val x = (W - bw) / 2; val y = (H - bh) / 2
                box.layout(x, y, x + bw, y + bh)
            }
            apply()
        }

        private fun start(show: Boolean) {
            tm = 0f
            if (src) {
                sc.opts = if (show) Tap.SHAPE else Tap.SHAPE_CLOSE
                dim.opts = if (show) SpringOptions(260f, 32f, 1f, 2e-4f) else SpringOptions(420f, 41f, 1f, 2e-4f)
                if (show) { sc.set(0f); dim.set(0f) }
                sc.target = if (show) 1f else 0f; pr.set(if (show) 1f else 0f); dim.target = if (show) 1f else 0f
            } else if (show) {
                sc.set(0.86f); pr.set(0f); dim.set(0f)
                sc.opts = SpringOptions(420f, 24f, 1f, 5e-5f); dim.opts = SpringOptions(260f, 32f, 1f, 2e-4f)
                sc.target = 1f; pr.target = 1f; dim.target = 1f
            } else {
                sc.opts = SpringOptions(520f, 46f, 1f, 5e-5f); dim.opts = SpringOptions(420f, 41f, 1f, 2e-4f)
                sc.target = 0.94f; pr.target = 0f; dim.target = 0f
            }
            loop.start()
        }

        fun close() {
            if (!visible) return
            visible = false
            back?.remove(); back = null
            start(false)
        }

        private fun frame(dt: Float): Boolean {
            tm += dt; sc.step(dt); pr.step(dt); dim.step(dt)
            var busy = !(sc.settled && pr.settled && dim.settled)
            if (src) busy = busy || tm < (if (visible) Tap.CONTENT_IN1 else Tap.GLASS_OUT1)
            apply()
            if (!busy && !visible) { post { (parent as? ViewGroup)?.removeView(this); owner.gone() } }
            return busy
        }

        private fun apply() {
            val closing = !visible
            val boxAlpha: Float; val innerAlpha: Float; val innerBlur: Float
            if (!src) {
                val p = pr.value.coerceIn(0f, 1f)
                boxAlpha = p
                innerAlpha = smooth01(0f, 1f, ((p - (if (closing) 0.45f else 0.4f)) / 0.55f).coerceIn(0f, 1f)); innerBlur = 0f
                box.scaleX = sc.value; box.scaleY = sc.value; box.translationX = 0f; box.translationY = 0f
            } else {
                boxAlpha = if (closing) 1 - smooth01(Tap.GLASS_OUT0, Tap.GLASS_OUT1, tm) else smooth01(0f, Tap.GLASS_IN1, tm)
                innerAlpha = if (closing) 1 - smooth01(0f, Tap.CONTENT_OUT1, tm) else smooth01(Tap.CONTENT_IN0, Tap.CONTENT_IN1, tm)
                innerBlur = if (closing) 0f else Tap.SIGMA * (1 - smooth01(0f, Tap.BLUR_IN1, tm))
                if (box.width > 0) {
                    val o = origin
                    val s0 = min(o.width() / box.width, o.height() / box.height).coerceIn(0.12f, 0.5f)
                    val s = s0 + (1 - s0) * sc.value
                    box.scaleX = s; box.scaleY = s
                    box.translationX = (o.centerX() - (box.left + box.width / 2f)) * (1 - s)
                    box.translationY = (o.centerY() - (box.top + box.height / 2f)) * (1 - s)
                }
            }
            box.alpha = boxAlpha.coerceIn(0f, 1f)
            inner.alpha = innerAlpha.coerceIn(0f, 1f)
            if (Build.VERSION.SDK_INT >= 31) {
                val px = innerBlur * 0.6f * resources.displayMetrics.density
                inner.setRenderEffect(if (px > 0.05f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.DECAL) else null)
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (mode != AlertStyle.Popup) canvas.drawColor(mulAlpha(0x42000000, dim.value.coerceIn(0f, 1f)))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && visible) close()
            return true
        }

        override fun onDetachedFromWindow() { loop.stop(); back?.remove(); super.onDetachedFromWindow() }
        /** closing, it takes no input: touches fall through to the screen under it while it animates away */
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = if (!visible) false else super.dispatchTouchEvent(ev)
    }
}
