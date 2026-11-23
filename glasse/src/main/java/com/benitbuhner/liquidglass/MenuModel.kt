package com.benitbuhner.liquidglass

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/*
 * The button → menu morph, ported line by line from the React Native kit's MenuModel
 * (packages/react-native/src/port/menuModel.ts): the same springs, the same Apple tables, the same per-version
 * MenuMotion. Plain Kotlin with no Android types: Compose (GlassMenu) and the View system (widget.GlassMenu)
 * both step it once per frame and draw what [frame] says, and the JVM tests compare its output with the
 * TypeScript model frame by frame.
 *
 * Units are dp, relative to the trigger button's top-left corner (the button is B × B at 0,0). The menu hangs
 * below the button; a menu that opens upward is drawn mirrored (see [MenuFrame]).
 */

/** One row of the menu list: a 44 dp item or a separator. */
class MenuRow(@JvmField val top: Float, @JvmField val height: Float, @JvmField val separator: Boolean)

/** `.lg-menu-list` layout: padding 7, items 44, separator 1 + 4 margins. */
fun menuListLayout(separators: List<Boolean>): Pair<List<MenuRow>, Float> {
    var y = 7f
    val rows = ArrayList<MenuRow>(separators.size)
    for (sep in separators) {
        if (sep) { rows.add(MenuRow(y + 4f, 1f, true)); y += 9f } else { rows.add(MenuRow(y, 44f, false)); y += 44f }
    }
    return rows to (y + 7f)
}

/** The body: a superellipse-cornered rect (corner exponent [m], 2 = circular). */
data class MenuBody(val x0: Float, val y0: Float, val w: Float, val h: Float, val rx: Float, val ry: Float, val m: Float, val j: Float = 0f, val ja: Float = 0f)

/** What to draw this frame (the RN model's `st`). */
class MenuFrame {
    /** the menu body (dp, button space) */
    @JvmField var body = MenuBody(0f, 0f, 44f, 44f, 22f, 22f, 2f)
    /** the button circle (the "head") the body melts into: centre and radius; radius 0 = absent */
    @JvmField var headX = 22f
    @JvmField var headY = 22f
    @JvmField var headR = 0f
    /** smooth-union radius between body and head (dp) */
    @JvmField var neck = 0f
    /** the body has drained fully into the head: draw only the head */
    @JvmField var landed = false
    /** material alpha of the glass (the button's rest material when closed, thicker when open) */
    @JvmField var tint = 0.3f
    /** a white wash over the glass (the open flash / light-mode close frost), 0..1 */
    @JvmField var flash = 0f
    @JvmField var listOpacity = 0f
    @JvmField var listTy = 0f
    @JvmField var listScale = 1f
    /** blur on the rows (dp σ) */
    @JvmField var listBlur = 0f
    /** the open content clock (s) for per-row stagger, -1 = no stagger */
    @JvmField var rowT = -1f
    @JvmField var trigOpacity = 1f
    @JvmField var trigScale = 1f
    @JvmField var trigBlur = 0f
    /** cross-fade to the trigger's own rest glass (1 = the button draws itself again) */
    @JvmField var handoff = 0f
    @JvmField var hlY = 0f
    @JvmField var hlOpacity = 0f
    /** press-drag offset / scale of the open menu */
    @JvmField var bodyTx = 0f
    @JvmField var bodyTy = 0f
    @JvmField var bodySc = 1f
}

