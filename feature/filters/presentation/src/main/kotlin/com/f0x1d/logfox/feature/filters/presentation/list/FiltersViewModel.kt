package com.f0x1d.logfox.feature.filters.presentation.list

import android.content.Context
import com.f0x1d.logfox.core.tea.BaseStoreViewModel
import com.f0x1d.logfox.feature.apps.picker.api.AppsPickerResultHandler
import com.f0x1d.logfox.feature.apps.picker.api.InstalledApp
import com.f0x1d.logfox.feature.filters.api.domain.GetAllFiltersFlowUseCase
import com.f0x1d.logfox.feature.strings.Strings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
internal class FiltersViewModel @Inject constructor(
    reducer: FiltersReducer,
    effectHandler: FiltersEffectHandler,
    viewStateMapper: FiltersViewStateMapper,
    getAllFiltersFlowUseCase: GetAllFiltersFlowUseCase,
) : BaseStoreViewModel<FiltersViewState, FiltersState, FiltersCommand, FiltersSideEffect>(
    initialState = FiltersState(filters = emptyList()),
    reducer = reducer,
    effectHandlers = listOf(effectHandler),
    viewStateMapper = viewStateMapper,
    initialSideEffects = listOf(FiltersSideEffect.LoadFilters),
),
    AppsPickerResultHandler {

    override val supportsMultiplySelection: Boolean = true

    override val checkedAppPackageNames: Flow<Set<String>> =
        getAllFiltersFlowUseCase().map { filters ->
            filters.mapNotNull { it.packageName }.toSet()
        }

    override fun providePickerTopAppBarTitle(context: Context): String = context.getString(Strings.filter_by_apps)

    override fun onAppChecked(app: InstalledApp, checked: Boolean) {
        send(FiltersCommand.ToggleAppFilter(title = app.title, packageName = app.packageName, checked = checked))
    }

    override fun onAppsChecked(apps: List<InstalledApp>, checked: Boolean) {
        send(FiltersCommand.ToggleMultipleAppsFilter(apps = apps, checked = checked))
    }

    override fun onAppSelected(app: InstalledApp): Boolean {
        send(FiltersCommand.ToggleAppFilter(title = app.title, packageName = app.packageName, checked = true))
        return false
    }
}
