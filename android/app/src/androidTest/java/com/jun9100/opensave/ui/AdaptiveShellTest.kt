package com.jun9100.opensave.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.ui.library.GameLibraryContent
import com.jun9100.opensave.ui.library.LibraryActions
import com.jun9100.opensave.ui.preview.PreviewData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell with fake library data at the measured Odin2 window sizes; no
 * ViewModel, storage or su. Rotation with a real Activity is checked on device.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveShellTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landscapeHasRailToolbarAndDetailPane() {
        compose.setContent { Shell(widthDp = 833, heightDp = 444) }
        compose.onNodeWithTag(NAVIGATION_RAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LIBRARY_TOOLBAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GAME_DETAIL_TAG).assertIsDisplayed()
    }

    @Test
    fun portraitHasToolbarButNoRailOrDetail() {
        compose.setContent { Shell(widthDp = 468, heightDp = 809) }
        compose.onNodeWithTag(NAVIGATION_RAIL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(LIBRARY_TOOLBAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GAME_DETAIL_TAG).assertDoesNotExist()
    }

    @Composable
    private fun Shell(widthDp: Int, heightDp: Int) {
        OpenSaveTheme {
            Box(modifier = Modifier.size(widthDp.dp, heightDp.dp)) {
                ProvideAdaptiveLayout {
                    AdaptiveShellFrame(
                        page = OpenSavePage.LIBRARY,
                        busy = false,
                        progress = null,
                        gameDetailOpen = false,
                        onShowPage = {},
                        onRefresh = {},
                        onCloseDetail = {},
                    ) { contentModifier ->
                        GameLibraryContent(
                            state = PreviewData.libraryState,
                            actions = LibraryActions(),
                            listState = rememberLazyListState(),
                            coverRepository = null,
                            metadataRepository = null,
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }
}
