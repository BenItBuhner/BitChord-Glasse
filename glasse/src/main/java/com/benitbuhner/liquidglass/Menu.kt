package com.benitbuhner.liquidglass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* ─────────────────────────── GlassHost: the screen backdrop + an overlay layer ───────────────────────────
 * Menus, alerts and sheets float over everything, including the glass chrome, so they need a backdrop of the
 * whole screen. GlassHost records its content into one and draws overlays above it (the Compose twin of the
 * web portal and the React Native kit's Portal). Wrap each screen (or the whole app) once:
 *
 *   GlassHost { MyScreen() }
 */

@Stable
class GlassHostState internal constructor(val screen: Backdrop) {
    internal val overlays = mutableStateListOf<Pair<Any, @Composable BoxScope.() -> Unit>>()
    internal var origin by mutableStateOf(Offset.Zero)
    internal var size by mutableStateOf(Size.Zero)
    /** every glass node under this host (menus hide the ones they cover) */
    internal val glass = LinkedHashSet<GlassNode>()
}

val LocalGlassHost = staticCompositionLocalOf<GlassHostState?> { null }

@Composable
fun GlassHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val screen = rememberBackdrop()
    val host = remember(screen) { GlassHostState(screen) }
    CompositionLocalProvider(LocalGlassHost provides host) {
        Box(
            modifier
                .fillMaxSize()
                .onGloballyPositioned { host.origin = it.positionInRoot(); host.size = Size(it.size.width.toFloat(), it.size.height.toFloat()) },
        ) {
            Box(Modifier.fillMaxSize().backdropSource(screen)) { content() }
            host.overlays.forEach { (k, c) -> key(k) { Box(Modifier.fillMaxSize()) { c() } } }
        }
    }
}

/** Draw [content] in the nearest [GlassHost]'s overlay layer while this composable is in the tree. */
@Composable
fun GlassOverlay(content: @Composable BoxScope.() -> Unit) {
    val host = LocalGlassHost.current ?: error("GlassOverlay / GlassMenuButton / GlassAlert / GlassSheet need a GlassHost above them")
    val token = remember { Any() }
    val latest by rememberUpdatedState(content)
    DisposableEffect(host, token) {
        val entry: Pair<Any, @Composable BoxScope.() -> Unit> = token to { latest() }
        host.overlays.add(entry)
        onDispose { host.overlays.remove(entry) }
    }
}

/* ─────────────────────────── GlassMenu ─────────────────────────── */

/** One menu row. [separatorBefore] draws a divider above it. */
data class GlassMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val separatorBefore: Boolean = false,
    val onSelect: () -> Unit = {},
)

private fun rowsOf(items: List<GlassMenuItem>): List<GlassMenuItem?> =
    buildList { items.forEach { if (it.separatorBefore && isNotEmpty()) add(null); add(it) } }

/**
 * The iOS 26 "…" button with its morphing menu, the Compose twin of `<GlassMenu>`. The menu pulls out of the
 * button and drains back into it with the measured motion of the iOS version in [GlassLooks] (or [motion]).
 * Needs a [GlassHost] above it.
 */
@Composable
fun GlassMenuButton(
    items: List<GlassMenuItem>,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    icon: ImageVector = GlassIcons.Ellipsis,
    contentDescription: String? = "More",
    width: Dp = 250.dp,
    motion: MenuMotion? = null,
    style: GlassStyle = GlassStyle(),
) {
    val host = LocalGlassHost.current ?: error("GlassMenuButton needs a GlassHost above it")
    val theme = LocalGlassTheme.current
    val state = remember { MenuState() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val press = rememberPress()
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .size(44.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .graphicsLayer {
                state.tick
                // hidden while the menu draws it; its rest glass takes back over as the close lands in the head,
                // riding the head's last settle into place (RN m8)
                val m = state.model
                alpha = m?.restHandoff() ?: 1f
                translationX = (m?.restDx ?: 0f) * density; translationY = (m?.restDy ?: 0f) * density * (if (state.flipped) -1 else 1)
            }
            .liquidGlass(backdrop, style, theme) { press.value }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    scope.launch { press.animateTo(1f, GlassSprings.press()) }
                    val up = waitForUpOrCancellation()
                    scope.launch { press.animateTo(0f, GlassSprings.bouncy()) }
                    if (up != null) state.open = true
                }
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, Modifier.size(22.dp), tint = theme.label) }

    if (state.open || state.model?.phase != MenuModel.Phase.Closed && state.model != null) {
        GlassOverlay {
            MenuOverlay(state, items, host.screen, bounds.translate(-host.origin.x, -host.origin.y), width, motion, icon, host.size) { state.open = false }
        }
    }
}

