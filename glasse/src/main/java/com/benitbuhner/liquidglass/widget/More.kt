package com.benitbuhner.liquidglass.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.benitbuhner.liquidglass.GlassBlob
import com.benitbuhner.liquidglass.GlassLooks
import com.benitbuhner.liquidglass.GlassPanel
import com.benitbuhner.liquidglass.GlassSpringPresets
import com.benitbuhner.liquidglass.GlassStyle
import com.benitbuhner.liquidglass.LiquidGlassLayout
import com.benitbuhner.liquidglass.R
import com.benitbuhner.liquidglass.SpringOptions
import com.benitbuhner.liquidglass.SpringSim
import com.benitbuhner.liquidglass.rubberBand
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/* ─────────────────────────── Sheet ─────────────────────────── */

/**
 * A glass sheet with detents for the View system (twin of Compose `GlassSheet`, RN `GlassSheet`): fractions of
 * the screen height, ascending. It floats inset 8 dp at the low detent and grows out to the screen edges and
 * turns opaque toward the top one; drag the header to move between detents, fling down to dismiss
 * (y spring k 300, d 30). Back / tap outside close it.
 *
 * ```java
 * new GlassSheet(view).setTitle("Share").setContent(myContent).setDetents(0.5f, 0.94f).show();
 * ```
 */
class GlassSheet(private val anchor: View) {
    private var title: CharSequence? = null
    private var content: View? = null
    private var detents = floatArrayOf(0.5f, 0.94f)
    private var onDismiss: Runnable? = null
    private var view: SheetView? = null

    fun setTitle(t: CharSequence?) = apply { title = t }
    fun setContent(v: View?) = apply { content = v }
    fun setDetents(vararg d: Float) = apply { detents = d.sortedArray() }
    fun setOnDismissListener(r: Runnable?) = apply { onDismiss = r }
    val isShowing get() = view != null

