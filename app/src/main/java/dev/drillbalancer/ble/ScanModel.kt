package dev.drillbalancer.ble

import dev.drillbalancer.protocol.Uuids
import java.util.UUID

/** A DrillBalancer seen while scanning. */
data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val bonded: Boolean,        // the phone holds a bond for it
    val lastSeenMs: Long,
)

/**
 * Whether an advertisement is ours: the name is in the advertising packet, the service
 * UUID only in the scan response (ble_service.c), and phones do not always merge the two.
 * Matched in software on purpose: hardware UUID filters miss the scan response on some
 * phones.
 */
fun isDrillBalancer(name: String?, serviceUuids: Collection<UUID>?): Boolean =
    name?.startsWith(Uuids.NAME_PREFIX) == true || serviceUuids?.contains(Uuids.SVC_MAIN) == true

/** Signal bars 1..4 for a scan RSSI (a device that was heard always gets one). */
fun signalBars(rssi: Int): Int = when {
    rssi >= -60 -> 4
    rssi >= -70 -> 3
    rssi >= -80 -> 2
    else -> 1
}

/** The devices found so far, in order of discovery; ones not seen for [staleMs] drop out. */
class ScanResults(private val staleMs: Long = 10_000) {
    private val byAddress = LinkedHashMap<String, ScannedDevice>()

    val devices: List<ScannedDevice> get() = byAddress.values.toList()

    /**
     * Returns whether the list changed in a way worth showing: the RSSI only when its
     * [signalBars] did (it varies with every advertisement). The newest RSSI is kept.
     */
    fun update(seen: ScannedDevice): Boolean {
        val old = byAddress[seen.address]
        // A report without the advertising packet carries no name: keep the known one.
        val merged = if (seen.name == null && old != null) seen.copy(name = old.name) else seen
        byAddress[seen.address] = merged
        return old == null || old.name != merged.name || old.bonded != merged.bonded ||
            signalBars(old.rssi) != signalBars(merged.rssi)
    }

    fun prune(nowMs: Long): Boolean = byAddress.values.removeAll { nowMs - it.lastSeenMs > staleMs }
}

/**
 * Android 7+ silently returns no results to an app that starts more than 5 scans in 30 s.
 * This keeps the starts under that limit by telling the scanner how long to wait first.
 */
class ScanThrottle(private val maxStarts: Int = 5, private val windowMs: Long = 30_000) {
    private val starts = ArrayDeque<Long>()

    /** How long to wait before a start at [nowMs] is safe (0: now). */
    fun waitMs(nowMs: Long): Long {
        while (starts.isNotEmpty() && nowMs - starts.first() >= windowMs) starts.removeFirst()
        return if (starts.size < maxStarts) 0 else starts.first() + windowMs - nowMs
    }

    fun recordStart(nowMs: Long) {
        starts.addLast(nowMs)
    }
}

/** The scan failed (onScanFailed(), or Bluetooth is off); the message says why. */
class ScanException(message: String) : Exception(message)

/** User-facing text for a ScanCallback.SCAN_FAILED_* code. */
fun scanErrorMessage(code: Int): String = when (code) {
    SCAN_FAILED_ALREADY_STARTED -> "A scan is already running."
    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
        "The phone's Bluetooth did not start the scan. Turn Bluetooth off and on, then try again."
    SCAN_FAILED_INTERNAL_ERROR -> "The phone's Bluetooth failed to scan. Try again."
    SCAN_FAILED_FEATURE_UNSUPPORTED -> "This phone does not support Bluetooth LE scanning."
    SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "The phone's Bluetooth is busy. Try again in a moment."
    SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scanning was started too often. Wait 30 seconds, then try again."
    else -> "Scanning failed (error $code)."
}

// ScanCallback.SCAN_FAILED_* (the last two are API 33+ constants, same values before).
internal const val SCAN_FAILED_ALREADY_STARTED = 1
internal const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
internal const val SCAN_FAILED_INTERNAL_ERROR = 3
internal const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
internal const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
internal const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
