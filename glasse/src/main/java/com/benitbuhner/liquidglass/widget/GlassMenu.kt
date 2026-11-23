package com.benitbuhner.liquidglass.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.benitbuhner.liquidglass.GlassBlob
import com.benitbuhner.liquidglass.GlassOverlayLayer
import com.benitbuhner.liquidglass.GlassPanel
import com.benitbuhner.liquidglass.GlassMaterial
import com.benitbuhner.liquidglass.GlassPainter
import com.benitbuhner.liquidglass.GlassStyle
import com.benitbuhner.liquidglass.LiquidGlassLayout
import com.benitbuhner.liquidglass.MenuModel
import com.benitbuhner.liquidglass.MenuMotion
import com.benitbuhner.liquidglass.R
import kotlin.math.max
import kotlin.math.min

/**
 * The iOS 26 pull-down menu for the View system: it pulls out of the button that opened it and drains back
 * into it, with the measured motion of the iOS version in [com.benitbuhner.liquidglass.GlassLooks] (or
 * [setMotion]). The same [MenuModel] drives Compose, so both move frame-for-frame like the web / RN kits.
 * Press-drag across the rows highlights them; lift to pick. Tap outside or Back closes it.
 *
 * ```java
 * new GlassMenu(button)
 *     .add("Select", R.drawable.ic_check, () -> select())
 *     .addSeparator()
 *     .addDestructive("Delete", R.drawable.ic_trash, () -> delete())
 *     .show();
 * ```
 * Needs a [LiquidGlassLayout] around the anchor. Or use [GlassMenuButton], which wires it for you.
 */
class GlassMenu(private val anchor: View) {
    /** One row. */
    class Item @JvmOverloads constructor(
        val label: CharSequence,
        val icon: Drawable? = null,
        val destructive: Boolean = false,
        val separatorBefore: Boolean = false,
        val onSelect: Runnable? = null,
    )

    fun interface OnDismissListener { fun onDismiss(menu: GlassMenu) }

    private val items = ArrayList<Item>()
    private var nextSep = false
    private var widthDp = 250f
    private var motion: MenuMotion? = null
    private var dismissListener: OnDismissListener? = null
    private var view: MenuView? = null

    private fun icon(@DrawableRes r: Int) = if (r == 0) null else ContextCompat.getDrawable(anchor.context, r)?.mutate()

    fun add(item: Item): GlassMenu = apply {
        items += if (nextSep && !item.separatorBefore) Item(item.label, item.icon, item.destructive, true, item.onSelect) else item
        nextSep = false
    }
    fun add(label: CharSequence, @DrawableRes icon: Int, onSelect: Runnable?) = add(Item(label, icon(icon), false, false, onSelect))
    fun add(label: CharSequence, onSelect: Runnable?) = add(Item(label, null, false, false, onSelect))
    fun addDestructive(label: CharSequence, @DrawableRes icon: Int, onSelect: Runnable?) = add(Item(label, icon(icon), true, false, onSelect))
    /** a divider before the next row */
    fun addSeparator() = apply { nextSep = true }
    fun setItems(list: List<Item>) = apply { items.clear(); items.addAll(list) }
    fun setWidthDp(w: Float) = apply { widthDp = w }
    /** Override the motion (default: the current [com.benitbuhner.liquidglass.GlassLook.menuMotion]). */
    fun setMotion(m: MenuMotion?) = apply { motion = m }
    fun setOnDismissListener(l: OnDismissListener?) = apply { dismissListener = l }

    val isShowing: Boolean get() = view?.closing == false

    fun show() {
        if (view != null) return
        val host = Overlays.hostOf(anchor)
        val layer = Overlays.layerOf(host)
        val v = MenuView(anchor.context, this, host, anchor, items.toList(), widthDp, motion)
        view = v
        layer.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.open()
    }

    fun dismiss() { view?.close() }