private object MenuApple {
    const val BLOB_K = 1.26f; const val BLOB_H = 1.07f; const val C0 = 0.267f; const val C_END = 0.9f
    const val ROW_STAGGER = 0.083f; const val CLOSE_T = 0.12f
    const val FLASH_LIGHT = 0.16f; const val FLASH_DARK = 0.05f
    val RESPREAD = SpringOptions(334f, 14.5f, 1f, 2e-4f)
    val OP = t(-0.033f, 0f, 0f, 0.5f, 0.067f, 0.85f, 0.133f, 1f)
    val SIG = t(0f, 4.6f, 0.033f, 3.7f, 0.1f, 1.5f, 0.167f, 0.9f, 0.233f, 0.6f, 0.433f, 0f)
    val SC = t(0f, 1.18f, 0.1f, 1.08f, 0.333f, 1f)
    val DY = t(0f, 0.77f, 0.1f, 0.75f, 0.133f, 0.55f, 0.167f, 0.37f, 0.2f, 0.22f, 0.233f, 0.1f, 0.267f, 0.014f, 0.3f, -0.042f, 0.333f, -0.077f, 0.367f, -0.09f, 0.4f, -0.09f, 0.433f, -0.084f, 0.467f, -0.07f, 0.5f, -0.056f, 0.533f, -0.035f, 0.567f, -0.021f, 0.6f, -0.014f, 0.633f, 0f)
    val DY_FLAT = t(0f, 0.77f, 0.1f, 0.75f, 0.133f, 0.55f, 0.167f, 0.37f, 0.2f, 0.22f, 0.233f, 0.1f, 0.267f, 0.035f, 0.3f, 0.012f, 0.333f, 0.003f, 0.367f, 0f)
    val FL = t(0.05f, 0f, 0.1f, 0.5f, 0.2f, 1f, 0.267f, 0.6f, 0.33f, 0f)
    val CL_OP = t(0f, 1f, 0.033f, 0.84f, 0.067f, 0.68f, 0.1f, 0.49f, 0.12f, 0f)
    val CL_SIG = t(0f, 0f, 0.033f, 1.2f, 0.067f, 3f, 0.1f, 6.8f)
    // m7 drain close (docs/APPLE-MENU-CLOSE.md)
    val CL_FX = t(0f, 0f, 0.017f, 0.064f, 0.05f, 0.274f, 0.083f, 0.467f, 0.117f, 0.648f, 0.15f, 0.792f, 0.183f, 0.896f, 0.217f, 0.967f, 0.25f, 1f)
    val CL_FY = t(0f, 0f, 0.017f, -0.052f, 0.05f, 0.116f, 0.083f, 0.332f, 0.117f, 0.549f, 0.15f, 0.723f, 0.183f, 0.853f, 0.217f, 0.94f, 0.25f, 1f)
    val CL_NX = t(0f, -0.5f, 0.083f, -0.5f, 0.117f, -0.49f, 0.15f, -0.4f, 0.183f, -0.36f)
    val CL_NY = t(0f, -0.5f, 0.017f, 0.64f, 0.05f, 0.82f, 0.083f, 0.56f, 0.117f, 0.42f, 0.15f, 0.25f, 0.183f, 0.1f, 0.217f, 0f)
    val CL_RR = t(0.017f, 0.2f, 0.05f, 0.25f, 0.083f, 0.3f, 0.117f, 0.36f, 0.15f, 0.45f, 0.183f, 0.5f)
    val CL_DRAIN = t(0.217f, 0f, 0.25f, 0.5f, 0.283f, 1f)
    val CL_HEAD_R = t(0.083f, 0f, 0.1f, 0.2f, 0.117f, 0.4f, 0.133f, 0.72f, 0.15f, 1f)
    val CL_HEAD_X = t(0.1f, 0.22f, 0.117f, 0.2f, 0.133f, 0.1f, 0.15f, 0f)
    val CL_HEAD_Y = t(0.1f, 0.32f, 0.117f, 0.25f, 0.133f, 0.1f, 0.15f, 0f, 0.183f, -0.15f, 0.217f, -0.19f, 0.25f, -0.15f, 0.283f, -0.12f, 0.317f, -0.095f, 0.35f, -0.07f, 0.383f, -0.045f, 0.417f, -0.022f, 0.45f, -0.007f, 0.467f, 0f)
    const val CLOSE_T_END = 0.5f
    const val CL_NECK = 0.5f
    val CL_FLASH = t(0f, 0f, 0.017f, 0.5f, 0.05f, 0.9f, 0.083f, 1f, 0.117f, 1f, 0.15f, 0.85f, 0.183f, 0.6f, 0.217f, 0.35f, 0.25f, 0.15f, 0.3f, 0f)
    const val CL_FLASH_LIGHT = 0.3f
    val CLOSE_OP = t(0f, 1f, 0.017f, 0.35f, 0.05f, 0.1f, 0.083f, 0f)
    val CLOSE_SIG = t(0f, 0f, 0.017f, 3f, 0.05f, 6f, 0.083f, 8f)
    val CLOSE_SC = t(0f, 1f, 0.017f, 1.04f, 0.083f, 1.08f)
    /** Blob open: the rows' blur (× contentBlur) on the content clock (26.2 / 26.4 / 27 device footage) */
    val BLOB_SIG = t(0f, 1f, 0.07f, 1f, 0.1f, 0.7f, 0.133f, 0.35f, 0.167f, 0.1f, 0.2f, 0f)
    val CLOSE_GLYPH = t(0.117f, 0f, 0.15f, 0.4f, 0.183f, 0.8f, 0.217f, 1f)
    val CLOSE_GSIG = t(0.117f, 2.5f, 0.15f, 2f, 0.183f, 1f, 0.217f, 0.3f, 0.25f, 0f)
    val CLOSE_GSC = t(0.117f, 1.1f, 0.15f, 1.08f, 0.217f, 1f)
    val CLOSE_HO = t(0.467f, 0f, 0.483f, 0.5f, 0.5f, 1f)

    private fun t(vararg v: Float) = Array(v.size / 2) { floatArrayOf(v[it * 2], v[it * 2 + 1]) }
}

/** content clock time at which the open opacity table reaches [a] (inverse of OP, for a retargeted reopen) */
/** shortest-decimal Float → Double (0.1f → 0.1), so thresholds equal the TypeScript literals */
internal fun Float.d(): Double = toString().toDouble()

private fun invOP(a: Float): Float {
    val tb = MenuApple.OP
    for (i in 1 until tb.size) if (a <= tb[i][1]) {
        val (x0, v0) = tb[i - 1].let { it[0] to it[1] }; val (x1, v1) = tb[i].let { it[0] to it[1] }
        return x0 + (a - v0) * (x1 - x0) / max(1e-6f, v1 - v0)
    }
    return tb[tb.size - 1][0]
}

