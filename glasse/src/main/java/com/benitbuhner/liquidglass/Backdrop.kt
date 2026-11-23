package com.benitbuhner.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.node.invalidateDraw

/**
 * Whatever is drawn inside [backdropSource] is recorded into a GraphicsLayer each frame, and every
 * [liquidGlass] surface that points at the same Backdrop samples that recording at its own position.
 *
 * Glass chrome (bars, floating buttons) is a sibling drawn after the source. Glass *inside* the source (a
 * button in a scrolling list) works too: it is lifted out of the recording and drawn over the backdrop where
 * it sits, so it refracts the content around it, never itself.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? = null
    /** true while the source records: glass drawn now lives inside the source */
    internal var recording = false
    internal val contentGlass = LinkedHashSet<GlassNode>()

    internal var invalidateSource: (() -> Unit)? = null

    /** Draw the glass that lives inside the source over it, each where it sits (scrolled, scaled), clipped to the source. */
    internal fun drawContentGlass(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
        val bc = coordinates ?: return
        if (contentGlass.isEmpty() || !bc.isAttached) return
        scope.clipRect { for (n in contentGlass.toList()) n.drawAsContent(this, bc) }
    }
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/**
 * A Backdrop over a [layer] the app already owns, for apps that record their page into a GraphicsLayer for
 * something else too (another blur or snapshot library): one recording feeds both, instead of the page being
 * recorded twice a frame. Record it with [backdropSource] as usual; the other reader draws [layer] directly.
 */
@Composable
fun rememberBackdrop(layer: GraphicsLayer): Backdrop = remember(layer) { Backdrop(layer) }

/** Where the recorded content sits, for readers outside Glassé that draw [Backdrop.layer] themselves. */
val Backdrop.sourceCoordinates: LayoutCoordinates? get() = coordinates?.takeIf { it.isAttached }

fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this then BackdropSourceElement(backdrop)

private data class BackdropSourceElement(val backdrop: Backdrop) : androidx.compose.ui.node.ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(backdrop)
    override fun update(node: BackdropSourceNode) { node.attachTo(backdrop); node.invalidateDraw() }
}

private class BackdropSourceNode(var backdrop: Backdrop) : Modifier.Node(),
    androidx.compose.ui.node.DrawModifierNode, androidx.compose.ui.node.GlobalPositionAwareModifierNode {
    private val invalidate: () -> Unit = { if (isAttached) invalidateDraw() }
    fun attachTo(b: Backdrop) { if (b !== backdrop) { if (backdrop.invalidateSource === invalidate) backdrop.invalidateSource = null; backdrop = b }; b.invalidateSource = invalidate }
    override fun onAttach() { backdrop.invalidateSource = invalidate }
    override fun onDetach() { if (backdrop.invalidateSource === invalidate) backdrop.invalidateSource = null }
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) { backdrop.coordinates = coordinates }
    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        val b = backdrop
        b.recording = true
        try { b.layer.record { this@draw.drawContent() } } finally { b.recording = false }
        drawLayer(b.layer)
        b.drawContentGlass(this)
    }
}

/**
 * Draws the part of [backdrop] under this node, blurred by [blur] (API 31+; plain tint below), with [tint] over
 * it. [mask] (e.g. a vertical gradient) fades the result, which is how the progressive scroll edge is drawn.
 * Read [alpha] during draw, so fading it in does not recompose.
 */
fun Modifier.backdropBlur(
    backdrop: Backdrop,
    blur: androidx.compose.ui.unit.Dp,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent,
    mask: androidx.compose.ui.graphics.Brush? = null,
    alpha: () -> Float = { 1f },
): Modifier = composed {
    val layer = rememberGraphicsLayer()
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    this
        .onGloballyPositioned { coords = it }
        .drawWithContent {
            val a = alpha()
            val bc = backdrop.coordinates; val mc = coords
            if (a > 0.002f && bc != null && mc != null && bc.isAttached && mc.isAttached) {
                val p = bc.localPositionOf(mc, androidx.compose.ui.geometry.Offset.Zero)
                layer.record(androidx.compose.ui.unit.IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))) {
                    translate(-p.x, -p.y) { drawLayer(backdrop.layer) }
                }
                val px = blur.toPx()
                layer.renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && px > 0.5f) {
                    android.graphics.RenderEffect.createBlurEffect(px, px, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
                } else null
                layer.alpha = 1f
                val content: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                    if (android.os.Build.VERSION.SDK_INT >= 31) drawLayer(layer)
                    if (tint.alpha > 0f) drawRect(tint)
                    if (mask != null) drawRect(mask, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                }
                drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size), androidx.compose.ui.graphics.Paint().apply { this.alpha = a })
                content()
                drawContext.canvas.restore()
            }
            drawContent()
        }
}
