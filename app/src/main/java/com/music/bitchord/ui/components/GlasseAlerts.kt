package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.benitbuhner.liquidglass.GlassAlert
import com.benitbuhner.liquidglass.GlassAlertAction
import com.benitbuhner.liquidglass.GlassMotion
import com.benitbuhner.liquidglass.LocalGlassHost
import com.music.bitchord.data.settings.AppSettings

/**
 * Whether an alert can be Glassé's [GlassAlert]: Liquid Glass on, supported,
 * blur not reduced, and a GlassHost (the app root) above to draw it in.
 */
@Composable
fun glasseAlertsOn(): Boolean {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    return !reduceDynamicBlur && LocalLiquidGlassEnabled.current && isGlassSupported() &&
        LocalGlassHost.current != null
}

/**
 * BitChord's alerts are shown while composed (`if (show) Alert(...)`) and gone
 * the moment the caller's flag flips. Glassé's [GlassAlert] animates out on
 * `visible = false`, so this keeps it composed through its own close and only
 * then runs the chosen action (or [onDismiss] for a tap outside / back), which
 * is what removes it.
 */
@Composable
fun AppGlassAlert(
    title: String,
    message: String?,
    actions: List<GlassAlertAction>,
    onDismiss: () -> Unit,
) {
    var open by remember { mutableStateOf(true) }
    var chosen by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    GlassAlert(
        visible = open,
        onDismiss = { open = false },
        title = title,
        message = message,
        actions = actions.map { a -> a.copy(onClick = { if (chosen == null) chosen = a.onClick }) },
    )
    LaunchedEffect(open) {
        if (open) return@LaunchedEffect
        // the close, on Glassé's clock (fixed steps when captured)
        var t = 0.0
        var last = withFrameNanos { it }
        while (t < CLOSE_SECONDS) {
            val now = withFrameNanos { it }
            t += GlassMotion.frame((now - last) / 1e9) * GlassMotion.timeScale
            last = now
        }
        (chosen ?: dismiss).invoke()
    }
}

private const val CLOSE_SECONDS = 0.34

/** One button of an [AppAlert]. */
data class AppAlertButton(
    val label: String,
    val onClick: () -> Unit = {},
    val role: AppAlertRole = AppAlertRole.Default,
    val enabled: Boolean = true,
    /** false: the alert stays up after it (a sign-in that may fail and say so) */
    val dismisses: Boolean = true,
)

enum class AppAlertRole { Default, Cancel, Destructive }

/**
 * An alert that is Glassé's [GlassAlert] while Liquid Glass is on and the
 * app's original Material dialog otherwise, from one description: [visible]
 * drives it either way, so the glass one can animate out. [glassContent] /
 * [materialText] hold what an alert asks for beyond its message (text fields),
 * in each design's own controls.
 */
@Composable
fun AppAlert(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    buttons: List<AppAlertButton>,
    glassContent: (@Composable () -> Unit)? = null,
    materialText: (@Composable () -> Unit)? = null,
) {
    if (glasseAlertsOn()) {
        GlassAlert(
            visible = visible,
            onDismiss = onDismiss,
            title = title,
            message = message,
            actions = buttons.map { b ->
                GlassAlertAction(
                    b.label,
                    when (b.role) {
                        AppAlertRole.Cancel -> com.benitbuhner.liquidglass.AlertActionStyle.Cancel
                        AppAlertRole.Destructive -> com.benitbuhner.liquidglass.AlertActionStyle.Destructive
                        AppAlertRole.Default -> com.benitbuhner.liquidglass.AlertActionStyle.Prominent
                    },
                    b.onClick,
                    enabled = b.enabled,
                    dismisses = b.dismisses,
                )
            }.sortedBy { if (it.style == com.benitbuhner.liquidglass.AlertActionStyle.Cancel) 0 else 1 },
            content = glassContent,
        )
        return
    }
    if (!visible) return
    val confirm = buttons.lastOrNull { it.role != AppAlertRole.Cancel }
    val cancel = buttons.firstOrNull { it.role == AppAlertRole.Cancel }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(title) },
        text = materialText ?: message?.let { m -> { androidx.compose.material3.Text(m) } },
        confirmButton = {
            if (confirm != null) androidx.compose.material3.TextButton(
                onClick = { confirm.onClick(); if (confirm.dismisses) onDismiss() },
                enabled = confirm.enabled,
            ) {
                androidx.compose.material3.Text(
                    confirm.label,
                    color = if (confirm.role == AppAlertRole.Destructive) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
        },
        dismissButton = cancel?.let { c ->
            {
                androidx.compose.material3.TextButton(onClick = { c.onClick(); onDismiss() }, enabled = c.enabled) {
                    androidx.compose.material3.Text(c.label)
                }
            }
        },
    )
}

/**
 * A sheet that is Glassé's [com.benitbuhner.liquidglass.GlassSheet] while
 * Liquid Glass is on and the app's original Material bottom sheet otherwise.
 * [item] non-null shows it; the last one is kept so the glass sheet can slide
 * away with its content after the caller has let go of it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun <T : Any> AppSheet(
    item: T?,
    onDismiss: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    skipPartiallyExpanded: Boolean = false,
    sheetState: androidx.compose.material3.SheetState? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(T) -> Unit,
) {
    if (glasseAlertsOn()) {
        var last by remember { mutableStateOf<T?>(null) }
        if (item != null && item != last) androidx.compose.runtime.SideEffect { last = item }
        val shown = item ?: last ?: return
        com.benitbuhner.liquidglass.GlassSheet(
            visible = item != null,
            onDismiss = onDismiss,
            detents = if (skipPartiallyExpanded) listOf(0.94f) else listOf(0.55f, 0.94f),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides com.benitbuhner.liquidglass.LocalGlassTheme.current.label,
            ) { content(shown) }
        }
        return
    }
    if (item == null) return
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState ?: androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        containerColor = containerColor,
    ) { content(item) }
}
