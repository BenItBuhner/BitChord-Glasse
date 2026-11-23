/*
 * The now playing accessory's content is from EchoMusicApp/Echo-Music's
 * FloatingMiniPlayer (GPL-3.0), with BitChord's song model, transport and haptics.
 */
@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.benitbuhner.liquidglass.GlassTab
import com.benitbuhner.liquidglass.GlassTabView
import com.benitbuhner.liquidglass.LocalGlassTheme
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarScrollConnection
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.playback.MusicOnlyPlayback
import com.music.bitchord.ui.player.PlayerDock
import com.music.bitchord.ui.player.rememberPlayerSkipMotion
import com.music.bitchord.ui.player.PlayerPlayPauseGlyph
import com.music.bitchord.ui.player.rememberPlayerPlayPauseMotion
import com.music.bitchord.ui.player.AndroidMusicVideo
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.fillMaxSize

/**
 * The liquid glass navigation bar: Glassé's [GlassTabView], the iOS 26 tab view
 * where the now playing controls are the bar's accessory and Search its own circle.
 *
 * Expanded, the now playing accessory floats over the tab bar with Search beside
 * it. Scrolling down minimizes it (Glassé's `minimized`): the bar shrinks to a
 * circle holding the selected tab and the accessory slides down between it and
 * Search. Scrolling back up, or tapping the circle, expands it. The scroll comes
 * from [scrollConnection]; see MainActivity's `nestedScroll`.
 *
 * Glassé draws every surface over the page backdrop, so this is only ever used
 * where glass is supported and switched on — off either,
 * MainActivity draws [MiniPlayer] and [FloatingBottomBar] instead.
 *
 * [song] null means nothing is playing, and the accessory is simply absent: the
 * bar is then the tab pill and Search alone, and the collapse still works.
 */
@Composable
fun GlassNavBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    scrollConnection: FloatingTabBarScrollConnection,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    /** @see com.music.bitchord.data.listentogether.ListenTogether.State.controlsLocked */
    controlsLocked: Boolean = false,
    onBlockedControl: () -> Unit = {},
    /** Where the player's artwork lands when it closes into this bar. */
    dock: PlayerDock? = null,
    /** Dragging the bar's player up into the full player. */
    pull: MiniPlayerPull? = null,
    /** False while the expanded player covers either accessory density. */
    animationsActive: Boolean = true,
    modifier: Modifier = Modifier,
    /** Called when the user taps the currently active tab (scroll-to-top). */
    onActiveTabTap: () -> Unit = {},
    autoMixArtwork: (@Composable (String?, Modifier) -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    val backdrop = LocalGlasseBackdrop.current ?: return
    // Search is the last of BitChord's tabs: Glassé's tab view draws it as the circle of its own at the
    // trailing end, the rest are the bar's tabs.
    val searchIndex = tabs.lastIndex
    val barTabs = remember(tabs) { tabs.dropLast(1).map { GlassTab(it.label, it.icon) } }
    val searchTab = remember(tabs) { tabs.last().let { GlassTab(it.label, it.icon) } }
    val minimized = scrollConnection.isInline
    GlassTabView(
        backdrop = backdrop,
        tabs = barTabs,
        selected = selectedIndex.coerceIn(0, barTabs.lastIndex),
        onSelect = { index ->
            if (index == selectedIndex) onActiveTabTap() else haptics.play(Haptic.Select)
            currentOnTabSelected(index)
        },
        modifier = modifier
            .windowInsetsPadding(floatingBarInsets)
            .padding(bottom = NAV_BAR_LIFT),
        searchTab = searchTab,
        searchSelected = selectedIndex == searchIndex,
        onSearch = {
            if (selectedIndex == searchIndex) onActiveTabTap() else haptics.play(Haptic.Select)
            currentOnTabSelected(searchIndex)
        },
        accessory = song?.let { current ->
            {
                GlassNowPlaying(
                    song = current,
                    isInline = minimized,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    animationsActive = animationsActive,
                    contentColor = LocalGlassTheme.current.label,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onExpand = onExpand,
                    controlsLocked = controlsLocked,
                    onBlockedControl = onBlockedControl,
                    dock = dock,
                    pull = pull,
                    modifier = Modifier.fillMaxWidth(),
                    autoMixArtwork = autoMixArtwork,
                )
            }
        },
        onAccessoryClick = onExpand,
        minimized = minimized,
        onExpand = { scrollConnection.expand() },
    )
}

