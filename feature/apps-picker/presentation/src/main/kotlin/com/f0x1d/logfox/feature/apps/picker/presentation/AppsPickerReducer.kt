package com.f0x1d.logfox.feature.apps.picker.presentation

import com.f0x1d.logfox.core.tea.ReduceResult
import com.f0x1d.logfox.core.tea.Reducer
import com.f0x1d.logfox.core.tea.noSideEffects
import com.f0x1d.logfox.core.tea.withSideEffects
import javax.inject.Inject

internal class AppsPickerReducer @Inject constructor() : Reducer<AppsPickerState, AppsPickerCommand, AppsPickerSideEffect> {

    override fun reduce(
        state: AppsPickerState,
        command: AppsPickerCommand,
    ): ReduceResult<AppsPickerState, AppsPickerSideEffect> = when (command) {
        is AppsPickerCommand.BackPressed -> {
            if (state.searchActive) {
                state.copy(searchActive = false).noSideEffects()
            } else {
                state.withSideEffects(AppsPickerSideEffect.PopBackStack)
            }
        }

        is AppsPickerCommand.SearchActiveChanged -> {
            state.copy(searchActive = command.active).noSideEffects()
        }

        is AppsPickerCommand.QueryChanged -> {
            state.copy(query = command.query)
                .withSideEffects(
                    AppsPickerSideEffect.FilterApps(
                        query = command.query,
                        apps = state.apps,
                        category = state.selectedCategory,
                    ),
                )
        }

        is AppsPickerCommand.CategoryChanged -> {
            state.copy(selectedCategory = command.category)
                .withSideEffects(
                    AppsPickerSideEffect.FilterApps(
                        query = state.query,
                        apps = state.apps,
                        category = command.category,
                    ),
                )
        }

        is AppsPickerCommand.AppsLoaded -> {
            state.copy(
                apps = command.apps,
                isLoading = false,
            ).withSideEffects(
                AppsPickerSideEffect.FilterApps(
                    query = state.query,
                    apps = command.apps,
                    category = state.selectedCategory,
                ),
            )
        }

        is AppsPickerCommand.SearchedAppsUpdated -> {
            state.copy(searchedApps = command.apps).noSideEffects()
        }

        is AppsPickerCommand.AppClicked -> {
            state.withSideEffects(AppsPickerSideEffect.HandleAppSelection(command.app))
        }

        is AppsPickerCommand.SelectAllInCurrentView -> {
            state.withSideEffects(
                AppsPickerSideEffect.HandleAppsChecked(
                    apps = command.apps,
                    checked = true,
                ),
            )
        }

        is AppsPickerCommand.ClearAllInCurrentView -> {
            state.withSideEffects(
                AppsPickerSideEffect.HandleAppsChecked(
                    apps = command.apps,
                    checked = false,
                ),
            )
        }
    }
}