    fun show() {
        if (view != null) return
        val host = Overlays.hostOf(anchor)
        val v = SheetView(anchor.context, host, this)
        view = v
        Overlays.layerOf(host).addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    fun dismiss() { view?.close() }

    @SuppressLint("ViewConstructor")
    private class SheetView(context: Context, private val host: LiquidGlassLayout, private val s: GlassSheet) : FrameLayout(context) {
        private val d = resources.displayMetrics.density
        private val panel = GlassPanel(context)
        private val header = FrameLayout(context)
        private val y = SpringSim(10000f, SpringOptions(300f, 30f, 1f, 0.05f)) // dp from the top detent
        private var visible = true
        private val loop = GlassFrameLoop { dt -> y.step(dt.toFloat()); applyY(); if (y.settled && !visible) { post { finish() }; false } else !y.settled || dragging }
        private var back: OnBackPressedCallback? = null
        private var dragging = false
        private val th = themeOf(host)

        init {
            setWillNotDraw(false)
            panel.isClickable = true
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            // head: grabber, title, close
            header.addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = 3 * d; setColor(0x73787880) }
            }, LayoutParams((36 * d).toInt(), (5 * d).toInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (6 * d).toInt() })
            s.title?.let { t ->
                header.addView(TextView(context).apply {
                    text = t; textSize = 17f; setTextColor(th.label.argb()); maxLines = 1
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { topMargin = (4 * d).toInt() })
            }
            header.addView(GlassButton(context).apply {
                setIcon(R.drawable.glasse_ic_close); contentDescription = "Close"; sizeDp = 40f
                setOnClickListener { close() }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = (16 * d).toInt(); topMargin = (4 * d).toInt() })
            col.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (52 * d).toInt()))
            s.content?.let { c ->
                (c.parent as? ViewGroup)?.removeView(c)
                col.addView(c, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    marginStart = (20 * d).toInt(); marginEnd = (20 * d).toInt(); bottomMargin = (24 * d).toInt()
                })
            }
            panel.addView(col)
            addView(panel)
            back = Overlays.onBack(this) { close() }
        }

        private val H get() = height / d
        private val Wd get() = width / d
        private val hs get() = (s.detents.max() * H).roundToInt().toFloat()
        private fun yFor(f: Float) = hs - (f * H).roundToInt()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (ow == 0 && w > 0) { y.set(hs + 60f); y.target = yFor(s.detents.first()); loop.start() }
        }

        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(w, h)
            val W = MeasureSpec.getSize(w); val Hh = MeasureSpec.getSize(h)
            val sheetH = (s.detents.max() * Hh / d).roundToInt() * d
            panel.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sheetH.toInt(), MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { applyY() }

        private fun progress(): Float {
            val top = yFor(s.detents.last()); val lo = yFor(s.detents.first())
            return if (s.detents.size > 1) ((lo - y.value) / max(1f, lo - top)).coerceIn(0f, 1f) else 0f
        }

        private fun applyY() {
            if (width == 0) return
            val p = progress()
            val inset = (8f * (1 - p) * 2).roundToInt() / 2f
            val w = ((Wd - 2 * inset) * d).toInt(); val h = (hs * d).toInt()
            val x = (inset * d).toInt(); val top = ((H - 8f - hs + y.value + (8 - inset)) * d).toInt()
            if (panel.width != w || panel.height != h) panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            panel.layout(x, top, x + w, top + h)
            val r = GlassLooks.current.concentricRadius(34f, 8f, Wd, H)
            val st = GlassStyle(cornerRadius = r.dp, bezel = 22.dp, thickness = 30.dp, blur = 9.dp, tintAlpha = 0.56f + 0.44f * p * p)
            if (panel.glassStyle != st) panel.glassStyle = st
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val p = progress()
            val a = (1 - y.value / max(1f, hs)).coerceIn(0f, 1f) * (0.1f + 0.2f * p)
            canvas.drawColor(mulAlpha(android.graphics.Color.BLACK, a))
        }

        fun close() {
            if (!visible) return
            visible = false; back?.remove(); back = null
            y.target = hs + 60f; loop.start()
        }

        private fun finish() { (parent as? ViewGroup)?.removeView(this); s.view = null; s.onDismiss?.run() }
        /** closing, it takes no input: touches fall through to the screen under it while it animates away */
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = if (!visible) false else super.dispatchTouchEvent(ev)

        // drag the header between detents; tap outside closes
        private var y0 = 0f; private var downY = 0f; private var vt: VelocityTracker? = null
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                val inHead = e.x >= panel.left && e.x <= panel.right && e.y >= panel.top && e.y <= panel.top + 52 * d
                if (inHead) { downY = e.y; y0 = y.value; vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) } }
                dragging = false
                return false
            }
            if (e.actionMasked == MotionEvent.ACTION_MOVE && vt != null && abs(e.y - downY) > 6 * d) { dragging = true; return true }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!dragging) {
                if (e.actionMasked == MotionEvent.ACTION_UP && visible && (e.y < panel.top || e.y > panel.bottom)) close()
                return true
            }
            vt?.addMovement(e)
            val top = yFor(s.detents.last())
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val raw = y0 + (e.y - downY) / d
                    y.set(if (raw < top) top + rubberBand(raw - top, 50f) else raw); applyY()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000); val vy = (vt?.yVelocity ?: 0f) / d
                    vt?.recycle(); vt = null; dragging = false
                    val proj = y.value + vy * 0.2f
                    val all = s.detents.map { yFor(it) } + (hs + 60f)
                    val best = all.indices.minBy { abs(all[it] - proj) }
                    if (best == all.size - 1) close() else { y.velocity = vy; y.target = all[best]; loop.start() }
                }
            }
            return true
        }

        override fun onDetachedFromWindow() { loop.stop(); back?.remove(); super.onDetachedFromWindow() }
    }
}

/* ─────────────────────────── Lens ─────────────────────────── */

