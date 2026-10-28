package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h360dp")
class AdaptiveFormSurfaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shortDialogKeepsToolbarReachableWhileBottomFieldIsFocused() {
        lateinit var strings: ComponentVaultStrings
        compose.setContent {
            strings = runtimeComponentVaultStrings()
            MaterialTheme {
                ProvideComponentVaultStrings(strings) {
                    AdaptiveFormSurface(
                        title = "Form",
                        layoutMode = InventoryLayoutMode(
                            widthClass = InventoryWidthClass.Expanded,
                            supportsListDetail = true,
                            formPresentation = InventoryFormPresentation.Dialog,
                            secondaryPanePresentation = InventorySecondaryPanePresentation.SplitPane,
                        ),
                        onDismiss = {},
                        saveLabel = "Save and print",
                        onSave = {},
                        onSecondarySave = {},
                    ) {
                        items(20) { index ->
                            OutlinedTextField(
                                value = "Draft $index",
                                onValueChange = {},
                                label = { Text("Field $index") },
                                modifier = Modifier.fillMaxWidth().testTag("field_$index"),
                            )
                        }
                    }
                }
            }
        }

        val fields = compose.onNodeWithTag("component_editor_fields")
        fields.performScrollToIndex(19)
        compose.onNodeWithTag("field_19").assertIsDisplayed().performClick()
        compose.onNodeWithText(strings.common.actionSave).assertIsDisplayed()
        compose.onNodeWithText(strings.common.actionCancel).assertIsDisplayed()
        compose.onNodeWithText("Save and print").assertIsDisplayed()
        val bounds = fields.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left <= 760.dp, "Dialog must honor its width limit on a wide viewport")
        assertTrue(bounds.bottom - bounds.top > 0.dp && bounds.bottom - bounds.top < 360.dp,
            "The scrollable body must fit below the toolbar in a short viewport")
    }
}
