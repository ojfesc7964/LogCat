package com.f0x1d.logfox.feature.filters.presentation.list

import com.f0x1d.logfox.core.tea.EffectHandler
import com.f0x1d.logfox.feature.filters.api.data.FiltersRepository
import com.f0x1d.logfox.feature.filters.api.domain.ClearAllFiltersUseCase
import com.f0x1d.logfox.feature.filters.api.domain.CreateAllFiltersUseCase
import com.f0x1d.logfox.feature.filters.api.domain.CreateFilterUseCase
import com.f0x1d.logfox.feature.filters.api.domain.DeleteFilterUseCase
import com.f0x1d.logfox.feature.filters.api.domain.ExportFiltersToUriUseCase
import com.f0x1d.logfox.feature.filters.api.domain.GetAllFiltersFlowUseCase
import com.f0x1d.logfox.feature.filters.api.domain.ImportFiltersFromUriUseCase
import com.f0x1d.logfox.feature.filters.api.domain.SwitchFilterUseCase
import com.f0x1d.logfox.feature.filters.api.model.UserFilter
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject

internal class FiltersEffectHandler @Inject constructor(
    private val getAllFiltersFlowUseCase: GetAllFiltersFlowUseCase,
    private val switchFilterUseCase: SwitchFilterUseCase,
    private val deleteFilterUseCase: DeleteFilterUseCase,
    private val createFilterUseCase: CreateFilterUseCase,
    private val createAllFiltersUseCase: CreateAllFiltersUseCase,
    private val clearAllFiltersUseCase: ClearAllFiltersUseCase,
    private val exportFiltersToUriUseCase: ExportFiltersToUriUseCase,
    private val importFiltersFromUriUseCase: ImportFiltersFromUriUseCase,
    private val filtersRepository: FiltersRepository,
) : EffectHandler<FiltersSideEffect, FiltersCommand> {

    override suspend fun handle(
        effect: FiltersSideEffect,
        onCommand: suspend (FiltersCommand) -> Unit,
    ) {
        when (effect) {
            is FiltersSideEffect.LoadFilters -> {
                getAllFiltersFlowUseCase()
                    .distinctUntilChanged()
                    .collect { filters ->
                        onCommand(FiltersCommand.FiltersLoaded(filters))
                    }
            }

            is FiltersSideEffect.ImportFilters -> importFiltersFromUriUseCase(effect.uri)

            is FiltersSideEffect.ExportAllFilters -> exportFiltersToUriUseCase(effect.uri, effect.filters)

            is FiltersSideEffect.SwitchFilter -> switchFilterUseCase(effect.filter, effect.checked)

            is FiltersSideEffect.DeleteFilter -> deleteFilterUseCase(effect.filter)

            is FiltersSideEffect.ClearAllFilters -> clearAllFiltersUseCase()

            is FiltersSideEffect.ToggleAppFilter -> {
                val filters = filtersRepository.getAll()
                val matchingFilter = filters.find { it.packageName == effect.packageName }
                if (effect.checked) {
                    if (matchingFilter == null) {
                        createFilterUseCase(
                            name = effect.title,
                            including = true,
                            enabled = true,
                            enabledLogLevels = emptyList(),
                            uid = null,
                            pid = null,
                            tid = null,
                            packageName = effect.packageName,
                            tag = null,
                            content = null,
                        )
                    } else if (!matchingFilter.enabled) {
                        switchFilterUseCase(matchingFilter, true)
                    }
                } else {
                    if (matchingFilter != null) {
                        deleteFilterUseCase(matchingFilter)
                    }
                }
            }

            is FiltersSideEffect.ToggleMultipleAppsFilter -> {
                val filters = filtersRepository.getAll()
                if (effect.checked) {
                    val existingPackages = filters.mapNotNull { it.packageName }.toSet()
                    val newApps = effect.apps.filter { it.packageName !in existingPackages }
                    val newFilters = newApps.map { app ->
                        UserFilter(
                            name = app.title,
                            including = true,
                            packageName = app.packageName,
                            enabled = true,
                        )
                    }
                    if (newFilters.isNotEmpty()) {
                        createAllFiltersUseCase(newFilters)
                    }
                } else {
                    val packagesToDelete = effect.apps.map { it.packageName }.toSet()
                    val filtersToDelete = filters.filter { it.packageName in packagesToDelete }
                    filtersToDelete.forEach { deleteFilterUseCase(it) }
                }
            }

            // UI side effects - handled by Fragment
            is FiltersSideEffect.NavigateToEditFilter -> Unit
            is FiltersSideEffect.NavigateToCreateFilter -> Unit
            is FiltersSideEffect.NavigateToAppsPicker -> Unit
        }
    }
}
