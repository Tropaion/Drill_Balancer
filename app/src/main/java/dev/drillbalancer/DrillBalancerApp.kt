package dev.drillbalancer

import android.app.Application
import dev.drillbalancer.ble.BleDeviceSession
import dev.drillbalancer.ble.DeviceScanner
import dev.drillbalancer.ble.LastDeviceStore

/** Process-wide singletons live here (manual DI, no framework). */
class DrillBalancerApp : Application() {
    val scanner: DeviceScanner by lazy { DeviceScanner(this) }
    val lastDevice: LastDeviceStore by lazy { LastDeviceStore(this) }
    val session: BleDeviceSession by lazy { BleDeviceSession(this, scanner, lastDevice) }
}
