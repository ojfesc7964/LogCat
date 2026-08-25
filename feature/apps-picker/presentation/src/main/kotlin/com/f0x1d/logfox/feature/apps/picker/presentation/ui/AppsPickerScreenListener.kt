package com.f0x1d.logfox.feature.apps.picker.presentation.ui

import com.f0x1d.logfox.feature.apps.picker.api.AppFilterCategory
import com.f0x1d.logfox.feature.apps.picker.api.InstalledApp

data class AppsPickerScreenListener(
    val onBackClicked: () -> Unit,
    val onAppClicked: (InstalledApp) -> Unit,
    val onAppChecked: (InstalledApp, Boolean) -> Unit,
    val onSearchActiveChanged: (Boolean) -> Unit,
    val onQueryChanged: (String) -> Unit,
    val onCategoryChanged: (AppFilterCategory) -> Unit,
    val onSelectAllClicked: (List<InstalledApp>) -> Unit,
    val onClearAllClicked: (List<InstalledApp>) -> Unit,
)

internal val MockAppsPickerScreenListener = AppsPickerScreenListener(
    onBackClicked = { },
    onAppClicked = { },
    onAppChecked = { _, _ -> },
    onSearchActiveChanged = { },
    onQueryChanged = { },
    onCategoryChanged = { },
    onSelectAllClicked = { },
    onClearAllClicked = { },
)
