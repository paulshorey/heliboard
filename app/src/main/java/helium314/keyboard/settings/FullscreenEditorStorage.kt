// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Build
import helium314.keyboard.latin.utils.Log

/**
 * Persistent storage for fullscreen editor text drafts, keyed by the package name
 * of the app that opened the keyboard.
 *
 * When the user switches to a different app while in fullscreen mode, the keyboard
 * loses the connection to the original app. This storage preserves each app's
 * in-progress text so it can be synced back when the user returns.
 *
 * Uses the same persistence mechanism as Settings (SharedPreferences with
 * device-protected storage when available), so drafts survive process death
 * and app switches.
 */
object FullscreenEditorStorage {

    private const val PREFS_FILE = "fullscreen_drafts"
    private const val KEY_PREFIX = "draft_"
    private const val TAG = "FullscreenEditorStorage"
    private const val MAX_TEXT_LENGTH = 100_000

    private fun getPrefs(context: Context) = runCatching {
        val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !context.isDeviceProtectedStorage) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }.getOrElse {
        Log.w(TAG, "Failed to get prefs: ${it.message}")
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    private fun keyForPackage(packageName: String) = "${KEY_PREFIX}$packageName"

    /**
     * Save text for the given package. Truncates if exceeding max length.
     */
    fun put(context: Context, packageName: String, text: String) {
        if (packageName.isBlank()) return
        val toStore = if (text.length > MAX_TEXT_LENGTH) {
            Log.w(TAG, "Truncating draft for $packageName from ${text.length} to $MAX_TEXT_LENGTH chars")
            text.take(MAX_TEXT_LENGTH)
        } else text
        getPrefs(context).edit().putString(keyForPackage(packageName), toStore).apply()
    }

    /**
     * Get saved text for the given package, or null if none.
     */
    fun get(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        return getPrefs(context).getString(keyForPackage(packageName), null)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Remove saved text for the given package (e.g. after successful sync).
     */
    fun remove(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        getPrefs(context).edit().remove(keyForPackage(packageName)).apply()
    }

    /**
     * Get all packages that have saved drafts. Keys are stripped of the prefix.
     * Useful for a settings screen showing in-progress drafts.
     */
    fun getAllPackageNames(context: Context): Set<String> {
        val prefix = KEY_PREFIX
        return getPrefs(context).all.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }
}
