package com.benitbuhner.liquidglass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A plain glass surface (the Android twin of `<GlassSurface>`). */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassStyle(),
    lift: () -> Float = { 0f },
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val theme = LocalGlassTheme.current
    CompositionLocalProvider(LocalContentColor provides theme.label) {
        Box(modifier.liquidGlass(backdrop, style, theme, lift), contentAlignment = contentAlignment, content = content)
    }
}

/** Press-to-lift interaction shared by buttons: returns a 0..1 spring you can read in draw. */
@Composable
internal fun rememberPress(): Animatable<Float, *> = remember { Animatable(0f) }

/** Glass push button. `prominent` fills it with the accent colour like `.glassProminent`. */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    tint: Color? = null,
    style: GlassStyle = GlassStyle(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val theme = LocalGlassTheme.current
    val press = rememberPress()
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    val st = if (prominent || tint != null) style.copy(tint = tint ?: theme.accent, tintAlpha = style.tintAlpha ?: 0.86f, adaptive = false) else style
    CompositionLocalProvider(LocalContentColor provides if (prominent) Color.White else theme.label) {
        Row(
            modifier
                .graphicsLayer {
                    val s = 1f + 0.08f * press.value
                    scaleX = s; scaleY = s
                }
                .liquidGlass(backdrop, st, theme) { press.value }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            scope.launch { press.animateTo(1f, GlassSprings.press()) }
                            tryAwaitRelease()
                            scope.launch { press.animateTo(0f, GlassSprings.bouncy()) }
                        },
                        onTap = { click() },
                    )
                }
                .heightIn(min = 44.dp)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/** Circular glass button holding one icon (toolbar / nav bar buttons). */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    prominent: Boolean = false,
    tint: Color? = null,
) {
    GlassButton(onClick, backdrop, modifier.size(size), prominent = prominent, tint = tint, contentPadding = PaddingValues(0.dp)) {
        Icon(icon, contentDescription, Modifier.size(size * 0.5f))
    }
}

private val thumbStyle = GlassStyle(
    blur = 0.dp, tintAlpha = 0.04f,
    saturation = 1.2f, brightness = 1.06f, adaptive = false, shadow = 0.9f,
)

/**
 * iOS 26 switch: the white thumb turns into a clear lens while touched, magnifying the track
 * under it, and can be dragged or tapped.
 */
@Composable
fun GlassSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val local = rememberBackdrop()
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val cur by rememberUpdatedState(checked)
    val cb by rememberUpdatedState(onCheckedChange)
    LaunchedEffect(checked) { pos.animateTo(if (checked) 1f else 0f, GlassSprings.bouncy()) }
    val trackW = 63.dp; val trackH = 28.dp; val thumbW = 39.dp; val thumbH = 24.dp
    val travel = trackW - thumbW - 4.dp
    val on = Color(0xFF34C759)
    val off = if (theme.isDark) Color(0xFF39393D) else Color(0xFFE3E3E6)
    Box(
        modifier
            .size(trackW, trackH)
            .pointerInput(Unit) {
                val travelPx = travel.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    scope.launch { lift.animateTo(1f, GlassSprings.press()) }
                    val gate = awaitSideways(down)
                    // a vertical drag (the page scrolls): no toggle
                    if (gate == false) { scope.launch { lift.animateTo(0f, GlassSprings.bouncy()) }; return@awaitEachGesture }
                    val dragged = gate == true
                    if (dragged) while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        if (!ch.pressed) break
                        val dx = ch.position.x - ch.previousPosition.x
                        val nv = (pos.value + dx / travelPx).coerceIn(0f, 1f)
                        scope.launch { pos.snapTo(nv) }
                        ch.consume()
                    }
                    val target = if (dragged) pos.value > 0.5f else !cur
                    scope.launch { lift.animateTo(0f, GlassSprings.bouncy()) }
                    if (target != cur) cb(target) else scope.launch { pos.animateTo(if (cur) 1f else 0f, GlassSprings.bouncy()) }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().backdropSource(local)) {
            drawRoundRect(lerp(off, on, pos.value.coerceIn(0f, 1f)), cornerRadius = CornerRadius(size.height / 2))
        }
        Box(
            Modifier
                .offset { IntOffset((2.dp.toPx() + travel.toPx() * pos.value).roundToInt(), 2.dp.roundToPx()) }
                .size(thumbW, thumbH)
                .graphicsLayer {
                    val s = 1f + 0.45f * lift.value
                    scaleX = s * (1f + 0.12f * lift.value); scaleY = s
                }
                .liquidGlass(local, thumbStyle, theme) { lift.value }
                .drawBehind {
                    drawRoundRect(Color.White.copy(alpha = (1f - lift.value).coerceIn(0f, 1f)), cornerRadius = CornerRadius(size.height / 2))
                },
        )
    }
}

