package com.benitbuhner.liquidglass

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * GlassMenuPopover: the iOS 26 button → menu morph with the app's own content in the body.
 *
 * [GlassMenuButton] / [GlassMenu] draw Glassé's own 44 dp rows. Apps that already have a menu design (their
 * own rows, quick-action strips, headers, toggles) keep it and get the glass and the motion: the body pulls
 * out of the trigger, melts back into it on close, and refracts [backdrop] while it moves, with the same
 * MenuModel (springs, per-iOS-version tables, flash, content blur-in) as the item menus.
 */

/** Hoisted state of a [GlassMenuPopover], so the trigger can hide while the menu draws it (see [triggerAlpha]). */
@Stable
class GlassMenuPopoverState {
    internal var model: MenuModel? by mutableStateOf(null)
    internal var tick by mutableIntStateOf(0)
    internal var flipped = false

    /** How much of the real trigger shows: 0 while the menu's head is drawing it, 1 at rest. Read in a draw phase. */
    val triggerAlpha: Float get() { tick; return model?.restHandoff() ?: 1f }
    /** The head's settle offset (dp) on a close, so the trigger rides it back into place. */
    val triggerOffset: Offset get() {
        tick
        val m = model ?: return Offset.Zero
        return Offset(m.restDx, m.restDy * (if (flipped) -1 else 1))
    }
    /** Whether anything of the menu is on screen. */
    val visible: Boolean get() { tick; return model?.let { it.phase != MenuModel.Phase.Closed } ?: false }
}

/**
 * A morphing glass menu holding [content]. Place it in a full-screen overlay above what it should refract.
 *
 * @param expanded open / close; the close plays out (drains back into the trigger) after it turns false
 * @param anchor the trigger's bounds in root coordinates. The morph's head is a 44 dp circle centred on it.
 * @param backdrop what the glass refracts (the screen, or the page under the overlay)
 * @param width the open body's width
 * @param maxHeight the tallest the body may grow before the content scrolls (its own business)
 */
