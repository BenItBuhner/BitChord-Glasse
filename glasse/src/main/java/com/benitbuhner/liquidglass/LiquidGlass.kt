package com.benitbuhner.liquidglass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Which tier actually renders on this device. */
enum class GlassMode { Refract, Frosted, Flat }

val glassModeForDevice: GlassMode
    get() = when {
        Build.VERSION.SDK_INT >= 33 -> GlassMode.Refract
        Build.VERSION.SDK_INT >= 31 -> GlassMode.Frosted
        else -> GlassMode.Flat
    }

/** One shape in a merging [glassBlobs] field, in the node's local px. */
@Immutable
data class GlassBlob(val center: Offset, val size: Size, val radius: Float)

private val NoLift: () -> Float = { 0f }

/**
 * Turns this node into a Liquid Glass surface that refracts whatever [backdrop] recorded.
 * The node's bounds are the glass (a rounded rect; [GlassStyle.cornerRadius] null = pill).
 * [lift] (0..1, read during draw so it can animate without recomposing) brightens the rim and
 * adds a little magnification, like a pressed iOS 26 control.
 */
fun Modifier.liquidGlass(
    backdrop: Backdrop,
    style: GlassStyle = GlassStyle(),
    theme: GlassTheme? = null,
    lift: () -> Float = NoLift,
): Modifier = this then GlassElement(backdrop, style, theme, lift, null, 0f, null)

/**
 * [liquidGlass] that also records each frame's glass picture (refracted backdrop and tint, not the content
 * drawn over it) into [export], so a lens above the surface (a tab bar's selection pill) can refract the glass
 * rather than the page under it.
 */
fun Modifier.exportingLiquidGlass(
    backdrop: Backdrop,
    export: GraphicsLayer?,
    style: GlassStyle = GlassStyle(),
    theme: GlassTheme? = null,
    lift: () -> Float = NoLift,
): Modifier = this then GlassElement(backdrop, style, theme, lift, null, 0f, export)

/**
 * A field of glass shapes that melt together (smooth-min union, [mergeRadius] px of "goo").
 * [blobs] is read during draw, so animating it only redraws.
 */
fun Modifier.glassBlobs(
    backdrop: Backdrop,
    blobs: () -> List<GlassBlob>,
    mergeRadius: Float,
    style: GlassStyle = GlassStyle(),
    theme: GlassTheme? = null,
): Modifier = this then GlassElement(backdrop, style, theme, NoLift, blobs, mergeRadius, null)

private data class GlassElement(
    val backdrop: Backdrop,
    val style: GlassStyle,
    val theme: GlassTheme?,
    val lift: () -> Float,
    val blobs: (() -> List<GlassBlob>)?,
    val k: Float,
    val export: GraphicsLayer?,
) : ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(backdrop, style, theme, lift, blobs, k, export)
    override fun update(node: GlassNode) {
        node.backdrop = backdrop; node.baseStyle = style; node.theme = theme
        node.lift = lift; node.blobs = blobs; node.k = k; node.export = export
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() { name = "liquidGlass" }
}

