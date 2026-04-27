package com.swordfish.lemuroid.app.mobile.feature.gamemenu.coreoptions

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuActivity
import com.swordfish.lemuroid.app.shared.coreoptions.CoreOptionsPreferenceHelper
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsGroup
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsList
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsSwitch
import com.swordfish.lemuroid.app.utils.android.settings.booleanPreferenceState
import com.swordfish.lemuroid.app.utils.android.settings.indexPreferenceState
import com.swordfish.lemuroid.lib.core.CoreVariablesManager

@Composable
fun GameMenuCoreOptionsScreen(
    viewModel: GameMenuCoreOptionsViewModel,
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
) {
    val context = LocalContext.current

    val connectedGamePads by viewModel.connectedGamePads.collectAsState(0)

    // Split into curated (has ExposedSetting) vs auto-detected options.
    val (curatedOptions, autoDetectedOptions) =
        remember(gameMenuRequest) {
            val all = gameMenuRequest.coreOptions + gameMenuRequest.advancedCoreOptions
            all.partition { !it.isAutoDetected }
        }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // Curated options (hand-crafted titles / filtered value lists) — shown first.
        if (curatedOptions.isNotEmpty()) {
            CoreOptionsSection(
                systemID = gameMenuRequest.game.systemId,
                coreOptions = curatedOptions,
                context = context,
            )
        }

        // Auto-detected options — every variable reported by the core that isn't curated.
        // Wrapped in a labelled group so the user knows these come directly from the core.
        if (autoDetectedOptions.isNotEmpty()) {
            LemuroidSettingsGroup(
                title = { Text(text = stringResource(R.string.core_settings_category_auto_detected)) },
            ) {
                CoreOptionsSection(
                    systemID = gameMenuRequest.game.systemId,
                    coreOptions = autoDetectedOptions,
                    context = context,
                )
            }
        }

        ControllersOptions(gameMenuRequest, maxOf(1, connectedGamePads), context)
    }
}

@Composable
private fun CoreOptionsSection(
    systemID: String,
    coreOptions: List<LemuroidCoreOption>,
    context: Context,
) {
    for (coreOption in coreOptions) {
        val entriesValues = coreOption.getEntriesValues()
        val entries = coreOption.getEntries(context)

        if (entriesValues.isEmpty() || entries.isEmpty()) continue

        if (entriesValues.toSet() == CoreOptionsPreferenceHelper.BOOLEAN_SET) {
            LemuroidSettingsSwitch(
                state =
                    booleanPreferenceState(
                        CoreVariablesManager.computeSharedPreferenceKey(coreOption.getKey(), systemID),
                        coreOption.getCurrentValue() == "enabled",
                    ),
                title = { Text(text = coreOption.getDisplayName(context)) },
            )
        } else {
            LemuroidSettingsList(
                title = { Text(text = coreOption.getDisplayName(context)) },
                items = entries,
                state =
                    indexPreferenceState(
                        CoreVariablesManager.computeSharedPreferenceKey(coreOption.getKey(), systemID),
                        entriesValues.first(),
                        entriesValues,
                    ),
            )
        }
    }
}

@Composable
private fun ControllersOptions(
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
    connectedGamePads: Int,
    context: Context,
) {
    val controllers = gameMenuRequest.coreConfig.controllerConfigs

    val visibleControllers =
        (0 until connectedGamePads)
            .map { it to controllers[it] }
            .filter { (_, controllers) -> controllers != null && controllers.size >= 2 }

    if (visibleControllers.isEmpty()) {
        return
    }

    LemuroidSettingsGroup(
        title = { Text(text = stringResource(R.string.core_settings_category_controllers)) },
    ) {
        visibleControllers.forEach { (port, controllerConfigs) ->
            LemuroidSettingsList(
                title = { Text(text = context.getString(R.string.core_settings_controller, (port + 1).toString())) },
                items = controllerConfigs!!.map { stringResource(id = it.displayName) },
                state =
                    indexPreferenceState(
                        ControllerConfigsManager.getSharedPreferencesId(
                            gameMenuRequest.game.systemId,
                            gameMenuRequest.coreConfig.coreID,
                            port,
                        ),
                        controllerConfigs.map { it.name }.first(),
                        controllerConfigs.map { it.name },
                    ),
            )
        }
    }
}
