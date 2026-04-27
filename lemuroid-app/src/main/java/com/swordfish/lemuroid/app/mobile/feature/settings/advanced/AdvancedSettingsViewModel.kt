package com.swordfish.lemuroid.app.mobile.feature.settings.advanced

import android.content.Context
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.shared.settings.SettingsInteractor
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.cache.CacheCleaner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class AdvancedSettingsViewModel(
    private val appContext: Context,
    private val settingsInteractor: SettingsInteractor,
    @Suppress("UnusedPrivateMember")
    private val directoriesManager: DirectoriesManager,
) : ViewModel() {

    class Factory(
        private val appContext: Context,
        private val settingsInteractor: SettingsInteractor,
        private val directoriesManager: DirectoriesManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AdvancedSettingsViewModel(appContext, settingsInteractor, directoriesManager) as T
    }

    data class CacheState(
        val default: String,
        val values: List<String>,
        val displayNames: List<String>,
    )

    data class State(val cache: CacheState)

    val uiState =
        buildState(appContext)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private fun buildState(ctx: Context): Flow<State> = flow {
        val supported = CacheCleaner.getSupportedCacheLimits()
        val default = CacheCleaner.getDefaultCacheLimit().toString()
        val displayNames = supported.map { Formatter.formatShortFileSize(ctx, it) }
        val values = supported.map { it.toString() }
        emit(State(cache = CacheState(default, values, displayNames)))
    }

    fun resetAllSettings() = settingsInteractor.resetAllSettings()
}
