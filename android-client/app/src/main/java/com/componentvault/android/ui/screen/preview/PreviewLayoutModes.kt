package com.componentvault.android.ui.screen.preview

import com.componentvault.android.ui.screen.InventoryFormPresentation
import com.componentvault.android.ui.screen.InventoryLayoutMode
import com.componentvault.android.ui.screen.InventorySecondaryPanePresentation
import com.componentvault.android.ui.screen.InventoryWidthClass

internal val CompactPreviewLayout = InventoryLayoutMode(
    widthClass = InventoryWidthClass.Compact,
    supportsListDetail = false,
    formPresentation = InventoryFormPresentation.FullScreenRoute,
    secondaryPanePresentation = InventorySecondaryPanePresentation.FullScreenRoute,
)

internal val MediumPreviewLayout = InventoryLayoutMode(
    widthClass = InventoryWidthClass.Medium,
    supportsListDetail = false,
    formPresentation = InventoryFormPresentation.FullScreenRoute,
    secondaryPanePresentation = InventorySecondaryPanePresentation.SplitPane,
)

internal val ExpandedPreviewLayout = InventoryLayoutMode(
    widthClass = InventoryWidthClass.Expanded,
    supportsListDetail = true,
    formPresentation = InventoryFormPresentation.Dialog,
    secondaryPanePresentation = InventorySecondaryPanePresentation.SplitPane,
)

internal val DialogPreviewLayout = ExpandedPreviewLayout
