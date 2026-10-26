package dev.drillbalancer.recorder

import android.app.Application
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import dev.drillbalancer.recorder.net.ApLink
import dev.drillbalancer.recorder.net.LinkException
import dev.drillbalancer.recorder.net.LinkPhase
import dev.drillbalancer.recorder.net.StreamEndpoint
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/** The soft-AP request's life: asked once, the approval hint, declined, and always given back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ApLinkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val ep = StreamEndpoint("192.168.4.1", 47100, ByteArray(16), "DB-1a2b-k7q2", "abcdefghijklmnopqrst", noAp = false)

    @Test
    fun declinedRequestFailsAndIsUnregistered() = runTest {
        val link = ApLink(app, ep, approvalHintMs = 1_500)
        val phases = mutableListOf<LinkPhase>()
        val connect = async { runCatching { link.connect { phases += it } } }
        runCurrent()
        val callbacks = shadowOf(cm).networkCallbacks
        assertEquals(1, callbacks.size)
        assertEquals(listOf(LinkPhase.JOINING_WIFI), phases)
        advanceTimeBy(1_600)
        assertEquals(listOf(LinkPhase.JOINING_WIFI, LinkPhase.WAITING_APPROVAL), phases)
        callbacks.first().onUnavailable()
        val e = connect.await().exceptionOrNull() as LinkException
        assertEquals(LinkException.Kind.DECLINED, e.kind)
        assertTrue(e.message!!, "DB-1a2b-k7q2" in e.message!!)
        assertTrue("the request is given back", shadowOf(cm).networkCallbacks.isEmpty())
        link.close()   // nothing left to release: no error
    }

    @Test
    fun aJoinAgainAfterTheApprovalShowsNoApprovalHint() = runTest {
        // Android remembers the approval of the session's network: no dialog when it is lost
        // and joined again, so no "waiting for your approval" either.
        val link = ApLink(app, ep, approvalHintMs = 1_500)
        val phases = mutableListOf<LinkPhase>()
        class Joined : Exception()
        suspend fun join(available: Boolean) = async {
            runCatching { link.connect { phases += it; if (it == LinkPhase.CONNECTING) throw Joined() } }
        }.also {
            runCurrent()
            advanceTimeBy(1_600)
            if (available) shadowOf(cm).networkCallbacks.single().onAvailable(ShadowNetwork.newInstance(7))
        }
        val first = join(available = true)
        assertTrue(first.await().exceptionOrNull() is Joined)
        assertEquals(listOf(LinkPhase.JOINING_WIFI, LinkPhase.WAITING_APPROVAL, LinkPhase.CONNECTING), phases)
        shadowOf(cm).networkCallbacks.single().onLost(ShadowNetwork.newInstance(7))
        assertTrue("released with the network", shadowOf(cm).networkCallbacks.isEmpty())
        phases.clear()
        val again = join(available = true)
        assertTrue(again.await().exceptionOrNull() is Joined)
        assertEquals(listOf(LinkPhase.JOINING_WIFI, LinkPhase.CONNECTING), phases)
        link.close()
    }

    @Test
    fun closeWhileJoiningGivesTheRequestBack() = runTest {
        val link = ApLink(app, ep)
        val connect = async { runCatching { link.connect {} } }
        runCurrent()
        assertEquals(1, shadowOf(cm).networkCallbacks.size)
        connect.cancel()
        runCurrent()
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
        link.close()
    }
}
