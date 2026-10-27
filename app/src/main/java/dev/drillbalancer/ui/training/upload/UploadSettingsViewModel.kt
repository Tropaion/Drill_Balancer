package dev.drillbalancer.ui.training.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drillbalancer.recorder.RecordingStore
import dev.drillbalancer.ui.components.UiMessage
import dev.drillbalancer.upload.ActiveUpload
import dev.drillbalancer.upload.GitHubApi
import dev.drillbalancer.upload.GitHubException
import dev.drillbalancer.upload.HttpGitHubApi
import dev.drillbalancer.upload.TokenRead
import dev.drillbalancer.upload.TokenState
import dev.drillbalancer.upload.TokenStore
import dev.drillbalancer.upload.UploadCounts
import dev.drillbalancer.upload.UploadQueue
import dev.drillbalancer.upload.UploadServices
import dev.drillbalancer.upload.UploadSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What Test connection found. */
sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest

    /** The repository answered: [good] what is right, [warnings] what to fix (none: ready to upload). */
    data class Done(val good: List<String>, val warnings: List<String>) : ConnectionTest

    /** [tokenRejected]: GitHub answered 401 (the token is marked rejected). */
    data class Failed(val message: String, val tokenRejected: Boolean = false) : ConnectionTest
}

data class UploadSettingsUiState(
    val loaded: Boolean = false,
    val settings: UploadSettings = UploadSettings(),
    val repoText: String = settings.repo,           // the field; Save repository takes it
    val repoError: String? = null,
    val token: TokenState = TokenState(),
    val tokenDialog: Boolean = false,               // Set / Replace open
    val tokenError: String? = null,
    val test: ConnectionTest = ConnectionTest.Idle,
    val counts: UploadCounts? = null,               // null: not counted yet, or the count failed
    val message: UiMessage? = null,
) {
    val repoChanged: Boolean get() = repoText.trim() != settings.repo

    /** Test connection asks about the saved repository: off while the field holds another one, or without a token. */
    val canTest: Boolean get() = loaded && token.set && !repoChanged && test != ConnectionTest.Running

    /** Nothing waits to be sent, as far as the counts know (unknown counts: not known to be nothing). */
    val nothingToSend: Boolean get() = counts?.toSend == 0

    /**
     * Upload now: not without a token, nor with one GitHub rejected (every upload would fail the
     * same way), nor with nothing to send.
     */
    val canUploadNow: Boolean get() = loaded && token.set && !token.rejected && !nothingToSend
}

/**
 * The upload settings: the data repository, the token (write-only: set, replace or remove; the
 * screen only learns whether one is set), Wi-Fi only, keep local copies, mute audio, Test
 * connection and the queue. A new token sends every reviewed recording not uploaded yet (a
 * rejected token stopped the queue); Wi-Fi only puts every queued upload under the new rule.
 */
