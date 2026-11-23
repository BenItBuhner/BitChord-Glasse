package com.benitbuhner.liquidglass

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Liquid Glass for the classic View system (Java or Kotlin, XML or code), same optics as the Compose
 * [liquidGlass] modifier and the web / React Native kits.
 *
 * Put your screen inside one LiquidGlassLayout. A [GlassPanel] anywhere inside it is glass: it refracts
 * the **backdrop** under it, and its own children (labels, icons, buttons) draw on top. The backdrop is every
 * direct child that holds no glass (a scroll view, an image, a map). Direct children that do hold glass (a
 * toolbar row of glass buttons, a tab bar) are the **chrome**, drawn over the backdrop. Overlays (menus,
 * alerts, sheets: [GlassOverlayLayer]) refract the backdrop *and* the chrome, like iOS.
 *
 * ```java
 * LiquidGlassLayout root = new LiquidGlassLayout(this);
 * root.addView(myScrollingContent);              // backdrop
 * LinearLayout toolbar = new LinearLayout(this); // chrome: holds glass
 * toolbar.addView(new GlassButton(this));
 * root.addView(toolbar, new FrameLayout.LayoutParams(MATCH_PARENT, dp(64), Gravity.BOTTOM));
 * ```
 *
 * Tiers: API 33+ refracts (AGSL), API 31-32 frosts (blur + tint), older draws a flat tint. The backdrop is a
 * live RenderNode, so scrolling / animating backdrop views stay in sync; a panel that moves re-records only
 * its own glass.
 */
