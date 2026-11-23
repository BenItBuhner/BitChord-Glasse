package com.benitbuhner.liquidglass.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.benitbuhner.liquidglass.EdgeEffect
import com.benitbuhner.liquidglass.GlassBackdropUser
import com.benitbuhner.liquidglass.GlassLooks
import com.benitbuhner.liquidglass.LiquidGlassLayout
import com.benitbuhner.liquidglass.R
import com.benitbuhner.liquidglass.SpringOptions
import kotlin.math.abs
import kotlin.math.min

/* ─────────────────────────── Nav bar ─────────────────────────── */

/**
 * iOS 26 navigation bar for the View system (twin of Compose `GlassNavBar`): transparent, with glass buttons
 * floating on each side and an inline title that appears once the large title has scrolled away. A changed
 * title blurs over (BlurReplace). Sits under the status bar (it pads itself by the status bar inset).
 *
 * ```java
 * GlassNavBar nav = new GlassNavBar(ctx);
 * nav.setTitle("Settings");
 * nav.setOnBackListener(v -> stack.pop());
 * nav.addTrailing(menuButton);
 * nav.attachLargeTitle(largeTitleView); // inline title fades in once it scrolls under the bar
 * ```
 */
class GlassNavBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val leading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    private val trailing = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    private var back: GlassButton? = null
    private val springs = ViewSprings(this)
    private val shown = springs.spring(1f, SpringOptions(900f, 60f))
    private val swap = springs.spring(1f, SpringOptions(380f, 34f, 1f, 2e-3f))
    private var title: CharSequence? = null
    private var oldTitle: CharSequence? = null
    private var subtitle: CharSequence? = null
    private var topInset = 0
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val titleNode = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-title") else null
    private val oldNode = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-title-old") else null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false // button shadows reach past the bar's padding and bottom edge
        val lp = { g: Int -> LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, g) }
        addView(leading, lp(android.view.Gravity.START))
        addView(trailing, lp(android.view.Gravity.END))
        setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, ins ->
            topInset = ins.getInsets(WindowInsetsCompat.Type.statusBars()).top
            requestLayout(); ins
        }
    }

    /** The inline title; changing it blurs the old one out and the new one in. */
    fun setTitle(t: CharSequence?) {
        if (t == title) return
        oldTitle = title; title = t
        if (oldTitle != null && isLaidOut) { swap.set(0f); springs.to(swap, 1f) } else swap.set(1f)
        invalidate()
    }
    fun setSubtitle(t: CharSequence?) { subtitle = t; invalidate() }

    /** Show / hide the inline title (springs 900/60). */
    fun setTitleVisible(v: Boolean) = springs.to(shown, if (v) 1f else 0f)

    /** Show a glass back button (chevron) on the leading side. Pass null to remove it. */
    fun setOnBackListener(l: OnClickListener?) {
        if (l == null) { back?.let { leading.removeView(it) }; back = null; return }
        val b = back ?: GlassButton(context).also { back = it; it.setIcon(R.drawable.glasse_ic_back); it.contentDescription = "Back"; leading.addView(it, 0) }
        b.setOnClickListener(l)
    }

    fun addLeading(v: View) = leading.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = if (leading.childCount > 0) dp(8f).toInt() else 0 })
    fun addTrailing(v: View) = trailing.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = if (trailing.childCount > 0) dp(8f).toInt() else 0 })

    /**
     * Fade the inline title in once [largeTitle] (a [LargeTitleView] or any view in the scrolling content)
     * has scrolled up under the bar, like `rememberScrolledPast` in Compose.
     */
    fun attachLargeTitle(largeTitle: View) {
        setTitleVisible(false)
        val loc = IntArray(2); val me = IntArray(2)
        val l = ViewTreeObserver.OnScrollChangedListener {
            largeTitle.getLocationInWindow(loc); getLocationInWindow(me)
            setTitleVisible(!largeTitle.isAttachedToWindow || loc[1] + largeTitle.height < me[1] + height)
        }
        largeTitle.viewTreeObserver.addOnScrollChangedListener(l)
    }

    override fun onMeasure(w: Int, h: Int) {
        val hh = topInset + dp(10f + 44f).toInt()
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = topInset + dp(10f).toInt(); val bh = dp(44f).toInt()
        val pl = paddingLeft; val pr = paddingRight
        leading.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(bh, MeasureSpec.EXACTLY))
        trailing.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(bh, MeasureSpec.EXACTLY))
        leading.layout(pl, top, pl + leading.measuredWidth, top + bh)
        trailing.layout(r - l - pr - trailing.measuredWidth, top, r - l - pr, top + bh)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val a = shown.value.coerceIn(0f, 1f)
        if (a < 0.003f) return
        val v = swap.value.coerceIn(0f, 1f)
        drawTitle(canvas, title, v * a, titleNode)
        if (v < 0.999f) drawTitle(canvas, oldTitle, (1 - v) * a, oldNode)
    }

    /** BlurReplace: alpha, 0.86→1 scale and a 6 dp blur that clears as it arrives */
    private fun drawTitle(canvas: Canvas, t: CharSequence?, p: Float, node: RenderNode?) {
        if (t == null || p < 0.003f) return
        val th = themeOf(this)
        val w = width - 2 * dp(112f); if (w <= 0) return
        val bh = dp(44f); val top = topInset + dp(10f)
        val draw = { c: Canvas ->
            tp.textSize = dp(17f); tp.color = th.label.argb(); tp.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            val s = TextUtils.ellipsize(t, tp, w, TextUtils.TruncateAt.END)
            val fm = tp.fontMetrics
            val sub = subtitle
            val cy = if (sub != null) bh / 2 - dp(7f) else bh / 2
            c.drawText(s, 0, s.length, (w - tp.measureText(s, 0, s.length)) / 2, cy - (fm.ascent + fm.descent) / 2, tp)
            if (sub != null) {
                tp.textSize = dp(12f); tp.color = th.secondaryLabel.argb(); tp.typeface = android.graphics.Typeface.DEFAULT
                val ss = TextUtils.ellipsize(sub, tp, w, TextUtils.TruncateAt.END)
                c.drawText(ss, 0, ss.length, (w - tp.measureText(ss, 0, ss.length)) / 2, cy + dp(17f), tp)
            }
        }
        canvas.save(); canvas.translate(dp(112f), top)
        if (node != null && canvas.isHardwareAccelerated) {
            node.setPosition(0, 0, w.toInt(), bh.toInt())
            val rc = node.beginRecording(); try { draw(rc) } finally { node.endRecording() }
            node.setAlpha(p); val s = 0.86f + 0.14f * p
            node.setPivotX(w / 2); node.setPivotY(bh / 2); node.setScaleX(s); node.setScaleY(s)
            // setRenderEffect itself is API 31: even a null effect must not be set below that
            if (Build.VERSION.SDK_INT >= 31) node.setRenderEffect(if (p < 0.99f && Build.VERSION.SDK_INT >= 31) RenderEffect.createBlurEffect((1 - p) * dp(6f), (1 - p) * dp(6f), Shader.TileMode.DECAL) else null)
            canvas.drawRenderNode(node)
        } else {
            canvas.saveLayerAlpha(0f, 0f, w, bh, (p * 255).toInt()); draw(canvas); canvas.restore()
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() { springs.stop(); super.onDetachedFromWindow() }
}

/** The 34 sp bold page title that scrolls with the content (the nav bar shows the inline one once it is gone). */
class LargeTitleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : android.widget.TextView(context, attrs) {
    init {
        textSize = 34f
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        letterSpacing = 0.012f
        setPadding(0, 0, 0, dp(14f).toInt())
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); setTextColor(themeOf(this).label.argb()) }
}

