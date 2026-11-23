package com.benitbuhner.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Rewrites a surface's [GlassStyle] (app-wide defaults; Java-friendly). */
fun interface GlassStyleTransform { fun apply(style: GlassStyle): GlassStyle }

/**
 * Ready-made [GlassStyle]s, named styles, and app-wide defaults. Same names and numbers as `GlassStyles`
 * in the web / React Native / Kotlin kit / Flutter packages, so a look carries over between platforms.
 *
 * ```kotlin
 * Modifier.liquidGlass(backdrop, GlassStyles.Clear)
 * Modifier.liquidGlass(backdrop, GlassStyles.tinted(Color(0xFFFF2D55)).copy(cornerRadius = 20.dp))
 * GlassStyles.register("brand", GlassStyles.Frosted.copy(tint = Brand, tintAlpha = 0.3f))
 * Modifier.liquidGlass(backdrop, GlassStyles["brand"])
 * GlassStyles.setDefaults { it.copy(specular = it.specular * 1.4f) }   // every surface, built-ins included
 * ```
 */
object GlassStyles {
    /** Apple's standard material (every built-in control's default). */
    @JvmField val Regular = GlassStyle()
    /** Apple's .clear: barely tinted, lightly frosted, for glass over rich media. */
    @JvmField val Clear = GlassStyle(tintAlpha = 0.06f, blur = 0.6.dp, saturation = 1.35f, shadow = 0.7f)
    /** Heavy blur and a stronger veil, for legibility over busy content. */
    @JvmField val Frosted = GlassStyle(blur = 9.dp, tintAlpha = 0.42f, saturation = 1.5f)
    /** A magnifying lens (loupes, slider thumbs). */
    @JvmField val Lens = GlassStyle(blur = 0.dp, tintAlpha = 0.02f, magnification = 0.22f, thickness = 34.dp, bezel = 16.dp, shadow = 0.6f, adaptive = false)
    /** Thick glass with rainbow fringes at the rim. */
    @JvmField val Prism = GlassStyle(thickness = 40.dp, bezel = 22.dp, aberration = 0.28f, tintAlpha = 0.08f, specular = 1.3f)
    /** A deeper bevel and brighter rim, like a glass pebble. */
    @JvmField val Thick = GlassStyle(thickness = 44.dp, bezel = 26.dp, specular = 1.5f, shadow = 1.3f)
    /** Soft rim and shadow, for large panels. */
    @JvmField val Subtle = GlassStyle(specular = 0.5f, shadow = 0.45f, bezel = 12.dp, thickness = 18.dp)
    /** Coloured glass. */
    @JvmStatic @JvmOverloads fun tinted(color: Color, alpha: Float = 0.32f) = GlassStyle(tint = color, tintAlpha = alpha)

    private val registry = LinkedHashMap<String, GlassStyle>().apply {
        put("regular", Regular); put("clear", Clear); put("frosted", Frosted); put("lens", Lens)
        put("prism", Prism); put("thick", Thick); put("subtle", Subtle)
    }
    /** Name a style so it can be shared by name (Views XML, settings screens, design tokens). */
    @JvmStatic fun register(name: String, style: GlassStyle) { registry[name] = style; version++ }
    /** A registered style; unknown names give [Regular]. */
    @JvmStatic operator fun get(name: String): GlassStyle = registry[name] ?: Regular
    @JvmStatic fun names(): List<String> = registry.keys.toList()

    /** Bumps whenever the defaults or a registered style change. */
    @JvmStatic var version = 0; private set

    /** App-wide defaults: runs on every surface's style before it draws (built-in controls included). */
    @JvmStatic var defaults: GlassStyleTransform? = null; private set
    /** Restyle every glass surface in the app (Compose and Views). null goes back to Apple's material.
     *  The transform sees each surface's own style, so `it.copy(tint = …)` recolours everything and
     *  `it.copy(specular = it.specular * 1.3f)` keeps each control's own balance. */
    @JvmStatic fun setDefaults(transform: GlassStyleTransform?) { defaults = transform; version++ }

    internal fun effective(s: GlassStyle, local: GlassStyleTransform?): GlassStyle {
        val t = local ?: defaults ?: return s
        return t.apply(s)
    }
}

/** Per-subtree defaults for Compose (wins over [GlassStyles.setDefaults] inside it). */
val LocalGlassStyleDefaults = staticCompositionLocalOf<GlassStyleTransform?> { null }

/** Restyle every glass surface inside [content]: `GlassStyleDefaults({ it.copy(tint = Pink) }) { … }`. */
@Composable
fun GlassStyleDefaults(transform: GlassStyleTransform?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassStyleDefaults provides transform, content = content)
}
