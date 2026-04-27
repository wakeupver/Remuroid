package com.swordfish.lemuroid.app.shared.coreoptions

import android.content.Context
import com.swordfish.lemuroid.lib.library.ExposedSetting
import java.io.Serializable

/**
 * A core option that can be either:
 * - **Curated** – backed by an [ExposedSetting] with a localised title and an optional
 *   allow-list of values (original Lemuroid behaviour).
 * - **Auto-detected** – sourced directly from the libretro variable descriptor, just like
 *   RetroArch does. In this case [exposedSetting] is null and the native description string
 *   is used as the display title.
 */
data class LemuroidCoreOption(
    private val exposedSetting: ExposedSetting?,
    private val coreOption: CoreOption,
    /** Fallback title used when [exposedSetting] is null (i.e. auto-detected). */
    private val autoTitle: String = coreOption.name,
) : Serializable {

    fun getKey(): String = exposedSetting?.key ?: coreOption.variable.key

    fun getDisplayName(context: Context): String =
        exposedSetting?.let { context.getString(it.titleId) } ?: autoTitle

    fun getEntries(context: Context): List<String> {
        val correctSettings = getCorrectExposedSettings()
        return when {
            exposedSetting == null || exposedSetting.values.isEmpty() || correctSettings.isEmpty() ->
                coreOption.optionValues.map { formatAutoValue(it) }
            else ->
                correctSettings.map { context.getString(it.titleId) }
        }
    }

    fun getEntriesValues(): List<String> {
        val correctSettings = getCorrectExposedSettings()
        return when {
            exposedSetting == null || exposedSetting.values.isEmpty() || correctSettings.isEmpty() ->
                coreOption.optionValues.toList()
            else ->
                correctSettings.map { it.key }
        }
    }

    fun getCurrentValue(): String = coreOption.variable.value

    fun getCurrentIndex(): Int = maxOf(getEntriesValues().indexOf(getCurrentValue()), 0)

    /** Whether this option was auto-detected from the core (not manually curated). */
    val isAutoDetected: Boolean get() = exposedSetting == null

    private fun getCorrectExposedSettings(): List<ExposedSetting.Value> =
        exposedSetting?.values?.filter { it.key in coreOption.optionValues } ?: emptyList()

    /**
     * Capitalises the first letter of a raw libretro value string so it looks nicer in the UI,
     * matching RetroArch's display behaviour.
     */
    private fun formatAutoValue(value: String): String =
        value.replaceFirstChar { it.uppercase() }

    companion object {
        /**
         * Builds a [LemuroidCoreOption] directly from a [CoreOption] with no curated
         * [ExposedSetting]. The native description string from the core is used as the title.
         * This mirrors how RetroArch populates its Core Options menu.
         */
        fun fromCoreOption(coreOption: CoreOption): LemuroidCoreOption =
            LemuroidCoreOption(
                exposedSetting = null,
                coreOption = coreOption,
                autoTitle = coreOption.name,
            )
    }
}
