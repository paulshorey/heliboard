// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** About and voice diagnostics export the same report through this action. */
@Composable
internal fun rememberDiagnosticLogExport(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val report = Log.collectDiagnosticReport("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                val output = requireNotNull(context.contentResolver.openOutputStream(uri))
                output.bufferedWriter().use { it.write(report) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DiagnosticLogExport", "Diagnostic export failed: ${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.diagnostic_export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    return {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        launcher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE,
                context.getString(R.string.english_ime_name).replace(" ", "_") + "_diagnostics_$date.txt")
            .setType("text/plain"))
    }
}
