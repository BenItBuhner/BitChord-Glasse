package com.benitbuhner.liquidglass

import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * A Compose animatable driven by the kit's own spring ([SpringSim]): the same integrator and numbers as the web
 * and React Native kits, so a push, a sheet or a tab lens moves identically on every platform. Read [value] in
 * a draw / graphicsLayer lambda to animate without recomposing.
 */
@Stable
class GlassSpring(initial: Float, opts: SpringOptions = GlassSpringPresets.bouncy) {
    private val sim = SpringSim(initial, opts)
    private val mutex = MutatorMutex()
    private var v by mutableFloatStateOf(initial)

    val value: Float get() = v
    val target: Float get() = sim.target
    val velocity: Float get() = sim.velocity
    val isSettled: Boolean get() = sim.settled
    var options: SpringOptions
        get() = sim.opts
        set(o) { sim.opts = o }

    /** Jump to [value] (no animation; cancels a running one). */
    suspend fun snapTo(value: Float) = mutex.mutate { sim.set(value); v = value }

    /** Spring to [target] from wherever it is, keeping its velocity (or starting with [velocity]). */
    suspend fun animateTo(target: Float, velocity: Float? = null, options: SpringOptions? = null) = mutex.mutate {
        if (options != null) sim.opts = options
        sim.target = target
        if (velocity != null) sim.velocity = velocity
        var last = withFrameNanos { it }
        while (!sim.settled) {
            val now = withFrameNanos { it }
            sim.step((GlassMotion.frame((now - last) / 1e9) * GlassMotion.timeScale).toFloat())
            last = now
            v = sim.value
        }
        v = sim.value
    }
}

@Composable
fun rememberGlassSpring(initial: Float, options: SpringOptions = GlassSpringPresets.bouncy): GlassSpring =
    remember { GlassSpring(initial, options) }

/** The app-wide [GlassLook] as Compose state: recomposes when [GlassLooks] changes. */
@Composable
fun rememberGlassLook(): GlassLook {
    var look by remember { mutableStateOf(GlassLooks.current) }
    DisposableEffect(Unit) {
        val h = GlassLooks.addListener { look = GlassLooks.current }
        look = GlassLooks.current
        onDispose { h.close() }
    }
    return look
}
