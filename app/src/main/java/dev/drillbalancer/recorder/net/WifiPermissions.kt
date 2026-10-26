package dev.drillbalancer.recorder.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import dev.drillbalancer.ble.BlePermissions
import javax.net.SocketFactory

/**
 * Runtime permissions of a recording. Joining the device's soft-AP with a
 * WifiNetworkSpecifier (API 29+) needs NEARBY_WIFI_DEVICES (API 33+, declared
 * neverForLocation) or ACCESS_FINE_LOCATION (API 29-32, with location services on;
 * asked with COARSE on 31-32). API 37+ also restricts the local network: the stream's TCP
 * connection to 192.168.4.1 (or the simulator on the LAN) needs ACCESS_LOCAL_NETWORK. API
 * 26-28 has no specifier: the user joins in the system settings, no permission. The
 * notification permission (API 33+) is asked with them but not needed: the recording runs
 * without its notification.
 */
object WifiPermissions {
    @SuppressLint("InlinedApi")   // only names, returned for their API levels only
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdk >= Build.VERSION_CODES.CINNAMON_BUN -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK)
        sdk >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        // API 31-32: FINE only together with COARSE (the dialog offers "approximate").
        sdk >= Build.VERSION_CODES.S -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        sdk >= Build.VERSION_CODES.Q -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    @SuppressLint("InlinedApi")
    fun optional(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Whether the phone can join the soft-AP for the app (else: the system settings). */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    fun canRequestNetwork(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.Q

    /** API 29-32: the network request finds nothing with location services off. */
    fun needsLocationServices(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2

    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filterNot { BlePermissions.isGranted(context, it) }

    /**
     * What Start asks Android for: nothing while the required ones are granted, else those
     * and the missing optional ones with them. So the notification permission comes with the
     * first request and is not asked again on every Start after it was refused.
     */
    fun toAsk(missingRequired: List<String>, missingOptional: List<String>): List<String> =
        if (missingRequired.isEmpty()) emptyList() else missingRequired + missingOptional

    /** Why the join cannot work (checked after the permission dialog); null if nothing is in the way. */
    fun block(missingRequired: Boolean, locationOn: Boolean, sdk: Int = Build.VERSION.SDK_INT): WifiBlock? = when {
        missingRequired -> WifiBlock.PERMISSION
        needsLocationServices(sdk) && !locationOn -> WifiBlock.LOCATION_OFF
        else -> null
    }

    /**
     * API < 29: the socket factory of a connected Wi-Fi network (the device's soft-AP the user
     * joined has no internet, so Android may keep mobile data as the default), else the default.
     */
    @Suppress("DEPRECATION")   // allNetworks: the only way before API 29 without a request
    fun wifiSocketFactory(context: Context): SocketFactory {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return SocketFactory.getDefault()
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        return wifi?.socketFactory ?: SocketFactory.getDefault()
    }
}

/** What stops a recording session before the join; the setup screen offers the fix. */
enum class WifiBlock {
    PERMISSION,     // a required permission was refused: the app settings
    LOCATION_OFF,   // API 29-32: the network request finds nothing with location services off
}