internal class GlassNode(
    var backdrop: Backdrop,
    var baseStyle: GlassStyle,
    var theme: GlassTheme?,
    var lift: () -> Float,
    var blobs: (() -> List<GlassBlob>)?,
    var k: Float,
    /** when set, every frame's glass (without the content drawn over it) is also recorded here, for a lens above */
    var export: GraphicsLayer? = null,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    /** the style this surface draws with: its own, through the app-wide / subtree defaults (GlassStyles) */
    val style: GlassStyle get() {
        val local = if (isAttached) currentValueOf(LocalGlassStyleDefaults) else null
        if (local == null && GlassStyles.defaults == null) return baseStyle
        if (effBase !== baseStyle || effLocal !== local || effVer != GlassStyles.version) {
            effBase = baseStyle; effLocal = local; effVer = GlassStyles.version
            eff = GlassStyles.effective(baseStyle, local)
        }
        return eff!!
    }
    private var eff: GlassStyle? = null
    private var effBase: GlassStyle? = null
    private var effLocal: GlassStyleTransform? = null
    private var effVer = -1

    private var layer: GraphicsLayer? = null
    private var coords: LayoutCoordinates? = null
    private var lastPos = Offset.Unspecified
    private var fx: Any? = null // GlassEffects on API 33+

    private var host: GlassHostState? = null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
        host = currentValueOf(LocalGlassHost)?.also { it.glass += this }
    }

    override fun onDetach() {
        backdrop.contentGlass.remove(this)
        host?.glass?.remove(this); host = null; coveredBy = null
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        own?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null; own = null; inSource = false; insideOf = null
    }

    // Glass inside the backdropSource it samples (a button in a scrolling list) can't draw in place, or the backdrop
    // would contain the glass sampling it. It records itself into [own] instead and the source draws that over the
    // backdrop (Backdrop.drawContentGlass), like content glass in the View system.
    internal var own: GraphicsLayer? = null
    private var inSource = false
    internal val position: LayoutCoordinates? get() = coords
    internal val isContent: Boolean get() = inSource

    /** set while an open menu covers this glass: the menu draws it under its body (no glass seen through glass) */
    private var coveredBy: Any? = null
    private var coverReady: Any? = null
    private var onCoverReady: (() -> Unit)? = null
    /** [ready] runs once this glass has recorded itself for the coverer to draw (it keeps drawing in place until then) */
    internal fun cover(by: Any, ready: () -> Unit) { coveredBy = by; onCoverReady = ready; invalidateDraw() }
    internal fun uncover(by: Any) { if (coveredBy === by) { coveredBy = null; coverReady = null; onCoverReady = null; if (isAttached) invalidateDraw() } }
    private var contentTheme: GlassTheme? = null

    /** Paint this glass (lifted out of the tree: content glass, or covered by a menu) into [scope], whose origin is [into]. */
    internal fun drawAsContent(scope: DrawScope, into: LayoutCoordinates) {
        val o = own ?: return; val l = layer ?: return; val mc = coords ?: return; val th = contentTheme ?: return
        val bc = backdrop.coordinates ?: return
        if (!mc.isAttached || !bc.isAttached || !into.isAttached) return
        val w = mc.size.width.toFloat(); val h = mc.size.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val p0 = into.localPositionOf(mc, Offset.Zero)
        val p1 = into.localPositionOf(mc, Offset(w, h))
        with(scope) {
            withTransform({ translate(p0.x, p0.y); scale((p1.x - p0.x) / w, (p1.y - p0.y) / h, pivot = Offset.Zero) }) {
                paintGlass(l, bc, mc, th, Size(w, h))
                drawLayer(o)
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        coords = coordinates
        val bc = backdrop.coordinates
        if (bc != null && bc.isAttached && coordinates.isAttached) {
            val p = bc.localPositionOf(coordinates, Offset.Zero)
            if (p != lastPos) {
                lastPos = p
                invalidateDraw()
            }
        } else invalidateDraw()
    }

    private var insideOf: Pair<LayoutCoordinates, LayoutCoordinates>? = null
    private var inside = false

    /**
     * Whether this glass is laid out inside the backdrop's source. The recording flag alone misses glass whose
     * first draw is a re-record of its own layer (a page's list item drawn outside its parent's pass): drawn in
     * place, that glass would put the backdrop inside itself, a render-node cycle that overflows the RenderThread.
     */
    private fun isInside(mc: LayoutCoordinates, bc: LayoutCoordinates): Boolean {
        val key = insideOf
        if (key == null || key.first !== mc || key.second !== bc) {
            var c = mc.parentCoordinates
            inside = false
            while (c != null) { if (c === bc) { inside = true; break }; c = c.parentCoordinates }
            insideOf = mc to bc
        }
        return inside
    }

    override fun ContentDrawScope.draw() {
        val l = layer
        val bc = backdrop.coordinates
        val mc = coords
        if (l == null || bc == null || mc == null || !bc.isAttached || !mc.isAttached || size.minDimension <= 0f) {
            drawContent()
            return
        }
        val th = theme ?: currentValueOf(LocalGlassTheme)
        if (backdrop.recording || isInside(mc, bc)) inSource = true
        if (inSource || coveredBy != null) {
            // record only the content now; the glass itself is painted by the source once its recording is closed
            val o = own ?: requireGraphicsContext().createGraphicsLayer().also { own = it }
            if (inSource) backdrop.contentGlass.add(this@GlassNode)
            contentTheme = th
            val outer = this
            o.record(IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) { outer.drawContent() }
            if (inSource && !backdrop.recording) backdrop.invalidateSource?.invoke() // redrawn alone (a press): repaint the glass
            if (!inSource && coveredBy != null && coverReady !== coveredBy) {
                // just covered: stay in place this one frame (the coverer has nothing recorded to draw yet)
                coverReady = coveredBy
                paintGlass(l, bc, mc, th); drawLayer(o)
                onCoverReady?.invoke()
            }
            return
        }
        paintGlass(l, bc, mc, th)
        drawContent()
    }

    private fun DrawScope.paintGlass(l: GraphicsLayer, bc: LayoutCoordinates, mc: LayoutCoordinates, th: GlassTheme, sz: Size = size) {
        val liftV = lift().coerceIn(0f, 1.5f)
        val tint = resolveTint(style, th)
        val shapes = blobs?.invoke()
        val radius = style.cornerRadius?.toPx() ?: (sz.minDimension / 2f)
        val outline: Path by lazy(LazyThreadSafetyMode.NONE) { outlinePath(sz, shapes, radius) }

        // Where this node sits over the backdrop, including any scale applied by graphicsLayer.
        val p0 = bc.localPositionOf(mc, Offset.Zero)
        val p1 = bc.localPositionOf(mc, Offset(sz.width, sz.height))
        val sx = ((p1.x - p0.x) / sz.width).takeIf { it > 0.01f } ?: 1f
        val sy = ((p1.y - p0.y) / sz.height).takeIf { it > 0.01f } ?: 1f

        if (style.shadow > 0f && Build.VERSION.SDK_INT >= 28) drawShadow(outline, th)

        l.record(IntSize(ceil(sz.width).toInt(), ceil(sz.height).toInt())) {
            withTransform({
                scale(1f / sx, 1f / sy, pivot = Offset.Zero)
                translate(-p0.x, -p0.y)
            }) { drawLayer(backdrop.layer) }
        }

        val refract = Build.VERSION.SDK_INT >= 33 && th.refraction
        if (refract) {
            val effects = (fx as? GlassEffects) ?: GlassEffects().also { fx = it }
            l.renderEffect = effects.build(density, sz.width, sz.height, style, tint, liftV, shapes, k, radius).asComposeRenderEffect()
            drawLayer(l)
            export?.record(IntSize(ceil(sz.width).toInt(), ceil(sz.height).toInt())) { drawLayer(l) }
        } else {
            val blurPx = max(style.blur.toPx() * 4f, 14f * density)
            l.renderEffect = if (Build.VERSION.SDK_INT >= 31) {
                RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP).asComposeRenderEffect()
            } else null
            clipPath(outline) {
                drawLayer(l)
                val extra = if (Build.VERSION.SDK_INT >= 31) 0.12f else 0.38f
                drawPath(outline, tint.light.copy(alpha = (tint.light.alpha + extra).coerceAtMost(0.97f)))
            }
            drawFallbackRim(sz, outline, th, liftV)
        }
    }


    private fun outlinePath(sz: Size, shapes: List<GlassBlob>?, radius: Float): Path {
        val path = Path()
        if (shapes == null) {
            path.addRoundRect(RoundRect(0f, 0f, sz.width, sz.height, CornerRadius(radius)))
        } else {
            shapes.forEach { b ->
                val r = min(b.radius, b.size.minDimension / 2)
                val one = Path().apply {
                    addRoundRect(
                        RoundRect(
                            b.center.x - b.size.width / 2, b.center.y - b.size.height / 2,
                            b.center.x + b.size.width / 2, b.center.y + b.size.height / 2, CornerRadius(r),
                        ),
                    )
                }
                if (path.isEmpty) path.addPath(one) else path.op(path, one, PathOperation.Union)
            }
        }
        return path
    }

    private fun DrawScope.drawShadow(outline: Path, th: GlassTheme) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            val a = (if (th.isDark) 0.30f else 0.13f) * this@GlassNode.style.shadow
            setShadowLayer(14f * density, 0f, 5f * density, Color.Black.copy(alpha = a).toArgb())
        }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            val ap = outline.asAndroidPath()
            nc.save()
            nc.clipOutPath(ap)
            nc.drawPath(ap, paint)
            nc.restore()
        }
    }

    private fun DrawScope.drawFallbackRim(sz: Size, outline: Path, th: GlassTheme, lift: Float) {
        val a = (if (th.isDark) 0.35f else 0.75f) * style.specular * (1f + lift * 0.4f)
        drawPath(
            outline,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = a.coerceAtMost(1f)),
                0.45f to Color.White.copy(alpha = 0.06f),
                1f to Color.White.copy(alpha = (a * 0.5f).coerceAtMost(1f)),
                start = Offset.Zero, end = Offset(sz.width, sz.height),
            ),
            style = Stroke(width = 1.1f * density),
        )
    }
}

