/*
 * The glass is Glassé (github.com/BenItBuhner/Glasse, MIT), used as a package
 * (com.github.BenItBuhner.Glasse:liquidglass): Apple's measured Liquid Glass
 * optics, material and motion for Compose. The tab bar, switches, segmented
 * controls, buttons, menus and alerts are its own components. This file is the
 * integration glue: it maps BitChord's shapes and
 * settings (Liquid Glass on/off, Reduce dynamic blur, glass performance) onto
 * Glassé surfaces. Kyant0/backdrop (Apache-2.0, vendored at
 * [com.music.bitchord.ui.components.backdrop]) is still what the tab bar's
 * selection lens and the menu stills are drawn with; both read the same page
 * recording the Glassé glass does. The original wiring was adapted from
 * EchoMusicApp/Echo-Music's GlassEffectConfig/Modifier.liquidGlass (GPL-3.0).
 */
package com.music.bitchord.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.highlight.Highlight
import com.music.bitchord.ui.components.backdrop.highlight.HighlightElement
import com.music.bitchord.ui.components.backdrop.internal.ShapeProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.benitbuhner.liquidglass.GlassStyle
import com.benitbuhner.liquidglass.LocalGlassTheme
import com.benitbuhner.liquidglass.exportingLiquidGlass
import com.benitbuhner.liquidglass.liquidGlass
import com.benitbuhner.liquidglass.backdropSource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import com.music.bitchord.ui.components.backdrop.backdrops.layerBackdrop
import com.benitbuhner.liquidglass.Backdrop as GlasseBackdrop
import com.benitbuhner.liquidglass.glassTouch

/** Whether the liquid glass nav bar is turned on — see [AppSettings.liquidGlass]. */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/** The backdrop content (app UI) that a liquid glass surface samples from. */
val LocalAppBackdrop = staticCompositionLocalOf<Backdrop> { error("No AppBackdrop provided") }

/**
 * Where a [liquidGlass] surface composed under it also records the glass it
 * draws — the sampled, blurred and tinted backdrop, without its rim or shadow —
 * so a lens above it can refract that surface rather than the page behind it.
 * The tab bar's travelling selection pill is the one reader; see
 * [com.music.bitchord.ui.components.floatingtabbar.GlassSelectionPill].
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], available from Android 12 (API 31).
 */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/** The veil of [lightweightLiquidGlass], which draws no backdrop of its own. */
private const val SURFACE_OPACITY = 0.4f

/**
 * The hairline along a bar's edge, and what stands in for the glass rim
 * wherever the glass itself is not drawn.
 *
 * A surface filled with the theme's own `surface` colour has no edge of its own
 * against a dark page — it is the same near-black the page is. The glass gets
 * its edge from [Highlight], and this is that edge for everything that does not.
 */
internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/**
 * Icon and label colour for content sitting on a glass surface.
 *
 * Glass shows whatever is behind it rather than the theme's surface colour, so
 * the usual onSurface greys have nothing dependable to sit against. Pure black
 * or white off the theme's luminance is the only tint that holds against
 * arbitrary artwork, and it is what Echo's own glass nav bar uses.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

/**
 * Selected-tab indicator colour for a glass surface: white in both themes.
 *
 * In light theme that is the inverse of [glassContentColor], a bright lift
 * behind a dark glyph. In dark theme it is a faint white lift over the bar's
 * dark glass rather than a black scrim pressed into it — the selected tab is
 * raised out of the bar, as on iOS, not sunk.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color.White.copy(alpha = 0.5f)
    } else {
        Color.White.copy(alpha = DARK_INDICATOR_OPACITY)
    }

/** The selected tab's pill in dark theme, a step whiter than the bar. */
private const val DARK_INDICATOR_OPACITY = 0.14f

/**
 * A lightweight visual match for liquid glass over a stable background.
 *
 * This keeps the same translucent tint, directional highlight and hairline as
 * [liquidGlass], but intentionally performs no backdrop capture, blur, lens
 * refraction or shadow rendering. When Liquid Glass is disabled or unsupported,
 * [fallbackColor] preserves the control's existing filled appearance.
 */
