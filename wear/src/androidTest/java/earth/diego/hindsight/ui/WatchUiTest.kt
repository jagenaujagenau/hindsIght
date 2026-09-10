package earth.diego.hindsight.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.AppScaffold
import earth.diego.hindsight.audio.CaptureState
import earth.diego.hindsight.data.Sensitivity
import earth.diego.hindsight.data.WaveStyle
import earth.diego.hindsight.service.SaveState
import earth.diego.hindsight.sync.SyncState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose instrumentation; no microphone or paired phone required. */
@RunWith(AndroidJUnit4::class)
class WatchUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun permanentDenialOffersSettingsInsteadOfAnotherPermissionRequest() {
        var opened = false
        compose.setContent {
            HindsightTheme { AppScaffold { PermissionScreen(true, onRequest = { error("Must not request again") }, onSettings = { opened = true }) } }
        }
        compose.onNodeWithText("Allow microphone").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Open settings"))
        compose.onNodeWithText("Open settings").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun saveAndStopIsDistinctFromDiscard() {
        var saved = false
        var discarded = false
        compose.setContent {
            HindsightTheme { AppScaffold {
                StopOptionsScreen(onSaveStop = { saved = true }, onDiscard = { discarded = true }, onCancel = {})
            } }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save & stop"))
        compose.onNodeWithText("Save & stop").performClick()
        compose.runOnIdle { assertTrue(saved); assertFalse(discarded) }
    }

    @Test fun experiencedUsersKeepAccessibleSaveWithoutPersistentHints() {
        var saved = false
        compose.setContent {
            HindsightTheme { AppScaffold {
                WaveScreen(CaptureState(recording = true), SaveState.Idle, 0, true,
                    WaveStyle.LINE, Color.Green, Sensitivity.MEDIUM,
                    onSave = { saved = true }, onStop = {}, onResume = {}, showHints = false)
            } }
        }
        compose.onNodeWithText("Tap to save").assertDoesNotExist()
        compose.onNodeWithText("Hold to stop · swipe for settings").assertDoesNotExist()
        compose.onNode(hasClickAction() and hasText("Listening")).performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun confirmationExpiresButPendingUploadsRemainVisible() {
        val save = mutableStateOf<SaveState>(SaveState.Idle)
        compose.setContent {
            HindsightTheme { AppScaffold {
                WaveScreen(CaptureState(recording = true), save.value, 1, true,
                    WaveStyle.BARS, Color.Green, Sensitivity.MEDIUM, {}, {}, {},
                    showHints = false, sync = SyncState.PhoneUnavailable)
            } }
        }
        compose.runOnIdle {
            save.value = SaveState.Saved("test.m4a", 1_000, 100, completedAtMs = System.currentTimeMillis())
        }
        compose.waitUntil(2_000) { compose.onAllNodesWithText("Saved 0:01 · waiting for phone").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(SAVE_CONFIRMATION_MS + 100)
        compose.waitUntil(7_000) { compose.onAllNodesWithText("Saved 0:01 · waiting for phone").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("1 pending · phone unavailable").assertIsDisplayed()
    }

    @Test fun enlargedTextOnSmallWatchCanScrollToSafetyMessage() {
        val warning = "Not enough free space. Sync clips or free storage, then retry. Saved clips were kept."
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                HindsightTheme { Box(Modifier.size(192.dp)) { AppScaffold {
                    WaveScreen(CaptureState(recording = true), SaveState.Failed(warning), 1, true,
                        WaveStyle.DOTS, Color.Green, Sensitivity.MEDIUM, {}, {}, {}, showHints = false)
                } } }
            }
        }
        compose.onNodeWithText(warning, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
}
