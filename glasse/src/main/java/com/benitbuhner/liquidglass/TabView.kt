package com.benitbuhner.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

data class GlassTab(val label: String, val icon: ImageVector)

private const val BAR_H = 62f
private const val MIN_H = 48f
private const val ACC_H = 50f
private const val GAP = 10f

/** Apple tab-bar minimize (m4r: width fraction fit, ω 16.8, ζ 0.72), shared with the RN kit's `TabView.k`. */
internal val TAB_MINIMIZE = SpringOptions(283f, 24.1f)
/** Apple B1 selection leading edge: R 398 ms, ζ 0.62, 8 % overshoot (RN `sel.x`). */
internal val TAB_SELECT = SpringOptions(249f, 19.7f)
/* The accessory (mini player) does not ride the bar's spring (Apple Music, WWDC25 284, tracked per frame):
   minimize: it jumps sideways into its inline slot in ~2 frames, then drops on R 385 ms ζ 1.12;
   expand:   it lifts in ~3 frames (R 124 ms ζ .96), then slides back out on R 392 ms ζ 1.05. */
internal val ACC_FAST = SpringOptions(2568f, 97.3f)
internal val ACC_DROP = SpringOptions(266f, 36.5f)
internal val ACC_SLIDE = SpringOptions(257f, 33.7f)

/**
 * Floating glass tab bar. Touching it lifts a clear lens over the selected item; dragging slides the lens
 * between tabs and it squashes and stretches with its velocity, then snaps with a wobble.
 */
@Composable
fun GlassTabBar(
    backdrop: Backdrop,
    tabs: List<GlassTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().height(BAR_H.dp)) {
        TabBarCore(backdrop, tabs, selected, onSelect, maxWidth.value, { 0f }, {})
    }
}

/**
 * The iOS 26 tab view chrome: the tab bar, an optional search tab as its own circle on the trailing side, an
 * optional floating accessory above it (a mini player), and the scroll-to-minimize collapse
 * (`tabBarMinimizeBehavior`): the bar shrinks to a circle holding the selected tab and the accessory slides
 * down beside it. Same layout and spring (k 320, d 29) as the web `<GlassTabView>`.
 */
@Composable
fun GlassTabView(
    backdrop: Backdrop,
    tabs: List<GlassTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    searchTab: GlassTab? = null,
    searchSelected: Boolean = false,
    onSearch: () -> Unit = {},
    accessory: (@Composable () -> Unit)? = null,
    onAccessoryClick: () -> Unit = {},
    minimized: Boolean = false,
    onExpand: () -> Unit = {},
    margin: Dp = 18.dp,
) {
    val theme = LocalGlassTheme.current
    val k = rememberGlassSpring(if (minimized) 1f else 0f, TAB_MINIMIZE)
    val kh = rememberGlassSpring(if (minimized) 1f else 0f, ACC_FAST) // accessory: sideways
    val kv = rememberGlassSpring(if (minimized) 1f else 0f, ACC_DROP) // accessory: down / up
    LaunchedEffect(minimized) {
        val t = if (minimized) 1f else 0f
        launch { kh.animateTo(t, options = if (minimized) ACC_FAST else ACC_SLIDE) }
        launch { kv.animateTo(t, options = if (minimized) ACC_DROP else ACC_FAST) }
        k.animateTo(t)
    }
    val hasAcc = accessory != null; val hasSearch = searchTab != null
    BoxWithConstraints(modifier.fillMaxWidth().height((BAR_H + ACC_H + 8).dp).padding(horizontal = margin)) {
        val W = maxWidth.value
        val expBarW = W - if (hasSearch) BAR_H + GAP else 0f
        val t = k.value; val tc = t.coerceIn(0f, 1f)
        val sz = lerp(BAR_H, MIN_H, tc)
        val d = LocalDensity.current.density
        if (accessory != null) {
            val th = kh.value; val tv = kv.value
            val ax = lerp(0f, MIN_H + GAP, th)
            val aw = lerp(W, W - (MIN_H + GAP) - (if (hasSearch) MIN_H + GAP else 0f), th)
            val ah = lerp(ACC_H, MIN_H, tv.coerceIn(0f, 1f))
            val ab = lerp(BAR_H + 8, 0f, tv)
            Box(
                Modifier
                    .offset { IntOffset((ax * d).roundToInt(), ((BAR_H + ACC_H + 8 - ab - ah) * d).roundToInt()) }
                    .size(aw.coerceAtLeast(ah).dp, ah.dp)
                    .liquidGlass(backdrop, GlassStyle(), theme)
                    .pointerInput(Unit) { detectTapGestures { onAccessoryClick() } },
                contentAlignment = Alignment.CenterStart,
            ) { CompositionLocalProvider(LocalContentColor provides theme.label) { accessory() } }
        }
        Box(
            Modifier
                .offset { IntOffset(0, ((BAR_H + ACC_H + 8 - sz) * d).roundToInt()) }
                .size(lerp(expBarW, MIN_H, t).coerceAtLeast(sz).dp, sz.dp),
        ) {
            TabBarCore(backdrop, tabs, if (searchSelected) -1 else selected, onSelect, expBarW, { k.value }, onExpand)
        }
        if (searchTab != null) {
            Box(
                Modifier
                    .offset { IntOffset(((W - sz) * d).roundToInt(), ((BAR_H + ACC_H + 8 - sz) * d).roundToInt()) }
                    .size(sz.dp)
                    .liquidGlass(backdrop, GlassStyle(), theme)
                    .pointerInput(Unit) { detectTapGestures { onSearch() } },
                contentAlignment = Alignment.Center,
            ) { Icon(searchTab.icon, searchTab.label, Modifier.size(24.dp), tint = if (searchSelected) theme.accent else theme.label) }
        }
    }
}

