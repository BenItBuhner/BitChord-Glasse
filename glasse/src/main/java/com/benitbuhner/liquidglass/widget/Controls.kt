package com.benitbuhner.liquidglass.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.benitbuhner.liquidglass.GlassPainter
import com.benitbuhner.liquidglass.GlassSpringPresets
import com.benitbuhner.liquidglass.GlassStyle
import com.benitbuhner.liquidglass.SpringOptions
import com.benitbuhner.liquidglass.rubberBand
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The clear thumb shared by switch, slider and segmented control (Compose `thumbStyle`). m5: the same rim as
 *  every other glass (default bezel / thickness, unmagnified body): Apple's switch and slider lenses only bend at the rim. */
internal val THUMB = GlassStyle(
    blur = 0.dp, tintAlpha = 0.04f,
    saturation = 1.2f, brightness = 1.06f, adaptive = false, shadow = 0.9f,
)

/**
 * A control that draws its own "track" into a local RenderNode so its glass thumb can refract it
 * (the thumb magnifies the track like iOS 26). Subclasses draw the track in [drawTrack] and the rest in [drawOver].
 */
abstract class LocalGlassView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val local: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-local") else null
    protected val painter = GlassPainter()
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    protected abstract fun drawTrack(c: Canvas)
    protected abstract fun drawOver(c: Canvas)

    final override fun onDraw(canvas: Canvas) {
        val n = local
        if (n != null && canvas.isHardwareAccelerated) {
            n.setPosition(0, 0, width, height)
            val rc = n.beginRecording(width, height)
            try { drawTrack(rc) } finally { n.endRecording() }
            canvas.drawRenderNode(n)
        } else drawTrack(canvas)
        drawOver(canvas)
    }

    /** glass of w×h centred at (cx, cy), refracting the track */
    protected fun thumb(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, style: GlassStyle, lift: Float, @ColorInt cover: Int, coverAlpha: Float) {
        val x = cx - w / 2; val y = cy - h / 2
        val n = local
        canvas.save(); canvas.translate(x, y)
        painter.draw(
            canvas, resources.displayMetrics.density, w, h, style, themeOf(this), lift, h / 2, null, 0f,
            if (n == null) null else { rc -> rc.translate(-x, -y); rc.drawRenderNode(n) },
        )
        if (coverAlpha > 0.003f) {
            paint.style = Paint.Style.FILL; paint.color = mulAlpha(cover, coverAlpha)
            canvas.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
        }
        canvas.restore()
    }

    /** let a scrolling / swiping parent take over only once we know the drag isn't ours */
    protected fun claim(claim: Boolean) { parent?.requestDisallowInterceptTouchEvent(claim) }

    /* direction gate: the first touch slop of a drag decides who owns it. Sideways: this control (it then
       claims the touch from a scrolling parent). Mostly vertical: the parent scrolls (it intercepts, we get
       CANCEL), so a scroll that starts on a slider / switch / segmented control keeps scrolling. */
    private var gx = 0f; private var gy = 0f
    /** the drag turned out sideways: the control has it */
    protected var owned = false
    /** the finger went past the slop in any direction (not a tap any more) */
    protected var far = false
    protected fun gateDown(e: MotionEvent) { gx = e.x; gy = e.y; owned = false; far = false }
    protected fun gateMove(e: MotionEvent): Boolean {
        if (owned) return true
        val dx = e.x - gx; val dy = e.y - gy
        if (kotlin.math.hypot(dx, dy) > ViewConfiguration.get(context).scaledTouchSlop) {
            far = true
            if (abs(dx) > abs(dy)) { owned = true; claim(true) }
        }
        return owned
    }
}

/* ─────────────────────────── Switch ─────────────────────────── */

/**
 * iOS 26 switch: the white thumb turns into a clear lens while touched, magnifying the track under it, and
 * can be dragged or tapped. 63×28 dp track inside a 44 dp touch target. Twin of Compose `GlassSwitch`.
 */
class GlassSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LocalGlassView(context, attrs) {
    fun interface OnCheckedChangeListener { fun onCheckedChanged(view: GlassSwitch, checked: Boolean) }