/**
 * iOS 26 slider: accent fill, a white knob that becomes a magnifying lens while dragged (×1.55). [min]..[max]
 * range, optional [step] snapping with [ticks] dots, optional [minIcon] / [maxIcon], and the knob rubber-bands
 * past the ends. Same spring (700/44) and layout as the web `<GlassSlider>` and the View-system `GlassSlider`.
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    fill: Color? = null,
    min: Float = 0f,
    max: Float = 1f,
    step: Float = 0f,
    ticks: Boolean = false,
    minIcon: ImageVector? = null,
    maxIcon: ImageVector? = null,
) {
    val theme = LocalGlassTheme.current
    val local = rememberBackdrop()
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val cb by rememberUpdatedState(onValueChange)
    val done by rememberUpdatedState(onValueChangeFinished)
    val v by rememberUpdatedState(value)
    val fillC = fill ?: theme.accent
    val trackC = if (theme.isDark) Color(0x5C787880) else Color(0x33787880)
    val thumbW = 38.dp; val thumbH = 24.dp
    fun frac(x: Float) = if (max == min) 0f else ((x - min) / (max - min)).coerceIn(0f, 1f)
    // the knob position in px along the track, sprung like the web slider
    val x = remember { GlassSpring(-1f, SpringOptions(700f, 44f)) }
    var dragging by remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (minIcon != null) { Icon(minIcon, null, Modifier.size(18.dp), tint = theme.label.copy(alpha = 0.55f)); Spacer12() }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(44.dp)
                .pointerInput(min, max, step) {
                    val wPx = size.width.toFloat()
                    fun apply(px: Float) {
                        val raw = px / wPx
                        val f = raw.coerceIn(0f, 1f)
                        var nv = min + f * (max - min)
                        if (step > 0f) nv = ((nv / step).roundToInt() * step).coerceIn(min, max)
                        val over = if (raw < 0) rubberBand(raw * wPx, 14.dp.toPx()) else if (raw > 1) rubberBand((raw - 1) * wPx, 14.dp.toPx()) else 0f
                        scope.launch { x.animateTo(f * wPx + over) }
                        if (nv != v) cb(nv)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        scope.launch { lift.animateTo(1f, GlassSprings.press()) }
                        // the value moves once the drag is sideways or on a tap; a vertical drag scrolls the page
                        val gate = awaitSideways(down)
                        if (gate == false) { scope.launch { lift.animateTo(0f, GlassSprings.bouncy()) }; return@awaitEachGesture }
                        dragging = true
                        apply(down.position.x)
                        if (gate == true) while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.first()
                            if (!ch.pressed) break
                            apply(ch.position.x)
                            ch.consume()
                        }
                        dragging = false
                        scope.launch { lift.animateTo(0f, GlassSprings.bouncy()) }
                        scope.launch { x.animateTo(frac(v) * wPx) }
                        done()
                    }
                },
        ) {
            val wPx = constraints.maxWidth.toFloat()
            LaunchedEffect(value, wPx) { if (!dragging) { if (x.value < 0f) x.snapTo(frac(value) * wPx) else x.animateTo(frac(value) * wPx) } }
            val px = if (x.value < 0f) frac(value) * wPx else x.value
            val nTicks = if (ticks && step > 0f) ((max - min) / step).roundToInt() + 1 else 0
            Canvas(Modifier.fillMaxSize().backdropSource(local)) {
                val th = 6.dp.toPx()
                val y = (size.height - th) / 2
                drawRoundRect(trackC, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, th), CornerRadius(th / 2))
                val fx = px.coerceIn(0f, size.width)
                if (fx > 0.5f) drawRoundRect(fillC, Offset(0f, y), androidx.compose.ui.geometry.Size(fx, th), CornerRadius(th / 2))
                for (i in 0 until nTicks) drawCircle(Color(0x8C787880), 2.dp.toPx(), Offset(size.width * i / (nTicks - 1).coerceAtLeast(1), size.height / 2))
            }
            Box(
                Modifier
                    .offset { IntOffset((px - thumbW.toPx() / 2).roundToInt(), ((44.dp - thumbH) / 2).roundToPx()) }
                    .size(thumbW, thumbH)
                    .graphicsLayer {
                        val s = 1f + 0.55f * lift.value
                        scaleX = s * (1f + 0.1f * lift.value); scaleY = s
                    }
                    .liquidGlass(local, thumbStyle, theme) { lift.value }
                    .drawBehind {
                        drawRoundRect(Color.White.copy(alpha = (1f - lift.value).coerceIn(0f, 1f)), cornerRadius = CornerRadius(size.height / 2))
                    },
            )
        }
        if (maxIcon != null) { Spacer12(); Icon(maxIcon, null, Modifier.size(18.dp), tint = theme.label.copy(alpha = 0.55f)) }
    }
}

@Composable private fun Spacer12() = androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))

/** Segmented control whose selection pill slides with a spring and lifts into glass while held. */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val local = rememberBackdrop()
    val lift = remember { Animatable(0f) }
    val x = remember { Animatable(selected.toFloat()) }
    val scope = rememberCoroutineScope()
    val cb by rememberUpdatedState(onSelect)
    LaunchedEffect(selected) { x.animateTo(selected.toFloat(), GlassSprings.bouncy()) }
    val bg = if (theme.isDark) Color(0x3D767680) else Color(0x1F767680)
    val pill = if (theme.isDark) Color(0xFF636366) else Color.White
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(options.size) {
                val n = options.size
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val segW = size.width / n.toFloat()
                    scope.launch { lift.animateTo(1f, GlassSprings.press()) }
                    var last = down.position.x
                    val gate = awaitSideways(down)
                    if (gate == false) { scope.launch { lift.animateTo(0f, GlassSprings.bouncy()); x.animateTo(selected.toFloat(), GlassSprings.bouncy()) }; return@awaitEachGesture }
                    if (gate == true) while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.first()
                        if (!ch.pressed) break
                        last = ch.position.x
                        val f = (last / segW - 0.5f).coerceIn(0f, n - 1f)
                        scope.launch { x.animateTo(f, GlassSprings.snappy()) }
                        ch.consume()
                    }
                    val idx = (last / segW).toInt().coerceIn(0, n - 1)
                    scope.launch { lift.animateTo(0f, GlassSprings.bouncy()) }
                    scope.launch { x.animateTo(idx.toFloat(), GlassSprings.bouncy()) }
                    cb(idx)
                }
            },
    ) {
        val segW = maxWidth / options.size
        val labels: @Composable () -> Unit = {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                options.forEachIndexed { i, o ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(o, color = theme.label, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().backdropSource(local).drawBehind { drawRoundRect(bg, cornerRadius = CornerRadius(size.height / 2)) }) { labels() }
        Box(
            Modifier
                .offset { IntOffset((segW.toPx() * x.value + 2.dp.toPx()).roundToInt(), 2.dp.roundToPx()) }
                .size(segW - 4.dp, 30.dp)
                .graphicsLayer {
                    val s = 1f + 0.18f * lift.value
                    scaleX = s * (1f + 0.06f * lift.value); scaleY = s
                }
                .liquidGlass(local, thumbStyle.copy(shadow = 0.6f), theme) { lift.value }
                .drawBehind { drawRoundRect(pill.copy(alpha = (1f - lift.value).coerceIn(0f, 1f)), cornerRadius = CornerRadius(size.height / 2)) },
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - lift.value).coerceIn(0f, 1f) }) { labels() }
    }
}

