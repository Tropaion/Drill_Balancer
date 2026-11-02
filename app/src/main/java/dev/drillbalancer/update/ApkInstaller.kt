package dev.drillbalancer.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.drillbalancer.ui.components.startActivitySafely
import java.io.File

/** Hands a downloaded APK to Android, which asks the user; the app never installs by itself. */
interface ApkInstaller {
    /** Where APKs wait for the installer; cleared when the app starts. */
    val cacheDir: File

    fun canInstall(): Boolean

    /** Android's "Install unknown apps" switch for this app. */
    fun openInstallPermission()

    fun install(apk: File)
}

class AndroidApkInstaller(context: Context) : ApkInstaller {
    private val context = context.applicationContext

    override val cacheDir = File(this.context.cacheDir, "updates")

    override fun canInstall() = context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermission() = context.startActivitySafely(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    override fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivitySafely(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