/** The bar itself: glass pill, sliding selection pill, items and the touch lens. [collapse] 0 = full, 1 = circle. */
@Composable
private fun TabBarCore(
    backdrop: Backdrop,
    tabs: List<GlassTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    expandedWidth: Float,
    collapse: () -> Float,
    onCollapsedTap: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    val local = rememberBackdrop()
    val sel = selected.coerceAtLeast(0)
    val x = remember { GlassSpring(sel.toFloat(), TAB_SELECT) }
    val lift = remember { GlassSpring(0f, GlassSpringPresets.press) }
    var dragging by remember { mutableStateOf(false) }
    var dragVel by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val cb by rememberUpdatedState(onSelect)
    val expand by rememberUpdatedState(onCollapsedTap)
    LaunchedEffect(sel) { if (!dragging) x.animateTo(sel.toFloat(), options = TAB_SELECT) }
    val n = tabs.size
    val d = LocalDensity.current.density
    val itemW = (expandedWidth - 8f) / n
    val itemPx = itemW * d
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(n, itemPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (collapse() > 0.5f) { expand(); return@awaitEachGesture }
                    dragging = true
                    val vt = VelocityTracker()
                    scope.launch { lift.animateTo(1f, options = GlassSpringPresets.press) }
                    val off = 4f * d
                    val start = ((down.position.x - off) / itemPx - 0.5f).coerceIn(0f, n - 1f)
                    scope.launch { x.animateTo(start, options = GlassSpringPresets.snappy) }
                    var last = down.position; var moved = 0f
                    while (true) {
                        val ev = awaitPointerEvent(); val ch = ev.changes.first()
                        if (!ch.pressed) break
                        vt.addPosition(ch.uptimeMillis, ch.position)
                        moved += abs(ch.position.x - last.x); last = ch.position
                        if (moved > viewConfiguration.touchSlop) {
                            val f = ((last.x - off) / itemPx - 0.5f).coerceIn(-0.25f, n - 0.75f)
                            dragVel = vt.calculateVelocity().x
                            scope.launch { x.snapTo(f) }
                            ch.consume()
                        }
                    }
                    val idx = ((last.x - off) / itemPx).toInt().coerceIn(0, n - 1)
                    val v = vt.calculateVelocity().x / itemPx
                    dragVel = 0f; dragging = false
                    cb(idx)
                    scope.launch { x.animateTo(idx.toFloat(), if (v.isFinite()) v else 0f, TAB_SELECT) }
                    scope.launch { delay(140); lift.animateTo(0f, options = GlassSpringPresets.gentle) }
                }
            },
    ) {
        Box(Modifier.fillMaxSize().backdropSource(local)) {
            Box(Modifier.fillMaxSize().liquidGlass(backdrop, GlassStyle(rim = RimClass.Bar), theme))
            // the selection pill (hidden once collapsing)
            if (selected >= 0) Box(
                Modifier
                    .offset { IntOffset(((x.value * itemW + 4f) * d).roundToInt(), (4 * d).roundToInt()) }
                    .size((itemW).dp, 54.dp)
                    .graphicsLayer { alpha = if (collapse() > 0.35f) 0f else 1f; val s = 1f - 0.2f * collapse(); scaleY = s }
                    .background(if (theme.isDark) Color(0x21FFFFFF) else Color(0x11000000), RoundedCornerShape(50)),
            )
            tabs.forEachIndexed { i, t ->
                val c = if (i == selected) theme.accent else theme.label
                Column(
                    Modifier
                        .offset {
                            val shift = lerp(0f, MIN_H / 2 - (4 + itemW * (sel + 0.5f)), collapse())
                            IntOffset(((4 + i * itemW + shift) * d).roundToInt(), 0)
                        }
                        .size(itemW.dp, BAR_H.dp)
                        .graphicsLayer { alpha = if (i == sel) 1f else (1 - collapse().coerceIn(0f, 1f) * 2.2f).coerceIn(0f, 1f) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(t.icon, t.label, Modifier.size(24.dp), tint = c)
                    Text(t.label, Modifier.graphicsLayer { alpha = (1 - collapse().coerceIn(0f, 1f) * 2.5f).coerceIn(0f, 1f) }, color = c, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        // the lens: a clear magnifying drop that only exists while touched
        Box(
            Modifier
                .offset { IntOffset(((x.value * itemW + 4f - 6f) * d).roundToInt(), (-7 * d).roundToInt()) }
                .size((itemW + 12).dp, 76.dp)
                .graphicsLayer {
                    val vel = if (dragging) dragVel / itemPx else x.velocity
                    val st = (abs(vel) / 9f).coerceAtMost(0.32f)
                    alpha = lift.value.coerceIn(0f, 1f)
                    val s = 0.85f + 0.15f * lift.value
                    scaleX = s * (1f + st); scaleY = s * (1f - st * 0.5f)
                }
                .liquidGlass(
                    local,
                    GlassStyle(blur = 0.dp, tintAlpha = 0.02f, shadow = 0.7f, adaptive = false, saturation = 1.15f),
                    theme,
                ) { lift.value },
        )
    }
}
