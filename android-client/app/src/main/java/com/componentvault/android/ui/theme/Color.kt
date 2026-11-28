package com.componentvault.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.componentvault.android.data.AppearanceAccent

val VaultPrimary = Color(0xFF426750)
val VaultOnPrimary = Color.White
val VaultPrimaryContainer = Color(0xFFDCE9DE)
val VaultOnPrimaryContainer = Color(0xFF263D2D)
val VaultSecondary = Color(0xFF56634F)
val VaultOnSecondary = Color.White
val VaultSecondaryContainer = Color(0xFFE0E8D8)
val VaultOnSecondaryContainer = Color(0xFF303C2A)
val VaultTertiary = Color(0xFF77613B)
val VaultTertiaryContainer = Color(0xFFEEE4D0)
val VaultBackground = Color(0xFFF3F3ED)
val VaultSurface = Color(0xFFFDFDF8)
val VaultSurfaceVariant = Color(0xFFE7E9E0)
val VaultOutline = Color(0xFF747D6D)
val VaultError = Color(0xFFA8382E)
val VaultErrorContainer = Color(0xFFFFDAD4)
// Light-theme compatibility tokens. UI should use LocalVaultWarning for both themes.
val VaultWarning = Color(0xFF885019)
val VaultWarningContainer = Color(0xFFF5E5CF)

internal data class VaultAccentColors(
    val primary: Color,
    val onPrimary: Color,
    val container: Color,
    val onContainer: Color,
)

internal fun vaultAccentColors(accent: AppearanceAccent, darkTheme: Boolean): VaultAccentColors =
    when (accent) {
        AppearanceAccent.Teal -> if (darkTheme) {
            VaultAccentColors(Color(0xFFA7CBB4), Color(0xFF203A2A), Color(0xFF354A3C), Color(0xFFDCE9DE))
        } else {
            VaultAccentColors(VaultPrimary, VaultOnPrimary, VaultPrimaryContainer, VaultOnPrimaryContainer)
        }
        AppearanceAccent.Blue -> if (darkTheme) {
            VaultAccentColors(Color(0xFFB2C8D9), Color(0xFF223746), Color(0xFF344651), Color(0xFFDDE8EF))
        } else {
            VaultAccentColors(Color(0xFF4C687E), Color.White, Color(0xFFDDE8EF), Color(0xFF253E52))
        }
        AppearanceAccent.Sand -> if (darkTheme) {
            VaultAccentColors(Color(0xFFD8C5A0), Color(0xFF3B311F), Color(0xFF494132), Color(0xFFEEE4D0))
        } else {
            VaultAccentColors(Color(0xFF77613B), Color.White, Color(0xFFEEE4D0), Color(0xFF4A391D))
        }
    }
