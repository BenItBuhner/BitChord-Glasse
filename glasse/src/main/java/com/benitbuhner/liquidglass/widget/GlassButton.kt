package com.benitbuhner.liquidglass.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.benitbuhner.liquidglass.GlassPanel
import com.benitbuhner.liquidglass.GlassSpringPresets
import kotlin.math.max

/**
 * The iOS 26 glass button for the View system, the twin of Compose `GlassButton` / `GlassIconButton` and the
 * web `<GlassButton>`: 44 dp tall, a circle when it holds only an icon, a capsule with a label. It swells
 * 8 % and its rim brightens while pressed (press spring 520/30, release bouncy 420/22).
 *
 * ```java
 * GlassButton more = new GlassButton(ctx);
 * more.setIcon(R.drawable.glasse_ic_ellipsis);
 * more.setOnClickListener(v -> ...);
 * ```
 */
open class GlassButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : GlassPanel(context, attrs, defStyleAttr) {

    private var icon: Drawable? = null
    private var label: CharSequence? = null
    private var prominent = false
    @ColorInt private var tintArgb = 0
    @ColorInt private var contentColor = 0
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val springs = ViewSprings(this) { applyPress() }
    private val press = springs.spring(0f, GlassSpringPresets.press)

    /** Button height in dp (default 44). */
    var sizeDp: Float = 44f
        set(v) { field = v; requestLayout() }

    init {
        isClickable = true
        isFocusable = true
    }

    fun setIcon(d: Drawable?) { icon = d?.mutate(); requestLayout(); invalidate() }
    fun setIcon(@DrawableRes res: Int) = setIcon(ContextCompat.getDrawable(context, res))
    fun getIcon(): Drawable? = icon

    fun setText(text: CharSequence?) { label = text; contentDescription = text; requestLayout(); invalidate() }
    fun getText(): CharSequence? = label

    /** Fill with the accent colour (`.glassProminent`): white content on accent glass. */
    fun setProminent(p: Boolean) { prominent = p; restyle() }
    fun isProminent() = prominent

    /** Tint the glass with a colour (0 = the plain material). */
    fun setGlassTint(@ColorInt argb: Int) { tintArgb = argb; restyle() }

    /** Colour of the icon / label (0 = the theme label colour, or white when prominent). */
    fun setContentColor(@ColorInt argb: Int) { contentColor = argb; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); restyle() }

    private fun restyle() {
        val accent = themeOf(this).accent
        val t = when {
            tintArgb != 0 -> Color(tintArgb)
            prominent -> accent
            else -> null
        }
        glassStyle = glassStyle.copy(tint = t, tintAlpha = if (t != null) 0.86f else null, adaptive = if (t != null) false else null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = dp(sizeDp)
        val l = label
        val w = if (l == null) h else {
            tp.text(dp(17f), 0, false)
            max(h, tp.measureText(l, 0, l.length) + dp(36f) + if (icon != null) dp(28f) else 0f)
        }
        setMeasuredDimension(resolveSize(w.toInt(), widthMeasureSpec), resolveSize(h.toInt(), heightMeasureSpec))
        // children (if any) fill the button
        for (i in 0 until childCount) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val th = themeOf(this)
        val c = if (contentColor != 0) contentColor else if (prominent || tintArgb != 0) android.graphics.Color.WHITE else th.label.argb()
        val alpha = if (isEnabled) 1f else 0.4f
        val l = label
        val cy = height / 2f
        if (l == null) {
            canvas.icon(icon, width / 2f, cy, height * 0.5f, c, alpha)
        } else {
            tp.text(dp(17f), mulAlpha(c, alpha), false) // body text, as Compose (Text 17 sp) and UIKit glass buttons
            val tw = tp.measureText(l, 0, l.length)
            val iw = if (icon != null) dp(28f) else 0f
            val x0 = (width - tw - iw) / 2f
            canvas.icon(icon, x0 + dp(11f), cy, dp(22f), c, alpha)
            val fm = tp.fontMetrics
            canvas.drawText(l, 0, l.length, x0 + iw, cy - (fm.ascent + fm.descent) / 2f, tp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> springs.to(press, 1f, GlassSpringPresets.press)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springs.to(press, 0f, GlassSpringPresets.bouncy)
        }
        return super.onTouchEvent(e)
    }

    private fun applyPress() {
        val s = 1f + 0.08f * press.value
        scaleX = s; scaleY = s
        setLift(press.value)
    }

    override fun onDetachedFromWindow() { springs.stop(); super.onDetachedFromWindow() }

    @Suppress("unused") private val keep = View.NO_ID
}