/**
 * A draggable magnifying glass (the artifact loupe: bezel 28, thickness 34, magnification 0.22, aberration
 * 0.12, press 1.08; springs 380 / 30, rubber band 40, fling × 0.1), kept inside its parent. 150 dp by default.
 */
class GlassLensView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassPanel(context, attrs) {
    private val o = SpringOptions(380f, 30f, 1f, 0.05f)
    private val springs = ViewSprings(this) { place() }
    private val lx = springs.spring(40f, o)
    private val ly = springs.spring(260f, o)
    private val press = springs.spring(0f, GlassSpringPresets.press)
    var sizeDp = 150f; set(v) { field = v; requestLayout() }

    init {
        glassStyle = GlassStyle(bezel = 28.dp, thickness = 34.dp, magnification = 0.22f, aberration = 0.12f, tintAlpha = 0.04f, blur = 0.dp, adaptive = false)
    }

    /** Move to (x, y) dp in the parent, without animating. */
    fun setPositionDp(x: Float, y: Float) { lx.set(x); ly.set(y); place() }

    override fun onMeasure(w: Int, h: Int) { val s = dp(sizeDp).toInt(); setMeasuredDimension(s, s) }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); place() }

    private fun place() {
        val d = resources.displayMetrics.density
        translationX = lx.value * d - left; translationY = ly.value * d - top
        val s = 1f + 0.08f * press.value; scaleX = s; scaleY = s
        setLift(press.value)
    }

    private var dx = 0f; private var dy = 0f; private var vt: VelocityTracker? = null
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        val p = parent as? View ?: return false
        val maxX = p.width / d - sizeDp; val maxY = p.height / d - sizeDp
        val px = e.rawX / d; val py = e.rawY / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                springs.to(press, 1f); dx = px - lx.value; dy = py - ly.value
                vt?.recycle(); vt = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> {
                val ev = MotionEvent.obtain(e); ev.setLocation(e.rawX, e.rawY); vt?.addMovement(ev); ev.recycle()
                val x = px - dx; val y = py - dy
                val cx = x.coerceIn(0f, maxX); val cy = y.coerceIn(0f, maxY)
                springs.to(lx, cx + rubberBand(x - cx, 40f)); springs.to(ly, cy + rubberBand(y - cy, 40f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000); val vx = (vt?.xVelocity ?: 0f) / d; val vy = (vt?.yVelocity ?: 0f) / d
                vt?.recycle(); vt = null
                springs.to(press, 0f, GlassSpringPresets.bouncy)
                springs.to(lx, (lx.target + vx * 0.1f).coerceIn(0f, maxX)); springs.to(ly, (ly.target + vy * 0.1f).coerceIn(0f, maxY))
            }
        }
        return true
    }

    override fun onDetachedFromWindow() { springs.stop(); super.onDetachedFromWindow() }
}

/* ─────────────────────────── Container: shapes that merge ─────────────────────────── */

/**
 * A row of glass shapes drawn as ONE surface: shapes closer than [spacingDp] melt together with a liquid neck,
 * like SwiftUI's `GlassEffectContainer` (twin of Compose `GlassContainer`). Each child is one shape (a pill /
 * circle of its own size, 44 dp tall). Animate a child's scale or [gapDp] and the shapes split and merge.
 */
class GlassContainer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassPanel(context, attrs) {
    /** distance under which shapes merge (dp, default 12) */
    var spacingDp = 12f; set(v) { field = v; changed() }
    /** gap between shapes (dp, default 10) */
    var gapDp = 10f; set(v) { field = v; requestLayout() }

    private val press = HashMap<View, SpringSim>()
    private val loop = GlassFrameLoop { dt ->
        var busy = false
        press.forEach { (v, s) -> if (!s.settled) { s.step(dt.toFloat()); busy = busy || !s.settled }; val k = 1f + 0.08f * s.value; v.scaleX = k; v.scaleY = k }
        changed(); busy
    }

