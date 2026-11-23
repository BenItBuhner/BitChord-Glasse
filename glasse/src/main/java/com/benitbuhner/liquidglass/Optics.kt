package com.benitbuhner.liquidglass

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Port of `@glasse/core` optics (packages/core/src/optics.ts).
 *
 * Default ([GlassOptics.Bevel]): the Apple-measured rim the web and React Native kits use. Content
 * at the rim folds back as a mirrored band over a flat, unmagnified body. The band's reach and
 * curve depend on the shape: circles and capsules ease between two measured fits (m4q), and
 * toolbar buttons use the per-pixel WWDC25 toolbar rim ([RimClass.Apple], m4r).
 * [GlassOptics.Legacy] keeps the earlier physical model: Snell refraction across a convex-squircle
 * bezel, clamped monotonic (no mirror).
 *
 * The web version bakes the resulting displacement table into PNG tiles for an SVG
 * feDisplacementMap. On Android the table is fitted with a short Chebyshev series so the
 * AGSL shader can evaluate it analytically per pixel (AGSL arrays can't be indexed by a
 * computed value, so a lookup table is not an option).
 */
enum class BezelProfile { Convex, Lip }

/** Which optics model draws the rim. Bevel (Apple-fitted, default) or the pre-m4p physical Legacy one. */
enum class GlassOptics { Bevel, Legacy }

/** Rim class (same as the web / RN `rim` prop). Auto eases circle → capsule by aspect ratio; Bar keeps the
 *  App Store tab-bar fold at every size; Apple is the measured toolbar-button rim. */
enum class RimClass { Auto, Bar, Apple }

internal object Optics {
    const val CHEB_TERMS = 8

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun height(profile: BezelProfile, t: Double): Double {
        val c = (1 - (1 - t).pow(4)).pow(0.25)
        return when (profile) {
            BezelProfile.Convex -> c
            BezelProfile.Lip -> c * (1 - 0.45 * smoothstep(0.5, 1.0, t))
        }
    }

    /** Inward displacement in px for t = i/(N-1) (t = 0 at the rim). Mirrors computeRefractionProfile. */
    fun refractionTable(bezel: Float, thickness: Float, ior: Float, profile: BezelProfile, n: Int = 128): DoubleArray {
        val eta = 1.0 / ior
        val bezelH = bezel.toDouble()
        val table = DoubleArray(n)
        for (i in 0 until n) {
            val t = max(1e-3, i / (n - 1.0))
            val e = 1e-3
            val t1 = min(1.0, t + e)
            val t0 = max(0.0, t - e)
            val slope = (height(profile, t1) - height(profile, t0)) / (t1 - t0) * (bezelH / bezel)
            val len = hypot(slope, 1.0)
            val ny = 1 / len
            val nx = -slope / len
            val dot = -ny
            val k = 1 - eta * eta * (1 - dot * dot)
            var d = 0.0
            if (k > 0) {
                val m = eta * dot + sqrt(k)
                val rx = -m * nx
                val ry = -eta - m * ny
                val remaining = height(profile, t) * bezelH + thickness
                d = rx * remaining / abs(ry)
            }
            table[i] = d
        }
        val dx = bezel / (n - 1.0)
        for (i in n - 2 downTo 0) table[i] = min(table[i], table[i + 1] + dx * 0.8)
        return table
    }

    class Fit(val coeffs: FloatArray, val maxPx: Float)

    private val cache = HashMap<String, Fit>()

