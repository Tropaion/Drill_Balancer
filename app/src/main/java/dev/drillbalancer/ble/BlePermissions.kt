package dev.drillbalancer.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** What stands between the app and a working scan, in the order the UI should fix it. */
enum class BleReadiness {
    UNSUPPORTED,            // no Bluetooth LE on this phone
    MISSING_PERMISSIONS,    // request [BlePermissions.required]
    BLUETOOTH_OFF,          // start [BlePermissions.enableBluetoothIntent]
    LOCATION_OFF,           // API <= 30: scans find nothing with location services off
    READY,
}

/**
 * Runtime permissions and switches BLE needs. API 31+: BLUETOOTH_SCAN (declared
 * neverForLocation) and BLUETOOTH_CONNECT. API 26..30: ACCESS_FINE_LOCATION, plus
 * location services switched on, or scans silently return nothing.
 */
object BlePermissions {
    /** The runtime permissions to request on [sdkInt]. */
    @SuppressLint("InlinedApi")   // only names, returned for API 31+ only
    fun required(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(scanPermission(sdkInt), Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(scanPermission(sdkInt))
        }

    /** The one runtime permission a scan needs on [sdkInt]. */
    @SuppressLint("InlinedApi")
    fun scanPermission(sdkInt: Int = Build.VERSION.SDK_INT): String =
        if (sdkInt >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

    /** Whether scanning needs location services on (API <= 30). */
    fun needsLocationServices(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt < Build.VERSION_CODES.S

    fun missing(context: Context): List<String> = required().filter { !isGranted(context, it) }

    fun hasAll(context: Context): Boolean = missing(context).isEmpty()

    fun canScan(context: Context): Boolean = isGranted(context, scanPermission())

    /** BLUETOOTH_CONNECT on API 31+; implied by the install-time permissions before. */
    fun canConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun isBluetoothEnabled(context: Context): Boolean = adapter(context)?.isEnabled == true

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    fun readiness(context: Context): BleReadiness = readiness(
        supported = adapter(context) != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
        permissionsGranted = hasAll(context),
        bluetoothOn = isBluetoothEnabled(context),
        locationOn = !needsLocationServices() || isLocationEnabled(context),
    )

    /** Pure core of [readiness]. */
    fun readiness(supported: Boolean, permissionsGranted: Boolean, bluetoothOn: Boolean, locationOn: Boolean) = when {
        !supported -> BleReadiness.UNSUPPORTED
        !permissionsGranted -> BleReadiness.MISSING_PERMISSIONS
        !bluetoothOn -> BleReadiness.BLUETOOTH_OFF
        !locationOn -> BleReadiness.LOCATION_OFF
        else -> BleReadiness.READY
    }

    /**
     * Asks the user to turn Bluetooth on (startActivityForResult or an
     * ActivityResultContracts.StartActivityForResult launcher). Needs BLUETOOTH_CONNECT on
     * API 31+, so request the permissions first.
     */
    fun enableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    /** Where the user removes a stale bond ("Forget" the device) before pairing again. */
    fun bluetoothSettingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    /** The app's own settings page, for permissions the user denied permanently. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))

    /** Whether the phone holds a bond for [device]; false without BLUETOOTH_CONNECT. */
    internal fun isBonded(context: Context, device: BluetoothDevice): Boolean =
        canConnect(context) &&
            try {
                device.bondState == BluetoothDevice.BOND_BONDED
            } catch (_: SecurityException) {
                false
            }

    internal fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    internal fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
}