    /** Add a shape holding [content] (an icon, a label), [widthDp] wide (≤ 0 = a 44 dp circle). */
    fun addItem(content: View, widthDp: Float, onClick: OnClickListener?): View {
        val box = FrameLayout(context)
        box.addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val d = resources.displayMetrics.density
        box.layoutParams = LayoutParams(if (widthDp > 0) (widthDp * d).toInt() else (44 * d).toInt(), (44 * d).toInt())
        box.setOnClickListener(onClick)
        val s = SpringSim(0f, GlassSpringPresets.press); press[box] = s
        @SuppressLint("ClickableViewAccessibility")
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { s.opts = GlassSpringPresets.press; s.target = 1f; loop.start() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { s.opts = GlassSpringPresets.bouncy; s.target = 0f; loop.start() }
            }
            false
        }
        addView(box)
        return box
    }

    /** Add an icon shape. */
    fun addIcon(@DrawableRes icon: Int, onClick: OnClickListener?): View {
        val iv = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, icon)?.mutate()?.also { it.setTint(themeOf(this@GlassContainer).label.argb()) })
        }
        val d = resources.displayMetrics.density
        iv.layoutParams = LayoutParams((22 * d).toInt(), (22 * d).toInt())
        return addItem(iv, 0f, onClick)
    }

    override fun onMeasure(w: Int, h: Int) {
        var W = 0; var Hh = 0
        val g = dp(gapDp).toInt()
        for (i in 0 until childCount) {
            val c = getChildAt(i); val lp = c.layoutParams
            c.measure(MeasureSpec.makeMeasureSpec(max(0, lp.width), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(0, lp.height), MeasureSpec.EXACTLY))
            W += c.measuredWidth + if (i > 0) g else 0; Hh = max(Hh, c.measuredHeight)
        }
        setMeasuredDimension(W, Hh)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0; val g = dp(gapDp).toInt()
        for (i in 0 until childCount) {
            val c = getChildAt(i); val y = (height - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight); x += c.measuredWidth + g
        }
        changed()
    }

    override fun glassShapes(): List<GlassBlob> = (0 until childCount).map { i ->
        val c = getChildAt(i)
        val w = c.width * c.scaleX; val h = c.height * c.scaleY
        GlassBlob(Offset(c.left + c.width / 2f + c.translationX, c.top + c.height / 2f + c.translationY), Size(w, h), h / 2)
    }

    override fun glassMergeRadius() = dp(spacingDp) * 2.2f

    override fun onDetachedFromWindow() { loop.stop(); super.onDetachedFromWindow() }
}

/* ─────────────────────────── Search field, button group, lists ─────────────────────────── */

/** Pill search field on glass (twin of Compose `GlassSearchField`): 48 dp, a magnifier and an EditText. */
class GlassSearchField @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassPanel(context, attrs) {
    fun interface OnQueryChangeListener { fun onQueryChange(query: String) }

    val editText: EditText = EditText(context)
    private val icon = ImageView(context)
    private var listener: OnQueryChangeListener? = null

    init {
        val d = resources.displayMetrics.density
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0) }
        icon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.glasse_ic_search)?.mutate())
        row.addView(icon, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
        editText.apply {
            background = null; textSize = 16f; hint = "Search"; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding((8 * d).toInt(), 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { listener?.onQueryChange(s?.toString() ?: "") }
            })
        }
        row.addView(editText, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setHint(h: CharSequence) { editText.hint = h }
    fun setOnQueryChangeListener(l: OnQueryChangeListener?) { listener = l }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val th = themeOf(this)
        icon.drawable?.setTint(th.secondaryLabel.argb())
        editText.setTextColor(th.label.argb()); editText.setHintTextColor(th.secondaryLabel.argb())
    }

    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(48f).toInt(), MeasureSpec.EXACTLY))
}