    private val springs = ViewSprings(this)
    private val pos = springs.spring(0f, GlassSpringPresets.bouncy)
    private val lift = springs.spring(0f, GlassSpringPresets.press)
    private var checked = false
    private var listener: OnCheckedChangeListener? = null
    @ColorInt var onColor: Int = 0xFF34C759.toInt()
        set(v) { field = v; invalidate() }

    init { isClickable = true; isFocusable = true }

    fun isChecked() = checked
    /** Set the state (animates). Does not call the listener. */
    fun setChecked(c: Boolean) {
        if (c == checked) return
        checked = c
        if (isAttachedToWindow && isLaidOut) springs.to(pos, if (c) 1f else 0f, GlassSpringPresets.bouncy) else pos.set(if (c) 1f else 0f)
    }
    fun toggle() { setChecked(!checked); listener?.onCheckedChanged(this, checked) }
    fun setOnCheckedChangeListener(l: OnCheckedChangeListener?) { listener = l }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(dp(75f).toInt(), w), resolveSize(dp(44f).toInt(), h))

    private val trackW get() = dp(63f)
    private val trackH get() = dp(28f)
    private val tx get() = (width - trackW) / 2
    private val ty get() = (height - trackH) / 2

    override fun drawTrack(c: Canvas) {
        val th = themeOf(this)
        val off = if (th.isDark) Color(0xFF39393D) else Color(0xFFE3E3E6)
        paint.style = Paint.Style.FILL
        paint.color = lerp(off, Color(onColor), pos.value.coerceIn(0f, 1f)).argb()
        c.drawRoundRect(tx, ty, tx + trackW, ty + trackH, trackH / 2, trackH / 2, paint)
    }

    override fun drawOver(c: Canvas) {
        val l = lift.value
        val tw = dp(39f); val tH = dp(24f); val travel = trackW - tw - dp(4f)
        val cx = tx + dp(2f) + travel * pos.value + tw / 2
        val s = 1f + 0.45f * l
        thumb(c, cx, height / 2f, tw * s * (1f + 0.12f * l), tH * s, THUMB, l, android.graphics.Color.WHITE, (1f - l).coerceIn(0f, 1f))
    }

    private var downX = 0f; private var dragged = false; private var acc = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val travel = trackW - dp(39f) - dp(4f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; acc = 0f; dragged = false; gateDown(e); springs.to(lift, 1f, GlassSpringPresets.press) }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; downX = e.x
                if (gateMove(e)) { acc += dx; dragged = true; pos.set((pos.value + dx / travel).coerceIn(0f, 1f)); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                claim(false)
                springs.to(lift, 0f, GlassSpringPresets.bouncy)
                // cancelled, or a vertical drag nobody scrolled: no toggle
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || far && !owned) { springs.to(pos, if (checked) 1f else 0f); return true }
                val target = if (dragged) pos.value > 0.5f else !checked
                if (target != checked) { checked = target; listener?.onCheckedChanged(this, checked) }
                springs.to(pos, if (checked) 1f else 0f, GlassSpringPresets.bouncy)
                if (!dragged) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { springs.stop(); super.onDetachedFromWindow() }
}

/* ─────────────────────────── Slider ─────────────────────────── */

/**
 * iOS 26 slider: accent fill, a white knob that becomes a magnifying lens while dragged (×1.55), optional
 * [step] with tick marks and min / max icons, rubber-banding past the ends. Same spring (700/44) as the web
 * `<GlassSlider>`; twin of Compose `GlassSlider`.
 */
class GlassSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LocalGlassView(context, attrs) {
    fun interface OnChangeListener { fun onValueChange(slider: GlassSlider, value: Float, fromUser: Boolean) }

    private val springs = ViewSprings(this)
    private val x = springs.spring(0f, SpringOptions(700f, 44f))
    private val lift = springs.spring(0f, GlassSpringPresets.press)
    private var value = 0.5f
    var min = 0f; set(v) { field = v; snapToValue() }
    var max = 1f; set(v) { field = v; snapToValue() }
    /** snap step (0 = continuous) */
    var step = 0f; set(v) { field = v; invalidate() }
    /** draw a dot at every [step] */
    var showTicks = false; set(v) { field = v; invalidate() }
    @ColorInt var fillColor: Int = 0; set(v) { field = v; invalidate() }
    private var minIcon: Drawable? = null
    private var maxIcon: Drawable? = null
    private var listener: OnChangeListener? = null
    private var dragging = false

