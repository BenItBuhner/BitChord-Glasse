package com.benitbuhner.liquidglass

import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

/* ─────────────────────────── Nav bar ─────────────────────────── */

/**
 * iOS 26 navigation bar: transparent, with glass buttons floating on each side and an inline title that
 * appears once the large title has scrolled away ([titleVisible]); a changed title blurs over (BlurReplace).
 * Draw it over a [GlassScrollEdge] so content fades under it.
 */
@Composable
fun GlassNavBar(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleVisible: Boolean = true,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val theme = LocalGlassTheme.current
    val shown = rememberGlassSpring(if (titleVisible) 1f else 0f, SpringOptions(900f, 60f))
    LaunchedEffect(titleVisible) { shown.animateTo(if (titleVisible) 1f else 0f) }
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 10.dp)
            .height(44.dp)
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) GlassIconButton(GlassIcons.Back, "Back", onBack, backdrop)
            leading()
        }
        if (title != null) {
            BlurReplace(title, Modifier.align(Alignment.Center).padding(horizontal = 112.dp).graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }) { t ->
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t as String, color = theme.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, color = theme.secondaryLabel, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** The 34 sp bold page title that scrolls with the content (the nav bar shows the inline one once it is gone). */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    Text(text, modifier.padding(bottom = 14.dp), color = theme.label, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
}

/** True once the list has scrolled more than [offset] (like the web `useScrolledPast`): drive [GlassNavBar.titleVisible]. */
@Composable
fun LazyListState.rememberScrolledPast(offset: Dp = 44.dp): Boolean {
    val px = with(LocalDensity.current) { offset.toPx() }
    val past by remember(this, px) { derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > px } }
    return past
}

@Composable
fun ScrollState.rememberScrolledPast(offset: Dp = 44.dp): Boolean {
    val px = with(LocalDensity.current) { offset.toPx() }
    val past by remember(this, px) { derivedStateOf { value > px } }
    return past
}

/* ─────────────────────────── Scroll edge effect ─────────────────────────── */

enum class ScrollEdge { Top, Bottom }

/**
 * The scroll edge under bars (iOS 26's soft progressive fade, or iOS 27's hard "Blocks" band, from
 * [GlassLook.edgeEffect]). Place it over the scrolling content and under the bar; [pageColor] is the page
 * background. Blocks is hidden until [scrolled] (content under the bar), then springs in, like iOS 27.
 * [height] defaults to the status bar + 64 dp for the top edge (the band ends 10 dp under the nav buttons).
 */
@Composable
fun GlassScrollEdge(
    backdrop: Backdrop,
    pageColor: Color,
    modifier: Modifier = Modifier,
    edge: ScrollEdge = ScrollEdge.Top,
    height: Dp? = null,
    scrolled: Boolean = true,
    effect: EdgeEffect? = null,
) {
    val look = rememberGlassLook()
    val fx = effect ?: look.edgeEffect
    val theme = LocalGlassTheme.current
    val top = edge == ScrollEdge.Top
    val sb = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val h = height ?: if (top) sb + 64.dp else 96.dp
    if (fx == EdgeEffect.Blocks && !top) return // iOS 27: no bottom edge by default
    val shown = rememberGlassSpring(if (fx == EdgeEffect.Progressive || scrolled) 1f else 0f, SpringOptions(900f, 60f, 1f, 2e-3f))
    LaunchedEffect(scrolled, fx) { shown.animateTo(if (fx == EdgeEffect.Progressive || scrolled) 1f else 0f) }
    val box = modifier.fillMaxWidth().height(h)
    if (fx == EdgeEffect.Progressive) {
        // a blur that strengthens toward the edge, under a page-colour veil: iOS 26's soft edge
        val (a, b) = if (top) 0f to 1f else 1f to 0f
        Box(
            box.backdropBlur(
                backdrop, 6.dp, pageColor.copy(alpha = 0.55f),
                Brush.verticalGradient(a to Color.Black, 0.55f to Color.Black.copy(alpha = 0.6f), b to Color.Transparent),
            ),
        )
    } else {
        // iOS 27 Blocks: a uniform blurred band veiled by the bar material, with a 1 px separator
        val veil = if (theme.isDark) Color(
            (pageColor.red + 14f / 255f).coerceAtMost(1f), (pageColor.green + 14f / 255f).coerceAtMost(1f), (pageColor.blue + 14f / 255f).coerceAtMost(1f), 0.7f,
        ) else Color(pageColor.red * 0.985f, pageColor.green * 0.985f, pageColor.blue * 0.985f, 0.82f)
        val line = if (theme.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.11f)
        Box(
            box
                .backdropBlur(backdrop, 10.dp, veil) { shown.value.coerceIn(0f, 1f) }
                .drawBehind { },
        ) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(LocalDensity.current) { 1f.toDp() }).graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }.background(line))
        }
    }
}