/** Several bar buttons sharing one capsule (iOS toolbar groups, the web `GlassButtonGroup`). */
class GlassButtonGroup @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassPanel(context, attrs) {
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    init {
        val d = resources.displayMetrics.density
        row.setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, (44 * d).toInt()))
    }

    /** An icon button (40×44 dp). [color] 0 = the label colour. */
    @JvmOverloads
    fun addIcon(@DrawableRes icon: Int, description: CharSequence, onClick: OnClickListener?, @ColorInt color: Int = 0): View {
        val d = resources.displayMetrics.density
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(ContextCompat.getDrawable(context, icon)?.mutate()?.also { it.setTint(if (color != 0) color else themeOf(this@GlassButtonGroup).label.argb()) })
            contentDescription = description
            setOnClickListener(onClick)
        }
        row.addView(iv, LinearLayout.LayoutParams((40 * d).toInt(), (44 * d).toInt()))
        return iv
    }

    /** A text button (17 sp medium). */
    @JvmOverloads
    fun addText(label: CharSequence, onClick: OnClickListener?, @ColorInt color: Int = 0): View {
        val d = resources.displayMetrics.density
        val tv = TextView(context).apply {
            text = label; textSize = 17f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(if (color != 0) color else themeOf(this@GlassButtonGroup).label.argb())
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
            setOnClickListener(onClick)
        }
        row.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (44 * d).toInt()))
        return tv
    }
}

/**
 * An inset grouped section (Settings style, twin of Compose `ListSection`): an optional caps header, a rounded
 * card holding [GlassListRow]s, and an optional footer.
 */
class GlassListSection @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val card = LinearLayout(context).apply { orientation = VERTICAL; clipToOutline = true }
    private var header: TextView? = null
    private var footer: TextView? = null

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density
        setPadding(0, 0, 0, (22 * d).toInt())
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setHeader(t: CharSequence?) {
        val d = resources.displayMetrics.density
        header?.let { removeView(it) }; header = null
        if (t == null) return
        header = TextView(context).apply { text = t.toString(); textSize = 13f; setPadding((20 * d).toInt(), 0, 0, (7 * d).toInt()) }.also { addView(it, 0) }
        restyle()
    }

    fun setFooter(t: CharSequence?) {
        val d = resources.displayMetrics.density
        footer?.let { removeView(it) }; footer = null
        if (t == null) return
        footer = TextView(context).apply { text = t; textSize = 13f; setPadding((20 * d).toInt(), (7 * d).toInt(), (20 * d).toInt(), 0) }.also { addView(it) }
        restyle()
    }

    fun addRow(row: View) { card.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)); if (card.childCount == 1 && row is GlassListRow) row.showDivider = false }

    /** Lists span the available width (like an inset grouped table), even when added with wrap_content. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(w, heightMeasureSpec)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restyle() }

    private fun restyle() {
        val th = themeOf(this); val d = resources.displayMetrics.density
        card.background = GradientDrawable().apply { cornerRadius = 22 * d; setColor(if (th.isDark) 0xFF1C1C1E.toInt() else android.graphics.Color.WHITE) }
        header?.setTextColor(th.secondaryLabel.argb()); footer?.setTextColor(th.secondaryLabel.argb())
    }
}

/**
 * A Settings-style row: a coloured rounded icon, the label, an optional value, a chevron, or any trailing view
 * (a [GlassSwitch]). Rows after the first draw a hairline inset past the icon.
 */
class GlassListRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val iconBox = FrameLayout(context)
    private val iconView = ImageView(context)
    private val label = TextView(context)
    private val subtitle = TextView(context)
    private val value = TextView(context)
    private val chevron = ImageView(context)
    private var trailing: View? = null
    private val line = Paint()
    var showDivider = true; set(v) { field = v; invalidate() }
    var destructive = false; set(v) { field = v; restyle() }

    init {
        val d = resources.displayMetrics.density
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (52 * d).toInt()
        setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
        setWillNotDraw(false)
        iconBox.addView(iconView, FrameLayout.LayoutParams((19 * d).toInt(), (19 * d).toInt(), Gravity.CENTER))
        iconBox.visibility = GONE
        addView(iconBox, LayoutParams((30 * d).toInt(), (30 * d).toInt()).apply { marginEnd = (14 * d).toInt() })
        label.textSize = 17f; label.maxLines = 2; label.ellipsize = android.text.TextUtils.TruncateAt.END
        subtitle.textSize = 13f; subtitle.maxLines = 3; subtitle.ellipsize = android.text.TextUtils.TruncateAt.END; subtitle.visibility = GONE
        val texts = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt()) }
        texts.addView(label); texts.addView(subtitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * d).toInt() })
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        value.textSize = 17f; value.maxLines = 1; value.visibility = GONE
        addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * d).toInt() })
        chevron.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.glasse_ic_chevron)?.mutate()); chevron.visibility = GONE
        addView(chevron, LayoutParams((16 * d).toInt(), (16 * d).toInt()).apply { marginStart = (6 * d).toInt() })
        isClickable = false
    }

    fun setLabel(t: CharSequence) { label.text = t }
    /** A second, smaller line under the label (a Settings row's summary); null hides it. */
    fun setSubtitle(t: CharSequence?) { subtitle.text = t; subtitle.visibility = if (t.isNullOrEmpty()) GONE else VISIBLE }
    /** A greyed-out row: labels dimmed, taps ignored. */
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.4f }
    fun setValue(t: CharSequence?) { value.text = t; value.visibility = if (t == null) GONE else VISIBLE }
    fun setChevron(show: Boolean) { chevron.visibility = if (show) VISIBLE else GONE }
    /** A white glyph on a rounded square of [color] (the Settings icon). */
    fun setIcon(@DrawableRes icon: Int, @ColorInt color: Int) {
        val d = resources.displayMetrics.density
        iconView.setImageDrawable(ContextCompat.getDrawable(context, icon)?.mutate()?.also { it.setTint(android.graphics.Color.WHITE) })
        iconBox.background = GradientDrawable().apply { cornerRadius = 8 * d; setColor(color) }
        iconBox.visibility = VISIBLE
    }
    /** An icon drawn as is, with no coloured square (an app or brand logo); [tint] 0 keeps its colours. */
    @JvmOverloads
    fun setGlyph(d: Drawable?, @ColorInt tint: Int = 0) {
        val px = (24 * resources.displayMetrics.density).toInt()
        iconView.layoutParams = FrameLayout.LayoutParams(px, px, Gravity.CENTER)
        iconView.setImageDrawable(d?.mutate()?.also { if (tint != 0) it.setTint(tint) })
        iconBox.background = null
        iconBox.visibility = if (d == null) GONE else VISIBLE
    }
    fun setTrailing(v: View?) { trailing?.let { removeView(it) }; trailing = v; if (v != null) addView(v, childCount - 1) }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener(l)
        val th = themeOf(this)
        foreground = if (l == null) null else android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(if (th.isDark) 0x1FFFFFFF else 0x14000000), null, android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE),
        )
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restyle() }
    private fun restyle() {
        val th = themeOf(this)
        label.setTextColor(if (destructive) 0xFFFF3B30.toInt() else th.label.argb())
        value.setTextColor(th.secondaryLabel.argb())
        subtitle.setTextColor(th.secondaryLabel.argb())
        chevron.drawable?.setTint(mulAlpha(th.secondaryLabel.argb(), 0.5f))
        line.color = mulAlpha(th.secondaryLabel.argb(), 0.18f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showDivider) return
        val d = resources.displayMetrics.density
        val x = if (iconBox.visibility == VISIBLE) 60 * d else 16 * d
        canvas.drawRect(x, 0f, width.toFloat(), 0.5f * d, line)
    }
}
