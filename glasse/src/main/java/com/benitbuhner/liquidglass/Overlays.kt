package com.benitbuhner.liquidglass

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/* ─────────────────────────── Alert ─────────────────────────── */

/** One alert button: [Prominent] = accent fill, [Destructive] = red label, [Cancel] = plain. */
enum class AlertActionStyle { Default, Prominent, Destructive, Cancel }

data class GlassAlertAction(val label: String, val style: AlertActionStyle = AlertActionStyle.Default, val onClick: () -> Unit = {})

/** Keynote-measured source-anchored timing (React Native `ALERT_FROM_TAP`), seconds. */
private object AlertTap {
    val SHAPE = SpringOptions(562f, 47.4f, 1f, 5e-5f)
    val SHAPE_CLOSE = SpringOptions(778f, 55.8f, 1f, 5e-5f)
    const val GLASS_IN0 = 0f; const val GLASS_IN1 = 0.067f
    const val CONTENT_IN0 = 0.067f; const val CONTENT_IN1 = 0.43f
    const val BLUR_IN0 = 0f; const val BLUR_IN1 = 0.267f; const val SIGMA = 8f
    const val GLASS_OUT0 = 0.06f; const val GLASS_OUT1 = 0.17f
    const val CONTENT_OUT0 = 0f; const val CONTENT_OUT1 = 0.1f
}

/**
 * iOS 26 alert on thick glass (r 34, bezel 22, blur 18). [style] (default: [GlassLook.alertStyle]):
 * Center pops in from 0.86 over a dim; Tap grows out of [source] (the control you tapped) and returns there;
 * Popup is Mail's card of capsule actions anchored at [source], no dimming. Same springs as the web / RN kits.
 * Needs a [GlassHost]. Back / tap outside cancel.
 */