/* ─────────────────────────── BlurReplace ─────────────────────────── */

/**
 * When [key] changes, the old content blurs, fades and shrinks out while the new one blurs in (the kit's
 * BlurReplace, the default transition for button labels and nav titles).
 */
@Composable
fun BlurReplace(key: Any, modifier: Modifier = Modifier, content: @Composable (Any) -> Unit) {
    val items = remember { mutableStateListOf(key) }
    if (items.last() != key) { items.remove(key); items.add(key) }
    Box(modifier, contentAlignment = Alignment.Center) {
        items.forEach { k ->
            key(k) {
                val p = rememberGlassSpring(if (items.size == 1) 1f else 0f, SpringOptions(380f, 34f, 1f, 2e-3f))
                val current = k == key
                LaunchedEffect(current) {
                    p.animateTo(if (current) 1f else 0f)
                    if (!current) items.remove(k)
                }
                val v = p.value.coerceIn(0f, 1f)
                Box(
                    Modifier
                        .graphicsLayer { alpha = v; val s = 0.86f + 0.14f * v; scaleX = s; scaleY = s }
                        .then(if (v < 0.99f && Build.VERSION.SDK_INT >= 31) Modifier.blur(((1 - v) * 6f).dp) else Modifier),
                ) { content(k) }
            }
        }
    }
}

/* ─────────────────────────── Push navigation with swipe back ─────────────────────────── */

/**
 * iOS push navigation: a pushed page slides in from the right over the page beneath, which parallaxes left
 * and dims (the web `usePush` spring, k 200 d 28). Swipe right anywhere on the page to go back: the page
 * follows the finger and completes or cancels by position and flick, with no seam between gesture and
 * animation. Horizontal scrollers inside win while they can still scroll left: the swipe starts on a drag
 * nothing inside consumed, or on a rightward drag over a horizontal list that is already at its start.
 * The system back gesture (predictive back) drives the same spring.
 *
 * [entries] is your back stack; when it grows, the new top is pushed; call [onBack] to pop.
 */
