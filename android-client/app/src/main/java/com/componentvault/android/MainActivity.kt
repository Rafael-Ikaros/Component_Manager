package com.componentvault.android

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.componentvault.android.data.AppearancePreferenceStore
import com.componentvault.android.ui.screen.ComponentVaultApp
import com.componentvault.android.ui.screen.InventoryViewModel
import com.componentvault.android.ui.screen.StartupFailureScreen
import com.componentvault.android.ui.screen.StartupUpdatePrompt
import com.componentvault.android.ui.screen.StartupUpdateViewModel
import com.componentvault.android.ui.screen.startupFailureDetails
import com.componentvault.android.ui.theme.ComponentVaultTheme
import com.componentvault.android.ui.theme.LocalAppearance
import com.componentvault.android.ui.theme.LocalOnAppearanceChange
import com.componentvault.android.ui.theme.isDarkTheme

class MainActivity : AppCompatActivity() {
    private val startupUpdateViewModel: StartupUpdateViewModel by viewModels()
    private val inventoryViewModel: InventoryViewModel by viewModels {
        InventoryViewModel.factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLocaleManager.ensureInitialized(applicationContext)
        super.onCreate(savedInstanceState)

        setContent {
            var retryAttempt by remember { mutableStateOf(0) }
            val initialization = remember(retryAttempt) { runCatching { inventoryViewModel } }
            val appearanceStore = remember { AppearancePreferenceStore(applicationContext) }
            var appearance by remember { mutableStateOf(appearanceStore.read()) }
            DisposableEffect(appearanceStore) {
                val observation = appearanceStore.observe { appearance = it }
                onDispose { observation.close() }
            }
            val darkTheme = appearance.isDarkTheme()
            CompositionLocalProvider(
                LocalAppearance provides appearance,
                LocalOnAppearanceChange provides { value ->
                    appearanceStore.save(value)
                    appearance = value
                },
            ) {
                ComponentVaultTheme(darkTheme = darkTheme, accent = appearance.accent) {
                    val background = MaterialTheme.colorScheme.background.toArgb()
                    SideEffect { applyAppearanceToSystemBars(window, darkTheme, background) }
                    val model = initialization.getOrNull()
                    val failure = initialization.exceptionOrNull()?.let(::startupFailureDetails)
                        ?: model?.startupFailure
                    if (failure != null) {
                        StartupFailureScreen(failure) {
                            if (model == null) retryAttempt++ else model.refresh()
                        }
                    } else if (model != null) {
                        ComponentVaultApp(viewModel = model)
                        StartupUpdatePrompt(
                            release = startupUpdateViewModel.availableRelease,
                            onDismiss = startupUpdateViewModel::dismiss,
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
internal fun applyAppearanceToSystemBars(window: Window, darkTheme: Boolean, background: Int) {
    // Also backs transparent system bars on API 35+, where edge-to-edge is enforced.
    window.setBackgroundDrawable(ColorDrawable(background))
    if (Build.VERSION.SDK_INT < 35) {
        // These releases still fit content inside the system bars. Give the bars their
        // own app-theme background rather than relying on the XML DayNight window.
        window.statusBarColor = background
        window.navigationBarColor = background
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}
