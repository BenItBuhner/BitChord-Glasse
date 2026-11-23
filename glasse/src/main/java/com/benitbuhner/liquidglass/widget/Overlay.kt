package com.benitbuhner.liquidglass.widget

import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.benitbuhner.liquidglass.GlassOverlayLayer
import com.benitbuhner.liquidglass.LiquidGlassLayout

/** Where menus, alerts and sheets float: the overlay layer of the outermost LiquidGlassLayout. */
internal object Overlays {
    fun hostOf(v: View): LiquidGlassLayout =
        LiquidGlassLayout.rootOf(v) ?: error("Glassé overlays (menus, alerts, sheets) need a LiquidGlassLayout around ${v.javaClass.simpleName}")

    fun layerOf(host: LiquidGlassLayout): GlassOverlayLayer {
        for (i in 0 until host.childCount) (host.getChildAt(i) as? GlassOverlayLayer)?.let { return it }
        val l = GlassOverlayLayer(host.context)
        host.addView(l, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return l
    }

    /** [v]'s bounds in [host]'s coordinates (after transforms) */
    fun boundsIn(v: View, host: View): RectF {
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationInWindow(a); host.getLocationInWindow(b)
        val x = (a[0] - b[0]).toFloat(); val y = (a[1] - b[1]).toFloat()
        return RectF(x, y, x + v.width * v.scaleX, y + v.height * v.scaleY)
    }

    fun activityOf(c: Context): ComponentActivity? {
        var x: Context? = c
        while (x is ContextWrapper) { if (x is ComponentActivity) return x; x = x.baseContext }
        return null
    }

    /** back closes the overlay while it is up; remove() the returned callback when it goes */
    fun onBack(v: View, action: () -> Unit): OnBackPressedCallback? {
        val a = activityOf(v.context) ?: return null
        val cb = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = action() }
        a.onBackPressedDispatcher.addCallback(cb)
        return cb
    }
}