/**
 * The menu morph state machine. Call [show] to open / close, then [step] every frame with the frame time
 * (seconds) until it returns false, drawing [frame] after each step.
 *
 * @param separators one entry per list row, true for a separator
 * @param width the open menu's width (dp, default 250 like iOS)
 * @param alignRight the menu extends to the left of the button (a top-right "…" button)
 * @param dark dark scheme (material and flash amounts)
 * @param darkTintAlpha the dark material alpha the closed button uses (core `darkTintAlpha()`)
 */
/** seconds over which a landed close hands over to the button's rest glass (RN chrome.ts LAND_FADE) */
private const val LAND_FADE = 0.1f

class MenuModel private constructor(
    layout: Pair<List<MenuRow>, Float>,
    val width: Float,
    val alignRight: Boolean,
    var dark: Boolean,
    private val darkTintAlpha: Float,
) {
    @JvmOverloads constructor(
        separators: List<Boolean>,
        width: Float = 250f,
        alignRight: Boolean = true,
        dark: Boolean = false,
        darkTintAlpha: Float = GlassMaterial.DARK_TINT_ALPHA,
    ) : this(menuListLayout(separators), width, alignRight, dark, darkTintAlpha)

    /**
     * A menu whose body holds the app's own content ([GlassMenuPopover]) rather than 44 dp item rows: the same
     * morph, springs and tables, opening to [contentHeight] dp. It has no rows of its own, so press-drag
     * highlighting is left to that content.
     */
    constructor(
        contentHeight: Float,
        width: Float,
        alignRight: Boolean,
        dark: Boolean,
        darkTintAlpha: Float = GlassMaterial.DARK_TINT_ALPHA,
    ) : this(emptyList<MenuRow>() to max(44f, contentHeight), width, alignRight, dark, darkTintAlpha)

    val B = 44f
    var motion: MenuMotion = GlassLooks.current.menuMotion
        private set
    /** the open corner radius (the version's, or the concentric one when the look asks for it) */
    var openRadius: Float = motion.radius
    /** override for the next open (otherwise GlassLooks.current.menuMotion) */
    var nextMotion: MenuMotion? = null

    val rows: List<MenuRow> = layout.first
    val Hm: Float = layout.second

    val frame = MenuFrame()
    var isOpen = false; private set
    /** closed / morphing / open */
    var phase = Phase.Closed; private set
    enum class Phase { Closed, Morph, Open }

    private enum class Mode { None, Open, Close }
    private var mode = Mode.None
    // seconds since show(); a Double so frame-multiple thresholds (0.1 s = 12 frames) tie-break like the TS model
    private var tm = 0.0
    private val tf get() = tm.toFloat()
    private var ghostArmed = false
    private var blob = false; private var cOpen = false; private var cClose = false; private var cFrom = 1f
    private var bfW = 44f; private var bfH = 44f; private var bfOx = 0f; private var bfOy = 0f
    private var cShift = 0f; private var cSoft = false; private var flashOn = false; private var fFrom = 1f
    private var tyFrom = 0f; private var scFrom = 1f; private var hoFrom = 0f; private var hoMax = 0f
    private var cHold = 0f
    /** a frosted menu: iOS 26.0 / 26.1 devices (BetaProfiles clip: a near-opaque light frost, not clear glass) */
    fun frosted() = motion.open == MenuOpenStyle.Scale
    /** how much the open menu thickens over the button's rest veil */
    fun up(dark: Boolean) = if (dark) 0.06f else if (frosted()) 0.3f else 0.16f
    private fun respread() = if (motion.close == MenuCloseStyle.Spring) SpringOptions(520f, 30f, 1f, 2e-4f) else MenuApple.RESPREAD
    private var wHold = 0f; private var landed = false
    // m7 drain close
    private var kf = false; private var kfFrom: MenuBody? = null; private var kfQ: MenuBody? = null
    private var kfK = 0f; private var kfHx = 0f; private var kfHy = 0f

    val sw = SpringSim(0f, SpringOptions(820f, 40f, 1f, 1e-4f))
    val sh = SpringSim(0f, SpringOptions(1000f, 46f, 1f, 1e-4f))
    val sm = SpringSim(1f, SpringOptions(200f, 25f, 1f, 2e-4f))
    val ox = SpringSim(0f, SpringOptions(625f, 42f, 1f, 0.004f))
    val oy = SpringSim(0f, SpringOptions(625f, 42f, 1f, 0.004f))
    val g = SpringSim(1f, SpringOptions(900f, 60f, 1f, 2e-4f))
    private val jelly = SpringSim(0f, SpringOptions(230f, 11f, 1f, 1e-4f))
    private val reveal = SpringSim(0f, SpringOptions(520f, 42f, 1f, 2e-4f))
    private val hy = SpringSim(0f, SpringOptions(900f, 52f, 1f))
    private val hlOn = SpringSim(0f, SpringOptions(900f, 52f, 1f))
    private val ex = SpringSim(0f, SpringOptions(420f, 24f, 1f, 0.004f))
    private val ey = SpringSim(0f, SpringOptions(420f, 24f, 1f, 0.004f))
    private val es = SpringSim(0f, SpringOptions(420f, 26f, 1f, 2e-4f))
    private var dragOn = false; private var dragIndex = -1; private var dragX = 0f; private var dragY = 0f

    private val shapeSprings get() = arrayOf(sw, sh, sm, ox, oy, g, jelly)
    private fun allSettled() = shapeSprings.all { it.settled }

    /** x of the open menu's left edge in button space */
    val menuX: Float get() = if (alignRight) B - width else 0f

    init { frame.trigOpacity = 1f; frame.handoff = 0f; restBody() }

    // RN chrome.ts m8 end state (renderer side, outside the golden-tested frame): once a close has drained into the
    // head (landed) the head IS the button, so the button's own rest glass takes over right there over LAND_FADE s
    // of the close's clock, riding the head's last settle (kfHx / kfHy) into place, instead of swapping materials
    // in the last frames after the head has stopped.
    private var landTm = -1.0

    /** How much the real (resting) button shows: 1 when closed; the frame's handoff, or the landing fade on a close. */
    fun restHandoff(): Float {
        if (phase == Phase.Closed) return 1f
        var hoL = 0f
        if (mode == Mode.Close && landed) {
            if (landTm < 0) landTm = tm
            hoL = smooth01(0f, LAND_FADE, (tm - landTm).toFloat())
        } else landTm = -1.0
        return max(frame.handoff, hoL)
    }

    /** the head's offset from the button while it settles after a close (pt, model coordinates) */
    val restDx: Float get() = if (phase != Phase.Closed && kf) kfHx else 0f
    val restDy: Float get() = if (phase != Phase.Closed && kf) kfHy else 0f

    private fun restBody() {
        val b = body(); frame.body = b; frame.headR = B / 2; frame.headX = B / 2; frame.headY = B / 2; frame.landed = true
    }

    fun body(): MenuBody {
        kfQ?.let { if (kf) return it }
        val w = max(6f, lerp(B, width, sw.value))
        val y0 = oy.value
        // Blob: the height spring drives the far edge and the near edge drops on the lift spring between
        val h = max(6f, lerp(B, Hm, sh.value) - (if (motion.open == MenuOpenStyle.Blob) y0 else 0f))
        val x1 = if (alignRight) B - ox.value else ox.value + w
        val k = sm.value.coerceIn(0f, 1f)
        val rx = min(w / 2, lerp(w / 2, openRadius, k))
        val ry = min(h / 2, lerp(h / 2, openRadius, k))
        val m = 2f + 1.6f * sin(PI.toFloat() * k) * (1 - k * 0.4f)
        return MenuBody(x1 - w, y0, w, h, rx, ry, m)
    }

    private fun ghostR() = B / 2 * g.value.coerceIn(0f, 1.2f)

    /** signed distance to the body (port of bodyField, jelly omitted: it is always 0 at rest and on Android) */
    private fun bodyField(q: MenuBody, x: Float, y: Float): Float {
        val cx = q.x0 + q.w / 2; val cy = q.y0 + q.h / 2
        val px = abs(x - cx) - (q.w / 2 - q.rx); val py = abs(y - cy) - (q.h / 2 - q.ry)
        if (px <= 0 && py <= 0) return max(px - q.rx, py - q.ry)
        if (px > 0 && py > 0) {
            val v = ((px / q.rx).pow(q.m) + (py / q.ry).pow(q.m)).pow(1f / q.m)
            return (v - 1) * min(q.rx, q.ry)
        }
        return if (px > 0) px - q.rx else py - q.ry
    }

    /** the union geometry (port of outline() minus the path): head, neck and whether the body has landed */
    private fun outline() {
        val q = body(); val gr = ghostR()
        val bcx = B / 2 + (if (kf) kfHx else 0f); val bcy = B / 2 + (if (kf) kfHy else 0f)
        val cx = q.x0 + q.w / 2; val cy = q.y0 + q.h / 2
        val sep = hypot(cx - bcx, cy - bcy)
        val k = if (kf) kfK else B * 0.42f * (sep / (B * 0.6f)).coerceIn(0f, 1f)
        val dc = bodyField(q, bcx, bcy)
        landed = false
        if (!(gr < 0.75f || dc - gr > k + 1)) {
            var far = 0f
            val e = 2f / q.m
            for (i in 0 until 24) {
                val t = i / 24f * PI.toFloat() * 2
                val c = cos(t); val s = sin(t)
                val px = (q.w / 2 - q.rx) * sign(c) + q.rx * sign(c) * abs(c).pow(e)
                val py = (q.h / 2 - q.ry) * sign(s) + q.ry * sign(s) * abs(s).pow(e)
                far = max(far, hypot(cx + px - bcx, cy + py - bcy))
            }
            landed = far <= gr - 0.5f
        }
        frame.body = q; frame.headX = bcx; frame.headY = bcy; frame.headR = if (gr < 0.75f) 0f else gr
        frame.neck = k; frame.landed = landed
    }

    /** Advance by [dt] seconds. Returns true while anything still moves (keep stepping). */
    fun step(dtSeconds: Float): Boolean = step(dtSeconds.toDouble())

    /** Advance by [dtSeconds] (Double: frame times from nanoTime keep TypeScript's precision). */
    fun step(dtSeconds: Double): Boolean {
        val dt = (dtSeconds * GlassMotion.timeScale).toFloat()
        tm += dtSeconds * GlassMotion.timeScale
        if (mode == Mode.Close && !kf && !ghostArmed && tm >= 0.12) {
            ghostArmed = true; g.opts = respread(); g.target = 1f
        }
        for (s in shapeSprings) s.step(dt)
        reveal.step(dt); hy.step(dt); hlOn.step(dt); ex.step(dt); ey.step(dt); es.step(dt)
        if (kf) kfStep()
        val P = motion
        if (wHold > 0 && tm >= wHold.d()) { wHold = 0f; sw.target = 1f; ox.target = 0f }
        if (cHold > 0 && tm >= cHold.d()) { cHold = 0f; sm.target = 1f }
        if (blob) {
            if (tm < P.pinch.d()) {
                val u = tf / P.pinch; val e = 2 * u - u * u
                val bw = lerp(bfW, B * MenuApple.BLOB_K, e); val bh = lerp(bfH, B * MenuApple.BLOB_H, e)
                sw.set((bw - B) / (width - B)); sh.set((bh - B) / (Hm - B))
                ox.set(lerp(bfOx, -(B * MenuApple.BLOB_K - B) / 2, e)); oy.set(lerp(bfOy, -(B * MenuApple.BLOB_H - B) / 2, e)); sm.set(0f)
            } else { blob = false; launchOpen(true) }
        }
        val morphing = kf || blob || !allSettled() || (mode == Mode.Close && (!ghostArmed || hoMax < 1))
        val o = min(sw.value, sh.value)
        val st = frame
        if (morphing || phase == Phase.Morph) {
            outline()
            val a0 = if (dark) darkTintAlpha else 0.3f
            val thick = a0 + up(dark)
            st.tint = if (kf) lerp(thick, a0, smooth01(0f, 0.05f, tf)) else lerp(a0, thick, ((sw.value + sh.value) / 2).coerceIn(0f, 1f))
            if (mode == Mode.Close && !kf && ghostArmed && landed && hoMax >= 1 && allSettled()) {
                mode = Mode.None
                for (s in shapeSprings) s.set(if (s === g || s === sm) 1f else 0f)
                jelly.set(0f); phase = Phase.Closed
            }
        }
        val rv = reveal.value.coerceIn(0f, 1f)
        if (cOpen) {
            val tcd = (tm + cShift.d() - P.contentDelay.d()) / P.contentPace.d(); val tc = tcd.toFloat()
            if (tcd >= 0.9 - 0.267) { cOpen = false; cSoft = false; reveal.set(1f) }
            st.listOpacity = if (tc < -0.033f) 0f else 1f
            st.rowT = if (cOpen) tc else -1f
            st.listTy = if (cOpen && !cSoft) lut(if (P.contentOvershoot) MenuApple.DY else MenuApple.DY_FLAT, tc) * (P.contentSlide / 0.77f) * 44f else 0f
            st.listScale = if (cOpen && !cSoft) 1 + (lut(MenuApple.SC, tc) - 1) * (P.contentScale / 0.18f) else 1f
            if (P.open == MenuOpenStyle.Scale && cOpen) { val q = body(); st.listScale = min(q.w / width, q.h / Hm).coerceIn(0.2f, 1.05f) }
            val b = if (cOpen) (if (P.open == MenuOpenStyle.Blob) lut(MenuApple.BLOB_SIG, tc) * P.contentBlur else lut(MenuApple.SIG, tc) * (P.contentBlur / 4.6f)) else 0f
            st.listBlur = if (b > 0.004f) b else 0f
        } else if (cClose) {
            val quick = kf || P.close == MenuCloseStyle.Scale
            val ctd = if (kf) tm / P.closePace.d() else tm; val ct = ctd.toFloat()
            val cT = if (quick) 0.083f else MenuApple.CLOSE_T
            if (ctd >= (if (quick) 0.083 else 0.12)) cClose = false
            val k = (ct / cT).coerceIn(0f, 1f)
            st.rowT = -1f
            st.listTy = if (cClose) tyFrom * (1 - k) else 0f
            st.listScale = if (cClose) lerp(scFrom, 1f, k) else 1f
            val q = kfQ; val q0 = kfFrom
            if (kf && cClose && q != null && q0 != null) {
                st.listTy += (q.y0 + q.h / 2) - (q0.y0 + q0.h / 2)
                st.listScale *= lut(MenuApple.CLOSE_SC, ct)
            }
            st.listOpacity = if (cClose) cFrom * lut(if (quick) MenuApple.CLOSE_OP else MenuApple.CL_OP, ct) else 0f
            val b = if (cClose) lut(if (quick) MenuApple.CLOSE_SIG else MenuApple.CL_SIG, ct) else 0f
            st.listBlur = if (b > 0.004f) b else 0f
        } else {
            st.rowT = -1f
            st.listOpacity = (rv * 1.4f).coerceIn(0f, 1f)
            st.listTy = (1 - rv) * -10f
            val thr = P.open == MenuOpenStyle.Throw
            st.listScale = 1 - (if (thr) P.contentScale else 0.14f) * (1 - rv)
            val blur = (1 - rv) * (if (thr) P.contentBlur else 6f)
            st.listBlur = if (blur > 0.004f) blur else 0f
        }
        st.flash = when {
            kf -> (if (dark) 0f else MenuApple.CL_FLASH_LIGHT) * lut(MenuApple.CL_FLASH, tf / P.closePace)
            flashOn && cOpen && tm < 0.33 -> (if (dark) MenuApple.FLASH_DARK else MenuApple.FLASH_LIGHT) * P.flash * lut(MenuApple.FL, tf)
            else -> 0f
        }
        st.bodyTx = ex.value; st.bodyTy = ey.value; st.bodySc = 1 + 0.025f * es.value
        run {
            val thr = P.open == MenuOpenStyle.Throw
            val tg = 0.117f + ((if (kf) tf / P.closePace else tf) - P.glyphInStart) * (0.1f / (P.glyphInEnd - P.glyphInStart))
            val f = when {
                mode == Mode.Open -> fFrom * (1 - smooth01(0f, if (thr) 0.04f else 0.067f, tf))
                mode == Mode.Close -> lut(MenuApple.CLOSE_GLYPH, tg)
                phase == Phase.Closed -> 1f
                else -> 0f
            }
            val gb = if (mode == Mode.Close && (kf || P.close == MenuCloseStyle.Scale)) lut(MenuApple.CLOSE_GSIG, tg) else (1 - f) * 2.5f
            st.trigBlur = if (thr || f < 0.001f) 0f else gb
            st.trigOpacity = f
            st.trigScale = when {
                thr -> 0.7f + 0.3f * f
                mode == Mode.Open -> 1 + 0.25f * (1 - f)
                kf || (mode == Mode.Close && P.close == MenuCloseStyle.Scale) -> lut(MenuApple.CLOSE_GSC, tg)
                else -> 1 + 0.1f * (1 - f)
            }
            hoMax = if (mode == Mode.Close) {
                if (kf) max(hoMax, lut(MenuApple.CLOSE_HO, tf / P.closePace)) else if (landed) min(1f, hoMax + dt / 0.12f) else hoMax
            } else 0f
            st.handoff = when (mode) {
                Mode.Close -> hoMax
                Mode.Open -> if (thr) 0f else hoFrom * (1 - smooth01(0f, 0.067f, tf))
                else -> 0f
            }
        }
        st.hlY = hy.value
        st.hlOpacity = hlOn.value.coerceIn(0f, 1f)
        if (mode == Mode.Open && o > (if (P.open == MenuOpenStyle.Throw) P.revealAt else 0.1f) && !cOpen) reveal.target = 1f
        if (!morphing && phase == Phase.Morph && mode == Mode.Open) {
            mode = Mode.None
            jelly.set(0f)
            for (s in shapeSprings) if (s !== g) s.set(s.target)
            outline()
            st.tint = (if (dark) darkTintAlpha else 0.3f) + up(dark)
            phase = Phase.Open
        }
        return morphing || cOpen || cClose || !reveal.settled || !hy.settled || !hlOn.settled || !ex.settled || !ey.settled || !es.settled || dragOn
    }

    /** m7 drain close at tm (the tables above); the springs mirror the shape so a reopen retargets from it */
    private fun kfStep() {
        val td = tm / motion.closePace.d(); val t = td.toFloat(); val q0 = kfFrom
        if (td >= 0.5 - 1e-4 || q0 == null) {
            kf = false; kfQ = null; mode = Mode.None; cClose = false
            for (s in shapeSprings) s.set(if (s === g || s === sm) 1f else 0f)
            jelly.set(0f); phase = Phase.Closed
            return
        }
        val c = B / 2
        val sx = if (q0.x0 + q0.w / 2 >= c) 1f else -1f
        val sy = if (q0.y0 + q0.h / 2 >= c) 1f else -1f
        fun axis(lo: Float, hi: Float, sg: Float, nt: Array<FloatArray>, ft: Array<FloatArray>): FloatArray {
            val n0 = ((if (sg > 0) lo else hi) - c) * sg / B; val f0 = ((if (sg > 0) hi else lo) - c) * sg / B
            val k = ((f0 - n0) / 4).coerceIn(0f, 1f)
            val near = lerp(n0, lut(nt, t), k)
            val far = max(lerp(f0, 0.5f, lut(ft, t)), near + 0.3f)
            return floatArrayOf(near, far)
        }
        val ax = axis(q0.x0, q0.x0 + q0.w, sx, MenuApple.CL_NX, MenuApple.CL_FX)
        val ay = axis(q0.y0, q0.y0 + q0.h, sy, MenuApple.CL_NY, MenuApple.CL_FY)
        var nx = ax[0]; var fx = ax[1]; var ny = ay[0]; var fy = ay[1]
        val hr = lut(MenuApple.CL_HEAD_R, t); val hsx = lut(MenuApple.CL_HEAD_X, t); val hsy = lut(MenuApple.CL_HEAD_Y, t)
        val dn = lut(MenuApple.CL_DRAIN, t)
        if (dn > 0) { val k = 1 - dn; nx = lerp(hsx, nx, k); fx = lerp(hsx, fx, k); ny = lerp(hsy, ny, k); fy = lerp(hsy, fy, k) }
        val xa = c + sx * nx * B; val xb = c + sx * fx * B; val ya = c + sy * ny * B; val yb = c + sy * fy * B
        val x0 = min(xa, xb); val y0 = min(ya, yb); val w = max(0.5f, abs(xb - xa)); val h = max(0.5f, abs(yb - ya))
        val r0 = min(q0.rx, q0.ry)
        val rr = if (t < 0.017f) lerp(r0 / min(q0.w, q0.h), 0.2f, t / 0.017f) else lut(MenuApple.CL_RR, t)
        val r = minOf(w / 2, h / 2, rr * min(w, h))
        val q = MenuBody(x0, y0, w, h, r, r, q0.m)
        kfQ = q; kfK = MenuApple.CL_NECK * B; kfHx = sx * hsx * B; kfHy = sy * hsy * B
        g.set(hr)
        sw.set((w - B) / (width - B)); sh.set((h - B) / (Hm - B))
        ox.set(if (alignRight) B - (q.x0 + w) else q.x0); oy.set(q.y0)
        sm.set(((w / 2 - q.rx) / max(1e-3f, w / 2 - openRadius)).coerceIn(0f, 1f))
    }

    private fun launchOpen(fresh: Boolean) {
        val P = motion
        sw.opts = SpringOptions.of(P.width, 1e-4f)
        sh.opts = SpringOptions.of(P.height, 1e-4f)
        sm.opts = SpringOptions.of(P.corner, 2e-4f)
        ox.opts = SpringOptions.of(P.width, 0.004f)
        oy.opts = SpringOptions.of(P.liftSpring, 0.004f)
        sh.target = 1f; oy.target = 0f
        // the blob stays round (an ellipse) for cornerDelay on a fresh open, then squares off
        if (fresh && P.cornerDelay > 0) { cHold = P.cornerDelay; sm.target = sm.value } else { cHold = 0f; sm.target = 1f }
        if (fresh && P.widthDelay > 0) { wHold = P.widthDelay; sw.target = sw.value; ox.target = ox.value }
        else { wHold = 0f; sw.target = 1f; ox.target = 0f }
        if (fresh && P.lift > 0) oy.velocity = P.lift * Hm
    }

    /** a row's opacity on the open content clock: rows nearest the trigger arrive first */
    fun rowAlpha(i: Int, n: Int): Float {
        val t = frame.rowT
        if (t < 0) return 1f
        val d = if (n > 1) MenuApple.ROW_STAGGER * i / (n - 1) else 0f
        return lut(MenuApple.OP, t - d)
    }

    /** Open (true) or close (false). Reopening mid-close (or closing mid-open) retargets from what is on screen. */
    fun show(v: Boolean) {
        tm = 0.0
        val closing = mode == Mode.Close
        val fNow = frame.trigOpacity; val opNow = frame.listOpacity; val tyNow = frame.listTy; val scNow = frame.listScale
        cShift = 0f; cSoft = false; flashOn = false
        hoFrom = if (phase == Phase.Closed) 1f else frame.handoff
        if (v) { motion = nextMotion ?: GlassLooks.current.menuMotion; if (!customRadius) openRadius = motion.radius }
        val P = motion
        if (v && P.open == MenuOpenStyle.Throw) {
            // Throw (Fast, the m4l open): thrown toward the far corner and pulled back, rows on the reveal spring
            mode = Mode.Open; cClose = false; cOpen = false; blob = false; wHold = 0f; cHold = 0f
            fFrom = if (phase == Phase.Closed) 1f else fNow.coerceIn(0f, 1f)
            if (phase == Phase.Closed) {
                sw.set(0f); sh.set(0f); sm.set(0f); ox.set(0f); oy.set(0f)
                g.set(0f); jelly.set(0f); reveal.set(0f)
                ox.velocity = P.throwX * B; oy.velocity = P.throwY * B
            }
            sw.opts = SpringOptions.of(P.width, 1e-4f)
            sh.opts = SpringOptions.of(P.height, 1e-4f)
            sm.opts = SpringOptions.of(P.corner, 2e-4f)
            ox.opts = SpringOptions.of(P.throwSpring, 0.004f); oy.opts = SpringOptions.of(P.throwSpring, 0.004f)
            g.opts = SpringOptions(1600f, 80f, 1f, 2e-4f)
            sw.target = 1f; sh.target = 1f; sm.target = 1f; ox.target = 0f; oy.target = 0f; g.target = 0f
            ghostArmed = false
            reveal.opts = SpringOptions.of(P.reveal, 2e-4f)
        } else if (v) {
            fFrom = if (phase == Phase.Closed) 1f else fNow.coerceIn(0f, 1f)
            mode = Mode.Open; cClose = false
            g.opts = SpringOptions(1600f, 80f, 1f, 2e-4f); g.target = 0f
            ghostArmed = false
            reveal.opts = SpringOptions(520f, 42f, 1f, 2e-4f)
            if (phase == Phase.Closed) {
                sw.set(0f); sh.set(0f); sm.set(0f); ox.set(0f); oy.set(0f)
                g.set(0f); g.target = 0f; jelly.set(0f)
                reveal.set(0f)
                bfW = B; bfH = B; bfOx = 0f; bfOy = 0f
                cOpen = true; flashOn = P.flash > 0
                if (P.open == MenuOpenStyle.Pinch) blob = true else { blob = false; launchOpen(true) }
            } else if (closing && min(sw.value, sh.value) < 0.3f && opNow < 0.05f) {
                val q = body()
                bfW = q.w; bfH = q.h; bfOx = ox.value; bfOy = oy.value
                reveal.set(0f)
                blob = true; cOpen = true; flashOn = true
            } else {
                blob = false; launchOpen(false)
                cOpen = true
                if (opNow > 0.01f) { cSoft = true; cShift = P.contentDelay + invOP(opNow) * P.contentPace }
                else cShift = max(0f, P.contentDelay - 0.1f * P.contentPace)
            }
        } else {
            mode = Mode.Close
            ghostArmed = false
            cFrom = opNow.coerceIn(0f, 1f); tyFrom = tyNow; scFrom = scNow
            blob = false; cOpen = false; cClose = P.close != MenuCloseStyle.Spring && cFrom > 0.01f; wHold = 0f; cHold = 0f
            if (P.close == MenuCloseStyle.Scale) {
                val end = B * 0.96f; val cs = SpringOptions.of(P.closeSpring)
                g.set(1f); g.target = 1f; ghostArmed = true; hoMax = 0f; kf = false; kfQ = null
                sw.opts = cs.copy(precision = 1e-4f); sh.opts = cs.copy(precision = 1e-4f)
                ox.opts = cs.copy(precision = 0.004f); oy.opts = cs.copy(precision = 0.004f)
                sm.opts = SpringOptions.of(P.corner, 2e-4f)
                sw.target = (end - B) / (width - B); sh.target = (end - B) / (Hm - B); sm.target = 0f
                ox.target = (B - end) / 2; oy.target = (B - end) / 2
                jelly.set(0f); reveal.set(0f); hlOn.target = 0f; isOpen = false; phase = Phase.Morph
                return
            }
            if (!closing && P.close != MenuCloseStyle.Spring) {
                val q = body()
                kfFrom = q; kf = true; kfQ = null; ghostArmed = true; hoMax = 0f
                jelly.set(0f); reveal.set(0f); hlOn.target = 0f; isOpen = false; phase = Phase.Morph
                kfStep()
                return
            }
            g.set(1f); ghostArmed = true; g.target = 1f
            g.opts = respread()
            val endSize = B * 0.55f
            sm.opts = SpringOptions(520f, 40f, 1f, 2e-4f)
            sw.opts = SpringOptions(260f, 30f, 1f, 1e-4f)
            sh.opts = SpringOptions(215f, 26f, 1f, 1e-4f)
            ox.opts = SpringOptions(230f, 27f, 1f, 0.004f)
            oy.opts = SpringOptions(240f, 26f, 1f, 0.004f)
            sm.target = 0f; sw.target = (endSize - B) / (width - B); sh.target = (endSize - B) / (Hm - B)
            ox.target = (B - endSize) / 2; oy.target = (B - endSize) / 2
            if (phase == Phase.Open) oy.velocity = 11 * B
            // Spring (Fast): the rows fade on closeSpring
            if (P.close == MenuCloseStyle.Spring) { reveal.opts = SpringOptions.of(P.closeSpring, 2e-4f); reveal.target = 0f; kf = false; kfQ = null }
            else reveal.set(0f)
        }
        if (v) { kf = false; kfQ = null }
        hlOn.target = 0f
        isOpen = v
        phase = Phase.Morph
    }

    private var customRadius = false
    /** Fix the open corner radius (e.g. concentric with the display); null = the version's radius. */
    fun setOpenRadius(r: Float?) { customRadius = r != null; openRadius = r ?: motion.radius }

    // press-drag selection (pointer coords in button space)
    fun indexAt(localY: Float): Int {
        val top = ey.value
        rows.forEachIndexed { i, r -> if (!r.separator && localY >= r.top + top && localY < r.top + r.height + top) return i }
        return -1
    }

    fun highlight(i: Int) {
        dragIndex = i
        val r = rows.getOrNull(i)
        if (r != null && !r.separator) {
            if (hlOn.value < 0.05f) hy.set(r.top)
            hy.target = r.top; hlOn.target = 1f
        } else hlOn.target = 0f
    }

    fun listDown(x: Float, y: Float) { dragOn = true; dragX = x; dragY = y; es.target = 1f; highlight(indexAt(y)) }
    fun listMove(x: Float, y: Float) {
        if (!dragOn) return
        ex.target = rubberBand(x - dragX, 60f, 0.3f); ey.target = rubberBand(y - dragY, 60f, 0.3f)
        val i = indexAt(y); if (i != dragIndex) highlight(i)
    }
    /** Returns the row index released over (or -1); the caller runs the item and closes the menu. */
    fun listUp(y: Float): Int {
        if (!dragOn) return -1
        dragOn = false
        ex.target = 0f; ey.target = 0f; es.target = 0f
        val i = indexAt(y)
        if (i < 0 || rows[i].separator) { hlOn.target = 0f; return -1 }
        return i
    }
}
