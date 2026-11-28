package com.componentvault.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.componentvault.android.data.AppearanceAccent

val VaultPrimary = Color(0xFF426750)
val VaultOnPrimary = Color.White
val VaultPrimaryContainer = Color(0xFFD6E8D8)
val VaultOnPrimaryContainer = Color(0xFF263D2D)
val VaultSecondary = Color(0xFF56634F)
val VaultOnSecondary = Color.White
val VaultSecondaryContainer = Color(0xFFE0E8D8)
val VaultOnSecondaryContainer = Color(0xFF303C2A)
val VaultTertiary = Color(0xFF77613B)
val VaultTertiaryContainer = Color(0xFFEFE3CC)
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
            VaultAccentColors(Color(0xFFA7CBB4), Color(0xFF153823), Color(0xFF2D4F3B), Color(0xFFD6E8D8))
        } else {
            VaultAccentColors(VaultPrimary, VaultOnPrimary, VaultPrimaryContainer, VaultOnPrimaryContainer)
        }
        AppearanceAccent.Blue -> if (darkTheme) {
            VaultAccentColors(Color(0xFFB2C8D9), Color(0xFF1C3447), Color(0xFF344D61), Color(0xFFDCE9F4))
        } else {
            VaultAccentColors(Color(0xFF4C687E), Color.White, Color(0xFFDCE9F4), Color(0xFF253E52))
        }
        AppearanceAccent.Sand -> if (darkTheme) {
            VaultAccentColors(Color(0xFFD8C5A0), Color(0xFF3E3017), Color(0xFF574627), Color(0xFFEFE3CC))
        } else {
            VaultAccentColors(Color(0xFF77613B), Color.White, Color(0xFFEFE3CC), Color(0xFF4A391D))
        }
    }
