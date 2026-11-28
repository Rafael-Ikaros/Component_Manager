package com.componentvault.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.componentvault.android.data.AppearanceAccent
import com.componentvault.android.data.AppearanceMode
import com.componentvault.android.data.AppearancePreferences

val LocalAppearance = staticCompositionLocalOf { AppearancePreferences() }
val LocalOnAppearanceChange = staticCompositionLocalOf<(AppearancePreferences) -> Unit> { {} }

data class VaultWarningColors(val foreground: Color, val container: Color)

private val LocalVaultWarningColors = staticCompositionLocalOf<VaultWarningColors?> { null }

/** Also works in previews and tests wrapped in bare MaterialTheme. */
object LocalVaultWarning {
    val current: VaultWarningColors
        @Composable
        @ReadOnlyComposable
        get() = LocalVaultWarningColors.current
            ?: vaultWarningColors(MaterialTheme.colorScheme.surface.luminance() < 0.5f)
}

internal fun vaultWarningColors(darkTheme: Boolean): VaultWarningColors = if (darkTheme) {
    VaultWarningColors(Color(0xFFEEC08B), Color(0xFF493C2A))
} else {
    VaultWarningColors(VaultWarning, VaultWarningContainer)
}

@Composable
@ReadOnlyComposable
fun AppearancePreferences.isDarkTheme(): Boolean = when (mode) {
    AppearanceMode.System -> isSystemInDarkTheme()
    AppearanceMode.Light -> false
    AppearanceMode.Dark -> true
}

internal fun vaultColorScheme(darkTheme: Boolean, accent: AppearanceAccent): ColorScheme {
    val colors = vaultAccentColors(accent, darkTheme)
    return if (darkTheme) {
        darkColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.container,
            onPrimaryContainer = colors.onContainer,
            inversePrimary = vaultAccentColors(accent, false).primary,
            secondary = Color(0xFFB9C8AE),
            onSecondary = Color(0xFF25341F),
            secondaryContainer = Color(0xFF3C4B34),
            onSecondaryContainer = Color(0xFFE0E8D8),
            tertiary = Color(0xFFD8C5A0),
            onTertiary = Color(0xFF3E3017),
            tertiaryContainer = Color(0xFF574627),
            onTertiaryContainer = Color(0xFFEFE3CC),
            background = Color(0xFF20221F),
            onBackground = Color(0xFFECEEE5),
            surface = Color(0xFF2B2E28),
            onSurface = Color(0xFFECEEE5),
            surfaceVariant = Color(0xFF343830),
            onSurfaceVariant = Color(0xFFB2B9AA),
            surfaceDim = Color(0xFF20221F),
            surfaceBright = Color(0xFF41453C),
            surfaceContainerLowest = Color(0xFF1B1E19),
            surfaceContainerLow = Color(0xFF272A23),
            surfaceContainer = Color(0xFF2B2E28),
            surfaceContainerHigh = Color(0xFF343830),
            surfaceContainerHighest = Color(0xFF3C4236),
            surfaceTint = colors.primary,
            inverseSurface = Color(0xFFECEEE5),
            inverseOnSurface = Color(0xFF262B24),
            outline = Color(0xFF8D9683),
            outlineVariant = Color(0xFF3C4236),
            error = Color(0xFFF1AAA0),
            onError = Color(0xFF601710),
            errorContainer = Color(0xFF7D2C23),
            onErrorContainer = Color(0xFFFFDAD4),
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            onPrimary = colors.onPrimary,
            primaryContainer = colors.container,
            onPrimaryContainer = colors.onContainer,
            inversePrimary = vaultAccentColors(accent, true).primary,
            secondary = VaultSecondary,
            onSecondary = VaultOnSecondary,
            secondaryContainer = VaultSecondaryContainer,
            onSecondaryContainer = VaultOnSecondaryContainer,
            tertiary = VaultTertiary,
            onTertiary = Color.White,
            tertiaryContainer = VaultTertiaryContainer,
            onTertiaryContainer = Color(0xFF4A391D),
            background = VaultBackground,
            onBackground = Color(0xFF262B24),
            surface = VaultSurface,
            onSurface = Color(0xFF262B24),
            surfaceVariant = VaultSurfaceVariant,
            // Slightly deepen the planned secondary text to keep 4.5:1 on raised surfaces.
            onSurfaceVariant = Color(0xFF5F6859),
            surfaceDim = Color(0xFFDCE0D5),
            surfaceBright = VaultSurface,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF7F7F0),
            surfaceContainer = Color(0xFFEFF1E8),
            surfaceContainerHigh = VaultSurfaceVariant,
            surfaceContainerHighest = Color(0xFFE1E5D9),
            surfaceTint = colors.primary,
            inverseSurface = Color(0xFF30362C),
            inverseOnSurface = Color(0xFFF3F3ED),
            outline = VaultOutline,
            outlineVariant = Color(0xFFDCE0D5),
            error = VaultError,
            onError = Color.White,
            errorContainer = VaultErrorContainer,
            onErrorContainer = Color(0xFF75231B),
            scrim = Color.Black,
        )
    }
}

@Composable
fun ComponentVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AppearanceAccent = AppearanceAccent.Teal,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalVaultWarningColors provides vaultWarningColors(darkTheme)) {
        MaterialTheme(
            colorScheme = vaultColorScheme(darkTheme, accent),
            typography = VaultTypography,
            content = content,
        )
    }
}