@Composable
fun GlassMenuPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: Rect,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    state: GlassMenuPopoverState = remember { GlassMenuPopoverState() },
    width: Dp = 250.dp,
    maxHeight: Dp = Dp.Unspecified,
    motion: MenuMotion? = null,
    style: GlassStyle? = null,
    /** what the content shows; a new key measures it again (another item's menu) */
    contentKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val theme = LocalGlassTheme.current
    val d = LocalDensity.current
    val px = d.density
    val B = 44f
    var origin by remember { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(Size.Zero) }
    var rootH by remember { mutableFloatStateOf(0f) }
    val insetTop = WindowInsets.safeDrawing.getTop(d) / d.density
    val insetBottom = WindowInsets.safeDrawing.getBottom(d) / d.density
    var contentH by remember(contentKey) { mutableFloatStateOf(0f) }

    if (!expanded && !state.visible) return

    Box(
        modifier.fillMaxSize().onGloballyPositioned {
            origin = it.positionInRoot(); area = Size(it.size.width.toFloat(), it.size.height.toFloat())
            rootH = it.findRootCoordinates().size.height.toFloat()
        },
    ) {
        if (area == Size.Zero) return@Box
        // the trigger, as a 44 dp circle centred on the anchor, in the overlay's own coordinates
        val c = anchor.center - origin
        val trig0 = Rect(c.x - B * px / 2, c.y - B * px / 2, c.x + B * px / 2, c.y + B * px / 2)
        val alignRight = trig0.center.x > area.width / 2
        val wDp = min(width.value, area.width / px - 24f)
        // a trigger hard against the edge (a row's ⋯) would push the body off screen: the head slides in so the
        // body keeps a margin, as iOS keeps its menus inside the screen
        val margin = 12f * px
        val a = trig0.translate(
            if (alignRight) min(0f, area.width - margin - trig0.right) else max(0f, margin - trig0.left), 0f,
        )
        val capH = (if (maxHeight == Dp.Unspecified) area.height / px - 48f else min(maxHeight.value, area.height / px - 48f))

        // measure the content once at the open width, before the model exists (it needs the open height)
        if (contentH <= 0f) {
            Box(
                Modifier.offset { IntOffset(-100_000, 0) }.requiredWidth(wDp.dp).heightIn(max = capH.dp)
                    .onSizeChanged { contentH = it.height / px }.graphicsLayer { alpha = 0f },
            ) { content() }
            return@Box
        }
        // open downwards when the content fits under the trigger, else towards the side with more room, and never
        // past the system bars: a taller menu is cut to the room and its content scrolls (the app's business)
        val roomBelow = area.height / px - max(0f, insetBottom - (rootH - origin.y - area.height) / px) - 8f - a.top / px
        val roomAbove = a.bottom / px - max(0f, insetTop - origin.y / px) - 8f
        val flipY = contentH > roomBelow && roomAbove > roomBelow
        val h = max(B, min(min(contentH, capH), if (flipY) roomAbove else roomBelow))
        val model = remember(alignRight, wDp, h) {
            MenuModel(h, wDp, alignRight, theme.isDark).also { state.model = it }
        }
        model.dark = theme.isDark
        state.flipped = flipY
        val ox = a.left; val oy = a.top
        fun bx(x: Float) = ox + x * px
        fun by(y: Float) = if (flipY) oy + (B - y) * px else oy + y * px

        LaunchedEffect(expanded, model) {
            if (expanded != model.isOpen) {
                if (motion != null) model.nextMotion = motion
                model.show(expanded)
            }
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val more = model.step(GlassMotion.frame((now - last) / 1e9))
                last = now
                state.tick++
                if (!more) break
            }
            state.tick++
        }
        state.tick // recompose per frame while it moves
        val f = model.frame
        val q = f.body
        val body = Rect(bx(q.x0), min(by(q.y0), by(q.y0 + q.h)), bx(q.x0 + q.w), max(by(q.y0), by(q.y0 + q.h)))
        val r = min(q.rx, q.ry) * px
        val headR = f.headR * px
        val head = Offset(bx(f.headX), by(f.headY))
        val shapes = buildList {
            if (!f.landed) add(GlassBlob(body.center, body.size, r))
            if (headR > 0.5f) add(GlassBlob(head, Size(2 * headR, 2 * headR), headR))
        }
        val dragT = Offset(f.bodyTx * px, f.bodyTy * px)
        val flash = f.flash; val listTy = f.listTy; val listOpacity = f.listOpacity; val listScale = f.listScale
        val listBlur = f.listBlur
        val tint0 = if (theme.isDark) GlassMaterial.DARK_TINT_ALPHA else GlassMaterial.LIGHT_TINT_ALPHA
        val up = model.up(theme.isDark)
        val thick = ((f.tint - (if (theme.isDark) GlassMaterial.DARK_TINT_ALPHA else 0.3f)) / up).coerceIn(0f, 1f)
        val small = min(body.width, body.height) < 70 * px
        val glass = (style ?: GlassStyle()).copy(
            bezel = if (small) 18.dp else 26.dp, thickness = if (small) 26.dp else 36.dp,
            tintAlpha = tint0 + thick * up, blur = (2f + (if (model.frosted()) 12f else 2f) * thick).dp,
        )

        // a tap outside the body dismisses; taps inside belong to the content
        val cbDismiss by rememberUpdatedState(onDismiss)
        Box(
            Modifier.fillMaxSize().pointerInput(model) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!body.contains(down.position)) cbDismiss()
                }
            },
        )
        // the glass: body ∪ head, melting together through the neck
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = dragT.x; translationY = dragT.y }
                .glassBlobs(backdrop, { shapes }, max(1f, f.neck * px), glass, theme)
                .drawWithContent {
                    drawContent()
                    if (flash > 0.001f && shapes.isNotEmpty()) {
                        val p = Path().apply { shapes.forEach { b -> addRoundRect(RoundRect(Rect(b.center - Offset(b.size.width / 2, b.size.height / 2), b.size), CornerRadius(b.radius))) } }
                        drawPath(p, Color.White.copy(alpha = flash))
                    }
                },
        )
        // the content, clipped to the body, faded / scaled / blurred in on the menu's content clock
        val menuLeft = bx(model.menuX)
        val listTop = if (flipY) by(model.Hm) else by(0f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = dragT.x; translationY = dragT.y }
                .drawWithContent {
                    val clip = Path().apply { addRoundRect(RoundRect(body, CornerRadius(r))) }
                    clipPath(clip) { this@drawWithContent.drawContent() }
                },
        ) {
            Box(
                Modifier
                    .offset { IntOffset(menuLeft.roundToInt(), (listTop + listTy * px * (if (flipY) -1 else 1)).roundToInt()) }
                    .width(model.width.dp)
                    .height(model.Hm.dp)
                    .graphicsLayer {
                        alpha = listOpacity.coerceIn(0f, 1f)
                        scaleX = listScale; scaleY = listScale
                        transformOrigin = TransformOrigin(if (model.alignRight) 1f else 0f, if (flipY) 1f else 0f)
                    }
                    .then(if (listBlur > 0.05f && Build.VERSION.SDK_INT >= 31) Modifier.blur((listBlur * 0.6f).dp) else Modifier)
                    .pointerInput(Unit) {}, // taps between rows land on the body, not the dismiss layer
            ) { content() } // cut to the room on screen: a taller content scrolls itself (it is measured to fit)
        }
    }
}
