package dev.drillbalancer.ui.firmware

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drillbalancer.protocol.FirmwareImage
import dev.drillbalancer.ui.components.KeepScreenOn
import dev.drillbalancer.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

@Composable
fun FirmwareRoute(vm: FirmwareViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()

    // The phone must not sleep mid-transfer: the transfer would stall and abort.
    if (state.phase == FirmwareUiState.Phase.TRANSFERRING || state.phase == FirmwareUiState.Phase.REBOOTING) {
        KeepScreenOn()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Both ask the document provider, which may be a slow cloud or USB one.
            val name = withContext(Dispatchers.IO) { displayName(context, uri) }
            try {
                val bytes = withContext(Dispatchers.IO) { readLimited(context, uri, FirmwareImage.OTA_SLOT_SIZE) }
                if (bytes == null) {
                    vm.onFileError(name, "The file is larger than the device's ${formatBytes(FirmwareImage.OTA_SLOT_SIZE.toLong())} update slot.")
                } else {
                    // Hashes up to 6 MB
                    vm.onFile(name, withContext(Dispatchers.Default) { FirmwareImage.parse(bytes) })
                }
            } catch (e: IOException) {
                vm.onFileError(name, "The file could not be read (${e.message}).")
            } catch (_: SecurityException) {
                vm.onFileError(name, "The app may not read this file.")
            }
        }
    }

    FirmwareScreen(
        state,
        FirmwareActions(
            // .bin files come with all kinds of MIME types; the parser decides.
            pick = { picker.launch(arrayOf("*/*")) },
            start = vm::start,
            cancel = vm::cancel,
            done = onDone,
        ),
    )
}

private fun displayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
} catch (_: Exception) {
    null
}

// The whole file, or null as soon as it is larger than [max].
private fun readLimited(context: Context, uri: Uri, max: Int): ByteArray? {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no data")
    return input.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        out.toByteArray()
    }
}
