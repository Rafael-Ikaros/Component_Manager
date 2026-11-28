package com.componentvault.android.data

import android.app.Application
import android.content.Context
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppearancePreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("component_vault_appearance", Context.MODE_PRIVATE)

    @Test fun emptyPreferencesFollowSystemWithTealAccent() {
        assertEquals(AppearancePreferences(), AppearancePreferenceStore(context).read())
    }

    @Test fun everyModeAndAccentSurvivesReopening() {
        val store = AppearancePreferenceStore(context)
        AppearanceMode.entries.forEach { mode ->
            AppearanceAccent.entries.forEach { accent ->
                val expected = AppearancePreferences(mode, accent)
                store.save(expected)
                assertEquals(expected, AppearancePreferenceStore(context).read())
            }
        }
    }

    @Test fun invalidValuesFallBackIndependentlyWithoutDiscardingValidSelection() {
        preferences.edit().putString("mode", "unknown").putString("accent", "Blue").commit()
        assertEquals(AppearancePreferences(accent = AppearanceAccent.Blue), AppearancePreferenceStore(context).read())
        preferences.edit().putString("mode", "Dark").putString("accent", "unknown").commit()
        assertEquals(AppearancePreferences(mode = AppearanceMode.Dark), AppearancePreferenceStore(context).read())
        preferences.edit().putInt("mode", 1).putBoolean("accent", true).commit()
        assertEquals(AppearancePreferences(), AppearancePreferenceStore(context).read())
    }

    @Test fun observerReceivesChangesFromAnotherStoreAndCanBeDisposed() {
        val values = mutableListOf<AppearancePreferences>()
        val observation = AppearancePreferenceStore(context).observe(values::add)
        assertEquals(AppearancePreferences(), values.last())
        val changed = AppearancePreferences(AppearanceMode.Dark, AppearanceAccent.Sand)
        AppearancePreferenceStore(context).save(changed)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(changed, values.last())
        observation.close()
        val count = values.size
        AppearancePreferenceStore(context).save(AppearancePreferences())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, values.size)
    }

    @Test fun savingAppearanceLeavesPersistedConnectionAndImportPreferencesUntouched() {
        val repository = InventoryRepository(context)
        repository.saveSyncConfiguration("https://saved.example", "test-token", false)
        repository.saveAppPreferences(repository.loadAppPreferences().copy(defaultImportMinStock = 8))
        val connection = repository.loadSyncConfiguration()
        val appPreferences = repository.loadAppPreferences()

        AppearancePreferenceStore(context).save(AppearancePreferences(AppearanceMode.Light, AppearanceAccent.Blue))
        assertEquals(connection, repository.loadSyncConfiguration())
        assertEquals(appPreferences, repository.loadAppPreferences())
    }
}
