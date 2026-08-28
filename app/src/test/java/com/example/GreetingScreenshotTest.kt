package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.Recording
import com.example.data.SimpleEncryption
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun recording_card_screenshot() {
    val sampleRecording = Recording(
        id = 1,
        title = "Client Call - Project Update",
        contentEncrypted = SimpleEncryption.encrypt("Alice: Hey, how is the project going?\nBob: We are on schedule to launch next week."),
        summaryEncrypted = SimpleEncryption.encrypt("• Project is on schedule for next week.\n• Launch preparations underway.")
    )

    composeTestRule.setContent {
        MyApplicationTheme {
            RecordingCard(
                recording = sampleRecording,
                onPlayAudio = {},
                onDelete = {},
                onAddToCalendar = {},
                onShare = {}
            )
        }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/recording_card.png")
  }
}
