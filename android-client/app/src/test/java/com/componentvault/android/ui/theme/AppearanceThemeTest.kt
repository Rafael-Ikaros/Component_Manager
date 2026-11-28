package com.componentvault.android.ui.theme

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.WindowCompat
import com.componentvault.android.applyAppearanceToSystemBars
import com.componentvault.android.data.AppearanceAccent
import com.componentvault.android.data.AppearanceMode
import com.componentvault.android.data.AppearancePreferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppearanceThemeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun palettesHaveReadableTextAndControlOutlines() {
        for (dark in listOf(false, true)) {
            for (accent in AppearanceAccent.entries) {
                val scheme = vaultColorScheme(dark, accent)
                val pairs = listOf(
                    scheme.onPrimary to scheme.primary,
                    scheme.onPrimaryContainer to scheme.primaryContainer,
                    scheme.onSecondary to scheme.secondary,
                    scheme.onSecondaryContainer to scheme.secondaryContainer,
                    scheme.onTertiary to scheme.tertiary,
                    scheme.onTertiaryContainer to scheme.tertiaryContainer,
                    scheme.onBackground to scheme.background,
                    scheme.onSurface to scheme.surface,
                    scheme.onSurfaceVariant to scheme.surfaceVariant,
                    scheme.onSurfaceVariant to scheme.surfaceContainerHighest,
                    scheme.inverseOnSurface to scheme.inverseSurface,
                    scheme.onError to scheme.error,
                    scheme.onErrorContainer to scheme.errorContainer,
                )
                pairs.forEach { (text, surface) ->
                    assertTrue(contrast(text, surface) >= 4.5, "$dark/$accent: $text on $surface")
                }
                assertTrue(contrast(scheme.outline, scheme.surface) >= 3.0)
                assertTrue(contrast(scheme.outline, scheme.surfaceContainerHighest) >= 3.0)
                val warning = vaultWarningColors(dark)
                assertTrue(contrast(warning.foreground, warning.container) >= 4.5)
            }
        }
    }

    @Test fun warningFallbackFollowsBareMaterialTheme() {
        val dark = mutableStateOf(false)
        var warning: VaultWarningColors? = null
        composeRule.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                warning = LocalVaultWarning.current
            }
        }
        composeRule.runOnIdle {
            assertEquals(vaultWarningColors(false), warning)
            dark.value = true
        }
        composeRule.runOnIdle { assertEquals(vaultWarningColors(true), warning) }
    }

    @Test fun systemModeFollowsConfigurationWhileExplicitModesOverrideIt() {
        val mode = mutableStateOf(AppearanceMode.System)
        val night = mutableStateOf(false)
        var darkTheme: Boolean? = null
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                darkTheme = AppearancePreferences(mode = mode.value).isDarkTheme()
            }
        }
        composeRule.runOnIdle {
            assertEquals(false, darkTheme)
            night.value = true
        }
        composeRule.runOnIdle {
            assertEquals(true, darkTheme)
            mode.value = AppearanceMode.Light
        }
        composeRule.runOnIdle {
            assertEquals(false, darkTheme)
            night.value = false
            mode.value = AppearanceMode.Dark
        }
        composeRule.runOnIdle { assertEquals(true, darkTheme) }
    }

    @Test fun themeReactsToAccentAndDarkModeAndKeepsWarningSemantic() {
        val accent = mutableStateOf(AppearanceAccent.Teal)
        val dark = mutableStateOf(false)
        var primary = Color.Unspecified
        var warning: VaultWarningColors? = null
        composeRule.setContent {
            ComponentVaultTheme(darkTheme = dark.value, accent = accent.value) {
                primary = MaterialTheme.colorScheme.primary
                warning = LocalVaultWarning.current
            }
        }
        composeRule.runOnIdle {
            assertEquals(Color(0xFF426750), primary)
            assertEquals(vaultWarningColors(false), warning)
            accent.value = AppearanceAccent.Blue
        }
        composeRule.runOnIdle {
            assertEquals(Color(0xFF4C687E), primary)
            dark.value = true
        }
        composeRule.runOnIdle {
            assertEquals(Color(0xFFB2C8D9), primary)
            assertEquals(vaultWarningColors(true), warning)
            accent.value = AppearanceAccent.Sand
        }
        composeRule.runOnIdle { assertEquals(Color(0xFFD8C5A0), primary) }
    }

    @Test
    @Config(sdk = [34])
    @Suppress("DEPRECATION")
    fun api34SystemBarBackgroundsAndIconsFollowAppearanceWithoutChangingImeOrLayout() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window = activity.get().window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val layoutMask = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            val originalLayout = window.decorView.systemUiVisibility and layoutMask
            val originalFlags = window.attributes.flags
            val originalSoftInput = window.attributes.softInputMode
            for (dark in listOf(false, true, false)) {
                val background = vaultColorScheme(dark, AppearanceAccent.Teal).background.toArgb()
                applyAppearanceToSystemBars(window, dark, background)
                assertEquals(background, window.statusBarColor)
                assertEquals(background, window.navigationBarColor)
                assertEquals(background, (window.decorView.background as ColorDrawable).color)
                val insets = WindowCompat.getInsetsController(window, window.decorView)
                assertEquals(!dark, insets.isAppearanceLightStatusBars)
                assertEquals(!dark, insets.isAppearanceLightNavigationBars)
                assertEquals(originalLayout, window.decorView.systemUiVisibility and layoutMask)
                assertEquals(originalFlags, window.attributes.flags)
                assertEquals(originalSoftInput, window.attributes.softInputMode)
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