@Composable
fun Modifier.lightweightLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
): Modifier {
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val glassTint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF121212)
    }
    val shapeProvider = ShapeProvider { shape }

    return clip(shape)
        .background(
            color = if (useGlass) glassTint.copy(alpha = SURFACE_OPACITY) else fallbackColor,
            shape = shape,
        )
        .then(
            if (useGlass) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = { Highlight.Default },
                )
            } else {
                Modifier
            },
        )
        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

/**
 * Just the rim of liquid glass — the directional [Highlight] that catches the
 * light along a glass edge — for a control that keeps its own fill.
 *
 * Over a flat, stable page the blur and lens of [liquidGlass] would sample a
 * single colour and change nothing a reader could see; the rim is the part
 * that reads as glass. No backdrop capture, so it costs a stroke. Nothing at
 * all while Liquid Glass is off or unsupported.
 */
@Composable
fun Modifier.liquidGlassRim(shape: CornerBasedShape): Modifier {
    if (!LocalLiquidGlassEnabled.current || !isGlassSupported()) return this
    val shapeProvider = remember(shape) { ShapeProvider { shape } }
    return then(
        HighlightElement(
            shapeProvider = shapeProvider,
            highlight = { Highlight.Default },
        ),
    )
}

/**
 * The page the Glassé glass refracts: the same recording as [LocalAppBackdrop]
 * (one layer, two readers), provided by [com.music.bitchord.MainActivity].
 */
val LocalGlasseBackdrop = staticCompositionLocalOf<GlasseBackdrop?> { null }

/**
 * Everything the menus and alerts float over — the page, its bars and the mini
 * player — recorded live for their glass (see [screenGlassSource]).
 */
val LocalScreenGlass = staticCompositionLocalOf<GlasseBackdrop?> { null }

/**
 * Glassé's corner for a BitChord shape: null (a capsule) for anything rounded
 * by half or more, the shape's own radius otherwise.
 */
internal fun glassCornerOf(shape: CornerBasedShape, density: Density): Dp? {
    val corner = shape.topStart
    val small = corner.toPx(Size(100f, 100f), density)
    val large = corner.toPx(Size(10_000f, 10_000f), density)
    // A percentage scales with the box; a fixed radius does not.
    // A percentage scales with the box (every one BitChord uses is a capsule's
    // 50%); a fixed radius does not.
    return if (large > small * 2f) null else with(density) { small.toDp() }
}

/**
 * Renders this composable as a Liquid Glass surface: Glassé's Apple-measured
 * bevel optics (refraction on Android 13+, frost on 12), rim light, material
 * tint and shadow, over the page recorded into [LocalGlasseBackdrop]. Returns
 * the receiver unchanged on devices without RenderEffect support — callers
 * still gate on [isGlassSupported] to fall back to the regular Haze treatment.
 *
 * Under "reduce dynamic blur" the surface is filled solid instead, which is what
 * that setting promises everywhere else in the app. It is checked here rather
 * than at each call site so there is one answer to it: [MainActivity] also stops
 * recording the backdrop layer when it is on, and a surface that still tried to
 * sample would be sampling a layer nothing is drawing into.
 *
 * Under [AppSettings.glassPerformance] the refraction goes and the glass frosts
 * instead (Glassé's frosted tier), for phones that can't spare the shader.
 *
 * [pressed] (0..1, read while drawing) is Glassé's press lift: the rim
 * brightens and the lens swells a little, like a held iOS 26 control.
 *
 * Inside a [LocalGlassExport] the glass it draws is recorded there too, for the
 * tab bar's selection lens to refract.
 */
