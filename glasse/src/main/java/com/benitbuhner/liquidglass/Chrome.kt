package com.benitbuhner.liquidglass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

data class GlassDialogAction(val label: String, val destructive: Boolean = false, val primary: Boolean = false, val onClick: () -> Unit = {})

/** Alert on a thick glass card that springs in over a dimmed scrim. */
@Composable
fun GlassDialog(
    backdrop: Backdrop,
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    actions: List<GlassDialogAction>,
) {
    val theme = LocalGlassTheme.current
    val p by animateFloatAsState(if (visible) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 420f), label = "dialog")
    if (!visible && p < 0.002f) return
    val pa = p.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f * pa))
            .clickable(remember { MutableInteractionSource() }, null, enabled = visible) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdrop,
            Modifier
                .width(300.dp)
                .graphicsLayer {
                    val s = 1.14f - 0.14f * p
                    scaleX = s; scaleY = s; alpha = pa
                }
                .clickable(remember { MutableInteractionSource() }, null) {},
            style = GlassStyle(cornerRadius = 34.dp, bezel = 24.dp, thickness = 34.dp, blur = 7.dp, tintAlpha = if (theme.isDark) 0.55f else 0.5f),
        ) {
            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = theme.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(message, color = theme.label.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
                }
                Spacer(Modifier.height(18.dp))
                val horizontal = actions.size == 2
                val btn: @Composable (GlassDialogAction, Modifier) -> Unit = { act, m ->
                    val bg = when {
                        act.destructive -> Color(0xFFFF3B30)
                        act.primary -> theme.accent
                        theme.isDark -> Color(0x33FFFFFF)
                        else -> Color(0x1F000000)
                    }
                    val fg = if (act.destructive || act.primary) Color.White else theme.label
                    Box(
                        m.height(48.dp).background(bg, RoundedCornerShape(50)).clickable(enabled = visible) { act.onClick(); onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) { Text(act.label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (horizontal) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        actions.forEach { btn(it, Modifier.weight(1f)) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { actions.forEach { btn(it, Modifier.fillMaxWidth()) } }
                }
            }
        }
    }
}