/** State shared by the trigger and its overlay. */
@Stable
internal class MenuState {
    var open by mutableStateOf(false)
    var model: MenuModel? = null
    /** the menu opened upward (model y runs up the screen) */
    var flipped = false
    var tick by mutableIntStateOf(0)
    /** bumped when a press-drag starts, so the frame loop runs again for the highlight springs */
    var gen by mutableIntStateOf(0)
}

/**
 * The controlled form, kept for full-screen overlays you place yourself: [anchor] is the trigger's bounds in
 * the overlay's coordinates and [expanded] opens / closes it. Prefer [GlassMenuButton].
 */
@Composable
fun GlassMenu(
    backdrop: Backdrop,
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: Rect,
    items: List<GlassMenuItem>,
    modifier: Modifier = Modifier,
    anchorIcon: ImageVector? = null,
    width: Dp = 250.dp,
    motion: MenuMotion? = null,
) {
    val state = remember { MenuState() }
    state.open = expanded
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(Size.Zero) }
    if (!expanded && (state.model == null || state.model?.phase == MenuModel.Phase.Closed)) return
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot(); size = Size(it.size.width.toFloat(), it.size.height.toFloat()) }) {
        MenuOverlay(state, items, backdrop, anchor.translate(-origin.x, -origin.y), width, motion, anchorIcon ?: GlassIcons.Ellipsis, size, onDismiss)
    }
}

