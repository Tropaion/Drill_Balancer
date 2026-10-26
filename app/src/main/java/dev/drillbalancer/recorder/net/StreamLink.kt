package dev.drillbalancer.recorder.net

import dev.drillbalancer.protocol.RecorderStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Where the device's stream is and how to reach it, from rec_state. */
data class StreamEndpoint(
    val ip: String,
    val port: Int,
    val token: ByteArray,
    val ssid: String,
    val pass: String,
    val noAp: Boolean,    // the simulator: connect over the network the phone is on
) {
    // Never compared: equals keeps the data class default (token by reference).
    override fun toString() = "StreamEndpoint($ip:$port, ssid=$ssid, noAp=$noAp)"

    companion object {
        /** Null unless rec_state carries a usable endpoint. */
        fun of(st: RecorderStatus): StreamEndpoint? {
            if (!st.reachable || st.ip.isEmpty()) return null
            if (!st.noAp && st.ssid.isEmpty()) return null
            return StreamEndpoint(st.ip, st.port, st.tokenBytes(), st.ssid, st.pass, st.noAp)
        }
    }
}

/** How far a link has got; the record screen shows it. */
enum class LinkPhase {
    JOINING_WIFI,       // the phone asks Android for the device's network
    WAITING_APPROVAL,   // Android's "connect to device" dialog is likely up
    MANUAL_JOIN,        // API < 29: the user joins the network in the system settings
    CONNECTING,         // TCP connect to the stream server
}

/** A link that could not be made, or went away. */
class LinkException(message: String, val kind: Kind, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        DECLINED,     // Android's request was declined, or the network was not found in time
        PERMISSION,   // the app may not request Wi-Fi networks
        CONNECT,      // no TCP connection to the stream server
    }
}

/**
 * One way to the stream server. [connect] may be called again after the socket dropped: it
 * re-joins the network if that went too. [close] closes the socket and releases the network
 * (the phone leaves the device's Wi-Fi). Never binds the process to the network.
 */
interface StreamLink : Closeable {
    suspend fun connect(onPhase: (LinkPhase) -> Unit): Socket

    /** Whether the link uses the device's soft-AP (for the journal; the dataset says so). */
    val viaAp: Boolean
}

fun interface StreamLinkFactory {
    fun create(endpoint: StreamEndpoint): StreamLink
}

/**
 * Socket settings for the stream: a large receive buffer (the device holds 23 KB unsent). The
 * read timeout is the controller's (RecorderTiming.readTimeoutMs).
 */
object StreamSockets {
    const val RECEIVE_BUFFER = 256 * 1024
    const val CONNECT_TIMEOUT_MS = 5_000

    /** Opens a stream socket with [factory] (blocking: call on Dispatchers.IO). */
    fun open(factory: SocketFactory, endpoint: StreamEndpoint): Socket {
        val s = factory.createSocket()
        try {
            s.receiveBufferSize = RECEIVE_BUFFER   // before connect: it sets the window scale
            s.keepAlive = true
            s.connect(InetSocketAddress(endpoint.ip, endpoint.port), CONNECT_TIMEOUT_MS)
            return s
        } catch (e: IOException) {
            s.close()
            throw e
        }
    }
}

/**
 * The stream on the network the phone is on: the simulator (NO_AP), or on API < 29 the
 * device's soft-AP the user joined in the system settings ([manualJoin]: retried until the
 * user has, for up to [manualJoinMs] from the first attempt, connect timeouts included).
 * [factory] picks the network (the Wi-Fi network on API < 29, where Android may route
 * around a network without internet).
 */
class LanLink(
    private val endpoint: StreamEndpoint,
    private val manualJoin: Boolean = false,
    private val factory: () -> SocketFactory = { SocketFactory.getDefault() },
    private val manualJoinMs: Long = 120_000,
    private val retryMs: Long = 2_000,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : StreamLink {
    @Volatile private var socket: Socket? = null

    override val viaAp: Boolean get() = manualJoin

    override suspend fun connect(onPhase: (LinkPhase) -> Unit): Socket {
        onPhase(LinkPhase.CONNECTING)
        val since = elapsedMs()
        while (true) {
            try {
                val s = withContext(Dispatchers.IO) { StreamSockets.open(factory(), endpoint) }
                socket = s
                return s
            } catch (e: IOException) {
                if (!manualJoin || elapsedMs() - since + retryMs > manualJoinMs) {
                    throw LinkException("No connection to the recorder at ${endpoint.ip}:${endpoint.port} (${e.message}).", LinkException.Kind.CONNECT, e)
                }
                onPhase(LinkPhase.MANUAL_JOIN)
                delay(retryMs)
            }
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}
