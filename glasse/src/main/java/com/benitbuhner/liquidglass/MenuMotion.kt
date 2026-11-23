package com.benitbuhner.liquidglass

/*
 * Per-iOS-version menu motion: the Kotlin twin of `MenuMotion` in @glasse/core (packages/core/src/motion.ts).
 * The preset numbers live in MotionPresets.kt, generated from the TypeScript source by
 * scripts/gen-motion-kotlin.mjs, so web, React Native and Android always read the same measurements.
 */

/** How the menu body leaves the button (core `MenuOpenStyle`). */
enum class MenuOpenStyle {
    /** WWDC25 render: the button pinches into a round blob, holds [MenuMotion.pinch] s, then inflates (Springy). */
    Pinch,
    /** iOS 26.2+: the pressed button inflates at once as a detached blob that drops away and grows into the menu. */
    Blob,
    /** iOS 26.0 / 26.1: a rounded rect grows out of the button's corner, anchored there. Flatter and slower. */
    Scale,
    /** Fast: a round blob thrown off the button toward the far corner that springs back while it inflates. */
    Throw,
}

/** How the menu goes back (core `MenuCloseStyle`). */
enum class MenuCloseStyle {
    /** iOS 26.2+ (and Springy): the body drops off, shrinks, grows a tip into the button and drains into it. */
    Drain,
    /** iOS 26.0 / 26.1: the rect shrinks back into the button's corner on [MenuMotion.closeSpring]. */
    Scale,
    /** Fast: the body springs back into the button while the rows fade on [MenuMotion.closeSpring]. */
    Spring,
}

/** Every variable behind the menu's open and close, measured per iOS version (core `MenuMotion`). */
data class MenuMotion(
    val open: MenuOpenStyle,
    /** Pinch only: seconds the button spends pinching into a blob before the inflate */
    val pinch: Float,
    /** spring across the menu's axis (the width for a menu hanging from a bar button) */
    val width: SpringSpec,
    /** spring along the menu's axis: the far edge */
    val height: SpringSpec,
    /** seconds the width waits behind the height (26.0: the far edge leads by ~2 frames) */
    val widthDelay: Float,
    /** the near edge's kick away from the button, in menu heights per second (0 = it stays on the button) */
    val lift: Float,
    /** the spring that brings the near edge back */
    val liftSpring: SpringSpec,
    /** round blob → the open corner radius */
    val corner: SpringSpec,
    /** the open menu's corner radius (dp) */
    val radius: Float,
    /** when the rows start to fade in (s) */
    val contentDelay: Float,
    /** time scale of the content tables: 1 = the WWDC25 render, 0.5 = twice as fast */
    val contentPace: Float,
    /** peak row blur while they arrive (dp) */
    val contentBlur: Float,
    /** extra row scale while they arrive (0.18 = they start 1.18× and settle to 1) */
    val contentScale: Float,
    /** the rows start this many rows toward the far edge and slide home */
    val contentSlide: Float,
    /** the slide overshoots home and settles (WWDC25: 12 %) */
    val contentOvershoot: Boolean,
    /** the bright flash while the blob inflates fastest (1 = the WWDC25 render's amount) */
    val flash: Float,
    val close: MenuCloseStyle,
    /** Drain only: time scale of the drain (1 = the iOS 26.4/26.5 iPhone, < 1 is faster) */
    val closePace: Float,
    /** Scale close only: the shrink back into the corner */
    val closeSpring: SpringSpec,
    /** when the button's glyph blurs back in on close (s): start, sharp */
    val glyphInStart: Float,
    val glyphInEnd: Float,
    /** seconds the blob stays round (an ellipse) before the corner spring squares it off (26.2+ devices) */
    val cornerDelay: Float = 0f,
    /** Throw only: the blob's kick off the button, in button sizes per second, across (x) and along (y) the menu */
    val throwX: Float = 0f,
    val throwY: Float = 0f,
    /** Throw only: the spring that pulls the thrown blob back onto the button */
    val throwSpring: SpringSpec = SpringSpec.of(625f, 42f),
    /** Throw only: the rows' reveal spring, and the body size (0–1) at which it starts */
    val reveal: SpringSpec = SpringSpec.of(520f, 42f),
    val revealAt: Float = 0.1f,
) {
    companion object {
        /** A version's motion (core `menuMotion(version)`); copy() it to change any variable. */
        @JvmStatic
        fun of(version: MotionVersion): MenuMotion = MotionPresets.of(version)
    }
}
