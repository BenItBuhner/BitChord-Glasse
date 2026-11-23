package com.benitbuhner.liquidglass

import java.util.concurrent.CopyOnWriteArraySet

/*
 * Liquid Glass version presets, the Android twin of `ui/glassLook.ts` (React Native) and docs/GLASS-VERSIONS.md.
 * A preset is a named bundle of per-feature looks; every feature can be overridden on its own. Components read
 * GlassLooks.current when they start an animation or draw, and Compose screens recompose through
 * rememberGlassLook(); Views get onChange callbacks. Works the same from Kotlin and Java:
 *
 *   GlassLooks.setVersion(GlassVersion.IOS26_2);                   // a preset
 *   GlassLooks.set(GlassLooks.getCurrent().withEdgeEffect(EdgeEffect.Blocks));
 */

/** The scroll edge under bars: iOS 26's soft gradient, or iOS 27's hard band ("Blocks"). */
enum class EdgeEffect { Progressive, Blocks }

/** WWDC25 keynote effect: where alerts come from. Center (default), Tap (grow out of the tap), Popup (Mail's card at the tap). */
enum class AlertStyle { Center, Tap, Popup }

/** Specular rim: fixed light from the top-left, or following device tilt. */
enum class SpecularMode { Fixed, Motion }

/** The glass looks you can pick, one per shipping iOS version (plus the WWDC25 render the kit defaults to). */
enum class GlassVersion(val id: String, val label: String) {
    WWDC25("wwdc25", "WWDC25 (Springy)"),
    IOS26_0("ios26.0", "iOS 26.0"),
    IOS26_1("ios26.1", "iOS 26.1"),
    IOS26_2("ios26.2", "iOS 26.2"),
    IOS26_4("ios26.4", "iOS 26.4"),
    IOS26_X("ios26.x", "iOS 26.x"),
    IOS27("ios27", "iOS 27"),
    ;

    companion object {
        @JvmStatic fun fromId(id: String): GlassVersion? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Every per-version feature in one value. Copy it to change one feature
 * (`look.copy(edgeEffect = EdgeEffect.Blocks)` or, from Java, the `with…` helpers).
 */
data class GlassLook(
    /** scroll edge under the nav bar */
    val edgeEffect: EdgeEffect = EdgeEffect.Progressive,
    /** whose menu motion: the measured numbers in [MotionPresets] */
    val menu: MotionVersion = MotionVersion.WWDC25,
    /** any menu variable on top of [menu]'s preset (null = the preset as measured) */
    val menuOverride: MenuMotion? = null,
    /** iOS 26.1+ Liquid Glass setting: Tinted is more opaque */
    val tinted: Boolean = false,
    /** extra frost on top of the material (0 = as designed; iOS 27 ≈ 0.5) */
    val frost: Float = 0f,
    val alertStyle: AlertStyle = AlertStyle.Center,
    /** glass near a screen corner follows the display's corner radius (r = hardware − inset) */
    val concentric: Boolean = false,
    /** display corner radius in dp for [concentric] (0 = estimate from the screen size) */
    val cornerRadius: Float = 0f,
    val specular: SpecularMode = SpecularMode.Fixed,
) {
    /** the menu motion in effect */
    val menuMotion: MenuMotion get() = menuOverride ?: MotionPresets.of(menu)

    fun withEdgeEffect(v: EdgeEffect) = copy(edgeEffect = v)
    fun withMenu(v: MotionVersion) = copy(menu = v, menuOverride = null)
    fun withMenuMotion(v: MenuMotion?) = copy(menuOverride = v)
    fun withTinted(v: Boolean) = copy(tinted = v)
    fun withFrost(v: Float) = copy(frost = v)
    fun withAlertStyle(v: AlertStyle) = copy(alertStyle = v)
    fun withConcentric(v: Boolean) = copy(concentric = v)
    fun withCornerRadius(v: Float) = copy(cornerRadius = v)
    fun withSpecular(v: SpecularMode) = copy(specular = v)

    /** device corner radius in dp: the setting, else an estimate (Android phones ≈ 9 % of the short side). */
    fun hardwareRadius(widthDp: Float, heightDp: Float): Float =
        if (cornerRadius > 0f) cornerRadius else Math.round(minOf(widthDp, heightDp) * 0.09f).toFloat()

    /** concentric corner for glass inset [inset] dp from the nearest screen corner; [fallback] unless [concentric]. */
    @JvmOverloads
    fun concentricRadius(fallback: Float, inset: Float, widthDp: Float, heightDp: Float, height: Float = Float.MAX_VALUE, min: Float = 10f): Float =
        if (!concentric) fallback else maxOf(min, minOf(height / 2f, hardwareRadius(widthDp, heightDp) - inset))

    companion object {
        /** the per-version presets (`GLASS_PRESETS` in ui/glassLook.ts) */
        @JvmStatic
        fun preset(v: GlassVersion): GlassLook = when (v) {
            GlassVersion.WWDC25 -> GlassLook(EdgeEffect.Progressive, MotionVersion.WWDC25)
            GlassVersion.IOS26_0 -> GlassLook(EdgeEffect.Progressive, MotionVersion.IOS26_0)
            GlassVersion.IOS26_1 -> GlassLook(EdgeEffect.Progressive, MotionVersion.IOS26_1)
            GlassVersion.IOS26_2 -> GlassLook(EdgeEffect.Progressive, MotionVersion.IOS26_2)
            GlassVersion.IOS26_4, GlassVersion.IOS26_X -> GlassLook(EdgeEffect.Progressive, MotionVersion.IOS26_4)
            GlassVersion.IOS27 -> GlassLook(EdgeEffect.Blocks, MotionVersion.IOS26_4, frost = 0.5f) // the iOS 27 menu was removed: 26.4
        }
    }
}

/** The app-wide look (one store, like the web / React Native `setGlassVersion`). Thread-safe to read. */
object GlassLooks {
    @Volatile private var look: GlassLook = GlassLook.preset(GlassVersion.WWDC25)
    @Volatile private var ver: GlassVersion? = GlassVersion.WWDC25
    private val subs = CopyOnWriteArraySet<Runnable>()

    @JvmStatic val current: GlassLook get() = look
    /** the preset in effect, or null once a preset feature was changed by hand ("custom") */
    @JvmStatic val version: GlassVersion? get() = ver

    /**
     * Apply a preset. Keynote options (alert style, concentric corners, corner radius, specular) are kept,
     * like the TypeScript store.
     */
    @JvmStatic
    fun setVersion(v: GlassVersion) {
        val p = GlassLook.preset(v)
        look = p.copy(alertStyle = look.alertStyle, concentric = look.concentric, cornerRadius = look.cornerRadius, specular = look.specular)
        ver = v
        fire()
    }

    /** Replace the look (e.g. one feature changed); the version becomes the matching preset or null (custom). */
    @JvmStatic
    fun set(next: GlassLook) {
        if (next == look) return
        look = next
        ver = if (next.menuOverride != null) null else GlassVersion.entries.firstOrNull { v ->
            v != GlassVersion.IOS26_X && GlassLook.preset(v).let { p ->
                p.edgeEffect == next.edgeEffect && p.menu == next.menu && p.tinted == next.tinted && p.frost == next.frost
            }
        }
        fire()
    }

    /** Listen for changes; returns a handle that removes the listener. */
    @JvmStatic
    fun addListener(listener: Runnable): AutoCloseable {
        subs.add(listener)
        return AutoCloseable { subs.remove(listener) }
    }

    private fun fire() { subs.forEach { it.run() } }
}
