package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read string from context and render all Liquid Glass screens without crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AegisBox VPN", appName)

        composeTestRule.waitForIdle()
        // Verify MainScreen & TopSecurityGlassHeader (FlowRow) rendered
        composeTestRule.onNodeWithTag("header_logs_chip").assertExists()

        // Navigate to AppsScreen (also uses FlowRow)
        composeTestRule.onNodeWithTag("nav_tab_apps").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("search_apps_input").assertExists()

        // Navigate to SourcesScreen
        composeTestRule.onNodeWithTag("nav_tab_sources").performClick()
        composeTestRule.waitForIdle()
    }
}