@Composable
fun GlassAlert(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    actions: List<GlassAlertAction> = listOf(GlassAlertAction("OK", AlertActionStyle.Prominent)),
    style: AlertStyle? = null,
    source: Rect? = null,
) {
    val host = LocalGlassHost.current ?: error("GlassAlert needs a GlassHost above it")
    val theme = LocalGlassTheme.current
    val look = rememberGlassLook()
    var mounted by remember { mutableStateOf(visible) }
    if (visible) mounted = true
    if (!mounted) return
    val mode = remember(visible) { style ?: look.alertStyle }
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler(visible) { dismiss() }
    GlassOverlay {
        val d = LocalDensity.current
        val W = host.size.width; val H = host.size.height
        val sc = remember { SpringSim(0.86f, SpringOptions(420f, 24f, 1f, 5e-5f)) }
        val pr = remember { SpringSim(0f, SpringOptions(600f, 49f, 1f, 2e-4f)) }
        val dim = remember { SpringSim(0f, SpringOptions(260f, 32f, 1f, 2e-4f)) }
        var tm by remember { mutableFloatStateOf(0f) }
        var frame by remember { mutableFloatStateOf(0f) }
        val src = mode != AlertStyle.Center
        LaunchedEffect(visible) {
            tm = 0f
            if (src) {
                sc.opts = if (visible) AlertTap.SHAPE else AlertTap.SHAPE_CLOSE
                dim.opts = if (visible) SpringOptions(260f, 32f, 1f, 2e-4f) else SpringOptions(420f, 41f, 1f, 2e-4f)
                if (visible) { sc.set(0f); dim.set(0f) }
                sc.target = if (visible) 1f else 0f; pr.set(if (visible) 1f else 0f); dim.target = if (visible) 1f else 0f
            } else if (visible) {
                sc.set(0.86f); pr.set(0f); dim.set(0f)
                sc.opts = SpringOptions(420f, 24f, 1f, 5e-5f); dim.opts = SpringOptions(260f, 32f, 1f, 2e-4f)
                sc.target = 1f; pr.target = 1f; dim.target = 1f
            } else {
                sc.opts = SpringOptions(520f, 46f, 1f, 5e-5f); dim.opts = SpringOptions(420f, 41f, 1f, 2e-4f)
                sc.target = 0.94f; pr.target = 0f; dim.target = 0f
            }
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (GlassMotion.frame((now - last) / 1e9) * GlassMotion.timeScale).toFloat(); last = now
                tm += dt; sc.step(dt); pr.step(dt); dim.step(dt); frame++
                var busy = !(sc.settled && pr.settled && dim.settled)
                if (src) busy = busy || tm < (if (visible) AlertTap.CONTENT_IN1 else AlertTap.GLASS_OUT1)
                if (!busy) break
            }
            if (!visible) mounted = false
        }
        frame // recompose per frame while it moves
        val closing = !visible
        val red = if (theme.isDark) Color(0xFFFF453A) else Color(0xFFFF3B30)
        val tint = if (theme.isDark) 0.72f else 0.74f
        val glass = GlassStyle(cornerRadius = 34.dp, bezel = 22.dp, thickness = 30.dp, blur = 18.dp, tintAlpha = tint)
        // presence / content timing per style
        val boxAlpha: Float; val innerAlpha: Float; val innerBlur: Float
        if (!src) {
            val p = pr.value.coerceIn(0f, 1f)
            boxAlpha = p
            innerAlpha = smooth01(0f, 1f, ((p - (if (closing) 0.45f else 0.4f)) / 0.55f).coerceIn(0f, 1f)); innerBlur = 0f
        } else {
            boxAlpha = if (closing) 1 - smooth01(AlertTap.GLASS_OUT0, AlertTap.GLASS_OUT1, tm) else smooth01(AlertTap.GLASS_IN0, AlertTap.GLASS_IN1, tm)
            innerAlpha = if (closing) 1 - smooth01(AlertTap.CONTENT_OUT0, AlertTap.CONTENT_OUT1, tm) else smooth01(AlertTap.CONTENT_IN0, AlertTap.CONTENT_IN1, tm)
            innerBlur = if (closing) 0f else AlertTap.SIGMA * (1 - smooth01(AlertTap.BLUR_IN0, AlertTap.BLUR_IN1, tm))
        }
        val o = (source ?: Rect(Offset(W / 2 - 22 * d.density, H / 2 - 22 * d.density), androidx.compose.ui.geometry.Size(44 * d.density, 44 * d.density))).translate(-host.origin.x, -host.origin.y)
        var boxBounds by remember { mutableStateOf(Rect.Zero) }
        // read the springs here: sc / dim are plain objects, so layer blocks capturing only them would be remembered
        // across recompositions and keep their first values
        val dimV = dim.value; val scV = sc.value
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (mode == AlertStyle.Popup) 0f else dimV.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.26f)),
        )
        // closing, it takes no input: the screen under works while the alert fades (no catch-all scrim)
        if (visible) Box(Modifier.fillMaxSize().pointerInput(visible) { detectTapGestures { dismiss() } })
        val boxMod = Modifier
            .onGloballyPositioned { boxBounds = it.boundsInRoot().translate(-host.origin.x, -host.origin.y) }
            .graphicsLayer {
                if (!src) { scaleX = scV; scaleY = scV } else if (boxBounds.width > 0) {
                    val s0 = min(o.width / boxBounds.width, o.height / boxBounds.height).coerceIn(0.12f, 0.5f)
                    val s = s0 + (1 - s0) * scV
                    scaleX = s; scaleY = s
                    translationX = (o.center.x - boxBounds.center.x) * (1 - s)
                    translationY = (o.center.y - boxBounds.center.y) * (1 - s)
                }
                alpha = boxAlpha.coerceIn(0f, 1f)
            }
            .then(if (visible) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier)
        val innerMod = Modifier
            .graphicsLayer { alpha = innerAlpha.coerceIn(0f, 1f) }
            .then(if (innerBlur > 0.05f && Build.VERSION.SDK_INT >= 31) Modifier.blur((innerBlur * 0.6f).dp) else Modifier)
        @Composable fun button(a: GlassAlertAction, m: Modifier) {
            val fill = when (a.style) {
                AlertActionStyle.Prominent -> theme.accent
                else -> if (theme.isDark) Color(0x33FFFFFF) else Color(0x14000000)
            }
            val fg = when (a.style) {
                AlertActionStyle.Prominent -> Color.White
                AlertActionStyle.Destructive -> red
                else -> theme.label
            }
            Box(
                m.height(48.dp).background(fill, RoundedCornerShape(24.dp))
                    .then(if (visible) Modifier.pointerInput(a) { detectTapGestures { a.onClick(); dismiss() } } else Modifier),
                contentAlignment = Alignment.Center,
            ) { Text(a.label, color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (mode == AlertStyle.Popup) {
            val pw = min(270f, W / d.density - 32f)
            val leftSide = o.center.x < W / 2; val upper = o.center.y < H / 2
            val left = (if (leftSide) o.left / d.density else o.right / d.density - pw).coerceIn(16f, W / d.density - 16f - pw)
            val r = look.concentricRadius(34f, 16f, W / d.density, H / d.density, Float.MAX_VALUE, 22f)
            Box(
                Modifier
                    .offset { IntOffset((left * d.density).roundToInt(), if (upper) o.top.roundToInt() else 0) }
                    .then(if (upper) Modifier else Modifier.align(Alignment.BottomStart).offset { IntOffset(0, -(H - o.bottom).roundToInt()) })
                    .width(pw.dp)
                    .then(boxMod)
                    .liquidGlass(host.screen, glass.copy(cornerRadius = r.dp), theme)
                    .padding(10.dp),
            ) {
                Column(innerMod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (title.isNotEmpty()) Text(title, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), color = theme.label.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    actions.filter { it.style != AlertActionStyle.Cancel }.forEach { button(it, Modifier.fillMaxWidth()) }
                }
            }
        } else {
            val w = min(300f, W / d.density - 48f)
            val r = if (look.concentric) (look.hardwareRadius(W / d.density, H / d.density) - 6f).coerceIn(20f, 46f) else 34f
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(w.dp).then(boxMod)
                        .liquidGlass(host.screen, glass.copy(cornerRadius = r.dp), theme)
                        .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 16.dp),
                ) {
                    Column(innerMod) {
                        Text(title, Modifier.padding(horizontal = 6.dp), color = theme.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
                        if (message != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(message, Modifier.padding(horizontal = 6.dp), color = theme.label.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 20.sp)
                        }
                        Spacer(Modifier.height(20.dp))
                        if (actions.size > 2) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { actions.forEach { button(it, Modifier.fillMaxWidth()) } }
                        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions.forEach { button(it, Modifier.weight(1f)) } }
                    }
                }
            }
        }
    }
}