    /** Chebyshev coefficients of D(t)/max on x = 2t-1, plus max displacement in px. */
    fun fit(bezel: Float, thickness: Float, ior: Float, profile: BezelProfile): Fit {
        val key = "${(bezel * 4).toInt()}|${(thickness * 4).toInt()}|${(ior * 100).toInt()}|$profile"
        cache[key]?.let { return it }
        val table = refractionTable(bezel, thickness, ior, profile)
        val maxD = table.maxOf { abs(it) }.coerceAtLeast(1e-3)
        val n = table.size
        fun sample(t: Double): Double {
            val f = t.coerceIn(0.0, 1.0) * (n - 1)
            val i0 = f.toInt().coerceAtMost(n - 2)
            val fr = f - i0
            return (table[i0] + (table[i0 + 1] - table[i0]) * fr) / maxD
        }
        val nodes = 48
        val c = FloatArray(CHEB_TERMS)
        for (j in 0 until CHEB_TERMS) {
            var s = 0.0
            for (k in 0 until nodes) {
                val theta = PI * (k + 0.5) / nodes
                val x = cos(theta)
                s += sample((x + 1) / 2) * cos(j * theta)
            }
            c[j] = (s * 2.0 / nodes * if (j == 0) 0.5 else 1.0).toFloat()
        }
        return Fit(c, maxD.toFloat()).also { if (cache.size < 256) cache[key] = it }
    }

    /* ── m5: one physical rim for every glass (packages/core/src/optics.ts GLASS_RIM / glassRimD) ──
       A squircle-edged slab refracted by Snell's law, fitted to the measured m4r toolbar rim. Every RimClass
       resolves to it; GLASS = false restores the m4p-m4r per-class fits below. */
    @JvmField var GLASS = true
    const val GLASS_W = 0.35; /* reach rule K (optics.ts GLASS_RIM.K) */ const val GLASS_G = 2.05; const val GLASS_P = 2.85; const val GLASS_T = 26.0
    fun glassRimD(t: Double, thickness: Double = GLASS_T, ior: Double = 1.5): Double {
        if (t >= 1) return 0.0
        val x = 1 - max(0.0, t); val xp = x.pow(GLASS_P)
        val z = max(0.0, 1 - xp).pow(1 / GLASS_P)
        val slope = if (xp < 1) x.pow(GLASS_P - 1) * (1 - xp).pow(1 / GLASS_P - 1) else 1e9
        val th = atan(slope)
        val dev = th - asin(sin(th) / ior)
        val g = GLASS_G * min(2.0, max(0.5, thickness / GLASS_T))
        return tan(dev) * (g + z)
    }

    /* ── Apple bevel (m4p-m4r), same numbers as packages/core/src/optics.ts ── */
    private const val RIM_CIRCLE_W = 0.122; private const val RIM_CIRCLE_A = 0.517; private const val RIM_CIRCLE_P = 3.36
    private const val RIM_CAPSULE_W = 0.32; private const val RIM_CAPSULE_A = 0.64; private const val RIM_CAPSULE_P = 1.0
    const val APPLE_RIM_W = 0.53
    private val AU = doubleArrayOf(0.0, 0.0031, 0.0126, 0.0248, 0.0372, 0.0526, 0.0712, 0.0898, 0.1115, 0.1393, 0.1703, 0.2012, 0.2322, 0.2632, 0.2941, 0.3251, 0.356, 0.387, 0.418, 0.4489, 0.4799, 0.5108, 0.53)
    private val AD = doubleArrayOf(1.30, 1.2735, 1.1088, 0.9816, 0.8938, 0.8019, 0.7109, 0.6324, 0.5489, 0.4579, 0.3759, 0.3026, 0.2427, 0.1901, 0.1457, 0.1081, 0.076, 0.0502, 0.0292, 0.0143, 0.0051, 0.0011, 0.0)

    /** Measured toolbar rim: inward sample offset D/R at depth u = d/R (monotone cubic through the knots). */
    fun appleRimD(u: Double): Double {
        if (u <= 0) return AD[0]
        if (u >= APPLE_RIM_W) return 0.0
        var i = 0
        while (i < AU.size - 2 && u > AU[i + 1]) i++
        fun m(j: Int): Double {
            val n = AU.size - 1
            val d0 = if (j > 0) (AD[j] - AD[j - 1]) / (AU[j] - AU[j - 1]) else Double.NaN
            val d1 = if (j < n) (AD[j + 1] - AD[j]) / (AU[j + 1] - AU[j]) else Double.NaN
            if (d0.isNaN()) return d1
            if (d1.isNaN()) return d0
            if (d0 * d1 <= 0) return 0.0
            return 2 * d0 * d1 / (d0 + d1)
        }
        val h = AU[i + 1] - AU[i]; val t = (u - AU[i]) / h
        val h00 = (1 + 2 * t) * (1 - t) * (1 - t); val h10 = t * (1 - t) * (1 - t)
        val h01 = t * t * (3 - 2 * t); val h11 = t * t * (t - 1)
        return h00 * AD[i] + h10 * h * m(i) + h01 * AD[i + 1] + h11 * h * m(i + 1)
    }