open class LiquidGlassLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var theme = GlassTheme()
    internal val backdrop: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-backdrop") else null
    internal val screen: RenderNode? = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-screen") else null
    internal val panels = LinkedHashSet<View>()
    private var lookSub: AutoCloseable? = null
    private val posWatch = ViewTreeObserver.OnPreDrawListener { checkPanels(); true }

    /** set by GlassStackView while this page slides out: touches fall through to the page under it */
    internal var touchThrough = false
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = if (touchThrough) false else super.dispatchTouchEvent(ev)

    init {
        setWillNotDraw(false)
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        theme = theme.copy(scheme = if (night) GlassScheme.Dark else GlassScheme.Light, appearance = if (GlassLooks.current.tinted) GlassAppearance.Tinted else GlassAppearance.Clear)
        clipChildren = false
    }

    /** Dark glass (#1a1a1d material) instead of light. Defaults to the system night mode. */
    var isDark: Boolean
        get() = theme.isDark
        set(v) { theme = glassTheme.copy(scheme = if (v) GlassScheme.Dark else GlassScheme.Light); ownTheme = true; invalidatePanels() }

    /** iOS 26.1 "Tinted" appearance: frosts every panel a little more, for contrast. */
    var isTinted: Boolean
        get() = theme.appearance == GlassAppearance.Tinted
        set(v) { theme = glassTheme.copy(appearance = if (v) GlassAppearance.Tinted else GlassAppearance.Clear); ownTheme = true; invalidatePanels() }

    /** 0..1, how much of the backdrop shows through (1 = clearest). Default 0.6. */
    var translucency: Float
        get() = theme.translucency
        set(v) { theme = glassTheme.copy(translucency = v.coerceIn(0f, 1f)); ownTheme = true; invalidatePanels() }

    /** false forces the frosted tier everywhere (no refraction). */
    var isRefractionEnabled: Boolean
        get() = theme.refraction
        set(v) { theme = glassTheme.copy(refraction = v); ownTheme = true; invalidatePanels() }

    /** Accent colour used by prominent controls. */
    @get:ColorInt
    var accentColor: Int
        get() = theme.accent.toArgb()
        set(@ColorInt v) { theme = glassTheme.copy(accent = Color(v)); ownTheme = true; invalidatePanels() }

    /** The theme this layout's glass renders with (shared with Compose's [GlassTheme]). */
    val glassTheme: GlassTheme get() = (parentHost()?.glassTheme?.let { p -> if (ownTheme) theme else p }) ?: theme
    private var ownTheme = false
    fun setGlassTheme(t: GlassTheme) { theme = t; ownTheme = true; invalidatePanels() }

    private fun parentHost(): LiquidGlassLayout? {
        var p = parent
        while (p is View) { if (p is LiquidGlassLayout) return p; p = p.parent }
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(posWatch)
        lookSub = GlassLooks.addListener { post { if (parentHost() == null || ownTheme) isTinted = GlassLooks.current.tinted else invalidatePanels() } }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(posWatch)
        lookSub?.close(); lookSub = null
        super.onDetachedFromWindow()
    }

    /** Views that sample this layout's backdrop ([GlassPanel], [GlassBackdropUser]) register here, so their
     *  direct-child ancestor counts as chrome and is kept out of the backdrop it samples. */
    fun register(p: View) { if (panels.add(p)) invalidate() }
    fun unregister(p: View) { if (panels.remove(p)) invalidate() }

    /** a panel moved (layout, translation, scale, a scrolling parent): its glass samples a new part of the backdrop */
    private fun checkPanels() {
        for (p in panels) if (p is GlassPanel && p.placementChanged()) p.invalidate()
    }

    private fun invalidatePanels() {
        fun walk(v: View) {
            v.invalidate()
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this) // nested layouts inherit this theme, so everything below redraws
    }

    /** a direct child is backdrop when it holds no glass (glass in scrolling content doesn't count: see [GlassPanel.isContentGlass]) */
    internal fun isBackdrop(c: View): Boolean {
        if (c is GlassOverlayLayer || c is GlassPanel || c is GlassBackdropUser) return false
        for (p in panels) if (!(p is GlassPanel && p.isContentGlass) && p.isDescendantOf(c)) return false
        return true
    }

    /** true while this layout draws its content glass itself (they draw nothing in the normal pass) */
    internal var contentPass = false

    // content glass moved or changed: this layout re-draws it, so it must re-run dispatchDraw
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (contentPanels > 0) {
            var v: Any? = target
            while (v is View && v !== this) { if (v is GlassPanel && v.isContentGlass && v.hostLayout === this) { invalidate(); return }; v = v.parent }
        }
    }
    private val contentPanels get() = panels.count { it is GlassPanel && it.isContentGlass }

    private val clipR = android.graphics.RectF()
    /** draw glass that lives inside scrolling content over the backdrop, at its place, clipped by its scrollers */
    private fun drawContentGlass(c: Canvas) {
        for (p in panels) {
            if (p !is GlassPanel || !p.isContentGlass) continue
            // visible, and where (translation, scroll, clips) relative to this layout
            var x = 0f; var y = 0f; var alpha = 1f; var v: View = p; var shown = p.isAttachedToWindow
            clipR.set(-1e9f, -1e9f, 1e9f, 1e9f)
            val chain = ArrayList<View>()
            while (true) {
                if (v.visibility != View.VISIBLE) { shown = false; break }
                chain += v
                val par = v.parent as? View ?: run { shown = false; null } ?: break
                if (par === this) break
                v = par
            }
            if (!shown) continue
            // walk down from the direct child: accumulate offsets and intersect each ancestor's bounds
            for (i in chain.size - 1 downTo 0) {
                val cv = chain[i]
                x += cv.left + cv.translationX; y += cv.top + cv.translationY
                if (i > 0) {
                    alpha *= cv.alpha
                    // an ancestor cuts this panel off at its bounds only where its own parent clips children
                    if ((cv.parent as? ViewGroup)?.clipChildren != false) clipR.intersect(x, y, x + cv.width, y + cv.height)
                    x -= cv.scrollX; y -= cv.scrollY
                }
            }
            alpha *= p.alpha
            if (alpha <= 0.003f || clipR.isEmpty) continue
            val save = c.save()
            c.clipRect(clipR)
            if (alpha < 0.999f) c.saveLayerAlpha(clipR, (alpha * 255).toInt())
            c.translate(x, y)
            c.scale(p.scaleX, p.scaleY, p.pivotX, p.pivotY)
            contentPass = true
            try { p.draw(c) } finally { contentPass = false }
            c.restoreToCount(save)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val t = drawingTime
        val node = backdrop; val scr = screen
        if (node == null || scr == null || Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) {
            super.dispatchDraw(canvas) // panels draw a flat translucent fill themselves
            return
        }
        // 1. the backdrop: every direct child without glass, recorded into a live RenderNode
        node.setPosition(0, 0, width, height)
        val rc = node.beginRecording(width, height)
        try {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == View.VISIBLE && isBackdrop(c)) drawChild(rc, c, t)
            }
        } finally { node.endRecording() }
        // 2. the screen: backdrop + content glass + chrome (glass panels sample `backdrop` while they draw here)
        scr.setPosition(0, 0, width, height)
        val sc = scr.beginRecording(width, height)
        try {
            sc.drawRenderNode(node)
            drawContentGlass(sc)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c.visibility == View.VISIBLE && !isBackdrop(c) && c !is GlassOverlayLayer) drawChild(sc, c, t)
            }
        } finally { scr.endRecording() }
        canvas.drawRenderNode(scr)
        // 3. overlays (menus, alerts, sheets) sample the whole screen
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.VISIBLE && c is GlassOverlayLayer) drawChild(canvas, c, t)
        }
    }

    companion object {
        /** The LiquidGlassLayout that hosts [v] (nearest ancestor, or [v] itself). */
        @JvmStatic
        fun of(v: View): LiquidGlassLayout? {
            var p: Any? = v
            while (p is View) { if (p is LiquidGlassLayout) return p; p = p.parent }
            return null
        }

        /** The outermost LiquidGlassLayout above [v]: the one overlays (menus, alerts) attach to. */
        @JvmStatic
        fun rootOf(v: View): LiquidGlassLayout? {
            var p: Any? = v; var found: LiquidGlassLayout? = null
            while (p is View) { if (p is LiquidGlassLayout) found = p; p = p.parent }
            return found
        }
    }
}

