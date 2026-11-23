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
