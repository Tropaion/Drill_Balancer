package dev.drillbalancer.ble

import dev.drillbalancer.protocol.RecState
import dev.drillbalancer.protocol.RecorderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** A rec_state read whose caller resumes after a newer notification does not overwrite it. */
class RecStateOrderTest {
    private fun st(state: RecState) = RecorderStatus.NOT_READ.copy(state = state, read = true)

    @Test
    fun aNotificationAfterTheAnswerWins() {
        val o = RecStateOrder()
        o.set(RecorderStatus.NOT_READ)          // the link is set up
        val at = o.stamp()                      // the read's answer arrives: WAIT_CLIENT ...
        o.set(st(RecState.READY))               // ... then a notification, before the reader resumes
        assertEquals(RecState.READY, o.answered(st(RecState.WAIT_CLIENT), at).state)
        assertEquals(RecState.READY, o.value.value!!.state)
    }

    @Test
    fun anAnswerWithNothingAfterItCounts() {
        val o = RecStateOrder()
        o.set(st(RecState.WAIT_CLIENT))
        val at = o.stamp()
        assertEquals(RecState.READY, o.answered(st(RecState.READY), at).state)
        assertEquals(RecState.READY, o.value.value!!.state)
        // The link went meanwhile: nothing is put back, the caller gets its answer.
        val at2 = o.stamp()
        o.set(null)
        assertEquals(RecState.RECORDING, o.answered(st(RecState.RECORDING), at2).state)
        assertEquals(null, o.value.value)
        // A new link's placeholder is no answer either.
        val at3 = o.stamp()
        o.set(RecorderStatus.NOT_READ)
        assertEquals(RecState.READY, o.answered(st(RecState.READY), at3).state)
        assertEquals(RecorderStatus.NOT_READ, o.value.value)
    }
}
