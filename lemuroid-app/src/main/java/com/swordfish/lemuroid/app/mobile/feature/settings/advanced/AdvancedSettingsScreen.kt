package com.swordfish.lemuroid.app.mobile.feature.settings.advanced

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.main.MainRoute
import com.swordfish.lemuroid.app.shared.settings.StorageBaseDirPicker
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidCardSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsList
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsMenuLink
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsPage
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSlider
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSwitch
import com.swordfish.lemuroid.app.utils.android.settings.booleanPreferenceState
import com.swordfish.lemuroid.app.utils.android.settings.indexPreferenceState
import com.swordfish.lemuroid.app.utils.android.settings.intPreferenceState
import com.swordfish.lemuroid.lib.storage.DirectoriesManager

@Composable
fun AdvancedSettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: AdvancedSettingsViewModel,
    navController: NavHostController,
    directoriesManager: DirectoriesManager,
) {
    val uiState = viewModel.uiState.collectAsState().value

    LemuroidSettingsPage(modifier = modifier.fillMaxSize()) {
        if (uiState?.cache == null) return@LemuroidSettingsPage

        InputSettings()
        GeneralSettings(uiState, viewModel, navController)
        StorageSettings(directoriesManager)
    }
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

@Composable
private fun InputSettings() {
    LemuroidCardSettingsGroup(
        title = { Text(text = stringResource(R.string.settings_category_input)) },
    ) {
        val rumbleEnabled = booleanPreferenceState(R.string.pref_key_enable_rumble, false)
        LemuroidSettingsSwitch(
            state = rumbleEnabled,
            title = { Text(text = stringResource(R.string.settings_title_enable_rumble)) },
            subtitle = { Text(text = stringResource(R.string.settings_description_enable_rumble)) },
        )
        LemuroidSettingsSwitch(
            enabled = rumbleEnabled.value,
            state = booleanPreferenceState(R.string.pref_key_enable_device_rumble, false),
            title = { Text(text = stringResource(R.string.settings_title_enable_device_rumble)) },
            subtitle = { Text(text = stringResource(R.string.settings_description_enable_device_rumble)) },
        )
        LemuroidSettingsSlider(
            state = intPreferenceState(
                key = stringResource(R.string.pref_key_tilt_sensitivity_index),
                default = 6,
            ),
            steps = 10,
            valueRange = 0f..10f,
            enabled = true,
            title = { Text(text = stringResource(R.string.settings_title_tilt_sensitivity)) },
        )
    }
}

// ---------------------------------------------------------------------------
// General
// ---------------------------------------------------------------------------

@Composable
private fun GeneralSettings(
    uiState: AdvancedSettingsViewModel.State,
    viewModel: AdvancedSettingsViewModel,
    navController: NavController,
) {
    val factoryResetDialogState = remember { mutableStateOf(false) }

    LemuroidCardSettingsGroup(
        title = { Text(text = stringResource(R.string.settings_category_general)) },
    ) {
        LemuroidSettingsSwitch(
            state = booleanPreferenceState(R.string.pref_key_low_latency_audio, false),
            title = { Text(text = stringResource(R.string.settings_title_low_latency_audio)) },
            subtitle = { Text(text = stringResource(R.string.settings_description_low_latency_audio)) },
        )
        LemuroidSettingsList(
            title = { Text(text = stringResource(R.string.settings_title_maximum_cache_usage)) },
            items = uiState.cache.displayNames,
            state = indexPreferenceState(
                R.string.pref_key_max_cache_size,
                uiState.cache.default,
                uiState.cache.values,
            ),
        )
        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(R.string.settings_title_reset_settings)) },
            subtitle = { Text(text = stringResource(R.string.settings_description_reset_settings)) },
            onClick = { factoryResetDialogState.value = true },
        )
    }

    if (factoryResetDialogState.value) {
        FactoryResetDialog(factoryResetDialogState, viewModel, navController)
    }
}

// ---------------------------------------------------------------------------
// Storage
// ---------------------------------------------------------------------------

@Composable
private fun StorageSettings(directoriesManager: DirectoriesManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh display every time the screen is resumed (i.e. after returning from picker)
    val displayPath = remember { mutableStateOf(directoriesManager.getBaseDirDisplay()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                displayPath.value = directoriesManager.getBaseDirDisplay()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LemuroidCardSettingsGroup(
        title = { Text(text = stringResource(R.string.settings_category_storage_paths)) },
    ) {
        LemuroidSettingsMenuLink(
            title = { Text(text = stringResource(R.string.settings_title_storage_location)) },
            subtitle = { Text(text = displayPath.value) },
            onClick = { StorageBaseDirPicker.launch(context) },
        )
    }
}

// ---------------------------------------------------------------------------
// Factory reset dialog
// ---------------------------------------------------------------------------

@Composable
private fun FactoryResetDialog(
    state: MutableState<Boolean>,
    viewModel: AdvancedSettingsViewModel,
    navController: NavController,
) {
    val onDismiss = { state.value = false }
    AlertDialog(
        title = { Text(stringResource(R.string.reset_settings_warning_message_title)) },
        text = { Text(stringResource(R.string.reset_settings_warning_message_description)) },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                viewModel.resetAllSettings()
                navController.popBackStack(MainRoute.SETTINGS.route, false)
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