/* ─────────────────────────── Sheet ─────────────────────────── */

/**
 * A glass sheet with detents (fractions of the screen height, ascending). It floats inset 8 dp at the low
 * detent and grows out to the screen edges and turns opaque toward the top one; drag the header to move
 * between detents, fling down to dismiss (React Native `GlassSheet`: y spring k 300 d 30). Needs a [GlassHost].
 */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    detents: List<Float> = listOf(0.5f, 0.94f),
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalGlassHost.current ?: error("GlassSheet needs a GlassHost above it")
    val theme = LocalGlassTheme.current
    val look = rememberGlassLook()
    var mounted by remember { mutableStateOf(visible) }
    if (visible) mounted = true
    if (!mounted) return
    val dismiss by rememberUpdatedState(onDismiss)
    BackHandler(visible) { dismiss() }
    GlassOverlay {
        val d = LocalDensity.current.density
        val H = host.size.height / d; val Wd = host.size.width / d
        val hs = (detents.max() * H).roundToInt().toFloat()
        fun yFor(f: Float) = hs - (f * H).roundToInt()
        val y = remember { GlassSpring(hs + 60f, SpringOptions(300f, 30f, 1f, 0.05f)) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(visible) {
            if (visible) { y.snapTo(hs + 60f); y.animateTo(yFor(detents.first())) }
            else { y.animateTo(hs + 60f); mounted = false }
        }
        val top = yFor(detents.last()); val lo = yFor(detents.first())
        val p = if (detents.size > 1) ((lo - y.value) / maxOf(1f, lo - top)).coerceIn(0f, 1f) else 0f
        val inset = (8f * (1 - p) * 2).roundToInt() / 2f
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = (1 - y.value / maxOf(1f, hs)).coerceIn(0f, 1f) * (0.1f + 0.2f * p) }
                .background(Color.Black)
                // closing, neither the scrim nor the card takes input: the screen under works at once
                .then(if (visible) Modifier.pointerInput(visible) { detectTapGestures { dismiss() } } else Modifier),
        )
        val r = look.concentricRadius(34f, 8f, Wd, H)
        Box(
            Modifier
                .offset { IntOffset((inset * d).roundToInt(), ((H - 8f - hs + y.value + (8 - inset)) * d).roundToInt()) }
                .size((Wd - 2 * inset).dp, hs.dp)
                .liquidGlass(host.screen, GlassStyle(cornerRadius = r.dp, bezel = 22.dp, thickness = 30.dp, blur = 9.dp, tintAlpha = 0.56f + 0.44f * p * p), theme)
                .then(if (visible) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier),
        ) {
            Column(Modifier.fillMaxSize()) {
                // head: drag to move between detents (the close button still presses)
                Box(
                    Modifier.fillMaxWidth().height(52.dp).pointerInput(detents, hs) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val y0 = y.value; val vt = VelocityTracker()
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.first()
                                vt.addPosition(ch.uptimeMillis, ch.position)
                                if (!ch.pressed) break
                                val raw = y0 + (ch.position.y - down.position.y) / d
                                scope.launch { y.snapTo(if (raw < top) top + rubberBand(raw - top, 50f) else raw) }
                                ch.consume()
                            }
                            val vy = vt.calculateVelocity().y / d
                            val proj = y.value + vy * 0.2f
                            val all = detents.map { yFor(it) } + (hs + 60f)
                            val best = all.indices.minBy { abs(all[it] - proj) }
                            if (best == all.size - 1) dismiss() else scope.launch { y.animateTo(all[best], vy) }
                        }
                    },
                ) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(36.dp, 5.dp).background(Color(0x73787880), RoundedCornerShape(3.dp)))
                    if (title != null) Text(title, Modifier.align(Alignment.Center).padding(top = 4.dp), color = theme.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    GlassIconButton(GlassIcons.Close, "Close", { dismiss() }, host.screen, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, top = 4.dp), size = 40.dp)
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), content = content)
            }
        }
    }
}