    internal fun gone() { view = null; dismissListener?.onDismiss(this) }

    companion object {
        /** Show a menu for [anchor] with [items]. */
        @JvmStatic
        fun show(anchor: View, items: List<Item>): GlassMenu = GlassMenu(anchor).setItems(items).also { it.show() }
    }
}

/** A [GlassButton] (the "…" glyph by default) that opens a [GlassMenu] on tap. */
class GlassMenuButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassButton(context, attrs) {
    val menu = GlassMenu(this)
    init {
        setIcon(R.drawable.glasse_ic_ellipsis)
        contentDescription = "More"
        setOnClickListener { menu.show() }
    }
}

@SuppressLint("ViewConstructor")
private class MenuView(
    context: Context,
    private val owner: GlassMenu,
    private val host: LiquidGlassLayout,
    private val anchor: View,
    items: List<GlassMenu.Item>,
    widthDp: Float,
    private val motion: MenuMotion?,
) : View(context) {
    private val rows: List<GlassMenu.Item?> = buildList { items.forEach { if (it.separatorBefore && isNotEmpty()) add(null); add(it) } }
    private val px = resources.displayMetrics.density
    private var model: MenuModel? = null
    private var a = RectF() // the anchor, host px
    private var flipY = false
    private var s = 1f
    private val painter = GlassPainter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val listNode = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-menu-rows") else null
    private val glyphNode = if (Build.VERSION.SDK_INT >= 29) RenderNode("glasse-menu-glyph") else null
    private val clip = Path()
    private val wantWidth = widthDp
    var closing = false; private set
    private var back: OnBackPressedCallback? = null
    private val glyph: Drawable? = (anchor as? GlassButton)?.getIcon()?.constantState?.newDrawable()?.mutate()

    private val loop = GlassFrameLoop { dt ->
        val m = model ?: return@GlassFrameLoop false
        val more = m.step(dt / com.benitbuhner.liquidglass.GlassMotion.timeScale) // the model applies timeScale itself
        syncAnchor()
        invalidate()
        if (!more && closing && m.phase == MenuModel.Phase.Closed) { post { finish() }; false } else more
    }

    private fun B() = 44f

    fun open() {
        // wait for layout so we know the overlay's size
        if (width == 0) { post { open() }; return }
        val th = themeOf(host)
        a = Overlays.boundsIn(anchor, host)
        val alignRight = a.centerX() > width / 2f
        val wDp = min(wantWidth, width / px - 24f)
        val m = MenuModel(rows.map { it == null }, wDp, alignRight, th.isDark)
        model = m
        flipY = a.top / px + m.Hm + 24f > height / px && a.top > height / 2f
        s = a.width() / px / B()
        if (motion != null) m.nextMotion = motion
        m.show(true)
        cover(m)
        back = Overlays.onBack(this) { close() }
        loop.start()
    }

    fun close() {
        val m = model ?: return
        if (closing) return
        closing = true
        back?.remove(); back = null
        if (motion != null) m.nextMotion = motion
        m.show(false)
        loop.start()
    }

    /* Glass under an open menu (iOS 26: no glass seen through glass, RN kit coverGlass): the glass controls the open
       menu will cover leave the screen the menu refracts, and the menu draws them itself under its body, clipped to
       where the body is not, so they are covered as it grows and uncovered as it drains back. */
    private val covered = ArrayList<GlassPanel>()

    private fun cover(m: MenuModel) {
        val l = bx(m.menuX); val r = l + m.width * px * s
        val t0 = if (flipY) by(m.Hm) else by(0f); val t1 = t0 + m.Hm * px * s
        val open = RectF(l, min(t0, t1), r, max(t0, t1))
        fun walk(v: View) {
            if (v is GlassOverlayLayer || v === anchor || !v.isShown) return
            if (v is GlassPanel) {
                if (!v.isContentGlass && RectF.intersects(Overlays.boundsIn(v, host), open)) covered += v
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(host)
        for (p in covered) { p.coveredBy = this; p.invalidate() }
    }

    private fun uncover() {
        for (p in covered) if (p.coveredBy === this) { p.coveredBy = null; p.invalidate() }
        covered.clear()
    }

    private val coverClip = Path()
    private fun drawCovered(canvas: Canvas, f: com.benitbuhner.liquidglass.MenuFrame, r: Float, headR: Float, hx: Float, hy: Float) {
        if (covered.isEmpty()) return
        canvas.save()
        coverClip.reset()
        if (!f.landed) coverClip.addRoundRect(body, r, r, Path.Direction.CW)
        if (headR > 0.5f) coverClip.addCircle(hx, hy, headR, Path.Direction.CW)
        if (!coverClip.isEmpty) canvas.clipOutPath(coverClip)
        for (p in covered) {
            if (!p.isShown) continue
            val b = Overlays.boundsIn(p, host)
            canvas.save(); canvas.translate(b.left, b.top); canvas.scale(p.scaleX, p.scaleY)
            p.coverPass = true
            try { p.draw(canvas) } finally { p.coverPass = false }
            canvas.restore()
        }
        canvas.restore()
    }

    private fun finish() {
        uncover()
        anchor.alpha = 1f; moveAnchor(0f, 0f)
        loop.stop()
        (parent as? ViewGroup)?.removeView(this)
        owner.gone()
    }

    private fun syncAnchor() {
        val m = model ?: return
        // the menu draws the trigger while it is out; the button's rest glass takes back over as the close lands
        // in the head, riding the head's last settle into place (RN m8)
        anchor.alpha = m.restHandoff()
        moveAnchor(m.restDx * px * s, m.restDy * px * s * (if (flipY) -1 else 1))
    }

    private var movedX = 0f; private var movedY = 0f
    private fun moveAnchor(dx: Float, dy: Float) {
        anchor.translationX += dx - movedX; anchor.translationY += dy - movedY
        movedX = dx; movedY = dy
    }

    override fun onDetachedFromWindow() { loop.stop(); back?.remove(); uncover(); anchor.alpha = 1f; moveAnchor(0f, 0f); super.onDetachedFromWindow() }

    private fun bx(x: Float) = a.left + x * px * s
    private fun by(y: Float) = if (flipY) a.top + (B() - y) * px * s else a.top + y * px * s
    private fun yIn(m: MenuModel, top: Float, h: Float) = if (flipY) m.Hm - top - h else top

    private val body = RectF()
    private val shapes = ArrayList<GlassBlob>(2)

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val th = themeOf(host)
        m.dark = th.isDark
        val f = m.frame
        val q = f.body
        val dtx = f.bodyTx * px; val dty = f.bodyTy * px
        body.set(bx(q.x0), min(by(q.y0), by(q.y0 + q.h)), bx(q.x0 + q.w), max(by(q.y0), by(q.y0 + q.h)))
        body.offset(dtx, dty)
        val r = min(q.rx, q.ry) * px * s
        val headR = f.headR * px * s
        val hx = bx(f.headX) + dtx; val hy = by(f.headY) + dty

        drawCovered(canvas, f, r, headR, hx, hy)

        // 1. glass: body ∪ head melting through the neck, sampling the whole screen (backdrop + chrome)
        val bb = RectF(body)
        if (f.landed) bb.setEmpty()
        if (headR > 0.5f) bb.union(hx - headR, hy - headR, hx + headR, hy + headR)
        if (!bb.isEmpty) {
            bb.inset(-2f, -2f)
            shapes.clear()
            if (!f.landed) shapes += GlassBlob(Offset(body.centerX() - bb.left, body.centerY() - bb.top), Size(body.width(), body.height()), r)
            if (headR > 0.5f) shapes += GlassBlob(Offset(hx - bb.left, hy - bb.top), Size(2 * headR, 2 * headR), headR)
            val a0 = if (th.isDark) GlassMaterial.DARK_TINT_ALPHA else GlassMaterial.LIGHT_TINT_ALPHA
            val thick = ((f.tint - (if (th.isDark) GlassMaterial.DARK_TINT_ALPHA else 0.3f)) / (if (th.isDark) 0.06f else 0.16f)).coerceIn(0f, 1f)
            val small = min(body.width(), body.height()) < 70 * px
            val glass = GlassStyle(
                bezel = if (small) 18.dp else 26.dp, thickness = if (small) 26.dp else 36.dp,
                tintAlpha = a0 + thick * (if (th.isDark) 0.06f else 0.16f), blur = (2f + 2f * thick).dp,
            )
            val src = host.screen
            val ox = bb.left; val oy = bb.top
            canvas.save(); canvas.translate(ox, oy)
            painter.draw(
                canvas, px, bb.width(), bb.height(), glass, th, 0f, Float.MAX_VALUE, shapes, max(1f, f.neck * px * s),
                if (src == null) null else { rc -> rc.translate(-ox, -oy); rc.drawRenderNode(src) },
            )
            if (f.flash > 0.001f) { paint.style = Paint.Style.FILL; paint.color = mulAlpha(android.graphics.Color.WHITE, f.flash); canvas.drawPath(painter.outline, paint) }
            canvas.restore()
        }

        // 2. rows, clipped to the body
        if (f.listOpacity > 0.003f && !f.landed) {
            canvas.save()
            clip.reset(); clip.addRoundRect(body, r, r, Path.Direction.CW)
            canvas.clipPath(clip)
            val left = bx(m.menuX) + dtx
            val top = (if (flipY) by(m.Hm) else by(0f)) + f.listTy * px * (if (flipY) -1 else 1) + dty
            val lw = m.width * s * px; val lh = m.Hm * s * px
            val n = listNode
            if (n != null && canvas.isHardwareAccelerated) {
                n.setPosition(0, 0, lw.toInt().coerceAtLeast(1), lh.toInt().coerceAtLeast(1))
                val rc = n.beginRecording()
                try { drawRows(rc, m, th) } finally { n.endRecording() }
                n.setAlpha(f.listOpacity.coerceIn(0f, 1f))
                n.setPivotX(if (m.alignRight) lw else 0f); n.setPivotY(if (flipY) lh else 0f)
                n.setScaleX(f.listScale); n.setScaleY(f.listScale)
                // setRenderEffect itself is API 31: even a null effect must not be set below that
                if (Build.VERSION.SDK_INT >= 31) n.setRenderEffect(if (f.listBlur > 0.05f && Build.VERSION.SDK_INT >= 31) RenderEffect.createBlurEffect(f.listBlur * 0.6f * px, f.listBlur * 0.6f * px, Shader.TileMode.DECAL) else null)
                canvas.translate(left, top)
                canvas.drawRenderNode(n)
            } else {
                canvas.translate(left, top)
                canvas.saveLayerAlpha(0f, 0f, lw, lh, (f.listOpacity.coerceIn(0f, 1f) * 255).toInt())
                drawRows(canvas, m, th)
                canvas.restore()
            }
            canvas.restore()
        }

        // 3. the trigger's glyph rides the head: blurs out as the blob forms, blurs back in on close
        val ga = f.trigOpacity * (1f - m.restHandoff())
        if (ga > 0.001f && glyph != null) {
            val gs = 44f * px * s
            val g = glyphNode
            if (g != null && canvas.isHardwareAccelerated) {
                g.setPosition(0, 0, gs.toInt(), gs.toInt())
                val rc = g.beginRecording()
                try { rc.icon(glyph, gs / 2, gs / 2, 22f * px * s, th.label.argb()) } finally { g.endRecording() }
                g.setAlpha(ga.coerceIn(0f, 1f)); g.setPivotX(gs / 2); g.setPivotY(gs / 2); g.setScaleX(f.trigScale); g.setScaleY(f.trigScale)
                // setRenderEffect itself is API 31: even a null effect must not be set below that
                if (Build.VERSION.SDK_INT >= 31) g.setRenderEffect(if (f.trigBlur > 0.05f && Build.VERSION.SDK_INT >= 31) RenderEffect.createBlurEffect(f.trigBlur * 0.6f * px, f.trigBlur * 0.6f * px, Shader.TileMode.DECAL) else null)
                canvas.save(); canvas.translate(hx - gs / 2, hy - gs / 2); canvas.drawRenderNode(g); canvas.restore()
            } else canvas.icon(glyph, hx, hy, 22f * px * s * f.trigScale, th.label.argb(), ga)
        }
    }

    private fun drawRows(c: Canvas, m: MenuModel, th: com.benitbuhner.liquidglass.GlassTheme) {
        val f = m.frame
        val k = px * s
        // highlight: inset 7 dp, concentric with the menu corner (iOS)
        if (f.hlOpacity > 0.01f) {
            val hr = min(22f, m.openRadius - 7f).coerceAtLeast(8f) * px
            paint.style = Paint.Style.FILL
            paint.color = mulAlpha(if (th.isDark) 0x2EFFFFFF else 0x1A000000, f.hlOpacity)
            val y = yIn(m, f.hlY, 44f) * k
            c.drawRoundRect(7 * k, y, (m.width - 7) * k, y + 44 * k, hr, hr, paint)
        }
        val red = if (th.isDark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
        rows.forEachIndexed { i, item ->
            val row = m.rows[i]
            val top = yIn(m, row.top, row.height) * k
            if (item == null) {
                paint.style = Paint.Style.FILL
                paint.color = mulAlpha(th.secondaryLabel.argb(), 0.3f)
                c.drawRect(16 * k, top, (m.width - 16) * k, top + 0.5f * px, paint)
            } else {
                val ra = m.rowAlpha(if (flipY) rows.size - 1 - i else i, rows.size)
                if (ra <= 0.003f) return@forEachIndexed
                val col = if (item.destructive) red else th.label.argb()
                val cy = top + 22 * k
                val iconW = if (item.icon != null) 20 * px + 12 * px else 0f
                tp.textSize = 17 * px * s; tp.color = mulAlpha(col, ra); tp.isAntiAlias = true
                val avail = m.width * k - 36 * px - iconW
                val text = TextUtils.ellipsize(item.label, tp, avail, TextUtils.TruncateAt.END)
                val fm = tp.fontMetrics
                c.drawText(text, 0, text.length, 18 * px, cy - (fm.ascent + fm.descent) / 2, tp)
                c.icon(item.icon, m.width * k - 18 * px - 10 * px, cy, 20 * px, col, ra)
            }
        }
    }

    // dismiss on a tap outside; press-drag over the rows highlights and selects (iOS)
    private var tracking = false
    private var lastY = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = model ?: return true
        fun lx(x: Float) = (x - a.left) / px / s
        fun ly(y: Float) = if (flipY) B() - (y - a.top) / px / s else (y - a.top) / px / s
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = false
                if (closing) return true
                val inside = m.phase != MenuModel.Phase.Closed && body.contains(e.x, e.y)
                if (!inside) { close(); return true }
                tracking = true
                m.listDown(lx(e.x), ly(e.y)); lastY = ly(e.y); loop.start()
            }
            MotionEvent.ACTION_MOVE -> if (tracking) { m.listMove(lx(e.x), ly(e.y)); lastY = ly(e.y); loop.start() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                val i = m.listUp(if (e.actionMasked == MotionEvent.ACTION_CANCEL) -1000f else lastY)
                loop.start()
                val item = rows.getOrNull(i)
                if (item != null) { item.onSelect?.run(); close() }
            }
        }
        return true
    }
}
