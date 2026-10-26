package dev.drillbalancer.recorder.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The device's soft-AP, joined for this app only (API 29+): a WifiNetworkSpecifier request
 * without NET_CAPABILITY_INTERNET, so the phone keeps its mobile data and other apps their
 * routes. The first request for a network shows Android's "connect to device" dialog (the
 * record screen says so after [approvalHintMs]); Android remembers the approval, so a join
 * again after the network was lost (the same session's SSID) shows no dialog and no hint.
 * onUnavailable means it was declined or the network was not found within [joinTimeoutMs]. The socket comes from the network's own
 * socket factory; the process is never bound to the network. [close] (and a lost network)
 * unregisters the request, which takes the phone off the device's Wi-Fi.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ApLink(
    context: Context,
    private val endpoint: StreamEndpoint,
    private val joinTimeoutMs: Int = 30_000,
    private val approvalHintMs: Long = 1_500,
) : StreamLink {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var network: Network? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var approved = false      // joined once: Android asks no more for this network

    override val viaAp: Boolean get() = true

    override suspend fun connect(onPhase: (LinkPhase) -> Unit): Socket {
        val net = network ?: join(onPhase)
        onPhase(LinkPhase.CONNECTING)
        val s = try {
            withContext(Dispatchers.IO) { StreamSockets.open(net.socketFactory, endpoint) }
        } catch (e: IOException) {
            throw LinkException("No connection to the recorder on ${endpoint.ssid} (${e.message}).", LinkException.Kind.CONNECT, e)
        }
        socket = s
        return s
    }

    private suspend fun join(onPhase: (LinkPhase) -> Unit): Network = coroutineScope {
        onPhase(LinkPhase.JOINING_WIFI)
        val hint = if (approved) {
            null
        } else {
            launch {
                delay(approvalHintMs)
                onPhase(LinkPhase.WAITING_APPROVAL)
            }
        }
        try {
            request()
        } finally {
            hint?.cancel()
        }
    }

    private suspend fun request(): Network = suspendCancellableCoroutine { cont ->
        val spec = WifiNetworkSpecifier.Builder().setSsid(endpoint.ssid).setWpa2Passphrase(endpoint.pass).build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                approved = true
                if (cont.isActive) cont.resume(n)
            }

            override fun onUnavailable() {
                release()
                if (cont.isActive) {
                    cont.resumeWithException(
                        LinkException(
                            "The phone did not join the device's Wi-Fi ${endpoint.ssid}: the request was declined, " +
                                "or the network was not found. Keep the phone near the device and try again.",
                            LinkException.Kind.DECLINED,
                        ),
                    )
                }
            }

            // The device's Wi-Fi went away (session closed, out of range): the read fails with
            // it, and the request goes; a reconnect asks again.
            override fun onLost(n: Network) {
                if (n != network) return
                closeSocket()
                release()
            }
        }
        synchronized(lock) { callback = cb }
        try {
            cm.requestNetwork(req, cb, joinTimeoutMs)
        } catch (e: SecurityException) {
            synchronized(lock) { callback = null }
            cont.resumeWithException(
                LinkException("The app may not join Wi-Fi networks. Allow \"Nearby devices\" (or Location) in its settings.", LinkException.Kind.PERMISSION, e),
            )
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { release() }
    }

    override fun close() {
        closeSocket()
        release()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private fun release() {
        val cb = synchronized(lock) { callback.also { callback = null } } ?: return
        network = null
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "network callback already gone: $e")
        }
    }

    private companion object {
        const val TAG = "ApLink"
    }
}
