package dev.drillbalancer.ui.connect

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drillbalancer.ble.BlePermissions
import dev.drillbalancer.ble.BleReadiness

/** The connect screen wired to Android: permissions, Bluetooth and location switches, scanning. */
@Composable
fun ConnectRoute(vm: ConnectViewModel) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by vm.state.collectAsStateWithLifecycle()
    var readiness by remember { mutableStateOf(BlePermissions.readiness(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val refresh = { readiness = BlePermissions.readiness(context) }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    // Bluetooth or location switched from the quick settings while the screen is open.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val ready = readiness == BleReadiness.READY
    LifecycleStartEffect(ready) {
        if (ready) vm.startScan()
        onStopOrDispose { vm.stopScan() }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh()
        val refused = result.filterValues { !it }.keys
        // Refused without a rationale to show: Android will not ask again.
        denied = refused.isNotEmpty() && activity != null &&
            refused.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }
    val forResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val launch = { intent: Intent ->
        try {
            forResult.launch(intent)
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
            refresh()
        }
    }

    ConnectScreen(
        state = state,
        readiness = readiness,
        permissionsDenied = denied,
        legacyLocation = BlePermissions.needsLocationServices(),
        actions = ConnectActions(
            requestPermissions = { permissions.launch(BlePermissions.required().toTypedArray()) },
            openAppSettings = { launch(BlePermissions.appSettingsIntent(context)) },
            enableBluetooth = { launch(BlePermissions.enableBluetoothIntent()) },
            openLocationSettings = { launch(BlePermissions.locationSettingsIntent()) },
            openBluetoothSettings = { launch(BlePermissions.bluetoothSettingsIntent()) },
            connect = vm::connect,
            cancelConnect = vm::cancelConnect,
            rescan = vm::startScan,
            dismissMessage = vm::dismissMessage,
        ),
    )
}
