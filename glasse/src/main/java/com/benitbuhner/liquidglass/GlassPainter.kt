package com.benitbuhner.liquidglass

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one glass surface onto a View's Canvas: the shared engine behind every View-system control
 * ([GlassPanel], the `widget` package). [slice] records what lies under the glass, in the glass's own
 * coordinates (0,0 = its top-left), usually `canvas.translate(-x, -y); canvas.drawRenderNode(backdrop)`.
 *
 * Tiers like the Compose modifier: API 33+ refracts (AGSL), 31-32 frosts, older draws a flat tint.
 */
class GlassPainter {
    private var node: RenderNode? = null
    private var fx: Any? = null
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The outline of the last draw (the rounded rect, or the union of [GlassBlob]s), in glass coordinates. */
    val outline: Path get() = path

    /**
     * Draw glass of [width]×[height] px at the canvas origin.
     * [radius] px (clamped to a pill); [shapes] (glass coordinates) replace the rect with merging blobs.
     */
    @JvmOverloads
    fun draw(
        canvas: Canvas,
        density: Float,
        width: Float,
        height: Float,
        style: GlassStyle,
        theme: GlassTheme,
        lift: Float = 0f,
        radius: Float = Float.MAX_VALUE,
        shapes: List<GlassBlob>? = null,
        mergeRadius: Float = 0f,
        slice: ((Canvas) -> Unit)? = null,
    ) {
        if (width <= 0f || height <= 0f) return
        val r = min(radius, min(width, height) / 2f)
        buildOutline(width, height, r, shapes)
        if (style.shadow > 0f) drawShadow(canvas, density, style, theme)
        val tint = resolveTint(style, theme)
        if (slice == null || Build.VERSION.SDK_INT < 31 || !canvas.isHardwareAccelerated) {
            paint.style = Paint.Style.FILL; paint.shader = null
            paint.color = tint.light.copy(alpha = min(0.97f, tint.light.alpha + 0.38f)).toArgb()
            canvas.drawPath(path, paint)
            drawRim(canvas, density, width, height, style, theme, lift)
            return
        }
        val n = node ?: RenderNode("glasse").also { node = it }
        val w = width.toInt().coerceAtLeast(1); val h = height.toInt().coerceAtLeast(1)
        n.setPosition(0, 0, w, h)
        val rc = n.beginRecording(w, h)
        try { slice(rc) } finally { n.endRecording() }
        if (Build.VERSION.SDK_INT >= 33 && theme.refraction) {
            val e = (fx as? GlassEffects) ?: GlassEffects().also { fx = it }
            n.setRenderEffect(e.build(density, width, height, style, tint, lift, shapes, mergeRadius, r))
            // The material blur runs before the glass shader and spreads the slice past the node's bounds;
            // the shader only ever sees the bounds, so that spread drew as a blurred rectangle around the
            // glass (a square halo around tab bars and buttons). Keep the result inside the outline; merging
            // blobs keep their bounds instead, because the liquid neck between them lies outside both shapes.
            canvas.save()
            if (shapes == null) canvas.clipPath(path) else canvas.clipRect(0f, 0f, width, height)
            canvas.drawRenderNode(n)
            canvas.restore()
        } else {
            val blur = max(style.blur.value * density * 4f, 14f * density)
            n.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRenderNode(n)
            paint.style = Paint.Style.FILL; paint.shader = null
            paint.color = tint.light.copy(alpha = min(0.97f, tint.light.alpha + 0.12f)).toArgb()
            canvas.drawPath(path, paint)
            canvas.restore()
            drawRim(canvas, density, width, height, style, theme, lift)
        }
    }

    private fun buildOutline(w: Float, h: Float, r: Float, shapes: List<GlassBlob>?) {
        path.reset()
        if (shapes == null) path.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
        else shapes.forEach { b ->
            val br = min(b.radius, b.size.minDimension / 2)
            path.addRoundRect(b.center.x - b.size.width / 2, b.center.y - b.size.height / 2, b.center.x + b.size.width / 2, b.center.y + b.size.height / 2, br, br, Path.Direction.CW)
        }
    }

    private fun drawShadow(canvas: Canvas, d: Float, style: GlassStyle, theme: GlassTheme) {
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = android.graphics.Color.BLACK
        val a = (if (theme.isDark) 0.30f else 0.13f) * style.shadow
        paint.setShadowLayer(14f * d, 0f, 5f * d, Color.Black.copy(alpha = a.coerceIn(0f, 1f)).toArgb())
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.clearShadowLayer()
    }

    private fun drawRim(canvas: Canvas, d: Float, w: Float, h: Float, style: GlassStyle, theme: GlassTheme, lift: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * d
        val a = ((if (theme.isDark) 0.35f else 0.75f) * style.specular * (1f + lift * 0.4f)).coerceIn(0f, 1f)
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.White.copy(alpha = a).toArgb(), Color.White.copy(alpha = 0.06f).toArgb(), Color.White.copy(alpha = a * 0.5f).toArgb()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
    }
}
