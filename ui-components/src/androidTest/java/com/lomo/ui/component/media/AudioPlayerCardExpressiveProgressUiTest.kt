package com.lomo.ui.component.media

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lomo.ui.media.AudioPlayerController
import com.lomo.ui.media.LocalAudioPlayerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/*
 * Test Contract:
 * - Unit under test: AudioPlayerCard media playback progress UI.
 * - Behavior focus: the voice-memo card must render a display-only expressive playback progress
 *   track and show both elapsed and total duration.
 * - Observable outcomes: visible timestamp text in `mm:ss / mm:ss` format, tagged playback-track
 *   node presence, and absence of seek semantics on that track.
 * - Red phase: Fails before the fix because the card still renders a seekable slider with
 *   `SetProgress` semantics instead of a display-only expressive progress indicator.
 * - Excludes: ExoPlayer decoding, waveform animation visuals, and full-app navigation wiring.
 */
@RunWith(AndroidJUnit4::class)
class AudioPlayerCardExpressiveProgressUiTest {
    private companion object {
        const val AUDIO_URI = "voice/sample.m4a"
        const val PLAYBACK_PROGRESS_TAG = "audio_playback_progress"
        val HasNoSetProgressAction =
            SemanticsMatcher("does not expose SetProgress") {
                !it.config.contains(SemanticsActions.SetProgress)
            }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun currentItem_showsElapsedAndTotalDuration() {
        val controller =
            FakeAudioPlayerController(
                currentUri = AUDIO_URI,
                playing = true,
                positionMs = 15_000L,
                durationMs = 60_000L,
            )

        setAudioPlayerCardContent(controller)

        assertTrue(composeRule.onAllNodesWithText("00:15 / 01:00").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun playbackTrack_isDisplayOnly_andDoesNotExposeSeekSemantics() {
        val controller =
            FakeAudioPlayerController(
                currentUri = AUDIO_URI,
                playing = true,
                positionMs = 15_000L,
                durationMs = 60_000L,
            )

        setAudioPlayerCardContent(controller)

        assertTrue(composeRule.onAllNodesWithTag(PLAYBACK_PROGRESS_TAG).fetchSemanticsNodes().isNotEmpty())
        composeRule
            .onNodeWithTag(PLAYBACK_PROGRESS_TAG)
            .assert(HasNoSetProgressAction)
    }

    private fun setAudioPlayerCardContent(controller: FakeAudioPlayerController) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAudioPlayerManager provides controller) {
                MaterialTheme {
                    AudioPlayerCard(relativeFilePath = AUDIO_URI)
                }
            }
        }
    }
}

private class FakeAudioPlayerController(
    currentUri: String?,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
) : AudioPlayerController {
    private val currentUriState = MutableStateFlow(currentUri)
    override val currentPlayingUri: StateFlow<String?> = currentUriState.asStateFlow()

    private val isPlayingState = MutableStateFlow(playing)
    override val isPlaying: StateFlow<Boolean> = isPlayingState.asStateFlow()

    private val playbackPositionState = MutableStateFlow(positionMs)
    override val playbackPosition: StateFlow<Long> = playbackPositionState.asStateFlow()

    private val durationState = MutableStateFlow(durationMs)
    override val duration: StateFlow<Long> = durationState.asStateFlow()

    override fun play(uri: String) = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun pause() = Unit

    override fun stop() = Unit

    override fun release() = Unit

    override fun updateProgress() = Unit
}