@Composable
private fun BoxScope.MenuOverlay(
    state: MenuState,
    items: List<GlassMenuItem>,
    backdrop: Backdrop,
    anchor: Rect,
    width: Dp,
    motion: MenuMotion?,
    icon: ImageVector,
    area: Size,
    onDismiss: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    val d = LocalDensity.current
    val rows = remember(items) { rowsOf(items) }
    val px = d.density
    val B = 44f
    // flip to whichever side fits: right-aligned under a button on the right half, upward near the bottom
    val alignRight = anchor.center.x > area.width / 2
    val wDp = min(width.value, area.width / px - 24f)
    val model = remember(rows, alignRight, wDp) {
        MenuModel(rows.map { it == null }, wDp, alignRight, theme.isDark).also { state.model = it }
    }
    model.dark = theme.isDark
    val flipY = anchor.top / px + model.Hm + 24f > area.height / px && anchor.top > area.height / 2
    state.flipped = flipY
    val s = anchor.width / px / B // the trigger may not be exactly 44 dp
    val ox = anchor.left; val oy = anchor.top
    fun bx(x: Float) = ox + x * px * s
    fun by(y: Float) = if (flipY) oy + (B - y) * px * s else oy + y * px * s

    // glass under an open menu (iOS 26: no glass seen through glass, RN kit coverGlass): the glass the open menu will
    // cover leaves the screen the menu refracts and is drawn here under the body, clipped to where the body is not
    val host = LocalGlassHost.current
    val covered = remember(model) { mutableStateListOf<GlassNode>() } // state: the layer below redraws as it changes
    var coverGen by remember(model) { mutableIntStateOf(0) }
    fun cover() {
        if (host == null || covered.isNotEmpty()) return
        val l = bx(model.menuX); val r = l + model.width * px * s
        val t0 = if (flipY) by(model.Hm) else by(0f); val t1 = t0 + model.Hm * px * s
        val openRect = Rect(l, min(t0, t1), r, max(t0, t1))
        for (n in host.glass.toList()) {
            if (n.backdrop === host.screen || n.isContent) continue
            val c = n.position ?: continue
            if (!c.isAttached) continue
            val b = c.boundsInRoot().translate(-host.origin.x, -host.origin.y)
            if (anchor.contains(b.center) || !b.overlaps(openRect)) continue // the trigger itself is the menu's head
            covered += n; n.cover(model) { coverGen++ }
        }
    }
    fun uncover() { covered.toList().forEach { it.uncover(model) }; covered.clear() }
    DisposableEffect(model) { onDispose { uncover() } }

    val open = state.open
    LaunchedEffect(open, state.gen) {
        if (open != model.isOpen) {
            if (motion != null) model.nextMotion = motion
            model.show(open)
        }
        if (open) cover()
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val more = model.step(GlassMotion.frame((now - last) / 1e9))
            last = now
            state.tick++
            if (!more) break
        }
        if (model.phase == MenuModel.Phase.Closed) uncover()
        state.tick++
    }
    state.tick // recompose per frame while it moves (a menu is a handful of nodes)
    val f = model.frame
    val q = f.body
    val body = Rect(bx(q.x0), min(by(q.y0), by(q.y0 + q.h)), bx(q.x0 + q.w), max(by(q.y0), by(q.y0 + q.h)))
    val r = min(q.rx, q.ry) * px * s
    val headR = f.headR * px * s
    val head = Offset(bx(f.headX), by(f.headY))
    val shapes = buildList {
        if (!f.landed) add(GlassBlob(body.center, body.size, r))
        if (headR > 0.5f) add(GlassBlob(head, Size(2 * headR, 2 * headR), headR))
    }
    val dragT = Offset(f.bodyTx * px, f.bodyTy * px)
    // copy what the lambdas below read: `f` is one mutable object, so a lambda capturing only `f` is remembered
    // across recompositions (strong skipping) and its layer would keep the first frame's values
    val flash = f.flash; val listTy = f.listTy; val listOpacity = f.listOpacity; val listScale = f.listScale
    val hlOpacity = f.hlOpacity; val hlY = f.hlY; val trigAlpha = f.trigOpacity * (1f - model.restHandoff()); val trigScale = f.trigScale
    val rowAlphas = FloatArray(rows.size) { i -> model.rowAlpha(if (flipY) rows.size - 1 - i else i, rows.size) }
    val a0 = if (theme.isDark) GlassMaterial.DARK_TINT_ALPHA else GlassMaterial.LIGHT_TINT_ALPHA
    val up = model.up(theme.isDark)
    val thick = ((f.tint - (if (theme.isDark) GlassMaterial.DARK_TINT_ALPHA else 0.3f)) / up).coerceIn(0f, 1f)
    val small = min(body.width, body.height) < 70 * px
    val glass = GlassStyle(
        bezel = if (small) 18.dp else 26.dp, thickness = if (small) 26.dp else 36.dp,
        tintAlpha = a0 + thick * up, blur = (2f + (if (model.frosted()) 12f else 2f) * thick).dp, // iOS 26.0: a heavy frost
    )

    // dismiss on a tap outside; press-drag over the rows highlights and selects (iOS)
    val cbDismiss by rememberUpdatedState(onDismiss)
    Box(
        Modifier.fillMaxSize().pointerInput(model, flipY) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun local(p: Offset) = Offset((p.x - ox) / px / s, if (flipY) B - (p.y - oy) / px / s else (p.y - oy) / px / s)
                val inside = model.phase != MenuModel.Phase.Closed && body.contains(down.position)
                if (!inside) { cbDismiss(); return@awaitEachGesture }
                val l0 = local(down.position)
                model.listDown(l0.x, l0.y); state.gen++
                var lastY = l0.y
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.first()
                    val l = local(ch.position)
                    if (!ch.pressed) { lastY = l.y; break }
                    model.listMove(l.x, l.y); lastY = l.y; ch.consume()
                }
                val i = model.listUp(lastY)
                val item = rows.getOrNull(i)
                if (item != null) { item.onSelect(); cbDismiss() }
                state.gen++
            }
        },
    )
    run {
        // always composed: glass is covered from a LaunchedEffect, so it must already be drawn here on that frame
        var me by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val showBody = !f.landed
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { me = it }.drawBehind {
                val into = me ?: return@drawBehind
                coverGen // a covered glass has recorded itself: draw it
                if (covered.isEmpty()) return@drawBehind
                val hole = Path().apply {
                    if (showBody) addRoundRect(RoundRect(body.translate(dragT), CornerRadius(r)))
                    if (headR > 0.5f) addOval(Rect(head + dragT, headR))
                }
                clipPath(hole, ClipOp.Difference) { covered.forEach { it.drawAsContent(this, into) } }
            },
        )
    }
    // the glass: body ∪ head, melting together through the neck
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = dragT.x; translationY = dragT.y }
            .glassBlobs(backdrop, { shapes }, max(1f, f.neck * px * s), glass, theme)
            .drawWithContent {
                drawContent()
                if (flash > 0.001f && shapes.isNotEmpty()) {
                    val p = Path().apply { shapes.forEach { b -> addRoundRect(RoundRect(Rect(b.center - Offset(b.size.width / 2, b.size.height / 2), b.size), CornerRadius(b.radius))) } }
                    drawPath(p, Color.White.copy(alpha = flash))
                }
            },
    )
    // the rows, clipped to the body
    val menuLeft = bx(model.menuX)
    val listTop = if (flipY) by(model.Hm) else by(0f)
    Box(
        Modifier
            .offset { IntOffset(0, 0) }
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
                .size((model.width * s).dp, (model.Hm * s).dp)
                .graphicsLayer {
                    alpha = listOpacity.coerceIn(0f, 1f)
                    scaleX = listScale; scaleY = listScale
                    transformOrigin = TransformOrigin(if (model.alignRight) 1f else 0f, if (flipY) 1f else 0f)
                }
                .then(if (f.listBlur > 0.05f && Build.VERSION.SDK_INT >= 31) Modifier.blur((f.listBlur * 0.6f).dp) else Modifier),
        ) {
            // highlight: inset 7 dp, concentric with the menu corner (iOS)
            if (hlOpacity > 0.01f) {
                val hr = min(22f, model.openRadius - 7f).coerceAtLeast(8f)
                Box(
                    Modifier
                        .offset { IntOffset((7 * px * s).roundToInt(), (yIn(model, hlY, 44f, flipY) * px * s).roundToInt()) }
                        .size(((model.width - 14) * s).dp, (44 * s).dp)
                        .graphicsLayer { alpha = hlOpacity }
                        .clip(RoundedCornerShape(hr.dp))
                        .background(if (theme.isDark) Color(0x2EFFFFFF) else Color(0x1A000000)),
                )
            }
            rows.forEachIndexed { i, item ->
                val row = model.rows[i]
                val top = yIn(model, row.top, row.height, flipY)
                if (item == null) {
                    Box(
                        Modifier.offset { IntOffset((16 * px * s).roundToInt(), (top * px * s).roundToInt()) }
                            .size(((model.width - 32) * s).dp, 0.5.dp)
                            .background(theme.secondaryLabel.copy(alpha = 0.3f)),
                    )
                } else {
                    val c = if (item.destructive) Color(0xFFFF3B30) else theme.label
                    Row(
                        Modifier
                            .offset { IntOffset(0, (top * px * s).roundToInt()) }
                            .size((model.width * s).dp, (44 * s).dp)
                            .graphicsLayer { alpha = rowAlphas[i] }
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.label, color = c, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        if (item.icon != null) Icon(item.icon, null, Modifier.size(20.dp), tint = c)
                    }
                }
            }
        }
    }
    // the trigger's glyph rides the head: blurs out as the blob forms, blurs back in on close
    if (f.trigOpacity > 0.001f) {
        Box(
            Modifier
                .offset { IntOffset((head.x - 22 * px * s).roundToInt(), (head.y - 22 * px * s).roundToInt()) }
                .size((44 * s).dp)
                .graphicsLayer { alpha = trigAlpha; scaleX = trigScale; scaleY = trigScale }
                .then(if (f.trigBlur > 0.05f && Build.VERSION.SDK_INT >= 31) Modifier.blur((f.trigBlur * 0.6f).dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(22.dp), tint = theme.label) }
    }
}

/** a row's top in the drawn list (rows mirror when the menu opens upward, so the nearest row stays nearest) */
private fun yIn(model: MenuModel, top: Float, h: Float, flipY: Boolean) = if (flipY) model.Hm - top - h else top
