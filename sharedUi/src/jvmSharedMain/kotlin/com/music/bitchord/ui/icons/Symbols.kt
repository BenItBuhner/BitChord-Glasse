package com.music.bitchord.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * iOS's symbols for the app's common glyphs. With Liquid Glass on, the app sets [provider] to Glassé's symbol
 * set (SF Symbols-style shapes: xmark, checkmark, person, share, trash...), so close buttons, ticks, row
 * chevrons and the rest are iOS's and not Material's; otherwise each call returns its Material fallback.
 * This module cannot see Glassé, hence the hook.
 */
object Symbols {
    @Volatile var provider: ((String) -> ImageVector?)? = null
    fun of(name: String, fallback: ImageVector): ImageVector = provider?.invoke(name) ?: fallback
}
