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
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.benitbuhner.liquidglass.GlassPainter
import com.benitbuhner.liquidglass.GlassPanel
import com.benitbuhner.liquidglass.GlassSpringPresets
import com.benitbuhner.liquidglass.GlassStyle
import com.benitbuhner.liquidglass.RimClass
import com.benitbuhner.liquidglass.SpringOptions
import kotlin.math.abs
import kotlin.math.max

private const val BAR_H = 62f
private const val MIN_H = 48f
private const val ACC_H = 50f
private const val GAP = 10f
/** room around the bar for its shadow (blur 14 dp + 5 dp drop) */
private const val SHADOW_ROOM = 24f

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The iOS 26 floating tab bar for the View system (twin of Compose `GlassTabBar`, web `<GlassTabBar>`).
 * Touching it lifts a clear lens over the selected item; dragging slides the lens between tabs and it
 * squashes and stretches with its velocity, then snaps with a wobble. 62 dp tall.
 *
 * ```java
 * GlassTabBar bar = new GlassTabBar(ctx);
 * bar.addTab("Home", R.drawable.ic_home);
 * bar.addTab("Library", R.drawable.ic_library);
 * bar.setOnTabSelectedListener((b, i) -> show(i));
 * ```
 */
class GlassTabBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassPanel(context, attrs) {
    fun interface OnTabSelectedListener { fun onTabSelected(bar: GlassTabBar, index: Int) }

    class Tab(val label: CharSequence, val icon: Drawable?)

    private val tabs = ArrayList<Tab>()
    private var selected = 0
    private var listener: OnTabSelectedListener? = null
    private val springs = ViewSprings(this)
    private val x = springs.spring(0f, com.benitbuhner.liquidglass.TAB_SELECT)
    private val lift = springs.spring(0f, GlassSpringPresets.press)
    private var dragging = false
    private var dragVel = 0f
    private val local: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-tabbar") else null
    private val lensPainter = GlassPainter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensStyle = GlassStyle(blur = 0.dp, tintAlpha = 0.02f, shadow = 0.7f, adaptive = false, saturation = 1.15f)

    /** 0 = full bar, 1 = collapsed to a circle holding the selected tab ([GlassTabView] drives it). */
    internal var collapse = 0f
        set(v) { field = v; invalidate() }
    /** the width the items are laid out for (the full bar's, while collapsing) */
    internal var expandedWidth = -1f
    internal var onCollapsedTap: (() -> Unit)? = null
    /** -1 = no tab highlighted (the search tab is selected) */
    internal var showSelection = true
        set(v) { field = v; invalidate() }

    init {
        glassStyle = GlassStyle(rim = RimClass.Bar)
        isClickable = true
    }