@Composable
fun <T : Any> GlassStack(
    entries: List<T>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val pushSpring = SpringOptions(200f, 28f, 1f, 2e-4f)
    // the stack as drawn: popped pages stay until they have slid out
    val shown = remember { mutableStateListOf<T>().also { it.addAll(entries) } }
    // pages already in the stack on first composition (deep links, restored state) start in place, not off screen
    val springs = remember { HashMap<T, GlassSpring>().also { m -> entries.forEach { m[it] = GlassSpring(1f, pushSpring) } } }
    val scope = rememberCoroutineScope()
    val back by rememberUpdatedState(onBack)
    fun springOf(e: T, initial: Float) = springs.getOrPut(e) { GlassSpring(initial, pushSpring) }
    // pushes and pops
    LaunchedEffect(entries.toList()) {
        entries.forEach { e -> if (e !in shown) { shown.add(e); val s = springOf(e, 0f); scope.launch { s.animateTo(1f) } } }
        shown.toList().forEach { e ->
            if (e !in entries) {
                val s = springOf(e, 1f)
                scope.launch { s.animateTo(0f); shown.remove(e); springs.remove(e) }
            }
        }
    }
    entries.firstOrNull()?.let { springOf(it, 1f) }
    var dragging by remember { mutableStateOf(false) }
    val canBack = entries.size > 1
    // the system back gesture: predictive back follows the finger, commit pops
    PredictiveBackHandler(enabled = canBack) { progress ->
        val top = entries.last(); val s = springOf(top, 1f)
        try {
            progress.collect { ev -> s.snapTo(1f - ev.progress * 0.9f) }
            back()
        } catch (e: CancellationException) {
            scope.launch { s.animateTo(1f) }
            throw e
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        // a horizontal list at its start hands back the rightward drag it can't use (its leftover scroll
        // delta): that drag is the swipe back, as long as the list scrolled none of it first
        val nest = remember { NestSwipe() }
        val canBackNow by rememberUpdatedState(canBack)
        val handoff = remember(w) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!nest.active) return Offset.Zero
                    nest.dx += available.x
                    val v = (nest.v0 - nest.dx / w).coerceIn(0f, 1f)
                    nest.spring?.let { sp -> scope.launch { sp.snapTo(v) } }
                    return Offset(available.x, 0f)
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || nest.active) return Offset.Zero
                    if (consumed.x != 0f) nest.scrolled = true
                    if (nest.scrolled || !canBackNow || dragging || available.x <= 0f || available.x <= abs(available.y) * 1.2f) return Offset.Zero
                    val sp = springOf(entries.last(), 1f)
                    nest.active = true; nest.spring = sp; nest.dx = available.x; nest.v0 = sp.value.coerceIn(0f, 1f)
                    dragging = true
                    val v = (nest.v0 - nest.dx / w).coerceIn(0f, 1f)
                    scope.launch { sp.snapTo(v) }
                    return Offset(available.x, 0f)
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!nest.active) return Velocity.Zero
                    settleNest(nest, available.x / w)
                    return Velocity(available.x, 0f)
                }
                private fun settleNest(n: NestSwipe, vx: Float) {
                    val sp = n.spring
                    n.active = false; n.spring = null; dragging = false
                    if (sp == null) return
                    val goBack = vx > 0.35f || (sp.value < 0.5f && vx > -0.15f)
                    if (goBack) { scope.launch { sp.animateTo(0f, -vx) }; back() }
                    else scope.launch { sp.animateTo(1f, -vx) }
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(handoff)
                .pointerInput(canBack, w) {
                    if (!canBack) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Final)
                        nest.scrolled = false
                        val top = entries.last(); val s = springOf(top, 1f)
                        var claimed = false
                        var v0 = 1f
                        val vt = VelocityTracker()
                        var start = down.position
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Final)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            vt.addPosition(ch.uptimeMillis, ch.position)
                            if (!ch.pressed) break
                            val dx = ch.position.x - start.x; val dy = ch.position.y - start.y
                            if (!claimed) {
                                // a child (a horizontal list, a slider) used this drag: not ours
                                if (ch.isConsumed) break
                                if (abs(dx) + abs(dy) < viewConfiguration.touchSlop) continue
                                // a swipe that lands while the page is still sliding in catches it where it is
                                if (dx > 0 && dx > abs(dy) * 1.2f) { claimed = true; dragging = true; start = ch.position; v0 = s.value.coerceIn(0f, 1f) } else break
                            }
                            if (claimed) {
                                ch.consume()
                                val v = (v0 - (ch.position.x - start.x) / w).coerceIn(0f, 1f)
                                scope.launch { s.snapTo(v) }
                            }
                        }
                        if (claimed) {
                            dragging = false
                            val vx = vt.calculateVelocity().x / w // screens / s
                            val v = s.value
                            val goBack = vx > 0.35f || (v < 0.5f && vx > -0.15f)
                            if (goBack) { scope.launch { s.animateTo(0f, -vx) }; back() }
                            else scope.launch { s.animateTo(1f, -vx) }
                        }
                    }
                },
        ) {
            shown.forEachIndexed { i, e ->
                key(e) {
                    val s = springOf(e, if (i == 0) 1f else 0f)
                    val above = shown.getOrNull(i + 1)?.let { springs[it] }
                    val covered = above != null && above.value >= 0.9998f && !dragging
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val v = if (i == 0) 1f else s.value
                                val parallax = above?.value?.coerceIn(0f, 1.2f) ?: 0f
                                translationX = (1f - v) * w - 0.3f * w * parallax
                                alpha = if (covered) 0f else 1f
                                shadowElevation = if (i > 0 && v < 0.9998f) 24f * density * v.coerceAtMost(1f) else 0f
                            },
                    ) {
                        content(e)
                        if (above != null && above.value > 0.0002f && !covered) {
                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.1f * above.value.coerceIn(0f, 1f) }.background(Color.Black))
                        }
                    }
                }
            }
        }
    }
}

/** a back swipe started by a horizontal list's leftover drag (GlassStack's nested scroll handoff) */
private class NestSwipe { var active = false; var scrolled = false; var dx = 0f; var v0 = 1f; var spring: GlassSpring? = null }

/** A spacer the height of the nav bar area (status bar + 54 dp), for the top of scrolling pages. */
@Composable
fun NavBarSpacer() {
    Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(64.dp).width(1.dp))
}
