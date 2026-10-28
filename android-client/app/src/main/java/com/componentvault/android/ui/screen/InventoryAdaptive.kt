package com.componentvault.android.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

internal enum class InventoryWidthClass {
    Compact,
    Medium,
    Expanded,
}

internal enum class InventoryFormPresentation {
    FullScreenRoute,
    Dialog,
}

internal enum class InventorySecondaryPanePresentation {
    FullScreenRoute,
    SplitPane,
}

internal data class InventoryLayoutMode(
    val widthClass: InventoryWidthClass,
    val supportsListDetail: Boolean,
    val formPresentation: InventoryFormPresentation,
    val secondaryPanePresentation: InventorySecondaryPanePresentation,
) {
    val usesNavigationRail: Boolean
        get() = widthClass != InventoryWidthClass.Compact

    val showsListDetail: Boolean
        get() = supportsListDetail

    val prefersDialogForms: Boolean
        get() = formPresentation == InventoryFormPresentation.Dialog
}

@Composable
internal fun rememberInventoryLayoutMode(): InventoryLayoutMode {
    val windowSizeClass = currentWindowAdaptiveInfo(
        supportLargeAndXLargeWidth = true,
    ).windowSizeClass

    return remember(windowSizeClass) {
        val widthClass = when {
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> {
                InventoryWidthClass.Expanded
            }
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> {
                InventoryWidthClass.Medium
            }
            else -> InventoryWidthClass.Compact
        }

        InventoryLayoutMode(
            widthClass = widthClass,
            supportsListDetail = widthClass == InventoryWidthClass.Expanded,
            formPresentation = when (widthClass) {
                InventoryWidthClass.Compact,
                InventoryWidthClass.Medium,
                -> InventoryFormPresentation.FullScreenRoute
                InventoryWidthClass.Expanded -> InventoryFormPresentation.Dialog
            },
            secondaryPanePresentation = if (widthClass == InventoryWidthClass.Compact) {
                InventorySecondaryPanePresentation.FullScreenRoute
            } else {
                InventorySecondaryPanePresentation.SplitPane
            },
        )
    }
}

@Composable
internal fun rememberContentPadding(
    contentPadding: PaddingValues,
    horizontal: Dp = 0.dp,
    vertical: Dp = 0.dp,
): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current

    return remember(contentPadding, horizontal, vertical, layoutDirection) {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + horizontal,
            top = contentPadding.calculateTopPadding() + vertical,
            end = contentPadding.calculateEndPadding(layoutDirection) + horizontal,
            bottom = contentPadding.calculateBottomPadding() + vertical,
        )
    }
}
