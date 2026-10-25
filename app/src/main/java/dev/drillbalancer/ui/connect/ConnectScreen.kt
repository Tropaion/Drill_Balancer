package dev.drillbalancer.ui.connect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drillbalancer.ble.BleReadiness
import dev.drillbalancer.ble.ScannedDevice
import dev.drillbalancer.ble.signalBars
import dev.drillbalancer.ui.components.ActionButton
import dev.drillbalancer.ui.components.BusyPanel
import dev.drillbalancer.ui.components.ButtonKind
import dev.drillbalancer.ui.components.DbIcons
import dev.drillbalancer.ui.components.MessageBanner
import dev.drillbalancer.ui.components.Panel
import dev.drillbalancer.ui.components.SectionTitle
import dev.drillbalancer.ui.components.SignalBars
import dev.drillbalancer.ui.theme.Blue
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green

/** What the connect screen can ask Android for. */
class ConnectActions(
    val requestPermissions: () -> Unit = {},
    val openAppSettings: () -> Unit = {},
    val enableBluetooth: () -> Unit = {},
    val openLocationSettings: () -> Unit = {},
    val openBluetoothSettings: () -> Unit = {},
    val connect: (String) -> Unit = {},
    val cancelConnect: () -> Unit = {},
    val rescan: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: ConnectUiState,
    readiness: BleReadiness,
    permissionsDenied: Boolean,     // asked and refused for good: only the app settings help
    legacyLocation: Boolean,        // API <= 30: the permission is "Location"
    actions: ConnectActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Connect") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let {
                MessageBanner(
                    it,
                    onDismiss = actions.dismissMessage,
                    actionLabel = if (state.removeBond) "Bluetooth settings" else null,
                    onAction = if (state.removeBond) actions.openBluetoothSettings else null,
                )
            }
            when (readiness) {
                BleReadiness.UNSUPPORTED -> Blocker(
                    "No Bluetooth LE",
                    "This phone has no Bluetooth Low Energy, which the app needs to talk to the DrillBalancer.",
                )
                BleReadiness.MISSING_PERMISSIONS -> Blocker(
                    if (legacyLocation) "Allow location access" else "Allow Nearby devices",
                    if (legacyLocation) {
                        "Android 11 and older only let apps scan for Bluetooth devices with the Location " +
                            "permission. The app uses it to find your DrillBalancer, never to locate you."
                    } else {
                        "The app needs the Nearby devices permission to find your DrillBalancer and connect " +
                            "to it over Bluetooth. It does not use your location."
                    },
                    if (permissionsDenied) "Open app settings" else "Allow",
                    if (permissionsDenied) actions.openAppSettings else actions.requestPermissions,
                )
                BleReadiness.BLUETOOTH_OFF -> Blocker(
                    "Bluetooth is off",
                    "Turn on Bluetooth to find and connect to your DrillBalancer.",
                    "Turn on Bluetooth",
                    actions.enableBluetooth,
                )
                BleReadiness.LOCATION_OFF -> Blocker(
                    "Location is off",
                    "On Android 11 and older, Bluetooth scans find nothing while location is switched off. " +
                        "Turn it on in the phone's settings.",
                    "Location settings",
                    actions.openLocationSettings,
                )
                BleReadiness.READY -> DeviceList(state, actions)
            }
        }
    }
}

@Composable
private fun Blocker(title: String, text: String, button: String? = null, onClick: () -> Unit = {}) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(DbIcons.Bluetooth, contentDescription = null, tint = Blue, modifier = Modifier.size(32.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
        if (button != null) ActionButton(button, onClick, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun DeviceList(state: ConnectUiState, actions: ConnectActions) {
    state.connecting?.let { address ->
        val name = state.devices.firstOrNull { it.address == address }?.name ?: address
        BusyPanel("Connecting to $name…") {
            ActionButton("Cancel", actions.cancelConnect, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        }
    }

    val enabled = state.connecting == null
    state.lastAddress?.let { last ->
        SectionTitle("Last used")
        val seen = state.lastDevice
        DeviceRow(
            name = seen?.name ?: "DrillBalancer",
            detail = if (seen != null) last else "$last · not in range",
            bars = seen?.let { signalBars(it.rssi) },
            paired = seen?.bonded == true,
            enabled = enabled,
            onClick = { actions.connect(last) },
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Nearby", Modifier.weight(1f))
        if (state.scanning) {
            CircularProgressIndicator(Modifier.padding(top = 8.dp).size(18.dp), strokeWidth = 2.dp)
            Text(
                "Searching", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    for (d in state.others) {
        DeviceRow(
            name = d.name ?: "DrillBalancer",
            detail = d.address,
            bars = signalBars(d.rssi),
            paired = d.bonded,
            enabled = enabled,
            onClick = { actions.connect(d.address) },
        )
    }
    if (state.others.isEmpty() && state.scanError == null) {
        Text(
            if (state.lastDevice != null) "No other DrillBalancer nearby."
            else "No DrillBalancer found yet. Move the level to wake it up and keep it within a few metres.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.scanError?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
    if (!state.scanning && state.connecting == null) {
        ActionButton("Scan again", actions.rescan, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
    }
}

@Composable
private fun DeviceRow(
    name: String,
    detail: String,
    bars: Int?,
    paired: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, Dark),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        detail, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (paired) PairedBadge()
                }
            }
            if (bars != null) SignalBars(bars)
            Icon(DbIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PairedBadge() {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Green)) {
        Text(
            "Paired", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge, color = Green,
        )
    }
}
