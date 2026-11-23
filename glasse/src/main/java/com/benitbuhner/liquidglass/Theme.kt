package com.benitbuhner.liquidglass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class GlassScheme { Light, Dark }

/** iOS 26.1 "Liquid Glass" setting: Clear keeps the glass see-through, Tinted frosts it for contrast. */
enum class GlassAppearance { Clear, Tinted }

/**
 * App-wide glass appearance, the Android twin of `<GlassProvider>`.
 * [translucency] 0..1 scales how much of the backdrop shows through (1 = clearest).
 * [refraction] = false forces the blur/tint tier everywhere (handy for comparisons).
 */
@Immutable
data class GlassTheme(
    val scheme: GlassScheme = GlassScheme.Light,
    val appearance: GlassAppearance = GlassAppearance.Clear,
    val translucency: Float = 0.6f,
    val accent: Color = Color(0xFF0A84FF),
    val refraction: Boolean = true,
    val adaptive: Boolean = true,
) {
    val isDark get() = scheme == GlassScheme.Dark
    val label: Color get() = if (isDark) Color.White else Color(0xFF111114)
    val secondaryLabel: Color get() = if (isDark) Color(0x99EBEBF5) else Color(0x993C3C43)
}

val LocalGlassTheme = staticCompositionLocalOf { GlassTheme() }

@Composable
fun GlassProvider(theme: GlassTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassTheme provides theme, content = content)
}

/**
 * Optical recipe for one glass surface. Mirrors `defaultGlass` in the web core.
 * Null tint values follow the theme scheme.
 */
@Immutable
data class GlassStyle(
    /** Corner radius; null = fully rounded (pill / circle). */
    val cornerRadius: Dp? = null,
    val bezel: Dp = 18.dp,
    val thickness: Dp = 26.dp,
    val ior: Float = 1.5f,
    val strength: Float = 1f,
    val profile: BezelProfile = BezelProfile.Convex,
    /** Bevel = the Apple-measured mirrored rim shared with the web / React Native kits (default). */
    val optics: GlassOptics = GlassOptics.Bevel,
    /** Rim class: Auto (circle → capsule by aspect), Bar (tab bars) or Apple (toolbar buttons, menus' trigger). */
    val rim: RimClass = RimClass.Auto,
    val magnification: Float = 0f,
    val aberration: Float = 0f,
    val blur: Dp = 2.dp,
    val saturation: Float = 1.7f,
    val brightness: Float = 1.04f,
    val tint: Color? = null,
    val tintAlpha: Float? = null,
    val specular: Float = 1f,
    val lightAngle: Float = 320f,
    val shadow: Float = 1f,
    val adaptive: Boolean? = null,
)

/** Spring feels from `src/core/spring.ts` (stiffness / damping → Compose damping ratio). */
object GlassSprings {
    fun <T> bouncy() = spring<T>(dampingRatio = 0.54f, stiffness = 420f)
    fun <T> press() = spring<T>(dampingRatio = 0.66f, stiffness = 520f)
    fun <T> snappy() = spring<T>(dampingRatio = 0.78f, stiffness = 600f)
    fun <T> gentle() = spring<T>(dampingRatio = 0.81f, stiffness = 220f)
    fun <T> lens() = spring<T>(dampingRatio = 0.93f, stiffness = 260f)
    fun <T> wobble() = spring<T>(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium)
}

internal data class ResolvedTint(val light: Color, val dark: Color, val adaptive: Boolean)

internal fun resolveTint(style: GlassStyle, theme: GlassTheme): ResolvedTint {
    val tinted = theme.appearance == GlassAppearance.Tinted
    // translucency 0..1 → alpha multiplier 1.7..0.5 ; Tinted adds a frosted base
    val mul = 1.7f - theme.translucency * 1.2f
    val base = if (theme.isDark) 0.38f else 0.30f
    val a = ((style.tintAlpha ?: base) * mul + if (tinted) 0.32f else 0f).coerceIn(0f, 0.96f)
    val lightC = Color.White
    val darkC = Color(0xFF1A1A1D)
    val primary = style.tint ?: if (theme.isDark) darkC else lightC
    val adaptive = (style.adaptive ?: theme.adaptive) && style.tint == null
    // the adaptive partner is the opposite scheme's tint, a little lighter in alpha
    val partner = if (style.tint != null) primary else if (theme.isDark) darkC else Color(0xFF2A2A2E)
    return ResolvedTint(
        primary.copy(alpha = a),
        partner.copy(alpha = if (theme.isDark) a else (a * 0.9f).coerceAtMost(0.5f)),
        adaptive,
    )
}