    fun addTab(label: CharSequence, icon: Drawable?) = apply { tabs += Tab(label, icon?.mutate()); invalidate() }
    fun addTab(label: CharSequence, @DrawableRes icon: Int) = addTab(label, if (icon == 0) null else ContextCompat.getDrawable(context, icon))
    fun clearTabs() { tabs.clear(); invalidate() }
    val tabCount get() = tabs.size
    fun getSelected() = selected
    /** Select (animates); does not call the listener. */
    fun setSelected(i: Int) { selected = i.coerceIn(0, max(0, tabs.size - 1)); if (!dragging) { if (isLaidOut) springs.to(x, selected.toFloat(), com.benitbuhner.liquidglass.TAB_SELECT) else x.set(selected.toFloat()) } }
    fun setOnTabSelectedListener(l: OnTabSelectedListener?) { listener = l }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (parent as? ViewGroup)?.clipChildren = false // the lens rises a little above the bar
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(dp(360f).toInt(), w), resolveSize(dp(BAR_H).toInt(), h))
    }

    private fun itemW(): Float { val W = if (expandedWidth > 0) expandedWidth else width.toFloat(); return (W - dp(8f)) / max(1, tabs.size) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val th = themeOf(this)
        if (tabs.isEmpty()) return
        val iw = itemW(); val c = collapse
        val sel = selected
        // the selection pill (hidden once collapsing)
        if (showSelection && c <= 0.35f) {
            paint.style = Paint.Style.FILL
            paint.color = if (th.isDark) 0x21FFFFFF else 0x11000000
            val l = x.value * iw + dp(4f); val sy = 1f - 0.2f * c
            val ph = dp(54f) * sy; val top = (height - ph) / 2
            canvas.drawRoundRect(l, top, l + iw, top + ph, ph / 2, ph / 2, paint)
        }
        tabs.forEachIndexed { i, t ->
            val col = if (showSelection && i == sel) th.accent.argb() else th.label.argb()
            val shift = lerp(0f, dp(MIN_H / 2) - (dp(4f) + iw * (sel + 0.5f)), c)
            val cx = dp(4f) + i * iw + iw / 2 + shift
            val a = if (i == sel) 1f else (1 - c.coerceIn(0f, 1f) * 2.2f).coerceIn(0f, 1f)
            val la = (1 - c.coerceIn(0f, 1f) * 2.5f).coerceIn(0f, 1f) * a
            val cy = height / 2f - (if (la > 0.01f) dp(7f) else 0f) * (1 - c)
            canvas.icon(t.icon, cx, cy, dp(24f), col, a)
            if (la > 0.01f) {
                tp.text(dp(10f), mulAlpha(col, la), true)
                val s = t.label
                canvas.drawText(s, 0, s.length, cx - tp.measureText(s, 0, s.length) / 2, cy + dp(25f), tp)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val n = local
        if (n != null && canvas.isHardwareAccelerated) {
            // The node reaches past the bar by the shadow's spread: recorded at the bar's own size, the
            // bar's shadow was cut off square at its bounds.
            val m = dp(SHADOW_ROOM).toInt()
            n.setPosition(-m, -m, width + m, height + m)
            val rc = n.beginRecording(width + 2 * m, height + 2 * m)
            try { rc.translate(m.toFloat(), m.toFloat()); super.draw(rc) } finally { n.endRecording() }
            canvas.drawRenderNode(n)
        } else super.draw(canvas)
        drawLens(canvas)
    }

    /** the lens: a clear magnifying drop that only exists while touched */
    private fun drawLens(canvas: Canvas) {
        val L = lift.value.coerceIn(0f, 1f)
        if (L < 0.003f || tabs.isEmpty()) return
        val iw = itemW()
        val vel = if (dragging) dragVel / iw else x.velocity
        val st = (abs(vel) / 9f).coerceAtMost(0.32f)
        val s = 0.85f + 0.15f * lift.value
        val w0 = iw + dp(12f); val h0 = dp(76f)
        val w = w0 * s * (1 + st); val h = h0 * s * (1 - st * 0.5f)
        val cx = x.value * iw + dp(4f) - dp(6f) + w0 / 2; val cy = -dp(7f) + h0 / 2
        val lx = cx - w / 2; val ly = cy - h / 2
        val n = local
        canvas.saveLayerAlpha(lx - dp(20f), ly - dp(20f), lx + w + dp(20f), ly + h + dp(20f), (L * 255).toInt())
        canvas.translate(lx, ly)
        lensPainter.draw(canvas, resources.displayMetrics.density, w, h, lensStyle, themeOf(this), lift.value, h / 2, null, 0f,
            if (n == null) null else { rc -> rc.translate(-lx, -ly); rc.drawRenderNode(n) })
        canvas.restore()
    }

    private var vt: VelocityTracker? = null
    private var moved = 0f
    private var lastX = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tabs.isEmpty()) return false
        val n = tabs.size; val iw = itemW(); val off = dp(4f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (collapse > 0.5f) { onCollapsedTap?.invoke(); return true }
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true; moved = 0f; lastX = e.x
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                springs.to(lift, 1f, GlassSpringPresets.press)
                springs.to(x, ((e.x - off) / iw - 0.5f).coerceIn(0f, n - 1f), GlassSpringPresets.snappy)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                vt?.addMovement(e)
                moved += abs(e.x - lastX); lastX = e.x
                if (moved > ViewConfiguration.get(context).scaledTouchSlop) {
                    vt?.computeCurrentVelocity(1000); dragVel = vt?.xVelocity ?: 0f
                    x.set(((lastX - off) / iw - 0.5f).coerceIn(-0.25f, n - 0.75f)); springs.kick(); invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                parent?.requestDisallowInterceptTouchEvent(false)
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                val v = (vt?.xVelocity ?: 0f) / iw
                vt?.recycle(); vt = null
                dragging = false; dragVel = 0f
                val idx = if (e.actionMasked == MotionEvent.ACTION_CANCEL) selected else ((lastX - off) / iw).toInt().coerceIn(0, n - 1)
                springs.to(x, idx.toFloat(), com.benitbuhner.liquidglass.TAB_SELECT, if (v.isFinite()) v else 0f)
                postDelayed({ springs.to(lift, 0f, GlassSpringPresets.gentle) }, 140)
                if (idx != selected) { selected = idx; listener?.onTabSelected(this, idx) }
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { springs.stop(); super.onDetachedFromWindow() }
}

/**
 * The iOS 26 tab view chrome (twin of Compose `GlassTabView`, web `<GlassTabView>`): the [bar], an optional
 * search tab as its own circle on the trailing side, an optional floating accessory above it (a mini player),
 * and the scroll-to-minimize collapse: the bar shrinks to a circle holding the selected tab and the accessory
 * slides down beside it (spring k 320, d 29). Place it at the bottom of a LiquidGlassLayout.
 */
class GlassTabView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    val bar = GlassTabBar(context)
    private var search: GlassButton? = null
    private var accessory: GlassPanel? = null
    private val springs = ViewSprings(this) { requestLayout() }
    private val k = springs.spring(0f, com.benitbuhner.liquidglass.TAB_MINIMIZE)
    private val kh = springs.spring(0f, com.benitbuhner.liquidglass.ACC_FAST) // accessory: sideways
    private val kv = springs.spring(0f, com.benitbuhner.liquidglass.ACC_DROP) // accessory: down / up
    private var minimized = false
    private var searchSelected = false
    /** side margin in dp (default 18) */
    var marginDp = 18f; set(v) { field = v; requestLayout() }

    init {
        clipChildren = false
        addView(bar)
        bar.onCollapsedTap = { setMinimized(false) }
    }

    /** Add the trailing search circle. */
    fun setSearchTab(@DrawableRes icon: Int, onClick: View.OnClickListener) {
        val b = search ?: GlassButton(context).also { search = it; addView(it) }
        b.setIcon(icon); b.contentDescription = "Search"
        b.setOnClickListener(onClick)
    }

    /** Highlight the search circle (and no tab) while search is the current page. */
    fun setSearchSelected(sel: Boolean) {
        searchSelected = sel
        bar.showSelection = !sel
        search?.setContentColor(if (sel) themeOf(this).accent.argb() else 0)
    }

    /** A floating accessory above the bar (e.g. a mini player): [content] goes inside a glass capsule. */
    fun setAccessory(content: View?, onClick: View.OnClickListener?) {
        accessory?.let { removeView(it) }; accessory = null
        if (content == null) return
        val p = GlassPanel(context)
        p.addView(content)
        p.setOnClickListener(onClick)
        accessory = p
        addView(p, 0)
    }

    /** Collapse the bar to the selected tab's circle (call on scroll down; expand on scroll up / tap). */
    fun setMinimized(m: Boolean) {
        if (m == minimized) return
        minimized = m
        val t = if (m) 1f else 0f
        springs.to(kh, t, if (m) com.benitbuhner.liquidglass.ACC_FAST else com.benitbuhner.liquidglass.ACC_SLIDE)
        springs.to(kv, t, if (m) com.benitbuhner.liquidglass.ACC_DROP else com.benitbuhner.liquidglass.ACC_FAST)
        springs.to(k, t)
    }
    fun isMinimized() = minimized

    override fun onMeasure(w: Int, h: Int) {
        val hp = dp(BAR_H + ACC_H + 8f)
        setMeasuredDimension(resolveSize(dp(400f).toInt(), w), resolveSize(hp.toInt(), h))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = resources.displayMetrics.density
        val m = marginDp * d
        val W = (r - l) - 2 * m
        val H = (b - t).toFloat()
        val hasSearch = search != null
        val expBarW = W - if (hasSearch) (BAR_H + GAP) * d else 0f
        val tv = k.value; val tc = tv.coerceIn(0f, 1f)
        val sz = lerp(BAR_H, MIN_H, tc) * d
        fun place(v: View, x: Float, y: Float, w: Float, h: Float) {
            v.measure(MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.toInt(), MeasureSpec.EXACTLY))
            v.layout((m + x).toInt(), y.toInt(), (m + x + w).toInt(), (y + h).toInt())
        }
        accessory?.let {
            val th = kh.value; val tvv = kv.value
            val ax = lerp(0f, (MIN_H + GAP) * d, th)
            val aw = lerp(W, W - (MIN_H + GAP) * d - (if (hasSearch) (MIN_H + GAP) * d else 0f), th)
            val ah = lerp(ACC_H, MIN_H, tvv.coerceIn(0f, 1f)) * d
            val ab = lerp((BAR_H + 8) * d, 0f, tvv)
            place(it, ax, H - ab - ah, max(aw, ah), ah)
        }
        bar.expandedWidth = expBarW
        bar.collapse = tv
        place(bar, 0f, H - sz, max(lerp(expBarW, MIN_H * d, tv), sz), sz)
        search?.let { place(it, W - sz, H - sz, sz, sz) }
    }

    override fun onDetachedFromWindow() { springs.stop(); super.onDetachedFromWindow() }
}