    init { isClickable = true; isFocusable = true }

    fun getValue() = value
    fun setValue(v: Float) { value = v.coerceIn(min, max); if (!dragging) { if (isLaidOut) springs.to(x, frac(value) * area()) else snapToValue() } }
    fun setOnChangeListener(l: OnChangeListener?) { listener = l }
    fun setMinIcon(d: Drawable?) { minIcon = d?.mutate(); invalidate() }
    fun setMinIcon(@DrawableRes r: Int) = setMinIcon(ContextCompat.getDrawable(context, r))
    fun setMaxIcon(d: Drawable?) { maxIcon = d?.mutate(); invalidate() }
    fun setMaxIcon(@DrawableRes r: Int) = setMaxIcon(ContextCompat.getDrawable(context, r))

    private fun frac(v: Float) = if (max == min) 0f else (v - min) / (max - min)
    private val icon get() = dp(18f)
    private val gap get() = dp(12f)
    private fun left() = if (minIcon != null) icon + gap else 0f
    private fun area() = width - left() - (if (maxIcon != null) icon + gap else 0f)
    private fun snapToValue() { x.set(frac(value) * max(1f, area())); invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(dp(200f).toInt(), w), resolveSize(dp(44f).toInt(), h))
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = snapToValue()

    override fun drawTrack(c: Canvas) {
        val th = themeOf(this)
        val cy = height / 2f; val l = left(); val W = area()
        val col = th.label.argb()
        c.icon(minIcon, icon / 2, cy, icon, mulAlpha(col, 0.55f))
        c.icon(maxIcon, width - icon / 2, cy, icon, mulAlpha(col, 0.55f))
        val t = dp(6f)
        paint.style = Paint.Style.FILL
        paint.color = if (th.isDark) 0x5C787880 else 0x33787880
        c.drawRoundRect(l, cy - t / 2, l + W, cy + t / 2, t / 2, t / 2, paint)
        paint.color = if (fillColor != 0) fillColor else th.accent.argb()
        val fx = x.value.coerceIn(0f, W)
        if (fx > 0.5f) c.drawRoundRect(l, cy - t / 2, l + fx, cy + t / 2, t / 2, t / 2, paint)
        if (showTicks && step > 0f) {
            val n = ((max - min) / step).roundToInt() + 1
            paint.color = 0x8C787880.toInt()
            for (i in 0 until n) c.drawCircle(l + W * i / (n - 1).coerceAtLeast(1), cy, dp(2f), paint)
        }
    }

    override fun drawOver(c: Canvas) {
        val L = lift.value
        val s = 1f + 0.55f * L
        thumb(c, left() + x.value, height / 2f, dp(38f) * s * (1f + 0.1f * L), dp(24f) * s, THUMB, L, android.graphics.Color.WHITE, (1f - L).coerceIn(0f, 1f))
    }

    private fun fromPointer(px: Float) {
        val W = max(1f, area())
        val raw = (px - left()) / W
        val f = raw.coerceIn(0f, 1f)
        var v = min + f * (max - min)
        if (step > 0f) v = ((v / step).roundToInt() * step).coerceIn(min, max)
        // the thumb follows the finger (rubber-banding past the ends); stepped values snap on release
        val over = if (raw < 0) rubberBand(raw * W, dp(14f)) else if (raw > 1) rubberBand((raw - 1) * W, dp(14f)) else 0f
        springs.to(x, f * W + over)
        if (v != value) { value = v; listener?.onValueChange(this, v, true) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            // the value moves only once the drag is sideways (or on a tap): a vertical drag scrolls the page
            MotionEvent.ACTION_DOWN -> { gateDown(e); springs.to(lift, 1f, GlassSpringPresets.press) }
            MotionEvent.ACTION_MOVE -> if (gateMove(e)) { dragging = true; fromPointer(e.x) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_UP && !far) fromPointer(e.x) // a tap jumps there
                dragging = false; claim(false)
                springs.to(lift, 0f, GlassSpringPresets.bouncy)
                springs.to(x, frac(value) * area())
            }
        }
        return true
    }

    override fun onDetachedFromWindow() { springs.stop(); super.onDetachedFromWindow() }
}

