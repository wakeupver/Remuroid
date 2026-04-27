package com.swordfish.lemuroid.app.shared.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.android.RetrogradeActivity
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.SafUriHelper
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Transparent Activity that launches the SAF directory picker for selecting
 * the app-wide base storage directory (saves, states, system, roms).
 *
 * Cores always remain in internal storage (/data/data/<pkg>/files/cores).
 *
 * Flow:
 *  1. Launch SAF tree picker
 *  2. Validate URI authority (must be external storage provider)
 *  3. Resolve to real FS path
 *  4. On Android 11+: if path is not app-specific, request MANAGE_EXTERNAL_STORAGE if needed
 *  5. Validate directory is writable
 *  6. Save path and trigger library index
 */
class StorageBaseDirPicker : RetrogradeActivity() {

    @Inject
    lateinit var directoriesManager: DirectoriesManager

    private var mandatory = false

    // Pending path waiting for MANAGE_EXTERNAL_STORAGE grant
    private var pendingPath: String? = null
    private var pendingUri: Uri? = null
    private var waitingForManagePermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mandatory = intent.getBooleanExtra(EXTRA_MANDATORY, false)

        if (savedInstanceState == null) {
            launchPicker()
        } else {
            pendingPath = savedInstanceState.getString(STATE_PENDING_PATH)
            pendingUri = savedInstanceState.getParcelable(STATE_PENDING_URI)
            waitingForManagePermission = savedInstanceState.getBoolean(STATE_WAITING_MANAGE, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_PATH, pendingPath)
        outState.putParcelable(STATE_PENDING_URI, pendingUri)
        outState.putBoolean(STATE_WAITING_MANAGE, waitingForManagePermission)
    }

    override fun onResume() {
        super.onResume()
        // Returning from MANAGE_EXTERNAL_STORAGE settings screen
        if (waitingForManagePermission) {
            waitingForManagePermission = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                // Permission granted — complete the save
                val path = pendingPath
                val uri = pendingUri
                if (path != null) {
                    commitPath(path, uri)
                } else {
                    finishWithCancel()
                }
            } else {
                // User didn't grant — fall back to default
                Toast.makeText(
                    this,
                    R.string.storage_picker_permission_denied_fallback,
                    Toast.LENGTH_LONG,
                ).show()
                finishWithCancel()
            }
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_DIR)
        } catch (e: Exception) {
            Timber.e(e, "StorageBaseDirPicker: SAF not available")
            finishWithCancel()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE_PICK_DIR) {
            finish()
            return
        }

        if (resultCode != Activity.RESULT_OK) {
            handleCancel()
            return
        }

        val uri = data?.data ?: run { handleCancel(); return }

        // Step 1: Resolve URI to real FS path
        val realPath = SafUriHelper.treeUriToPath(uri)
        if (realPath == null) {
            Timber.w("StorageBaseDirPicker: cannot resolve URI to path: $uri")
            Toast.makeText(
                this,
                R.string.storage_picker_unsupported_location,
                Toast.LENGTH_LONG,
            ).show()
            // Re-launch so user can pick a valid location
            launchPicker()
            return
        }

        // Step 2: On Android 11+, check if we need MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && !SafUriHelper.isAppSpecificPath(this, realPath)
            && !Environment.isExternalStorageManager()
        ) {
            // Store pending state and request the permission
            pendingPath = realPath
            pendingUri = uri
            waitingForManagePermission = true
            persistUriPermission(uri)

            Toast.makeText(
                this,
                R.string.storage_picker_need_manage_permission,
                Toast.LENGTH_LONG,
            ).show()

            try {
                val manageIntent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                startActivity(manageIntent)
            } catch (e: Exception) {
                // Fallback to general manage storage settings
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            return
        }

        persistUriPermission(uri)
        commitPath(realPath, uri)
    }

    /** Final step: validate writable, save, trigger sync. */
    private fun commitPath(path: String, uri: Uri?) {
        val dir = File(path)
        if (!dir.exists()) dir.mkdirs()

        if (!dir.exists() || !dir.canWrite()) {
            Timber.w("StorageBaseDirPicker: path '$path' is not writable")
            Toast.makeText(
                this,
                R.string.storage_picker_not_writable,
                Toast.LENGTH_LONG,
            ).show()
            // Re-launch picker
            pendingPath = null
            pendingUri = null
            launchPicker()
            return
        }

        Timber.i("StorageBaseDirPicker: saving base dir '$path'")
        directoriesManager.saveBaseDir(path)
        LibraryIndexScheduler.scheduleLibrarySync(applicationContext)

        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun handleCancel() {
        if (mandatory) {
            // On first launch, mandatory=false so this branch won't normally be hit,
            // but guard against it anyway.
            launchPicker()
        } else {
            finishWithCancel()
        }
    }

    private fun finishWithCancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun persistUriPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            contentResolver.persistedUriPermissions
                .filter { it.uri != uri }
                .forEach { runCatching { contentResolver.releasePersistableUriPermission(it.uri, flags) } }
            contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    companion object {
        const val REQUEST_CODE_PICK_DIR = 2001
        const val EXTRA_MANDATORY = "extra_mandatory"

        private const val STATE_PENDING_PATH = "pending_path"
        private const val STATE_PENDING_URI = "pending_uri"
        private const val STATE_WAITING_MANAGE = "waiting_manage"

        fun launch(context: Context) {
            context.startActivity(Intent(context, StorageBaseDirPicker::class.java))
        }

        fun launchForResult(activity: Activity, mandatory: Boolean = false) {
            val intent = Intent(activity, StorageBaseDirPicker::class.java).apply {
                putExtra(EXTRA_MANDATORY, mandatory)
            }
            activity.startActivityForResult(intent, REQUEST_CODE_PICK_DIR)
        }
    }
}