class UploadSettingsViewModel(
    private val services: UploadServices,
    private val store: RecordingStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val repoText: String? = null,
        val repoError: String? = null,
        val tokenDialog: Boolean = false,
        val tokenError: String? = null,
        val test: ConnectionTest = ConnectionTest.Idle,
        val counts: UploadCounts? = null,
        val message: UiMessage? = null,
    )

    private val local = MutableStateFlow(Local())

    // loaded once the settings were read: the initial state until then
    val state: StateFlow<UploadSettingsUiState> = combine(services.prefs.settings, services.tokens.state, local) { s, t, l ->
        UploadSettingsUiState(
            loaded = true,
            settings = s,
            repoText = l.repoText ?: s.repo,
            repoError = l.repoError,
            token = t,
            tokenDialog = l.tokenDialog,
            tokenError = l.tokenError,
            test = l.test,
            counts = l.counts,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UploadSettingsUiState())

    // A reload asked while one counts is one more after it: a burst (the screen's open, an upload's end) is not a scan each.
    private val reloads = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        viewModelScope.launch { reloads.collect { count() } }
        reload()
        viewModelScope.launch { store.changes.drop(1).collect { reload() } }
        // the counts change with an upload's start and end, not its progress
        viewModelScope.launch {
            services.queue.active.map { a -> a.mapValues { it.value is ActiveUpload.Running } }.distinctUntilChanged().drop(1).collect { reload() }
        }
        viewModelScope.launch { services.tokens.state.drop(1).collect { reload() } }
    }

    /** The queue counts again. */
    fun reload() {
        reloads.tryEmit(Unit)
    }

    private suspend fun count() {
        val repo = services.prefs.current().repo
        val active = services.queue.active.first()
        val rejected = services.tokens.state.first().rejected
        val counts = withContext(io) {
            try {
                val entries = store.list()
                UploadQueue.counts(entries, services.queue.local(entries, repo), active, rejected)
            } catch (_: IOException) {
                null
            }
        }
        // a failed count is unknown, not nothing to send
        local.update { it.copy(counts = counts) }
    }

    fun editRepo(text: String) = local.update { it.copy(repoText = text, repoError = null) }

    fun saveRepo() {
        val text = state.value.repoText.trim()
        if (HttpGitHubApi.parseRepo(text) == null) {
            local.update { it.copy(repoError = "Write it as owner/name, for example ${UploadSettings.DEFAULT_REPO}.") }
            return
        }
        viewModelScope.launch {
            services.prefs.update { it.copy(repo = text) }
            local.update { it.copy(repoText = null, repoError = null, test = ConnectionTest.Idle) }
            reload()
        }
    }

    fun setWifiOnly(on: Boolean) {
        viewModelScope.launch {
            services.prefs.update { it.copy(wifiOnly = on) }
            services.queue.requeue()
        }
    }

    fun setKeepLocal(on: Boolean) = change { it.copy(keepLocal = on) }

    fun setMuteAudio(on: Boolean) = change { it.copy(muteAudio = on) }

    private fun change(f: (UploadSettings) -> UploadSettings) {
        viewModelScope.launch { services.prefs.update(f) }
    }

    fun openTokenDialog() = local.update { it.copy(tokenDialog = true, tokenError = null) }

    fun closeTokenDialog() = local.update { it.copy(tokenDialog = false, tokenError = null) }

    /** Keeps [token] (never in this state); then the queue runs again. */
    fun setToken(token: String) {
        TokenStore.problem(token)?.let { p ->
            local.update { it.copy(tokenError = p) }
            return
        }
        viewModelScope.launch {
            try {
                services.tokens.set(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(tokenError = "The token could not be kept (${e.javaClass.simpleName}).") }
                return@launch
            }
            local.update { it.copy(tokenDialog = false, tokenError = null, test = ConnectionTest.Idle) }
            val n = services.queue.uploadNow()
            if (n > 0) local.update { it.copy(message = UiMessage(queuedText(n), UiMessage.Kind.INFO)) }
        }
    }

    fun removeToken() {
        viewModelScope.launch {
            services.tokens.remove()
            local.update { it.copy(test = ConnectionTest.Idle) }
        }
    }

    /** Every reviewed recording not uploaded yet into the queue. */
    fun uploadNow() {
        viewModelScope.launch {
            if (services.tokens.state.first().rejected) {
                local.update { it.copy(message = UiMessage("GitHub rejected the token: replace it first.", UiMessage.Kind.ERROR)) }
                return@launch
            }
            val n = services.queue.uploadNow()
            local.update { it.copy(message = UiMessage(if (n == 0) "Nothing to upload: every reviewed recording is uploaded." else queuedText(n), UiMessage.Kind.INFO)) }
            reload()
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    /**
     * GET the repository (exists, private, push permitted, size) and the dataset template in it,
     * with the saved repository. A 401 marks the token rejected (as an upload's does); GitHub
     * answering marks a rejected token good again (the 401 has gone away) and sends the
     * recordings not uploaded yet.
     */
    fun testConnection() {
        if (state.value.test == ConnectionTest.Running) return
        viewModelScope.launch {
            local.update { it.copy(test = ConnectionTest.Running) }
            val repo = services.prefs.current().repo
            val result = when (val t = services.tokens.read()) {
                TokenRead.None -> ConnectionTest.Failed("Set a token first.")
                TokenRead.Unreadable -> ConnectionTest.Failed("The phone could not read the token (its keystore failed). Test again; if it keeps failing, set the token again.")
                is TokenRead.Ok -> {
                    val (o, n) = HttpGitHubApi.parseRepo(repo) ?: ("" to "")
                    val r = withContext(io) { test(services.api(o, n, t.token)) }
                    if (r is ConnectionTest.Failed && r.tokenRejected) services.tokens.reject(t.id)
                    if (r is ConnectionTest.Done && services.tokens.accepted(t.id)) {
                        val queued = services.queue.uploadNow()
                        if (queued > 0) local.update { it.copy(message = UiMessage("GitHub accepts the token again. " + queuedText(queued), UiMessage.Kind.INFO)) }
                    }
                    r
                }
            }
            local.update { it.copy(test = result) }
        }
    }

    private fun queuedText(n: Int) = if (n == 1) "1 recording is queued for upload." else "$n recordings are queued for upload."

    companion object {
        const val TEMPLATE_FILE = "schema/meta.v1.schema.json"
        const val SIZE_WARNING_KB = 1L shl 20            // 1 GB
        const val TEST_TOKEN_REJECTED = "GitHub rejected the token. Replace it above."

        /** What [api]'s repository says about itself. Blocking. */
        fun test(api: GitHubApi): ConnectionTest = try {
            val r = api.repo()
            val good = ArrayList<String>()
            val warn = ArrayList<String>()
            good += "Found ${r.fullName}."
            if (r.isPrivate) good += "It is private." else warn += "It is public: recordings may hold voices. Make it private on GitHub."
            if (r.canPush) good += "You may write to it." else warn += "No write access: give the token Contents read and write for this repository."
            if (api.fileExists(TEMPLATE_FILE)) good += "The dataset template is there." else warn += "No dataset template ($TEMPLATE_FILE): push it from the PC first (bootstrap_repo.py)."
            if (r.sizeKb >= SIZE_WARNING_KB) warn += "It holds ${r.sizeKb / 1024} MB. Near 5 GB start a new repository for the next year."
            ConnectionTest.Done(good, warn)
        } catch (e: GitHubException.Unauthorized) {
            ConnectionTest.Failed(TEST_TOKEN_REJECTED, tokenRejected = true)
        } catch (e: GitHubException.NotFound) {
            ConnectionTest.Failed("Repo not found or no access. Check the name and that the token has access to this repository.")
        } catch (e: GitHubException.RateLimited) {
            ConnectionTest.Failed("GitHub's rate limit: try again in ${maxOf(1, e.waitMs / 60_000)} min.")
        } catch (e: GitHubException.Forbidden) {
            ConnectionTest.Failed("GitHub refused: the token may not read or write this repository. Give it Contents read and write.")
        } catch (e: GitHubException.Server) {
            ConnectionTest.Failed("GitHub had a problem (HTTP ${e.status}). Test again later.")
        } catch (e: GitHubException) {
            ConnectionTest.Failed("GitHub answered HTTP ${e.status}: ${e.message}")
        } catch (e: IOException) {
            ConnectionTest.Failed("No connection to GitHub. Check the network, then test again.")
        }
    }
}
