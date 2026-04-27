package com.swordfish.lemuroid.lib.storage

import android.content.Context
import androidx.preference.PreferenceManager
import timber.log.Timber
import java.io.File

class DirectoriesManager(private val appContext: Context) {

    // -------------------------------------------------------------------------
    // Cores — ALWAYS in internal app-private storage, never customizable.
    // Path: /data/data/<pkg>/files/cores
    // -------------------------------------------------------------------------

    fun getCoresDirectory(): File =
        File(appContext.filesDir, "cores").apply { mkdirs() }

    // -------------------------------------------------------------------------
    // All other dirs — use custom base if configured AND writable,
    // otherwise fall back silently to getExternalFilesDir(null).
    // -------------------------------------------------------------------------

    private fun resolveBase(): File {
        val path = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(PREF_KEY_CUSTOM_BASE_DIR, null)

        if (!path.isNullOrBlank()) {
            val custom = File(path)
            // Attempt to create the directory tree
            if (!custom.exists()) custom.mkdirs()
            // Verify we can actually write to it
            if (custom.exists() && custom.canWrite()) return custom
            // Can't write — warn and fall back to default
            Timber.w("DirectoriesManager: custom base dir '$path' is not writable, falling back to default")
        }

        return appContext.getExternalFilesDir(null)!!
    }

    @Deprecated("Use the external states directory")
    fun getInternalStatesDirectory(): File =
        File(appContext.filesDir, "states").apply { mkdirs() }

    fun getSystemDirectory(): File =
        File(resolveBase(), "system").apply { mkdirs() }

    fun getStatesDirectory(): File =
        File(resolveBase(), "states").apply { mkdirs() }

    fun getStatesPreviewDirectory(): File =
        File(resolveBase(), "state-previews").apply { mkdirs() }

    fun getSavesDirectory(): File =
        File(resolveBase(), "saves").apply { mkdirs() }

    fun getInternalRomsDirectory(): File =
        File(resolveBase(), "roms").apply { mkdirs() }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** True if user has explicitly configured a base dir AND it's writable. */
    fun isBaseDirConfigured(): Boolean {
        val path = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(PREF_KEY_CUSTOM_BASE_DIR, null)
        if (path.isNullOrBlank()) return false
        val f = File(path)
        return f.exists() && f.canWrite()
    }

    /** True if a custom path is stored (regardless of whether it's accessible right now). */
    fun isBaseDirSet(): Boolean {
        val path = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(PREF_KEY_CUSTOM_BASE_DIR, null)
        return !path.isNullOrBlank()
    }

    /** Human-readable display of the current active base directory. */
    fun getBaseDirDisplay(): String {
        val path = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(PREF_KEY_CUSTOM_BASE_DIR, null)
        if (!path.isNullOrBlank()) {
            val f = File(path)
            if (f.exists() && f.canWrite()) return path
            // Stored but not accessible — show with warning indicator
            return "$path ⚠ (not accessible)"
        }
        return appContext.getExternalFilesDir(null)?.absolutePath ?: "(default)"
    }

    /** Persist a new base dir path. Pass blank/null to revert to default. */
    fun saveBaseDir(path: String?) {
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .putString(PREF_KEY_CUSTOM_BASE_DIR, path?.takeIf { it.isNotBlank() })
            .apply()
    }

    companion object {
        const val PREF_KEY_CUSTOM_BASE_DIR = "custom_base_dir"
    }
}
