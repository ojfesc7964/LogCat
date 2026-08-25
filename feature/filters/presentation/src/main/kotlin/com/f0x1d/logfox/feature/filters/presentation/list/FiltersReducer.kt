package com.f0x1d.logfox.feature.filters.presentation.list

import com.f0x1d.logfox.core.tea.ReduceResult
import com.f0x1d.logfox.core.tea.Reducer
import com.f0x1d.logfox.core.tea.noSideEffects
import com.f0x1d.logfox.core.tea.withSideEffects
import javax.inject.Inject

internal class FiltersReducer @Inject constructor() : Reducer<FiltersState, FiltersCommand, FiltersSideEffect> {

    override fun reduce(
        state: FiltersState,
        command: FiltersCommand,
    ): ReduceResult<FiltersState, FiltersSideEffect> = when (command) {
        is FiltersCommand.Load -> {
            state.withSideEffects(FiltersSideEffect.LoadFilters)
        }

        is FiltersCommand.FiltersLoaded -> {
            state.copy(filters = command.filters).noSideEffects()
        }

        is FiltersCommand.Import -> {
            state.withSideEffects(FiltersSideEffect.ImportFilters(command.uri))
        }

        is FiltersCommand.ExportAll -> {
            state.withSideEffects(FiltersSideEffect.ExportAllFilters(command.uri, state.filters))
        }

        is FiltersCommand.Switch -> {
            state.withSideEffects(FiltersSideEffect.SwitchFilter(command.filter, command.checked))
        }

        is FiltersCommand.Delete -> {
            state.withSideEffects(FiltersSideEffect.DeleteFilter(command.filter))
        }

        is FiltersCommand.ClearAll -> {
            state.withSideEffects(FiltersSideEffect.ClearAllFilters)
        }

        is FiltersCommand.OpenFilter -> {
            state.withSideEffects(FiltersSideEffect.NavigateToEditFilter(command.filterId))
        }

        is FiltersCommand.CreateNewFilter -> {
            state.withSideEffects(FiltersSideEffect.NavigateToCreateFilter)
        }

        is FiltersCommand.OpenAppsFilter -> {
            state.withSideEffects(FiltersSideEffect.NavigateToAppsPicker)
        }

        is FiltersCommand.ToggleAppFilter -> {
            state.withSideEffects(
                FiltersSideEffect.ToggleAppFilter(
                    title = command.title,
                    packageName = command.packageName,
                    checked = command.checked,
                ),
            )
        }

        is FiltersCommand.ToggleMultipleAppsFilter -> {
            state.withSideEffects(
                FiltersSideEffect.ToggleMultipleAppsFilter(
                    apps = command.apps,
                    checked = command.checked,
                ),
            )
        }
    }
}
