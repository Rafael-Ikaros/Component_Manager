package com.componentvault.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.componentvault.android.ui.screen.ComponentVaultApp
import com.componentvault.android.ui.screen.InventoryViewModel
import com.componentvault.android.ui.screen.StartupFailureScreen
import com.componentvault.android.ui.screen.StartupUpdatePrompt
import com.componentvault.android.ui.screen.StartupUpdateViewModel
import com.componentvault.android.ui.screen.startupFailureDetails
import com.componentvault.android.ui.theme.ComponentVaultTheme

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
            ComponentVaultTheme {
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
