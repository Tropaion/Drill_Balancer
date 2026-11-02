package dev.drillbalancer.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckerTest {
    private val release = ReleaseInfo(ReleaseVersion(0, 2, 0), null, null)
    private var now = 1_000L
    private var calls = 0
    private var result: ReleaseResult = ReleaseResult.Found(release)
    private var gate: CompletableDeferred<Unit>? = null
    private val checker = UpdateChecker(
        source = {
            calls++
            gate?.await()
            result
        },
        clock = { now },
    )

    @Test
    fun keepsTheResultForTenMinutes() = runTest {
        checker.refresh()
        now += UpdateChecker.TTL_MS
        checker.refresh()
        assertEquals(1, calls)
        assertEquals(release, checker.latest.value)
    }

    @Test
    fun asksAgainAfterTenMinutes() = runTest {
        checker.refresh()
        now += UpdateChecker.TTL_MS + 1
        checker.refresh()
        assertEquals(2, calls)
    }

    @Test
    fun overlappingChecksAskOnce() = runTest {
        gate = CompletableDeferred()
        launch { checker.refresh() }
        launch { checker.refresh() }
        runCurrent()
        gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun aFailureKeepsWhatWasKnownAndIsNotKept() = runTest {
        checker.refresh()
        now += UpdateChecker.TTL_MS + 1
        result = ReleaseResult.Failed
        checker.refresh()
        assertEquals(release, checker.latest.value)
        checker.refresh()
        assertEquals(3, calls)
    }

    @Test
    fun noReleaseIsKeptAndClearsTheLast() = runTest {
        checker.refresh()
        now += UpdateChecker.TTL_MS + 1
        result = ReleaseResult.None
        checker.refresh()
        assertNull(checker.latest.value)
        checker.refresh()
        assertEquals(2, calls)
    }
}