@Composable
fun Modifier.liquidGlass(
    shape: CornerBasedShape,
    /** Drawn over the glass in place of the theme's default tint, when set. */
    surfaceTint: Color? = null,
    pressed: () -> Float = NoPress,
    /** Glassé's touch (press, follow, stretch toward the finger): the glass deforms with it */
    interaction: com.benitbuhner.liquidglass.GlassInteraction? = null,
): Modifier {
    if (!isGlassSupported()) return this
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val backdrop = LocalGlasseBackdrop.current
    if (reduceDynamicBlur || backdrop == null) {
        return background(MaterialTheme.colorScheme.surface, shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val theme = LocalGlassTheme.current.let { if (glassPerformance) it.copy(refraction = false) else it }
    val corner = remember(shape, density) { glassCornerOf(shape, density) }
    val style = remember(corner, surfaceTint) {
        GlassStyle(
            cornerRadius = corner,
            tint = surfaceTint?.copy(alpha = 1f),
            tintAlpha = surfaceTint?.alpha,
        )
    }
    val export = LocalGlassExport.current
    return then(
        if (export != null) Modifier.onGloballyPositioned { export.layerCoordinates = it } else Modifier,
    ).exportingLiquidGlass(backdrop, export?.graphicsLayer, style, theme, lift = pressed, interaction = interaction)
}

private val NoPress: () -> Float = { 0f }

/**
 * Records the page for the glass over it: into [backdrop]'s layer, which both
 * Glassé ([LocalGlasseBackdrop]) and the vendored backdrop library read, so the
 * page is drawn into it once a frame. Glassé's source also draws any glass that
 * lives inside the page (a button in a list) over the recording, lifted out of
 * it, so that glass refracts the page around it and never itself.
 */
@Composable
fun Modifier.pageGlassSource(backdrop: LayerBackdrop): Modifier {
    val glasse = LocalGlasseBackdrop.current ?: return layerBackdrop(backdrop)
    return onGloballyPositioned { if (it.isAttached) backdrop.layerCoordinates = it }
        .backdropSource(glasse)
        .drawWithContent { backdrop.onDraw(this) }
}

/**
 * Records everything drawn here — the page, its bars, the mini player — on
 * [floor] for the Liquid Glass menus over it to refract. Only a display list is
 * recorded, the same one the screen draws from, so it costs no extra raster.
 */
fun Modifier.screenGlassSource(backdrop: GlasseBackdrop, floor: Color? = null): Modifier =
    backdropSource(backdrop).then(if (floor != null) Modifier.drawBehind { drawRect(floor) } else Modifier)

/**
 * A control's fill as Liquid Glass while it is on: Glassé glass tinted with
 * [fill], refracting the page around it (glass inside the page is drawn over
 * the page's recording, so it never samples itself). With glass off, or blur
 * reduced, it is [fill] and [edge] as they always were.
 */
@Composable
fun Modifier.glassControl(
    shape: CornerBasedShape,
    fill: Color,
    edge: Color? = null,
    pressed: () -> Float = NoPress,
): Modifier {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val on = LocalLiquidGlassEnabled.current && isGlassSupported() && !reduceDynamicBlur &&
        LocalGlasseBackdrop.current != null
    return if (on) {
        // a Glassé control: it presses, follows and stretches toward the finger, as every Glassé button does
        val touch = com.benitbuhner.liquidglass.rememberGlassInteraction()
        liquidGlass(shape, surfaceTint = fill, pressed = pressed, interaction = touch)
            .glassTouch(touch)
    } else {
        background(fill, shape).then(if (edge != null) Modifier.border(1.dp, edge, shape) else Modifier)
    }
}

/**
 * A floating panel's material (a dialog card, a picker, the profile switcher, the
 * DAC volume HUD): while Liquid Glass is on it is Glassé's thick alert glass over
 * everything under it, as iOS 26 draws its alerts and popovers; otherwise it is
 * [fallback] (the frost or the plain surface the panel always had) in [shape].
 */
@Composable
fun Modifier.panelGlass(shape: CornerBasedShape, fallback: Modifier): Modifier {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val screenGlass = LocalScreenGlass.current
    val on = !reduceDynamicBlur && screenGlass != null && LocalLiquidGlassEnabled.current && isGlassSupported()
    if (!on || screenGlass == null) return clip(shape).then(fallback)
    val density = LocalDensity.current
    val corner = remember(shape, density) { glassCornerOf(shape, density) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return liquidGlass(
        screenGlass,
        GlassStyle(cornerRadius = corner, bezel = 22.dp, thickness = 30.dp, blur = 18.dp, tintAlpha = if (dark) 0.72f else 0.74f),
    ).clip(shape)
}