/** Holds the RuntimeShaders for one node and turns the current params into a RenderEffect. */
@RequiresApi(33)
internal class GlassEffects {
    private val single = RuntimeShader(Shaders.GLASS)
    private var field: RuntimeShader? = null
    private val shapeBuf = FloatArray(32)
    private val radBuf = FloatArray(8)

    fun build(
        scope: DrawScope, style: GlassStyle, tint: ResolvedTint, lift: Float,
        shapes: List<GlassBlob>?, k: Float, radius: Float,
    ): RenderEffect = build(scope.density, scope.size.width, scope.size.height, style, tint, lift, shapes, k, radius)

    /** Scope-free form, used by the View system ([LiquidGlassLayout]) as well as Compose. */
    fun build(
        density: Float, width: Float, height: Float, style: GlassStyle, tint: ResolvedTint, lift: Float,
        shapes: List<GlassBlob>?, k: Float, radius: Float,
    ): RenderEffect {
        fun Dp.toPx() = value * density
        val sh = if (shapes == null) single else (field ?: RuntimeShader(Shaders.BLOBS).also { field = it })
        val r = rimFor(density, width, height, style, shapes)
        sh.setFloatUniform("uMaxD", r.maxPx)
        sh.setFloatUniform("uCheb", r.coeffs)
        val mag = style.magnification + lift * 0.05f
        sh.setFloatUniform("uMag", mag, mag)
        sh.setFloatUniform("uAberration", style.aberration)
        sh.setFloatUniform("uTint", tint.light.red, tint.light.green, tint.light.blue, tint.light.alpha * (1f - lift * 0.35f))
        sh.setFloatUniform("uTintDark", tint.dark.red, tint.dark.green, tint.dark.blue, tint.dark.alpha * (1f - lift * 0.35f))
        sh.setFloatUniform("uAdaptive", if (tint.adaptive) 1f else 0f)
        sh.setFloatUniform("uSaturation", style.saturation)
        sh.setFloatUniform("uBrightness", style.brightness)
        val ang = Math.toRadians(style.lightAngle.toDouble())
        sh.setFloatUniform("uLight", sin(ang).toFloat(), -cos(ang).toFloat())
        sh.setFloatUniform("uSpecular", style.specular * (1f + lift * 0.5f))
        sh.setFloatUniform("uRimDark", 0.12f)
        sh.setFloatUniform("uBezel", r.reach)
        if (shapes == null) {
            sh.setFloatUniform("uSize", width, height)
            sh.setFloatUniform("uRadius", radius)
        } else {
            shapeBuf.fill(0f); radBuf.fill(0f)
            val n = min(8, shapes.size)
            for (i in 0 until n) {
                val b = shapes[i]
                shapeBuf[i * 4] = b.center.x; shapeBuf[i * 4 + 1] = b.center.y
                shapeBuf[i * 4 + 2] = b.size.width; shapeBuf[i * 4 + 3] = b.size.height
                radBuf[i] = b.radius
            }
            sh.setFloatUniform("uShapes", shapeBuf)
            sh.setFloatUniform("uRadii", radBuf)
            sh.setFloatUniform("uCount", n.toFloat())
            sh.setFloatUniform("uK", k)
        }
        val glass = RenderEffect.createRuntimeShaderEffect(sh, "content")
        // Default material blur is Apple's measured 0.6 of the nominal radius (same as @glasse/core BLUR_STD)
        val blur = style.blur.toPx() * (if (style.optics == GlassOptics.Bevel) GlassMaterial.BLUR_STD else 1f) * 1.7f
        return if (blur >= 0.5f) {
            RenderEffect.createChainEffect(glass, RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
        } else glass
    }
}

/** Rim parameters for one draw: reach (px), peak inward offset (px) and the curve fit. */
internal class Rim(val reach: Float, val maxPx: Float, val coeffs: FloatArray)

internal fun rimFor(density: Float, width: Float, height: Float, style: GlassStyle, shapes: List<GlassBlob>?): Rim {
    fun Dp.toPx() = value * density
    val bezel = style.bezel.toPx()
    val s = style.strength
    if (style.optics == GlassOptics.Bevel && style.profile == BezelProfile.Convex) {
        // the rim follows the shape: one blob field uses its smallest member (tab bar / toolbar pills)
        val w: Float; val h: Float
        if (shapes.isNullOrEmpty()) { w = width; h = height }
        else { val m = shapes.minBy { it.size.minDimension }; w = m.size.width; h = m.size.height }
        val b = Optics.bevel(bezel, w, h, style.rim)
        // strength scales the rim's reach (not its depth) below 1, like bevelLook on the web
        return Rim(b.reach * min(1f, max(1e-3f, s)), b.fit.maxPx * s, b.fit.coeffs)
    }
    val fit = Optics.fit(bezel, style.thickness.toPx(), style.ior, style.profile)
    return Rim(bezel, fit.maxPx * s, fit.coeffs)
}

/** App-wide material constants shared with @glasse/core (glass.ts). */
object GlassMaterial {
    /** Default material blur: Apple's measured σ is 0.6 of the nominal radius. */
    const val BLUR_STD = 0.6f
    /** Dark glass fill (#1a1a1d) and its alpha: buttons, bars and menus share one material. */
    const val DARK_TINT = 0xFF1A1A1D.toInt()
    const val DARK_TINT_ALPHA = 0.38f
    const val LIGHT_TINT_ALPHA = 0.30f
}
