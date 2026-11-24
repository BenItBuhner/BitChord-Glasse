package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.benitbuhner.liquidglass.GlassMenuItem

/*
 * The app's menus are written once, as rows (IosMenuRow, IosMenuSeparator, the quick-action tiles). While glass is
 * on they are drawn by Glassé's own GlassMenu: each row, composed out of sight, hands Glassé a GlassMenuItem in
 * the order it sits in, and Glassé draws the list, the highlight, the drag to select, the stretch and the morph.
 * Nothing of BitChord's own drawing is on screen. A page of a menu that is not rows (the custom sleep timer's
 * slider, the rename form) says so with [MenuCollector.custom], and only then is the content drawn as it is.
 */

@Stable
class MenuCollector {
    private data class Entry(val y: Float, val x: Float, val item: GlassMenuItem?)
    private val entries = mutableStateMapOf<Any, Entry>()
    /** pages of the menu that are not rows are showing */
    var custom by mutableIntStateOf(0)
    /** the rows have reported where they are at least once */
    var ready by mutableStateOf(false)
        private set

    /** The rows in order, separators folded into the row after them. */
    val items: List<GlassMenuItem> by derivedStateOf {
        val out = ArrayList<GlassMenuItem>()
        var sep = false
        entries.values.sortedWith(compareBy({ it.y }, { it.x })).forEach { e ->
            val it = e.item
            if (it == null) { sep = out.isNotEmpty(); return@forEach }
            out += if (sep && !it.separatorBefore) it.copy(separatorBefore = true) else it
            sep = false
        }
        out
    }

    internal fun put(key: Any, at: Offset, item: GlassMenuItem?) { entries[key] = Entry(at.y, at.x, item); ready = true }
    internal fun remove(key: Any) { entries.remove(key) }
}

/** Set while a menu's rows are being collected for Glassé's GlassMenu. */
val LocalMenuCollector = compositionLocalOf<MenuCollector?> { null }

/**
 * One row (or, for a null [label], a separator) handed to the collecting menu. [onClick] is kept current without
 * changing the item, so the list Glassé lays out only changes when what it shows changes.
 */
@Composable
internal fun MenuCollector.Item(
    label: String?,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    checked: Boolean? = null,
) {
    val key = remember { Any() }
    val click by rememberUpdatedState(onClick)
    val stable = remember { { click?.invoke(); Unit } }
    var at by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.size(1.dp).onGloballyPositioned { at = it.positionInRoot() })
    val item = label?.let {
        GlassMenuItem(
            label = it, icon = icon, destructive = destructive, onSelect = stable,
            checked = checked, subtitle = subtitle, disabled = !enabled || onClick == null,
            // the app's own click decides whether the menu closes (it lets go of what the menu is for)
            keepOpen = true,
        )
    }
    val p = at
    SideEffect { if (p != null) put(key, p, item) }
    DisposableEffect(key) { onDispose { remove(key) } }
}

/** A page of a menu that is not rows: while it shows, the menu draws its content as it is. */
@Composable
internal fun MenuCollector.Custom() {
    DisposableEffect(this) { custom++; onDispose { custom-- } }
}

/** Composes [content] where it is measured but never seen or touched, so its rows can report themselves. */
internal fun Modifier.outOfSight(): Modifier = layout { m, _ ->
    val p = m.measure(Constraints(maxWidth = 1200, maxHeight = 40000))
    layout(0, 0) { p.place(-100_000, 0) }
}