/* ─────────────────────────── Segmented control ─────────────────────────── */

/**
 * Segmented control whose selection pill slides with a spring and lifts into a clear glass lens while held,
 * magnifying the labels under it (twin of Compose `GlassSegmented` / the web `<GlassSegmented>`).
 */
class GlassSegmentedControl @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LocalGlassView(context, attrs) {
    fun interface OnSelectListener { fun onSelect(control: GlassSegmentedControl, index: Int) }

    private val springs = ViewSprings(this)
    private val x = springs.spring(0f, GlassSpringPresets.bouncy)
    private val lift = springs.spring(0f, GlassSpringPresets.press)
    private var labels: List<String> = listOf("One", "Two")
    private var selected = 0
    private var listener: OnSelectListener? = null
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true; isFocusable = true }

    fun setSegments(vararg items: String) { labels = items.toList(); selected = selected.coerceIn(0, labels.size - 1); x.set(selected.toFloat()); requestLayout(); invalidate() }
    fun setSegments(items: List<String>) = setSegments(*items.toTypedArray())
    fun getSelected() = selected
    fun setSelected(i: Int) { selected = i.coerceIn(0, labels.size - 1); springs.to(x, selected.toFloat(), GlassSpringPresets.bouncy) }
    fun setOnSelectListener(l: OnSelectListener?) { listener = l }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize(dp(260f).toInt(), w), resolveSize(dp(34f).toInt(), h))

    private fun labelsAt(c: Canvas, alpha: Float) {
        val th = themeOf(this)
        val segW = width / labels.size.toFloat()
        labels.forEachIndexed { i, s ->
            tp.text(dp(13f), mulAlpha(th.label.argb(), alpha), i == selected)
            val fm = tp.fontMetrics
            c.drawText(s, segW * (i + 0.5f) - tp.measureText(s) / 2, height / 2f - (fm.ascent + fm.descent) / 2, tp)
        }
    }

    override fun drawTrack(c: Canvas) {
        val th = themeOf(this)
        paint.style = Paint.Style.FILL
        paint.color = if (th.isDark) 0x3D767680 else 0x1F767680
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        labelsAt(c, 1f)
    }

    override fun drawOver(c: Canvas) {
        val th = themeOf(this)
        val L = lift.value
        val segW = width / labels.size.toFloat()
        val w = segW - dp(4f); val h = height - dp(4f)
        val s = 1f + 0.18f * L
        val pill = if (th.isDark) 0xFF636366.toInt() else android.graphics.Color.WHITE
        thumb(c, segW * x.value + dp(2f) + w / 2, height / 2f, w * s * (1f + 0.06f * L), h * s, THUMB.copy(shadow = 0.6f), L, pill, (1f - L).coerceIn(0f, 1f))
        // labels above the resting pill; while lifted they show through the lens instead
        val a = (1f - L).coerceIn(0f, 1f)
        if (a > 0.003f) labelsAt(c, a)
    }

    private var lastX = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || labels.isEmpty()) return false
        val n = labels.size; val segW = width / n.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { gateDown(e); lastX = e.x; springs.to(lift, 1f, GlassSpringPresets.press) }
            MotionEvent.ACTION_MOVE -> if (gateMove(e)) { lastX = e.x; springs.to(x, (lastX / segW - 0.5f).coerceIn(0f, n - 1f), GlassSpringPresets.snappy) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                claim(false)
                val idx = (lastX / segW).toInt().coerceIn(0, n - 1)
                springs.to(lift, 0f, GlassSpringPresets.bouncy)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || far && !owned) { springs.to(x, selected.toFloat(), GlassSpringPresets.bouncy); return true }
                springs.to(x, idx.toFloat(), GlassSpringPresets.bouncy)
                if (idx != selected) { selected = idx; listener?.onSelect(this, idx) }
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { springs.stop(); super.onDetachedFromWindow() }
    @Suppress("unused") private val keep = VelocityTracker::class
}