/* ─────────────────────────── Lens ─────────────────────────── */

/**
 * A draggable magnifying glass (the artifact loupe: bezel 28, thickness 34, magnification 0.22, aberration
 * 0.12, press 1.08; springs 380 / 30, rubber band 40, fling × 0.1), kept inside its parent.
 */
@Composable
fun GlassLens(backdrop: Backdrop, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 150.dp, initial: Offset = Offset(40f, 260f)) {
    val theme = LocalGlassTheme.current
    val o = SpringOptions(380f, 30f, 1f, 0.05f)
    val lx = remember { GlassSpring(initial.x, o) }; val ly = remember { GlassSpring(initial.y, o) }
    val press = rememberGlassSpring(0f, GlassSpringPresets.press)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val dd = LocalDensity.current.density
        val maxX = maxWidth.value - size.value; val maxY = maxHeight.value - size.value
        Box(
            Modifier
                .offset { IntOffset((lx.value * dd).roundToInt(), (ly.value * dd).roundToInt()) }
                .size(size)
                .graphicsLayer { val s = 1f + 0.08f * press.value; scaleX = s; scaleY = s }
                .liquidGlass(backdrop, GlassStyle(bezel = 28.dp, thickness = 34.dp, magnification = 0.22f, aberration = 0.12f, tintAlpha = 0.04f, blur = 0.dp, adaptive = false), theme) { press.value }
                .pointerInput(maxX, maxY) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        scope.launch { press.animateTo(1f) }
                        val dx = down.position.x / dd; val dy = down.position.y / dd
                        val vt = VelocityTracker()
                        while (true) {
                            val ev = awaitPointerEvent(); val ch = ev.changes.first()
                            if (!ch.pressed) break
                            // positions are local to the moving lens: convert to the parent's space
                            val px = lx.value + ch.position.x / dd - dx; val py = ly.value + ch.position.y / dd - dy
                            vt.addPosition(ch.uptimeMillis, Offset(px, py))
                            val cx = px.coerceIn(0f, maxX); val cy = py.coerceIn(0f, maxY)
                            scope.launch { lx.animateTo(cx + rubberBand(px - cx, 40f)) }
                            scope.launch { ly.animateTo(cy + rubberBand(py - cy, 40f)) }
                            ch.consume()
                        }
                        val v = vt.calculateVelocity()
                        scope.launch { press.animateTo(0f, options = GlassSpringPresets.bouncy) }
                        scope.launch { lx.animateTo((lx.target + v.x * 0.1f).coerceIn(0f, maxX)) }
                        scope.launch { ly.animateTo((ly.target + v.y * 0.1f).coerceIn(0f, maxY)) }
                    }
                },
        )
    }
}
