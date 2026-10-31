package com.jun9100.opensave.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jun9100.opensave.GameLibrary
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.OpenSaveTheme
import com.jun9100.opensave.RestoreRequest
import com.jun9100.opensave.StorageMode
import com.jun9100.opensave.ui.dialogs.RestoreConfirmDialog
import com.jun9100.opensave.ui.input.FocusWatch
import com.jun9100.opensave.ui.input.HandheldCommand
import com.jun9100.opensave.ui.input.HandheldInput
import com.jun9100.opensave.ui.input.LocalShellFocus
import com.jun9100.opensave.ui.input.ProvideHandheldInput
import com.jun9100.opensave.ui.input.watchFocus
import com.jun9100.opensave.ui.library.GAME_LIST_TAG
import com.jun9100.opensave.ui.library.GameLibraryContent
import com.jun9100.opensave.ui.library.LibraryActions
import com.jun9100.opensave.ui.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pad navigation with fake library data at the Odin2 landscape size (plan
 * §7 phase 2 automation). Keys are sent through Compose in keyboard input
 * mode; the Activity-level X/Y/Start mapping is driven via [HandheldInput.send]
 * because `performKeyInput` bypasses `MainActivity.dispatchKeyEvent`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HandheldNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private val input = HandheldInput()
    private lateinit var inputModeManager: InputModeManager
    private val selected = mutableListOf<String>()

    @Test
    fun libraryFocusesARowAndDpadKeepsASingleFocusStop() {
        compose.setContent { Shell() }
        enterKeyboardMode()

        compose.onNode(isFocused() and hasAnyAncestor(hasTestTag(GAME_LIST_TAG))).assertExists()
        repeat(3) {
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
            // One row = one focus stop: the focus is still a row of the list, never a child control.
            compose.onNode(isFocused() and hasAnyAncestor(hasTestTag(GAME_LIST_TAG))).assertExists()
        }
    }

    @Test
    fun dpadCenterActivatesTheFocusedRow() {
        compose.setContent { Shell() }
        enterKeyboardMode()

        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        val firstKey = GameLibrary.sourceGames(PreviewData.libraryState.sourceScans).first().libraryKey
        assertEquals(firstKey, selected.last())
    }

    @Test
    fun xOpensTheGameMenuWithFocusInside() {
        compose.setContent { Shell() }
        enterKeyboardMode()

        compose.runOnIdle { input.send(HandheldCommand.GAME_MENU) }
        compose.waitForIdle()
        compose.onNodeWithTag(GAME_ACTION_MENU_TAG).assertExists()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(GAME_ACTION_MENU_TAG))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("备份这款游戏").assertIsFocused()
    }

    @Test
    fun yOpensTheCommandPanelWithFocusInside() {
        compose.setContent { Shell() }
        enterKeyboardMode()

        compose.runOnIdle { input.send(HandheldCommand.COMMAND_PANEL) }
        compose.waitForIdle()
        compose.onNodeWithTag(COMMAND_DIALOG_TAG).assertExists()
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag(COMMAND_DIALOG_TAG))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("扫描游戏").assertIsFocused()
    }

    @Test
    fun hintsShowOnlyWithThePad() {
        compose.setContent { Shell() }
        compose.onAllNodesWithTag(BUTTON_HINTS_TAG).assertCountEquals(0)
        enterKeyboardMode()
        compose.onNodeWithTag(BUTTON_HINTS_TAG).assertExists()
    }

    @Test
    fun destructiveConfirmStartsOnCancel() {
        compose.setContent {
            OpenSaveTheme {
                RestoreConfirmDialog(
                    request = RestoreRequest(game = PreviewData.switchGame),
                    storageMode = StorageMode.ROOT,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("取消").assertIsFocused()
    }

    private fun enterKeyboardMode() {
        compose.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
    }

    @Composable
    private fun Shell() {
        inputModeManager = LocalInputModeManager.current
        val shellFocus = remember { FocusWatch() }
        OpenSaveTheme {
            ProvideHandheldInput(input) {
                CompositionLocalProvider(LocalShellFocus provides shellFocus) {
                    Box(modifier = Modifier.size(833.dp, 444.dp).watchFocus(shellFocus)) {
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
                                    // Nothing selected: the first row gets the initial focus.
                                    state = PreviewData.libraryState.copy(librarySelection = emptySet()),
                                    actions = LibraryActions(onSelectGame = { selected += it }),
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
    }
}
