package dev.drillbalancer.upload

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The upload settings in memory. */
class FakeUploadPrefs(initial: UploadSettings = UploadSettings()) : UploadPrefs {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<UploadSettings> = flow

    override suspend fun update(change: (UploadSettings) -> UploadSettings) = flow.update(change)
}

/**
 * A token store in memory (TokenVault's rules: set clears rejected, reject and accepted only
 * for the token stored now, each set a new [id]); [unreadable]: read() fails as a busy keystore does.
 */
class FakeTokenStore(token: String? = null) : TokenStore {
    @Volatile var value: String? = token
        private set
    @Volatile var id: String = "t0"
        private set
    @Volatile var unreadable = false
    private var n = 0
    val flow = MutableStateFlow(TokenState(set = token != null))
    override val state: Flow<TokenState> = flow
    val sets = ArrayList<String>()

    override suspend fun set(token: String) {
        TokenStore.problem(token)?.let { throw IllegalArgumentException(it) }
        synchronized(this) {
            value = token.trim()
            id = "t${++n}"
        }
        sets += token.trim()
        flow.value = TokenState(set = true)
    }

    override suspend fun remove() {
        value = null
        flow.value = TokenState()
    }

    override suspend fun read(): TokenRead = synchronized(this) {
        val v = value
        when {
            v == null -> TokenRead.None
            unreadable -> TokenRead.Unreadable
            else -> TokenRead.Ok(v, id)
        }
    }

    override suspend fun reject(id: String): Boolean {
        if (value == null || id != this.id) return false
        flow.value = flow.value.copy(rejected = true)
        return true
    }

    override suspend fun accepted(id: String): Boolean {
        if (value == null || id != this.id || !flow.value.rejected) return false
        flow.value = flow.value.copy(rejected = false)
        return true
    }
}

/** A scheduler that records what is asked of it; [active] is set by the test. */
class FakeScheduler : UploadScheduler {
    data class Enqueued(val id: String, val wifiOnly: Boolean, val delayMs: Long, val overwrite: Boolean = false, val restart: Boolean = false)

    val activeFlow = MutableStateFlow<Map<String, ActiveUpload>>(emptyMap())
    override val active: Flow<Map<String, ActiveUpload>> = activeFlow
    val enqueued = ArrayList<Enqueued>()
    var cancels = 0

    override suspend fun enqueue(id: String, wifiOnly: Boolean, delayMs: Long, overwrite: Boolean, restart: Boolean) {
        synchronized(enqueued) { enqueued += Enqueued(id, wifiOnly, delayMs, overwrite, restart) }
    }

    override fun cancelAll() {
        cancels++
    }
}
