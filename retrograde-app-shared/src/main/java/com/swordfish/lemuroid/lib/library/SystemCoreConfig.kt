package com.swordfish.lemuroid.lib.library

import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.lemuroid.lib.core.CoreVariable
import java.io.Serializable

data class SystemCoreConfig(
    val coreID: CoreID,
    val controllerConfigs: HashMap<Int, ArrayList<ControllerConfig>>,
    val exposedSettings: List<ExposedSetting> = listOf(),
    val exposedAdvancedSettings: List<ExposedSetting> = listOf(),
    val defaultSettings: List<CoreVariable> = listOf(),
    val statesSupported: Boolean = true,
    val rumbleSupported: Boolean = false,
    val requiredBIOSFiles: List<String> = listOf(),
    val regionalBIOSFiles: Map<String, String> = mapOf(),
    val statesVersion: Int = 0,
    val skipDuplicateFrames: Boolean = true,
    val supportedOnlyArchitectures: Set<String>? = null,
    val supportsMicrophone: Boolean = false,
    /**
     * When true, all core variables reported by the libretro core are automatically shown
     * in the Core Options menu — similar to RetroArch's behaviour. Variables that are
     * already listed in [exposedSettings] or [exposedAdvancedSettings] keep their
     * localised title and filtered value list; every other variable is shown with its
     * native description string and the full value list reported by the core.
     *
     * Set to false only for cores whose full variable list is noisy / confusing and you
     * want to curate the options manually via [exposedSettings].
     */
    val autoDetectSettings: Boolean = true,
) : Serializable
