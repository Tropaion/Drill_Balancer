package dev.drillbalancer.recorder

import java.time.Instant

/** Where a recording session is, as the record screen shows it. */
enum class RecordPhase {
    IDLE,
    STARTING_DEVICE,    // OPEN, waiting for the device's Wi-Fi
    JOINING_WIFI,
    WAITING_APPROVAL,   // Android's "connect to device" dialog
    CONNECTING,         // TCP + HELLO, until READY
    READY,              // session open, no take
    RECORDING,
    STOPPING,           // STOP sent, waiting for END
    DONE,               // session closed
    FAILED;

    /** A session is (being) set up or open: the foreground service runs. */
    val active: Boolean get() = this != IDLE && this != DONE && this != FAILED
}

/** What went wrong, for the FAILED panel. */
data class RecordError(val title: String, val text: String)

/** A short message on the record screen. */
data class Notice(val text: String, val error: Boolean = false)

/** The last take of the session. */
data class TakeSummary(
    val recordingId: String,
    val durationS: Double,
    val bytes: Long,
    val complete: Boolean,     // END received
    val endText: String,       // how it ended
    val missingRecords: Long = 0,
)

/** The live view of the take being recorded, published at most every 100 ms. */
data class LiveView(
    val elapsedS: Double = 0.0,
    val sparkline: List<Float> = emptyList(),   // |a| in g, oldest first, 5 s
    val levels: AudioLevels = AudioLevels(),
    val kbPerS: Float = 0f,
    val bytes: Long = 0,
    val imuHz: Double? = null,
    val audioHz: Double? = null,
    val missingRecords: Long = 0,
    val imuGapSamples: Long = 0,
    val audioGapFrames: Long = 0,
    val deviceDropped: Long = 0,
    val quietMs: Long = 0,                      // since the last bytes arrived
)

/** Everything the record screen and the notification show. */
data class RecordingState(
    val phase: RecordPhase = RecordPhase.IDLE,
    val sessionId: String? = null,
    val setup: SessionSetup? = null,    // the session's labels, as its takes record them
    val ssid: String? = null,           // the device's Wi-Fi (null on the LAN)
    val pass: String? = null,           // only for MANUAL_JOIN (API < 29)
    val lan: Boolean = false,           // the simulator over the current network
    val recordingId: String? = null,    // the take being recorded
    val takes: Int = 0,                 // ended this session
    val deleted: Int = 0,               // of those, deleted since (the review's Delete take)
    val lastTake: TakeSummary? = null,
    val live: LiveView = LiveView(),
    val stalled: Boolean = false,
    val bleLinked: Boolean = true,
    val notice: Notice? = null,
    val error: RecordError? = null,
) {
    /** API < 29: the user joins the device's Wi-Fi in the system settings, with [pass]. */
    val manualJoin: Boolean get() = pass != null
}

/** Every wait of the controller (shorter in tests). */
data class RecorderTiming(
    val apStartMs: Long = 20_000,        // OPEN until rec_state has the endpoint
    val readyMs: Long = 10_000,          // HELLO until READY
    val pollMs: Long = 1_000,            // rec_state re-read while waiting (a missed notification)
    val openRetries: Int = 3,            // OPEN refused while the last session's endpoint goes down
    val takeStartSeenMs: Long = 2_000,   // a TAKE_START whose answer was lost: its SESSION says it ran
    val endWaitMs: Long = 5_000,         // STOP until END
    val closeWaitMs: Long = 5_000,       // CLOSE until the stream's FIN (drain + the device's 1 s linger)
    val readTimeoutMs: Int = 1_000,      // socket read timeout: the stall check's tick
    val stallWarnMs: Long = 3_000,
    val stallFailMs: Long = 10_000,
    val reconnectAttempts: Int = 3,
    val reconnectDelayMs: Long = 1_000,
    val publishMs: Long = 100,
    val syncMs: Long = 1_000,
    val minFreeBytes: Long = 500L * 1024 * 1024,
)

/** Monotonic time for timeouts and rates, wall time for the journal. */
interface RecorderClock {
    fun elapsedMs(): Long
    fun now(): Instant

    companion object {
        val SYSTEM = object : RecorderClock {
            override fun elapsedMs() = System.nanoTime() / 1_000_000
            override fun now(): Instant = Instant.now()
        }
    }
}

/**
 * Runs a device call that needs the bonded link: the UI passes its PairingGate, which pairs
 * first if needed. Throws [Cancelled] when the user backs out of pairing.
 */
fun interface Secure {
    suspend fun run(block: suspend () -> Unit)

    class Cancelled : Exception("Pairing cancelled")

    companion object {
        /** No pairing screen (the notification's Stop, a link that is bonded already). */
        val DIRECT = Secure { it() }
    }
}