    /** Shape class k (0 circle .. 1 capsule) of a w×h glass, quantised to quarters like the web. */
    fun rimClassK(w: Float, h: Float, rim: RimClass): Double {
        if (rim == RimClass.Bar) return 1.0
        val m = max(1f, min(w, h)); val a = max(w, h) / m
        return Math.round(((a - 1f) / 1.5f).toDouble().coerceIn(0.0, 1.0) * 4) / 4.0
    }

    /** The rim's reach in px (width of the mirrored band), capped by bezel / 2 like the web. */
    fun rimReach(bezel: Float, w: Float, h: Float, rim: RimClass): Float {
        val half = min(w, h) / 2f
        if (GLASS) return max(1f, min(bezel / 2f, (GLASS_W * half).toFloat()))
        if (rim == RimClass.Apple) return max(1f, (APPLE_RIM_W * half).toFloat())
        val k = rimClassK(w, h, rim)
        val wk = RIM_CIRCLE_W + (RIM_CAPSULE_W - RIM_CIRCLE_W) * k
        return max(1f, min(bezel / 2f, (wk * half).toFloat()))
    }

    /** Bevel rim for a w×h glass: the reach, and a fit of the normalised offset curve with its peak in px. */
    class Bevel(val reach: Float, val fit: Fit)

    fun bevel(bezel: Float, w: Float, h: Float, rim: RimClass): Bevel {
        val reach = rimReach(bezel, w, h, rim)
        val half = min(w, h) / 2f
        if (GLASS) {
            val d0 = glassRimD(0.0)
            val f = fitCurve("glass") { t -> glassRimD(t) / d0 }
            return Bevel(reach, Fit(f.coeffs, (d0 * reach).toFloat()))
        }
        if (rim == RimClass.Apple) {
            val f = fitCurve("apple") { t -> appleRimD(t * APPLE_RIM_W) / AD[0] }
            return Bevel(reach, Fit(f.coeffs, (AD[0] * half).toFloat()))
        }
        val k = rimClassK(w, h, rim)
        val wk = RIM_CIRCLE_W + (RIM_CAPSULE_W - RIM_CIRCLE_W) * k
        val ak = RIM_CIRCLE_A + (RIM_CAPSULE_A - RIM_CIRCLE_A) * k
        val pk = RIM_CIRCLE_P + (RIM_CAPSULE_P - RIM_CIRCLE_P) * k
        val amp = ak / wk // offset at the outer edge, in units of the reach
        val f = fitCurve("k$k") { t -> (1 - t).pow(pk) }
        return Bevel(reach, Fit(f.coeffs, (amp * reach).toFloat()))
    }

    private val curveCache = HashMap<String, Fit>()
    private fun fitCurve(key: String, f: (Double) -> Double): Fit = curveCache.getOrPut(key) {
        val nodes = 64
        val c = FloatArray(CHEB_TERMS)
        for (j in 0 until CHEB_TERMS) {
            var s = 0.0
            for (k in 0 until nodes) {
                val theta = PI * (k + 0.5) / nodes
                s += f((cos(theta) + 1) / 2) * cos(j * theta)
            }
            c[j] = (s * 2.0 / nodes * if (j == 0) 0.5 else 1.0).toFloat()
        }
        Fit(c, 1f)
    }

    fun evalFit(fit: Fit, t: Float): Float {
        val x = 2 * t - 1
        var b1 = 0f
        var b2 = 0f
        for (k in CHEB_TERMS - 1 downTo 1) {
            val tmp = 2 * x * b1 - b2 + fit.coeffs[k]
            b2 = b1; b1 = tmp
        }
        return fit.coeffs[0] + x * b1 - b2
    }
}
