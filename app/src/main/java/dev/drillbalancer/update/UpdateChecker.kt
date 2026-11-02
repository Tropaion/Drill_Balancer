package dev.drillbalancer.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The latest release, known for [TTL_MS]: GitHub allows 60 requests an hour without a login, and
 * opening the app and connecting should use one. A failed check is not kept, so the next one
 * tries again, and it leaves what was known.
 */
class UpdateChecker(private val source: ReleaseSource, private val clock: () -> Long = System::currentTimeMillis) {
    private val _latest = MutableStateFlow<ReleaseInfo?>(null)
    val latest: StateFlow<ReleaseInfo?> = _latest.asStateFlow()

    private val mutex = Mutex()
    private var checkedAt: Long? = null

    suspend fun refresh() = mutex.withLock {
        val at = checkedAt
        if (at != null && clock() - at <= TTL_MS) return@withLock
        when (val result = source.latest()) {
            is ReleaseResult.Found -> keep(result.info)
            ReleaseResult.None -> keep(null)
            ReleaseResult.Failed -> Unit
        }
    }

    private fun keep(info: ReleaseInfo?) {
        _latest.value = info
        checkedAt = clock()
    }

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
    }
}
