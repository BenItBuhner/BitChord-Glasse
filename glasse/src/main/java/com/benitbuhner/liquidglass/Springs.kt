package com.benitbuhner.liquidglass

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * The shared spring physics, ported 1:1 from @glasse/core (spring.ts + motion.ts) so a spring with the same
 * numbers moves the same on the web, in React Native and on Android. Plain Kotlin (no Android types), so
 * the JVM unit tests check it against the TypeScript numbers.
 */

/** SwiftUI-style spring: [response] in seconds (2π / ω) and [damping] ratio (1 = critically damped, < 1 bounces). */
data class SpringSpec(val response: Float, val damping: Float) {
    /** stiffness for mass 1 */
    val stiffness: Float get() { val w = 2f * PI.toFloat() / response; return w * w }
    /** damping coefficient (not the ratio) for mass 1 */
    val dampingCoefficient: Float get() = 2f * damping * (2f * PI.toFloat() / response)

    companion object {
        /** the SpringSpec of a stiffness / damping-coefficient pair (mass 1), like core `springSpec()` */
        @JvmStatic
        fun of(stiffness: Float, damping: Float): SpringSpec {
            val w = sqrt(stiffness)
            return SpringSpec(2f * PI.toFloat() / w, damping / (2f * w))
        }
    }
}

/** Physical spring parameters exactly as `SpringOptions` in @glasse/core. */
data class SpringOptions(
    val stiffness: Float = 300f,
    val damping: Float = 20f,
    val mass: Float = 1f,
    val precision: Float = 0.0008f,
) {
    /** Compose damping ratio for these options */
    val dampingRatio: Float get() = damping / (2f * sqrt(stiffness * mass))

    companion object {
        @JvmStatic
        fun of(spec: SpringSpec, precision: Float = 0.0008f) =
            SpringOptions(spec.stiffness, spec.dampingCoefficient, 1f, precision)
    }
}

/** Global motion switches, the twin of core `motion`. */
object GlassMotion {
    /** Reduce Motion: springs become critically damped (no overshoot), like iOS. */
    @JvmField var reduced = false
    /** Global time scale (0.2 = 5× slow motion, like iOS "Slow Animations"). */
    @JvmField var timeScale = 1f
    /**
     * Capture mode: when > 0 every frame advances exactly this many seconds (before [timeScale]), whatever the
     * real frame time, so a slow device or emulator still records the motion frame-accurately (each displayed
     * frame is one step; play the recording back at 1 / fixedStep fps).
     */
    @JvmField var fixedStep = 0f

    /** the dt to animate by for a frame that really took [seconds] (see [fixedStep]), before [timeScale] */
    @JvmStatic fun frame(seconds: Double): Double = if (fixedStep > 0f) fixedStep.toDouble() else seconds
}

/**
 * A damped spring stepped by semi-implicit Euler, sub-stepped at 240 Hz so stiff springs stay stable.
 * Identical maths to core `Spring`, so the same dt sequence gives the same values on every platform.
 */
class SpringSim @JvmOverloads constructor(initial: Float = 0f, var opts: SpringOptions = GlassSpringPresets.bouncy) {
    @JvmField var value = initial
    @JvmField var target = initial
    @JvmField var velocity = 0f

    val settled: Boolean
        get() = abs(target - value) < opts.precision && abs(velocity) < opts.precision * 8

    /** jump without animating */
    fun set(v: Float) { value = v; target = v; velocity = 0f }

    fun step(dt: Float) {
        val k = opts.stiffness; val m = opts.mass
        val c = if (GlassMotion.reduced) 2f * sqrt(k * m) else opts.damping
        // doubles inside the loop: the TS reference runs in double precision
        var t = min(dt.toDouble(), 0.064)
        val h = 1.0 / 240.0
        var x = value.toDouble(); var v = velocity.toDouble(); val tgt = target.toDouble()
        while (t > 1e-9) {
            val s = min(h, t)
            val a = (-k * (x - tgt) - c * v) / m
            v += a * s
            x += v * s
            t -= s
        }
        value = x.toFloat(); velocity = v.toFloat()
        if (settled) { value = target; velocity = 0f }
    }
}

/** The named feels of core `springs` (stiffness / damping coefficient, mass 1). */
object GlassSpringPresets {
    @JvmField val bouncy = SpringOptions(420f, 22f)
    @JvmField val press = SpringOptions(520f, 30f)
    @JvmField val snappy = SpringOptions(600f, 38f)
    @JvmField val gentle = SpringOptions(220f, 24f)
    @JvmField val lens = SpringOptions(260f, 30f)
}

/** Piecewise-linear lookup in a table of (x, y) pairs, clamped at both ends (core `lutM`). */
fun lut(table: Array<FloatArray>, x: Float): Float {
    if (x <= table[0][0]) return table[0][1]
    for (i in 1 until table.size) {
        if (x <= table[i][0]) {
            val a = table[i - 1]; val b = table[i]
            return a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0])
        }
    }
    return table[table.size - 1][1]
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun smooth01(a: Float, b: Float, x: Float): Float {
    val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return u * u * (3 - 2 * u)
}

/** iOS-style rubber band: [d] px of drag past an edge → visible offset, limited to [limit] (core `rubberBand`). */
fun rubberBand(d: Float, limit: Float, k: Float = 0.55f): Float {
    val a = abs(d)
    return sign(d) * (1f - 1f / (a * k / limit + 1f)) * limit
}

/** A spring's step response at [t] seconds (0 → 1), core `springStep`: for tests and fitting. */
fun springStep(s: SpringSpec, t: Float): Float {
    if (t <= 0f) return 0f
    val w = 2.0 * PI / s.response; val z = s.damping.toDouble(); val tt = t.toDouble()
    val r = when {
        z < 1 -> { val wd = w * sqrt(1 - z * z); 1 - exp(-z * w * tt) * (kotlin.math.cos(wd * tt) + (z * w / wd) * kotlin.math.sin(wd * tt)) }
        z == 1.0 -> 1 - exp(-w * tt) * (1 + w * tt)
        else -> {
            val r1 = -w * (z - sqrt(z * z - 1)); val r2 = -w * (z + sqrt(z * z - 1))
            1 - (r2 * exp(r1 * tt) - r1 * exp(r2 * tt)) / (r2 - r1)
        }
    }
    return r.toFloat()
}