/**
 * The now playing controls docked into [GlassNavBar] as its accessory.
 *
 * Two densities of the same row rather than two components, so the shared
 * element carrying it between the bar's states has one thing to interpolate:
 * [isInline] drops the skip button and draws the play button smaller. The
 * artwork and the type are the same at both, as they are in Apple's accessory,
 * which only changes width between its two placements.
 *
 * The content is [MiniPlayer]'s — same artwork, same transport, same haptics —
 * and not Echo's, which reaches into a player connection this app doesn't have.
 * The press response is Echo's, and belongs to the glass rather than the row:
 * a surface you can push on is the whole point of the material.
 */
@Composable
private fun GlassNowPlaying(
    song: Song,
    isInline: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    animationsActive: Boolean,
    contentColor: Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    dock: PlayerDock?,
    pull: MiniPlayerPull?,
    modifier: Modifier = Modifier,
    autoMixArtwork: (@Composable (String?, Modifier) -> Unit)?,
) {
    val haptics = rememberHaptics()
    val pressSource = remember { MutableInteractionSource() }
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val nextMotion = rememberPlayerSkipMotion(active = animationsActive, reduceMotion = reduceAnimation)
    val playPauseMotion = rememberPlayerPlayPauseMotion(
        isPlaying = isPlaying,
        active = animationsActive && !isLoading,
        reduceMotion = reduceAnimation,
    )
    val isPressed by pressSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 1.04f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "accessoryPressScale",
    )

    val titleStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = ACCESSORY_TITLE_SIZE,
        fontWeight = ACCESSORY_TITLE_WEIGHT,
        letterSpacing = ACCESSORY_TITLE_TRACKING,
    )
    val artistStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = ACCESSORY_ARTIST_SIZE,
        letterSpacing = ACCESSORY_ARTIST_TRACKING,
    )
    val glyphSlot = if (isInline) 32.dp else 40.dp
    val glyphSize = if (isInline) 24.dp else EXPANDED_GLYPH_SIZE

    Box(
        // Inline, the accessory is stretched to the row's height by the tab bar
        // (`fillMaxHeight` on a row measured at IntrinsicSize.Max), and this row
        // is shorter than that — the tab pill beside it, with a 28dp glyph in
        // 10dp of padding, is the tallest thing in the row and sets the height.
        // A Box defaults to TopStart, so the artwork and the transport sat a
        // couple of dp above the pill's centre line. Expanded the Box wraps its
        // content, so centring is a no-op there.
        contentAlignment = Alignment.Center,
        // The gestures and the tap belong to the whole glass surface, not the
        // row centred in it. Inline, that row is shorter than the pill it sits
        // in, and a pull started in the strip above or below it went nowhere —
        // on a bar only 48dp tall to begin with.
        modifier = Modifier
            // Outside the press scale, which it lifts along with everything else.
            .miniPlayerGestures(
                pull = pull,
                onNext = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                onPrevious = {
                    haptics.play(Haptic.SkipPrevious)
                    onPrevious()
                },
                locked = controlsLocked,
                onBlocked = onBlockedControl,
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(modifier)
            .clickable(
                interactionSource = pressSource,
                indication = null,
                onClick = onExpand,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    // The artwork sits in the end of a capsule, which curves
                    // away from it; at 8dp its corner met the curve.
                    start = 16.dp,
                    end = if (isInline) 8.dp else 12.dp,
                    // Expanded, the 40dp play button and these come to the 48dp
                    // Apple's accessory is.
                    top = 4.dp,
                    bottom = 4.dp,
                ),
        ) {
            // A music video plays in the cover's place at its own 16:9, as in
            // the floating mini player; the full player takes it over when up.
            val showsPicture = song.isVideo && MusicOnlyPlayback.showsPicture(song.videoId)
            val artWidth by animateDpAsState(
                targetValue = if (showsPicture && !VideoThumbnails.square) ACCESSORY_ART_SIZE * (16f / 9f) else ACCESSORY_ART_SIZE,
                label = "glassMiniArtWidth",
            )
            Box(
                modifier = Modifier
                    .size(width = artWidth, height = ACCESSORY_ART_SIZE)
                    // Whichever of the two densities is on screen is the one
                    // the player flies to: only one is composed at rest.
                    .playerDockArt(dock, ACCESSORY_ART_CORNER)
                    .clip(RoundedCornerShape(ACCESSORY_ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                MiniPlayerArtwork(song, autoMixArtwork)
                if (showsPicture) {
                    AndroidMusicVideo(
                        videoId = song.videoId,
                        fit = false,
                        onRenderedChanged = {},
                        modifier = Modifier.fillMaxSize(),
                        priority = 0,
                    )
                }
            }
            Spacer(Modifier.width(if (isInline) 8.dp else 9.dp))
            // Title and artist at both densities, as Apple Music has them, in its
            // small, openly spaced type (see [ACCESSORY_TITLE_SIZE]). The inline
            // row shares its width with the tab pill and the Search circle, so
            // a long line fades out at its end, as Apple's does.
            Column(Modifier.weight(1f)) {
                val secondary = contentColor.copy(alpha = 0.7f)
                ExplicitSongTitle(
                    song = song,
                    style = titleStyle,
                    color = contentColor,
                    badgeColor = secondary,
                    fadeOverflow = true,
                )
                FadingLine(
                    text = song.artist,
                    style = artistStyle,
                    color = secondary,
                )
            }
            if (isLoading) {
                Box(Modifier.size(glyphSlot), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = contentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(if (isInline) 18.dp else 20.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                        playPauseMotion.onTap()
                        onPlayPause()
                    },
                    modifier = Modifier.size(glyphSlot),
                ) {
                    PlayerPlayPauseGlyph(
                        motion = playPauseMotion,
                        play = Icons.Rounded.PlayArrow,
                        pause = Icons.Rounded.Pause,
                        playPainter = rememberVectorPainter(Icons.Rounded.PlayArrow),
                        pausePainter = rememberVectorPainter(Icons.Rounded.Pause),
                        contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                        tint = contentColor,
                        modifier = Modifier.size(glyphSize),
                    )
                }
            }
            // Dropped inline: the collapsed row is sharing its width with the
            // tab pill and the Search circle, and the title is what has to
            // survive that, not a second transport button.
            if (!isInline) {
                // Glyph centres 44dp apart, as Apple's are.
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        haptics.play(Haptic.SkipNext)
                        nextMotion.onTap()
                        onNext()
                    },
                    enabled = !controlsLocked,
                    modifier = Modifier.size(glyphSlot),
                ) {
                    MiniPlayerNextGlyph(
                        motion = nextMotion,
                        tint = contentColor.copy(alpha = if (controlsLocked) 0.3f else 1f),
                    )
                }
            }
        }
    }
}


/**
 * The accessory's type, measured off Apple's, collapsed and expanded alike:
 * 13pt over 12pt, set looser than the scale's 16 and 14 as SF Pro Text spaces
 * these sizes — the title less so than measured, tuned by eye on device: in
 * Semibold +0.4 read loose and the cut's own spacing tight.
 */
private val ACCESSORY_TITLE_SIZE = 13.sp

/**
 * W700, which in this app's family draws the Semibold cut (see SFProDisplay):
 * Apple's accessory sets its title in Semibold, and the scale's W600 — the
 * Medium cut — read thin at 13sp.
 */
private val ACCESSORY_TITLE_WEIGHT = FontWeight.W700
private val ACCESSORY_TITLE_TRACKING = 0.15.sp
private val ACCESSORY_ARTIST_SIZE = 12.sp
private val ACCESSORY_ARTIST_TRACKING = 0.35.sp

/** The accessory's artwork, about Apple's 30pt at both densities, and its corner. */
private val ACCESSORY_ART_SIZE = 30.4.dp
private val ACCESSORY_ART_CORNER = 5.7.dp

/** The expanded play glyph: its triangle about Apple's 15pt tall. */
private val EXPANDED_GLYPH_SIZE = 29.dp