/* ─────────────────────────── Scroll edge ─────────────────────────── */

/**
 * The scroll edge under bars (twin of Compose `GlassScrollEdge`): iOS 26's soft progressive blur, or iOS 27's
 * hard "Blocks" band, from [GlassLooks] ([setEffect] overrides). Put it in the same [LiquidGlassLayout] as the
 * content, over it and under the bar: it blurs the content scrolling under it. [setPageColor] is the page
 * background. Blocks stays hidden until [setScrolled] (content under the bar), then springs in, like iOS 27.
 */
class GlassScrollEdge @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), GlassBackdropUser {
    private var host: LiquidGlassLayout? = null
    private val node = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-edge") else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val springs = ViewSprings(this)
    private val shown = springs.spring(1f, SpringOptions(900f, 60f, 1f, 2e-3f))
    private var effect: EdgeEffect? = null
    private var scrolled = true
    private var lookSub: AutoCloseable? = null
    /** true = the top edge (default), false = the bottom edge */
    var isTop = true; set(v) { field = v; invalidate() }
    @ColorInt var pageColor: Int = android.graphics.Color.WHITE; set(v) { field = v; invalidate() }

    fun setEffect(e: EdgeEffect?) { effect = e; sync() }
    fun setScrolled(s: Boolean) { scrolled = s; sync() }
    private fun fx() = effect ?: GlassLooks.current.edgeEffect
    private fun sync() { springs.to(shown, if (fx() == EdgeEffect.Progressive || scrolled) 1f else 0f); invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        host = LiquidGlassLayout.of(parent as View)?.also { it.register(this) }
        lookSub = GlassLooks.addListener { post { sync() } }
        sync()
    }
    override fun onDetachedFromWindow() { host?.unregister(this); host = null; lookSub?.close(); springs.stop(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val h = host ?: return
        val fx = fx()
        if (fx == EdgeEffect.Blocks && !isTop) return // iOS 27: no bottom edge by default
        val th = h.glassTheme
        val a = shown.value.coerceIn(0f, 1f)
        if (a < 0.003f) return
        val src = h.backdrop
        val W = width.toFloat(); val H = height.toFloat()
        val loc = Overlays.boundsIn(this, h)
        val blur = dp(10f)
        val veil = if (fx == EdgeEffect.Progressive) mulAlpha(pageColor or 0xFF000000.toInt(), 0.78f) else {
            val r = android.graphics.Color.red(pageColor); val g = android.graphics.Color.green(pageColor); val b = android.graphics.Color.blue(pageColor)
            if (th.isDark) android.graphics.Color.argb(178, min(255, r + 14), min(255, g + 14), min(255, b + 14))
            else android.graphics.Color.argb(209, (r * 0.985f).toInt(), (g * 0.985f).toInt(), (b * 0.985f).toInt())
        }
        val n = node
        val layer = canvas.saveLayerAlpha(0f, 0f, W, H, (a * 255).toInt())
        if (n != null && src != null && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= 31) {
            n.setPosition(0, 0, width, height)
            val rc = n.beginRecording(); try { rc.translate(-loc.left, -loc.top); rc.drawRenderNode(src) } finally { n.endRecording() }
            n.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
            canvas.drawRenderNode(n)
        }
        paint.shader = null; paint.color = veil
        canvas.drawRect(0f, 0f, W, H, paint)
        if (fx == EdgeEffect.Progressive) {
            // Full strength across the bar, then a soft fade over the last third: weaker than this, an inline
            // title or a status bar icon sits on sharp content and can't be read.
            val y0 = if (isTop) 0f else H; val y1 = if (isTop) H else 0f
            mask.shader = LinearGradient(0f, y0, 0f, y1, intArrayOf(0xFF000000.toInt(), 0xEB000000.toInt(), 0x66000000, 0), floatArrayOf(0f, 0.62f, 0.84f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, W, H, mask)
        } else {
            paint.color = if (th.isDark) 0x1AFFFFFF else 0x1C000000
            canvas.drawRect(0f, H - 1f, W, H, paint)
        }
        canvas.restoreToCount(layer)
    }
}

/* ─────────────────────────── Push navigation with swipe back ─────────────────────────── */

/**
 * iOS push navigation for Views (twin of Compose `GlassStack`): a pushed page slides in from the right over the
 * page beneath, which parallaxes left and dims (spring k 200, d 28). Swipe right anywhere on the page to go
 * back: the page follows the finger and completes or cancels by position and flick. Horizontal scrollers win:
 * the swipe only starts when the view under the finger can't scroll left any more. The system back gesture
 * (predictive back, Android 14+) drives the same spring.
 *
 * Each page becomes its own [LiquidGlassLayout] (pages that already are one are used as is), so glass on a page
 * refracts that page, and menus / alerts still see the whole screen through the outer layout.
 */
class GlassStackLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    fun interface OnStackChangeListener { fun onStackChanged(stack: GlassStackLayout, depth: Int) }

    private class Page(val view: View, val s: com.benitbuhner.liquidglass.SpringSim)
    private val pages = ArrayList<Page>()
    private val push = SpringOptions(200f, 28f, 1f, 2e-4f)
    private val loop = GlassFrameLoop { dt -> step(dt.toFloat()) }
    private var dragging = false
    private var listener: OnStackChangeListener? = null
    private var back: OnBackPressedCallback? = null
    private val dim = Paint()

    init { clipChildren = false; setWillNotDraw(false) }

    val depth: Int get() = pages.count { it.s.target > 0.5f }
    fun setOnStackChangeListener(l: OnStackChangeListener?) { listener = l }

    /** Push [page] (slides in). The first page shows at once. */
    fun push(page: View) {
        val v = if (page is LiquidGlassLayout) page else LiquidGlassLayout(context).also { it.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        val first = pages.isEmpty()
        val p = Page(v, com.benitbuhner.liquidglass.SpringSim(if (first) 1f else 0f, push))
        p.s.target = 1f
        pages += p
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        v.elevation = 0f
        changed(); loop.start()
    }

    /** Pop the top page (slides out). Returns false at the root. */
    fun pop(): Boolean {
        val live = pages.filter { it.s.target > 0.5f }
        if (live.size < 2) return false
        popOut(live.last())
        changed(); loop.start()
        return true
    }

    /** a page that starts sliding out takes no more input: the page under it scrolls and takes taps at once */
    private fun popOut(p: Page) { p.s.target = 0f; (p.view as? LiquidGlassLayout)?.touchThrough = true }

    private fun changed() {
        back?.isEnabled = depth > 1
        listener?.onStackChanged(this, depth)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val a = Overlays.activityOf(context) ?: return
        val top = { pages.lastOrNull { it.s.target > 0.5f } }
        back = object : OnBackPressedCallback(depth > 1) {
            override fun handleOnBackPressed() { dragging = false; pop() }
            override fun handleOnBackStarted(e: BackEventCompat) { dragging = true }
            override fun handleOnBackProgressed(e: BackEventCompat) { top()?.s?.set(1f - e.progress * 0.9f); applyPositions() }
            override fun handleOnBackCancelled() { dragging = false; top()?.s?.target = 1f; loop.start() }
        }.also { a.onBackPressedDispatcher.addCallback(it) }
    }

    override fun onDetachedFromWindow() { back?.remove(); back = null; loop.stop(); super.onDetachedFromWindow() }

    private fun step(dt: Float): Boolean {
        var busy = false
        if (!dragging) for (p in pages) if (!p.s.settled) { p.s.step(dt); busy = busy || !p.s.settled }
        // popped pages leave once they have slid out
        pages.toList().forEach { p -> if (p.s.target == 0f && p.s.settled && pages.size > 1) { pages.remove(p); removeView(p.view) } }
        if (!dragging) dragging = false
        applyPositions()
        return busy
    }

    private fun applyPositions() {
        val w = width.toFloat()
        pages.forEachIndexed { i, p ->
            val v = if (i == 0) 1f else p.s.value
            val above = pages.getOrNull(i + 1)?.s?.value?.coerceIn(0f, 1.2f) ?: 0f
            p.view.translationX = (1f - v) * w - 0.3f * w * above
            val covered = pages.getOrNull(i + 1)?.let { it.s.value >= 0.9998f && !dragging } ?: false
            p.view.visibility = if (covered) View.INVISIBLE else View.VISIBLE
            p.view.translationZ = if (i > 0 && v < 0.9998f) dp(24f) * v.coerceAtMost(1f) else 0f
        }
        invalidate()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val r = super.drawChild(canvas, child, drawingTime)
        // the page beneath dims while one slides over it
        val i = pages.indexOfFirst { it.view === child }
        val above = pages.getOrNull(i + 1)?.s?.value ?: 0f
        if (i >= 0 && above > 0.0002f && child.visibility == View.VISIBLE) {
            dim.color = mulAlpha(android.graphics.Color.BLACK, 0.1f * above.coerceIn(0f, 1f))
            canvas.drawRect(child.left + child.translationX, child.top.toFloat(), child.right + child.translationX, child.bottom.toFloat(), dim)
        }
        return r
    }

    // swipe back: claim a rightward horizontal drag nothing under the finger can scroll
    private var downX = 0f; private var downY = 0f; private var claimed = false; private var blocked = false; private var v0 = 1f
    private var vt: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (depth < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; claimed = false
                blocked = canScrollLeftAt(this, e.x, e.y)
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - downX; val dy = e.y - downY
                if (!blocked && !claimed && abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) {
                    // a swipe that lands while the page is still sliding in catches it where it is
                    if (dx > 0 && dx > abs(dy) * 1.2f) { claimed = true; dragging = true; downX = e.x; v0 = (pages.lastOrNull { it.s.target > 0.5f }?.s?.value ?: 1f).coerceIn(0f, 1f); return true } else blocked = true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!claimed) return false
        val top = pages.lastOrNull { it.s.target > 0.5f } ?: return false
        vt?.addMovement(e)
        val w = width.toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> { top.s.set((v0 - (e.x - downX) / w).coerceIn(0f, 1f)); applyPositions() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val vx = (vt?.xVelocity ?: 0f) / w // screens / s
                vt?.recycle(); vt = null
                claimed = false; dragging = false
                val v = top.s.value
                val goBack = e.actionMasked == MotionEvent.ACTION_UP && (vx > 0.35f || (v < 0.5f && vx > -0.15f))
                top.s.velocity = -vx
                if (goBack) { popOut(top); changed() } else top.s.target = 1f
                loop.start()
            }
        }
        return true
    }

    companion object {
        /** true if the deepest view under (x, y) in [root] (or a parent of it) can still scroll left */
        internal fun canScrollLeftAt(root: ViewGroup, x: Float, y: Float): Boolean {
            val r = Rect()
            fun walk(g: ViewGroup, gx: Float, gy: Float): Boolean {
                for (i in g.childCount - 1 downTo 0) {
                    val c = g.getChildAt(i)
                    if (c.visibility != View.VISIBLE) continue
                    val lx = gx + g.scrollX - c.left - c.translationX; val ly = gy + g.scrollY - c.top - c.translationY
                    c.getDrawingRect(r); r.offset(-c.scrollX, -c.scrollY)
                    if (lx < 0 || ly < 0 || lx > c.width || ly > c.height) continue
                    if (c.canScrollHorizontally(-1)) return true
                    if (c is ViewGroup && walk(c, lx, ly)) return true
                    return false
                }
                return false
            }
            return walk(root, x, y)
        }
    }
}

/** Gesture helpers. */
object GlassGestures {
    /**
     * Keep the system back gesture off a horizontal scroller (a carousel at the screen edge): excludes up to
     * 200 dp of its height on both edges, the most Android allows per edge.
     */
    @JvmStatic
    fun excludeFromSystemBack(scroller: View) {
        scroller.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (Build.VERSION.SDK_INT >= 29) {
                val h = min(v.height, v.dp(200f).toInt())
                val top = (v.height - h) / 2
                val edge = v.dp(28f).toInt()
                v.systemGestureExclusionRects = listOf(Rect(0, top, edge, top + h), Rect(v.width - edge, top, v.width, top + h))
            }
        }
    }
}
