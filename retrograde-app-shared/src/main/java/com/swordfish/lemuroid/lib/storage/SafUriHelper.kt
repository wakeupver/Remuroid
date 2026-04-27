package com.swordfish.lemuroid.lib.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import timber.log.Timber
import java.io.File

object SafUriHelper {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Convert a SAF tree URI to a real filesystem path.
     *
     * Handles:
     *  - Primary: content://…/tree/primary:Remuroid  → /storage/emulated/0/Remuroid
     *  - SD card: content://…/tree/ABCD-1234:Games  → /storage/ABCD-1234/Games
     *  - Root:    content://…/tree/primary:          → /storage/emulated/0
     *
     * Returns null if the URI authority is not the standard external storage provider
     * (e.g. Downloads provider, OEM providers) — callers should reject these.
     */
    fun treeUriToPath(treeUri: Uri): String? {
        return try {
            // Only handle the standard external storage provider
            if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) {
                Timber.w("SafUriHelper: unsupported SAF authority '${treeUri.authority}', cannot resolve to File path")
                return null
            }

            val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
            val colonIdx = docId.indexOf(':')
            if (colonIdx < 0) return null

            val storageId = docId.substring(0, colonIdx)
            val relativePath = docId.substring(colonIdx + 1)

            val root: File = if (storageId.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory()
            } else {
                // Removable SD card
                File("/storage/$storageId")
            }

            val resolved = if (relativePath.isEmpty()) root else File(root, relativePath)
            resolved.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "SafUriHelper: failed to resolve tree URI $treeUri")
            null
        }
    }

    /**
     * Returns true if the app has broad external storage access on this Android version.
     *  - Android ≤10 (API 29): READ/WRITE_EXTERNAL_STORAGE covers it
     *  - Android 11+ (API 30): needs MANAGE_EXTERNAL_STORAGE
     */
    fun hasExternalStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Covered by READ/WRITE_EXTERNAL_STORAGE declared in manifest
        }
    }

    /**
     * True if the given path is under the app's own external files dir.
     * These always have write access without extra permissions.
     */
    fun isAppSpecificPath(context: Context, path: String): Boolean {
        val extFiles = context.getExternalFilesDir(null) ?: return false
        return path.startsWith(extFiles.absolutePath)
    }
}
