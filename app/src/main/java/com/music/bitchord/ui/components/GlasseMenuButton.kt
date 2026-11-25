package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.benitbuhner.liquidglass.Backdrop as GlasseBackdrop
import com.benitbuhner.liquidglass.GlassIcons
import com.benitbuhner.liquidglass.GlassMenuButton
import com.benitbuhner.liquidglass.GlassMenuItem

/*
 * A button that opens a menu is Glassé's GlassMenuButton, and the menu is that button's own: one element that
 * turns into the menu and back, as in the Glassé demo. The app's menus are still written where they always were
 * (IosMenuPopover and its rows), so the two meet here: the button that was pressed offers itself, and the popover
 * that opens for it hands the button its rows instead of drawing a menu of its own.
 */
internal object GlassMenus {
    class Owner {
        var items by mutableStateOf<List<GlassMenuItem>>(emptyList())
        var open by mutableStateOf(false)
        var dismiss: () -> Unit = {}
        var gone: () -> Unit = {}
        var bounds: Rect = Rect.Zero
        var coordinates: LayoutCoordinates? = null
    }

    private var pending: Owner? = null
    private var offeredAt = 0L

    fun offer(owner: Owner) { pending = owner; offeredAt = System.nanoTime() }

    /** The button the menu at [anchor] was opened from, if it was one of Glassé's. */
    fun claim(anchor: Rect?): Owner? {
        val o = pending ?: return null
        if (System.nanoTime() - offeredAt > 3_000_000_000L) { pending = null; return null }
        if (anchor == null || (o.bounds.center - anchor.center).getDistance() > 24f) return null
        pending = null
        return o
    }
}

/** Glassé's menu button for a menu the app opens with [onOpen] (see [GlassMenus]). */
@Composable
internal fun GlasseMenuButton(
    onOpen: () -> Unit,
    backdrop: GlasseBackdrop,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    bare: Boolean = false,
    tint: Color = Color.Unspecified,
    size: Dp = 44.dp,
    iconSize: Dp = Dp.Unspecified,
    face: (@Composable () -> Unit)? = null,
) {
    val owner = remember { GlassMenus.Owner() }
    GlassMenuButton(
        items = owner.items,
        backdrop = backdrop,
        modifier = modifier.onPlaced { owner.coordinates = it },
        icon = icon ?: GlassIcons.Ellipsis,
        contentDescription = contentDescription,
        width = IosMenuWidth,
        size = size,
        face = face,
        bare = bare,
        tint = tint.takeIf { it.isSpecified },
        iconSize = iconSize,
        expanded = owner.open,
        onExpandedChange = { want ->
            if (want) {
                owner.coordinates?.takeIf { it.isAttached }?.let { owner.bounds = it.boundsInWindow() }
                MenuAnchor.record(owner.bounds, bare)
                GlassMenus.offer(owner)
                onOpen()
            } else owner.dismiss()
        },
        onClosed = { owner.gone() },
    )
}

/** [LocalMenuButton] filled with Glassé's menu button over [backdrop]. */
internal fun glasseMenuButtonSlot(backdrop: GlasseBackdrop): @Composable (MenuButtonSpec) -> Unit = { spec ->
    GlasseMenuButton(
        onOpen = spec.onOpen,
        backdrop = backdrop,
        contentDescription = spec.contentDescription,
        modifier = spec.modifier,
        icon = spec.icon,
        bare = spec.bare,
        tint = spec.tint,
        size = spec.size,
        iconSize = spec.iconSize,
    )
}
