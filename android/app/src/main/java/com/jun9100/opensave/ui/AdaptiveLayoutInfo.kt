package com.jun9100.opensave.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Which shell the app draws (plan §3.3). */
enum class AdaptiveMode {
    /** Portrait phones and handhelds held upright: NavigationBar, single column, full-screen detail. */
    COMPACT,

    /** Odin2-style landscape (833x444dp): NavigationRail, no TopAppBar, list + detail panes. */
    HANDHELD_LANDSCAPE,

    /** Tablets and external displays: same shell as landscape with a wider detail pane. */
    EXPANDED,
}

/**
 * The measured window, not `LocalConfiguration.screenHeightDp`: with edge-to-edge
 * that value no longer matches the space the app can draw in.
 */
data class AdaptiveLayoutInfo(
    val widthDp: Float,
    val heightDp: Float,
) {
    /**
     * Landscape is "wide and wider than tall". The Odin2 reports sw468dp, so a
     * smallest-width >= 600dp test would never pick the two-pane layout there.
     */
    val isLandscape: Boolean
        get() = widthDp >= LANDSCAPE_MIN_WIDTH_DP && widthDp > heightDp

    val mode: AdaptiveMode
        get() = when {
            !isLandscape -> AdaptiveMode.COMPACT
            widthDp >= EXPANDED_MIN_WIDTH_DP && heightDp >= EXPANDED_MIN_HEIGHT_DP -> AdaptiveMode.EXPANDED
            else -> AdaptiveMode.HANDHELD_LANDSCAPE
        }

    /** Width of the game list next to the detail pane. */
    val listPaneWidthDp: Float
        get() = if (mode == AdaptiveMode.EXPANDED) 400f else 360f

    companion object {
        const val LANDSCAPE_MIN_WIDTH_DP = 600f
        const val EXPANDED_MIN_WIDTH_DP = 840f
        const val EXPANDED_MIN_HEIGHT_DP = 600f

        /** Odin2 portrait, used until the first measurement. */
        val Default = AdaptiveLayoutInfo(widthDp = 468f, heightDp = 809f)
    }
}

val LocalAdaptiveLayout = staticCompositionLocalOf { AdaptiveLayoutInfo.Default }

/** Measures the space it is given and provides it to [content] through [LocalAdaptiveLayout]. */
@Composable
fun ProvideAdaptiveLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val info = AdaptiveLayoutInfo(widthDp = maxWidth.value, heightDp = maxHeight.value)
        CompositionLocalProvider(LocalAdaptiveLayout provides info) {
            content()
        }
    }
}