/**
 * Marker for a View that samples its [LiquidGlassLayout]'s backdrop without being a [GlassPanel] (e.g. the
 * scroll edge effect). Register it with [LiquidGlassLayout.register] while attached.
 */
interface GlassBackdropUser

/**
 * A layer for overlays inside a [LiquidGlassLayout] (add it as a direct child, last). Glass inside it samples
 * the whole screen (backdrop + chrome). Menus, alerts and sheets create one for themselves.
 */
open class GlassOverlayLayer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs)

private fun View.isDescendantOf(ancestor: View): Boolean {
    var p: Any? = this
    while (p is View) { if (p === ancestor) return true; p = p.parent }
    return false
}

/**
 * A glass surface: put it anywhere inside a [LiquidGlassLayout] and add children to it like any FrameLayout.
 * All setters take plain numbers (dp, ARGB ints), so they read the same from Java and Kotlin.
 */
open class GlassPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var style = GlassStyle()
    private var lift = 0f
    private var liftAnim: ValueAnimator? = null
    internal var dirty = true
    private val last = FloatArray(6)
    private var host: LiquidGlassLayout? = null
    private var inOverlay = false

    init { setWillNotDraw(false) }

    /** The Compose style this panel renders with (handy to share one recipe between Views and Compose). */
    var glassStyle: GlassStyle
        get() = style
        set(v) { style = v; changed() }

    /** Corner radius in dp; a negative value (the default) makes a pill / circle. */
    fun setCornerRadiusDp(dp: Float) { style = style.copy(cornerRadius = if (dp < 0f) null else dp.dp); changed() }
    /** Width of the refracting rim in dp (default 18). */
    fun setBezelDp(dp: Float) { style = style.copy(bezel = dp.dp); changed() }
    /** Backdrop blur in dp (default 2; Apple's material is barely blurred). */
    fun setBlurDp(dp: Float) { style = style.copy(blur = dp.dp); changed() }
    /** Optical strength: 1 = Apple, 0 = flat, >1 exaggerated. */
    fun setStrength(s: Float) { style = style.copy(strength = s); changed() }
    /** Colour the glass (e.g. the accent for a prominent button); pass 0 for the default material. */
    fun setTintColor(@ColorInt argb: Int) { style = style.copy(tint = if (argb == 0) null else Color(argb)); changed() }
    /** Opacity of the tint 0..1 (default: material alpha, 0.30 light / 0.38 dark). Negative = default. */
    fun setTintAlpha(a: Float) { style = style.copy(tintAlpha = if (a < 0f) null else a); changed() }
    /** Rim class: [RimClass.Auto] follows the shape, Bar for tab bars, Apple for small round controls. */
    fun setRimClass(rim: RimClass) { style = style.copy(rim = rim); changed() }
    /** Magnify the content under the panel (lens), 0 = none. */
    fun setMagnification(m: Float) { style = style.copy(magnification = m); changed() }
    /** Drop shadow strength (0 = none, 1 = default). */
    fun setShadow(s: Float) { style = style.copy(shadow = s); changed() }

    /** Press lift 0..1 (brighter rim, a touch of magnification), e.g. while a finger is down. */
    fun setLift(v: Float) { lift = v.coerceIn(0f, 1.5f); changed() }
    fun getLift(): Float = lift

    /** Lift on touch like an iOS 26 glass button (default off; turn on for tappable panels). */
    var isPressLift: Boolean = false

    /** The glass this panel draws, or null for the plain rounded rect of its bounds (override for merged shapes). */
    protected open fun glassShapes(): List<GlassBlob>? = null
    /** Smooth-union radius (px) between [glassShapes]. */
    protected open fun glassMergeRadius(): Float = 0f
    /** The theme of the hosting layout. */
    protected val theme: GlassTheme get() = host?.glassTheme ?: GlassTheme()

    /**
     * Glass inside scrolling content (a ScrollView, RecyclerView, ViewPager… between it and its layout) refracts
     * the content around it, so the scroller stays part of the backdrop and the layout draws this panel over it.
     * Detected automatically; set it to force either way (null = auto).
     */
    var contentGlass: Boolean? = null
        set(v) { field = v; if (isAttachedToWindow) { host?.unregister(this); attach() } }
    val isContentGlass: Boolean get() = contentAuto
    private var contentAuto = false
    internal val hostLayout: LiquidGlassLayout? get() = host

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attach()
    }

    private fun attach() {
        host = LiquidGlassLayout.of(parent as? View ?: this)?.takeIf { it !== this }
        inOverlay = false; var scroller = false
        var p = parent
        while (p is View && p !== host) {
            if (p is GlassOverlayLayer) inOverlay = true
            if (isScroller(p)) scroller = true
            p = p.parent
        }
        contentAuto = contentGlass ?: (scroller && !inOverlay)
        host?.register(this)
        // the shadow reaches past the panel's bounds; a plain container (a LinearLayout of buttons) would cut it
        // off at a square, so let it draw outside. Scrollers keep clipping their content.
        val pg = parent as? ViewGroup
        if (glassStyle.shadow > 0f && pg != null && pg !is LiquidGlassLayout && !isScroller(pg)) { pg.clipChildren = false; pg.clipToPadding = false }
    }

    private fun isScroller(v: View): Boolean {
        if (v is android.widget.ScrollView || v is android.widget.HorizontalScrollView || v is android.widget.AbsListView) return true
        var c: Class<*>? = v.javaClass
        while (c != null && c != View::class.java) {
            val n = c.simpleName
            if (n == "RecyclerView" || n == "NestedScrollView" || n == "ViewPager" || n == "ViewPager2") return true
            c = c.superclass
        }
        return false
    }

    override fun onDetachedFromWindow() {
        host?.unregister(this); host = null
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (isPressLift) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animateLift(1f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateLift(0f)
        }
        return super.onTouchEvent(e) || (isPressLift && e.actionMasked == MotionEvent.ACTION_DOWN)
    }

    private fun animateLift(to: Float) {
        liftAnim?.cancel()
        liftAnim = ValueAnimator.ofFloat(lift, to).apply {
            duration = if (to > lift) 220 else 380
            interpolator = OvershootInterpolator(if (to > lift) 1.6f else 1.1f)
            addUpdateListener { setLift(it.animatedValue as Float) }
            start()
        }
    }

    protected fun changed() { dirty = true; invalidate() }

    /** this panel's top-left in its host's coordinates (layout + translation + parents' scroll) */
    private fun offsetInHost(out: FloatArray) {
        var x = left + translationX; var y = top + translationY
        var p = parent
        while (p is View && p !== host) {
            x += p.left + p.translationX - p.scrollX; y += p.top + p.translationY - p.scrollY
            p = p.parent
        }
        if (p is View) { x -= p.scrollX; y -= p.scrollY }
        out[0] = x; out[1] = y
    }

    private val tmp = FloatArray(2)

    /** true when position / size / scale moved since the last glass draw */
    internal fun placementChanged(): Boolean {
        offsetInHost(tmp)
        return tmp[0] != last[0] || tmp[1] != last[1] || width.toFloat() != last[2] || height.toFloat() != last[3] ||
            scaleX != last[4] || scaleY != last[5]
    }

    private fun radiusPx(): Float {
        val d = resources.displayMetrics.density
        val r = GlassStyles.effective(style, null).cornerRadius?.value?.times(d) ?: Float.MAX_VALUE
        return min(r, min(width, height) / 2f)
    }

    private val painter = GlassPainter()

    /** The outline of the glass as last drawn (for clipping content to it). */
    val glassOutline: Path get() = painter.outline

    /** set while an open menu covers this panel: the menu draws it, under its body, so no glass shows through glass */
    internal var coveredBy: Any? = null
    internal var coverPass = false

    override fun draw(canvas: Canvas) {
        if (coveredBy != null && !coverPass) return
        // content glass is drawn by its layout over the backdrop (see isContentGlass), not inside it
        if (contentAuto && host?.contentPass != true && host?.backdrop != null && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= 31) return
        drawGlass(canvas)
        super.draw(canvas)
    }

    private fun drawGlass(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val h = host
        val src = if (inOverlay) h?.screen else h?.backdrop
        offsetInHost(tmp)
        val x = tmp[0]; val y = tmp[1]
        last[0] = x; last[1] = y; last[2] = width.toFloat(); last[3] = height.toFloat(); last[4] = scaleX; last[5] = scaleY
        val sx = scaleX; val sy = scaleY; val px = pivotX; val py = pivotY
        painter.draw(
            canvas, resources.displayMetrics.density, width.toFloat(), height.toFloat(), GlassStyles.effective(style, null), theme, lift,
            radiusPx(), glassShapes(), glassMergeRadius(),
            if (src == null) null else { rc ->
                // the slice of the backdrop under this panel (a live reference: backdrop updates flow through)
                rc.scale(1f / sx, 1f / sy, px, py)
                rc.translate(-x, -y)
                rc.drawRenderNode(src)
            },
        )
        dirty = false
    }
}