/** Pill search field on glass. */
@Composable
fun GlassSearchField(
    backdrop: Backdrop,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    trailing: @Composable (() -> Unit)? = null,
) {
    val theme = LocalGlassTheme.current
    GlassSurface(backdrop, modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = theme.secondaryLabel)
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                if (value.isEmpty()) Text(placeholder, color = theme.secondaryLabel, fontSize = 16.sp)
                BasicTextField(
                    value, onValueChange, singleLine = true,
                    textStyle = TextStyle(color = theme.label, fontSize = 16.sp),
                    cursorBrush = SolidColor(theme.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke()
        }
    }
}

/** Merging glass blobs (GlassEffectContainer). Shapes are in this composable's local px. */
@Composable
fun GlassBlobField(
    backdrop: Backdrop,
    blobs: () -> List<GlassBlob>,
    modifier: Modifier = Modifier,
    mergeRadius: Dp = 28.dp,
    style: GlassStyle = GlassStyle(),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val theme = LocalGlassTheme.current
    val k = with(androidx.compose.ui.platform.LocalDensity.current) { mergeRadius.toPx() }
    Box(modifier.glassBlobs(backdrop, blobs, k, style, theme), content = content)
}

/** Waits out the touch slop of a gesture on a horizontal control (switch, slider, segmented): true = the drag is
 *  sideways (the control owns it from here), false = it is vertical and a scrolling parent takes it (or it
 *  ended as one), null = a tap (released inside the slop). Nothing is consumed before a sideways decision, so a
 *  scroll that starts on the control keeps scrolling. */
internal suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitSideways(down: androidx.compose.ui.input.pointer.PointerInputChange): Boolean? {
    var acc = Offset.Zero
    var far = false
    while (true) {
        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return false
        if (ch.isConsumed) return false
        if (!ch.pressed) return if (far) false else null
        acc += ch.position - ch.previousPosition
        if (acc.getDistance() > viewConfiguration.touchSlop) {
            far = true
            if (abs(acc.x) > abs(acc.y)) { ch.consume(); return true }
            return false
        }
    }
}
