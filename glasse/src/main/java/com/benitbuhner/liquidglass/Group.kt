package com.benitbuhner.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.max

/* ─────────────────────────── GlassContainer: shapes that merge ─────────────────────────── */

/**
 * A row of glass shapes drawn as ONE surface: shapes closer than [spacing] melt together with a liquid neck,
 * like SwiftUI's `GlassEffectContainer` / the web `<GlassContainer>`. Each child is one shape (a pill / circle
 * of its own size); animate a child's size or the [gap] and the shapes split and merge.
 *
 * ```kotlin
 * GlassContainer(backdrop) {
 *     GlassContainerItem(onClick = {}) { Icon(...) }
 *     GlassContainerItem(onClick = {}, width = 88.dp) { Text("Edit") }
 * }
 * ```
 */
@Composable
fun GlassContainer(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    gap: Dp = 10.dp,
    style: GlassStyle = GlassStyle(),
    content: @Composable () -> Unit,
) {
    val theme = LocalGlassTheme.current
    var shapes by remember { mutableStateOf(emptyList<GlassBlob>()) }
    CompositionLocalProvider(LocalContentColor provides theme.label) {
        Layout(
            content = content,
            modifier = modifier.glassBlobs(backdrop, { shapes }, spacing.value * 2.2f, style, theme),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val ps = measurables.map { it.measure(loose) }
            val g = gap.roundToPx()
            val w = ps.sumOf { it.width } + g * max(0, ps.size - 1)
            val h = ps.maxOfOrNull { it.height } ?: 0
            val next = ArrayList<GlassBlob>(ps.size)
            var x = 0
            ps.forEach { p ->
                val y = (h - p.height) / 2
                val sc = (p.parentData as? Float) ?: 1f
                next += GlassBlob(Offset(x + p.width / 2f, y + p.height / 2f), Size(p.width * sc, p.height * sc), p.height * sc / 2f)
                x += p.width + g
            }
            if (next != shapes) shapes = next
            layout(w, h) {
                var xx = 0
                ps.forEach { p -> p.placeRelative(xx, (h - p.height) / 2); xx += p.width + g }
            }
        }
    }
}

/** One shape in a [GlassContainer]: a 44 dp tall pill (or circle when [width] is null and the content is an icon). */
@Composable
fun GlassContainerItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    val press = rememberGlassSpring(0f, GlassSpringPresets.press)
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .then(if (width != null) Modifier.size(width, height) else Modifier.heightIn(min = height).size(height))
            .graphicsLayer { val s = 1f + 0.08f * press.value; scaleX = s; scaleY = s }
            .pointerInput(onClick) {
                detectTapGestures(onPress = {
                    scope.launch { press.animateTo(1f, options = GlassSpringPresets.press) }
                    tryAwaitRelease()
                    scope.launch { press.animateTo(0f, options = GlassSpringPresets.bouncy) }
                }, onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** One button in a [GlassButtonGroup]. */
data class GlassGroupItem(val label: String, val icon: ImageVector? = null, val text: Boolean = false, val color: Color? = null, val onClick: () -> Unit = {})

/** Several bar buttons sharing one capsule (iOS toolbar groups, the web `GlassButtonGroup`). */
@Composable
fun GlassButtonGroup(items: List<GlassGroupItem>, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val press = rememberGlassSpring(0f, GlassSpringPresets.press)
    Row(
        modifier.height(44.dp).liquidGlass(backdrop, GlassStyle(), theme) { press.value }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { it ->
            val c = it.color ?: theme.label
            Box(
                Modifier.heightIn(min = 44.dp).padding(horizontal = if (it.text || it.icon == null) 10.dp else 0.dp)
                    .then(if (it.text || it.icon == null) Modifier else Modifier.size(40.dp, 44.dp))
                    .pointerInput(it) { detectTapGestures { _ -> it.onClick() } },
                contentAlignment = Alignment.Center,
            ) {
                if (it.text || it.icon == null) Text(it.label, color = c, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                else Icon(it.icon, it.label, Modifier.size(22.dp), tint = c)
            }
        }
    }
}

/* ─────────────────────────── Grouped lists (Settings style) ─────────────────────────── */

/** An inset grouped section: an optional caps header, a rounded card and an optional footer. */
@Composable
fun ListSection(modifier: Modifier = Modifier, header: String? = null, footer: String? = null, radius: Dp = 22.dp, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalGlassTheme.current
    val card = if (theme.isDark) Color(0xFF1C1C1E) else Color.White
    Column(modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        if (header != null) Text(header, Modifier.padding(start = 20.dp, bottom = 7.dp), color = theme.secondaryLabel, fontSize = 13.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(radius)).background(card), content = content)
        if (footer != null) Text(footer, Modifier.padding(start = 20.dp, end = 20.dp, top = 7.dp), color = theme.secondaryLabel, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

/**
 * A Settings-style row: a coloured rounded icon, the label, an optional value, a chevron, or any [trailing]
 * control (a GlassSwitch). Rows after the first draw a hairline inset past the icon.
 */
@Composable
fun ListRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconColor: Color = Color(0xFF8E8E93),
    value: String? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val theme = LocalGlassTheme.current
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(if (pressed) (if (theme.isDark) Color(0x1FFFFFFF) else Color(0x14000000)) else Color.Transparent)
            .then(if (onClick != null) Modifier.pointerInput(onClick) {
                detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() })
            } else Modifier),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(iconColor), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(19.dp), tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
            }
            Text(label, Modifier.weight(1f), color = if (destructive) Color(0xFFFF3B30) else theme.label, fontSize = 17.sp, maxLines = 1)
            if (value != null) Text(value, color = theme.secondaryLabel, fontSize = 17.sp, maxLines = 1)
            if (trailing != null) trailing()
            if (chevron) { Spacer(Modifier.width(6.dp)); Icon(GlassIcons.Chevron, null, Modifier.size(16.dp), tint = theme.secondaryLabel.copy(alpha = 0.5f)) }
        }
        if (divider) Box(Modifier.align(Alignment.TopStart).padding(start = if (icon != null) 60.dp else 16.dp).fillMaxWidth().height(0.5.dp).background(theme.secondaryLabel.copy(alpha = 0.18f)))
    }
}
