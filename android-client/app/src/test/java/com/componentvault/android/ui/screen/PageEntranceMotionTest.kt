package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.TextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PageEntranceMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun switchingDuringEntranceKeepsOnlyCurrentPageAndRestoresDraft() {
        val destination = mutableStateOf("inventory")
        val activePages = mutableSetOf<String>()
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            val current = destination.value
            Box(pageEntranceMotion(current)) {
                holder.SaveableStateProvider(current) {
                    DisposableEffect(current) {
                        activePages.add(current)
                        onDispose { activePages.remove(current) }
                    }
                    var draft by rememberSaveable { mutableStateOf("") }
                    TextField(draft, { draft = it }, Modifier.testTag("draft"))
                }
            }
        }
        compose.onNodeWithTag("draft").performTextReplacement("unsaved draft")
        compose.mainClock.autoAdvance = false
        for (target in listOf("settings", "overview", "settings", "inventory")) {
            compose.runOnUiThread {
                destination.value = target
                Snapshot.sendApplyNotifications()
            }
            // Deliver the state change and the next animation frame without waiting for idle.
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread { assertEquals(setOf(target), activePages) }
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("draft").assertTextContains("unsaved draft")
    }
}
