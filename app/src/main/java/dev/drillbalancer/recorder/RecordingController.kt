package dev.drillbalancer.recorder

import android.util.Log
import dev.drillbalancer.ble.GattErrors
import dev.drillbalancer.device.ConnectionState
import dev.drillbalancer.device.DeviceError
import dev.drillbalancer.device.DeviceSession
import dev.drillbalancer.protocol.AttError
import dev.drillbalancer.protocol.RecCtrl
import dev.drillbalancer.protocol.RecReason
import dev.drillbalancer.protocol.RecState
import dev.drillbalancer.protocol.RecorderStatus
import dev.drillbalancer.protocol.TakeRequest
import dev.drillbalancer.recorder.net.LinkException
import dev.drillbalancer.recorder.net.LinkPhase
import dev.drillbalancer.recorder.net.StreamEndpoint
import dev.drillbalancer.recorder.net.StreamLink
import dev.drillbalancer.recorder.net.StreamLinkFactory
import dev.drillbalancer.recorder.stream.RecStream
import dev.drillbalancer.recorder.stream.StreamDecoder
import dev.drillbalancer.recorder.stream.StreamRecord
import dev.drillbalancer.recorder.stream.StreamStatsTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * The recording engine, at app scope so it outlives the screens and a BLE drop. A session
 * (the device's soft-AP, one stream connection) holds many takes; each take is one
 * recording folder whose journal gets the stream's bytes unchanged.
 *
 * Open: OPEN over BLE, wait for rec_state's endpoint, join the Wi-Fi ([StreamLink]), TCP +
 * HELLO, wait for the device's HELLO_OK. Take: TAKE_START, bytes into the journal until END.
 * Stop: STOP, END within 5 s, else the stream is closed (the device stops a take whose client
 * leaves) and opened again. Close: CLOSE, the device drains and ends the stream with a FIN,
 * then the network is released. A take survives a BLE drop (the device keeps it 120 s); the
 * stream connection dropping cuts the take (kept as interrupted) and reconnects.
 *
 * The read loop runs on [io]; the operations one at a time on [scope]. Whatever else one of
 * them throws ends the session as FAILED ([guard]), never the process.
 */
class RecordingController(
    private val session: DeviceSession,
    private val store: RecordingStore,
    private val links: StreamLinkFactory,
    private val installId: suspend () -> String,
    private val app: AppInfo,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timing: RecorderTiming = RecorderTiming(),
    private val clock: RecorderClock = RecorderClock.SYSTEM,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(RecordingState(bleLinked = session.connection.value is ConnectionState.Connected))
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /** The phase alone, for who needs no more: [state] changes up to 10 times a second during a take. */
    val phase: StateFlow<RecordPhase> = _state.map { it.phase }.stateIn(scope, SharingStarted.Eagerly, _state.value.phase)

    private val ops = Mutex()
    private val lock = Any()
    private var opJob: Job? = null
    private val lossJobs = SupervisorJob(scope.coroutineContext[Job])   // dropped connections' handling (reconnects)
    private var setup = SessionSetup()
    private var deviceOpened = false
    @Volatile private var closing = false
    @Volatile private var link: StreamLink? = null
    @Volatile private var conn: Connection? = null
    @Volatile private var endpoint: StreamEndpoint? = null   // of the session, for reconnects
    private var sink: TakeSink? = null     // guarded by lock

    /** A bug (not a device or network failure): the session ends as FAILED, its takes kept. */
    private val guard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "recorder failed", e)
        opJob?.cancel()
        lossJobs.cancelChildren()
        _state.update {
            if (!it.phase.active) {
                it
            } else {
                it.copy(
                    phase = RecordPhase.FAILED, recordingId = null, stalled = false,
                    error = RecordError("The recorder stopped", "Something went wrong in the app (${e.message ?: e}). Recorded takes are kept."),
                )
            }
        }
        scope.launch(cleanupGuard) { ops.withLock { teardown() } }
    }
    private val cleanupGuard = CoroutineExceptionHandler { _, e -> Log.e(TAG, "recorder clean-up failed", e) }

    private class Connection(val socket: Socket) {
        val decoder = StreamDecoder()
        val closed = CompletableDeferred<Throwable?>()   // null: the device ended the stream (FIN)
        val taken = CompletableDeferred<Unit>()          // HELLO_OK: the device holds this connection
        @Volatile var expected = false                    // closed on purpose: nothing to recover
        @Volatile var lastDataMs = 0L
        var lastPublishMs = 0L
    }

    private class TakeSink(val writer: JournalWriter) {
        val tracker = StreamStatsTracker()
        val preview = LivePreview()
        val ended = CompletableDeferred<StreamRecord.End?>()
        val sessionSeen = CompletableDeferred<Unit>()
        @Volatile var answered = false                   // TAKE_START answered: the stall check runs
        var skippedBytes = 0L                            // dropped by the decoder while resynchronising
        val rate = ArrayDeque<Pair<Long, Long>>()        // (ms, bytes) over the last second
        var levels = AudioLevels()
    }

    private class StallException(quietMs: Long) : IOException("no data for ${quietMs / 1000} s")

    private class Failure(val title: String, text: String) : Exception(text)

    init {
        scope.launch(guard) {
            var last = session.connection.value
            session.connection.collect { c ->
                val linked = c is ConnectionState.Connected
                _state.update { it.copy(bleLinked = linked) }
                // Back after a drop (or bonded now): read rec_state again and subscribe.
                if (linked && c != last && _state.value.phase.active) quietly { session.refreshRecorder() }
                last = c
            }
        }
    }

    // ---- operations (one at a time) ----

    /** Opens a recording session with [setup] (the screen's gate in [secure]). */
    fun open(setup: SessionSetup, secure: Secure) {
        if (_state.value.phase.active) return
        launchOp { doOpen(setup.sanitized(), secure, retry = false) }
    }

    /** After FAILED: opens again with the same setup and session id (the device may still have the session). */
    fun retry(secure: Secure) {
        if (_state.value.phase != RecordPhase.FAILED) return
        launchOp { doOpen(setup, secure, retry = true) }
    }

    fun startTake(secure: Secure) {
        if (_state.value.phase != RecordPhase.READY) return
        launchOp { doStartTake(secure) }
    }

    fun stopTake() {
        if (_state.value.phase != RecordPhase.RECORDING) return
        launchOp { doStop(reconnect = true) }
    }

    /**
     * MARK during a take: an EVENT MARK in the stream. False, and nothing sent, outside a take
     * or for a code or text the device refuses (RecCtrl.Mark).
     */
    fun mark(code: Int, text: String = ""): Boolean {
        try {
            RecCtrl.Mark(code, text)   // its checks are the device's
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (_state.value.phase != RecordPhase.RECORDING) return false
        scope.launch(guard) { quietly { session.mark(code, text) } }
        return true
    }

    /** Ends the session (stopping a take first); cancels a session still being set up. */
    fun close() {
        val phase = _state.value.phase
        if (!phase.active && !(phase == RecordPhase.FAILED && deviceOpened)) return
        opJob?.cancel()
        lossJobs.cancelChildren()
        launchOp { doClose() }
    }

    /** DONE or FAILED back to IDLE. */
    fun dismiss() {
        if (_state.value.phase == RecordPhase.FAILED && deviceOpened) return close()
        _state.update { if (it.phase.active) it else RecordingState(bleLinked = it.bleLinked) }
    }

    /** Journals of a run that died are cut to their last record and marked interrupted (once at start-up). */
    fun recover(): Job = scope.launch(io + cleanupGuard) {
        val active = synchronized(lock) { sink?.writer?.journal?.recordingId }
        try {
            val ids = store.recover(setOfNotNull(active))
            if (ids.isNotEmpty()) Log.i(TAG, "recovered interrupted takes: $ids")
        } catch (e: IOException) {
            Log.w(TAG, "recovery failed", e)
        }
    }

    private fun launchOp(block: suspend () -> Unit) {
        val job = scope.launch(guard, start = CoroutineStart.LAZY) { ops.withLock { block() } }
        opJob = job
        job.start()
    }

    // ---- open ----

    private suspend fun doOpen(setup: SessionSetup, secure: Secure, retry: Boolean) {
        this.setup = setup
        val install = installId()
        _state.update {
            RecordingState(
                phase = RecordPhase.STARTING_DEVICE,
                sessionId = if (retry && it.sessionId != null) it.sessionId else RecordingIds.sessionId(clock.now(), install),
                setup = setup,
                takes = if (retry) it.takes else 0,
                lastTake = if (retry) it.lastTake else null,
                bleLinked = it.bleLinked,
            )
        }
        try {
            if (session.connection.value is ConnectionState.Connected && session.recorder.value == null) throw DeviceError.NoRecorder()
            secure.run { openOnDevice() }
            val ep = awaitEndpoint()
            connectStream(ep)
            _state.update { it.copy(phase = RecordPhase.READY, error = null) }
        } catch (_: Secure.Cancelled) {
            teardown()
            _state.update { RecordingState(bleLinked = it.bleLinked) }
        } catch (e: CancellationException) {
            teardown()
            throw e
        } catch (e: Exception) {
            teardown()
            fail(e, "Could not start the recording")
        }
    }

    /** OPEN, or the session the device still has; returns once rec_state is fresh. */
    private suspend fun openOnDevice() {
        var st = session.refreshRecorder()
        if (st.state == RecState.CLOSING) st = awaitStatus(timing.closeWaitMs) { it.state == RecState.IDLE } ?: st
        when (st.state) {
            // The endpoint of the last session may still be going down: OPEN is refused meanwhile.
            RecState.IDLE -> retryRecState(timing.openRetries) { session.openRecorderSession() }
            // A take nobody streams (the app restarted): stop it, the session stays.
            RecState.RECORDING, RecState.DRAINING -> session.stopTake()
            else -> Unit
        }
        deviceOpened = true
        session.refreshRecorder()
    }

    private suspend fun retryRecState(retries: Int, block: suspend () -> Unit) {
        var left = retries
        while (true) {
            try {
                return block()
            } catch (e: DeviceError.Att) {
                if (e.code != AttError.REC_STATE.code || left-- <= 0) throw e
                delay(timing.pollMs)
            }
        }
    }

    private suspend fun awaitEndpoint(): StreamEndpoint {
        val st = awaitStatus(timing.apStartMs) { it.reachable || it.state == RecState.IDLE }
            ?: throw Failure("The device's Wi-Fi did not come up", "The device did not announce its Wi-Fi in time. Try again.")
        if (st.state == RecState.IDLE) {
            deviceOpened = false
            throw Failure("The device could not start its Wi-Fi", "The recording session ended: ${(st.reason ?: RecReason.AP_FAIL).text}.")
        }
        return StreamEndpoint.of(st) ?: throw Failure("The device's Wi-Fi did not come up", "rec_state carries no usable address.")
    }

    private suspend fun connectStream(ep: StreamEndpoint) {
        val l = link ?: links.create(ep).also { link = it }
        _state.update {
            it.copy(
                phase = if (ep.noAp) RecordPhase.CONNECTING else RecordPhase.JOINING_WIFI,
                ssid = ep.ssid.takeIf { !ep.noAp }, pass = null, lan = ep.noAp,
            )
        }
        val socket = l.connect { p ->
            _state.update {
                it.copy(
                    phase = when (p) {
                        LinkPhase.JOINING_WIFI, LinkPhase.MANUAL_JOIN -> RecordPhase.JOINING_WIFI
                        LinkPhase.WAITING_APPROVAL -> RecordPhase.WAITING_APPROVAL
                        LinkPhase.CONNECTING -> RecordPhase.CONNECTING
                    },
                    pass = if (p == LinkPhase.MANUAL_JOIN) ep.pass else null,
                )
            }
        }
        _state.update { it.copy(phase = RecordPhase.CONNECTING, pass = null) }
        val c = Connection(socket)
        try {
            withContext(io) {
                socket.soTimeout = timing.readTimeoutMs
                socket.getOutputStream().apply {
                    write(RecStream.hello(ep.token))
                    flush()
                }
            }
        } catch (e: IOException) {
            closeQuietly(socket)
            throw LinkException("The connection to the recorder failed (${e.message}).", LinkException.Kind.CONNECT, e)
        }
        conn = c
        endpoint = ep
        c.lastDataMs = clock.elapsedMs()
        scope.launch(io + guard) { readLoop(c) }
        awaitReady(c)
    }

    /**
     * HELLO_OK on [c]: the device holds this connection, READY. rec_state cannot say so: after
     * a drop the device may still hold the old connection (the new one waits until it lets
     * that go), or show READY of the old one with this one just accepted. The stream closing
     * first is a refusal (a wrong token).
     */
    private suspend fun awaitReady(c: Connection) {
        val taken = withTimeoutOrNull(timing.readyMs) {
            select {
                c.taken.onAwait { true }
                c.closed.onAwait { false }
            }
        }
        if (taken == false) refused()
        if (taken == null) throw Failure("The recorder did not answer", "The device did not take the stream connection in time. Try again.")
    }

    private fun refused(): Nothing =
        throw Failure("The recorder refused the connection", "The device closed the stream at once (an old session token?). Try again.")

    /** The first rec_state (read or notified) that [pred] accepts, re-read every pollMs; null after [timeoutMs]. */
    private suspend fun awaitStatus(timeoutMs: Long, pred: (RecorderStatus) -> Boolean): RecorderStatus? =
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val poll = launch {
                    while (true) {
                        delay(timing.pollMs)
                        quietly { session.refreshRecorder() }
                    }
                }
                try {
                    session.recorder.filterNotNull().filter { it.read }.first(pred)
                } finally {
                    poll.cancel()
                }
            }
        }

    // ---- takes ----

    private suspend fun doStartTake(secure: Secure) {
        if (_state.value.phase != RecordPhase.READY || conn == null) return
        val free = withContext(io) { store.freeBytes() }
        if (free < timing.minFreeBytes) {
            notice("Only ${free / (1024 * 1024)} MB free on the phone; a recording needs at least ${timing.minFreeBytes / (1024 * 1024)} MB.", error = true)
            return
        }
        val now = clock.now()
        val install = installId()
        val request = TakeRequest(setup.profile, setup.micMask, setup.gainDb, setup.maxS, RecordingIds.takeId(random))
        val journal = TakeJournal(
            recordingId = RecordingIds.recordingId(now, install, random),
            sessionId = _state.value.sessionId ?: RecordingIds.sessionId(now, install),
            takeId = request.takeId,
            reviewer = RecordingIds.install4(install),
            startedUtc = RecordingIds.utc(now),
            setup = setup,
            take = TakeSettings(request.profile.name, request.micMask, request.gainDb, request.maxS),
            app = app,
            link = if (link?.viaAp == true) "ap" else "lan",
        )
        val writer = try {
            withContext(io) { store.create(journal, timing.syncMs, clock::elapsedMs) }
        } catch (e: IOException) {
            notice("The phone could not store the recording (${e.message}).", error = true)
            return
        }
        val s = TakeSink(writer)
        // From here on the stream's bytes go to this take: nothing is lost before its SESSION.
        synchronized(lock) { sink = s }
        _state.update { it.copy(phase = RecordPhase.RECORDING, recordingId = journal.recordingId, live = LiveView(), notice = null, stalled = false) }
        try {
            secure.run { session.startTake(request) }
            startStallClock(s)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A lost answer: the take may run all the same, and its SESSION says so.
            val running = e !is Secure.Cancelled && GattErrors.mayHaveArrived(e) && withTimeoutOrNull(timing.takeStartSeenMs) { s.sessionSeen.await() } != null
            if (running) return startStallClock(s)
            val mine = releaseSink(s)
            withContext(io) {
                writer.close()
                if (writer.bytes == 0L) store.delete(journal.recordingId)
            }
            _state.update { it.copy(phase = if (mine && it.phase == RecordPhase.RECORDING) RecordPhase.READY else it.phase, recordingId = null) }
            if (e !is Secure.Cancelled) notice(if (e is DeviceError.Att && e.error == AttError.REC_STATE) NOT_READY else errorText(e), error = true)
        }
    }

    /** The stream was quiet between takes: the take's stall check counts from now. */
    private fun startStallClock(s: TakeSink) {
        conn?.lastDataMs = clock.elapsedMs()
        s.answered = true
    }

    /**
     * STOP, then END; without END the stream is closed (the device stops a take whose client
     * left). The stream dropping meanwhile cuts the take as its loss handling would (that waits
     * behind this operation).
     */
    private suspend fun doStop(reconnect: Boolean) {
        val s = synchronized(lock) { sink } ?: return
        val c = conn
        _state.update { if (it.phase == RecordPhase.RECORDING) it.copy(phase = RecordPhase.STOPPING) else it }
        val sent = try {
            session.stopTake()
            true
        } catch (e: DeviceError) {
            Log.i(TAG, "STOP over BLE failed: ${e.message}")
            false
        }
        val dropped = sent && withTimeoutOrNull(timing.endWaitMs) {
            select {
                s.ended.onAwait { false }
                if (c != null) c.closed.onAwait { true }
            }
        } == true
        // END, or the take ended otherwise (a journal that could not be written).
        if (s.ended.isCompleted) return
        if (dropped && c != null) {
            val cause = c.closed.await()
            if (reconnect) onConnectionLost(c, cause) else cutTake(c, cause)
            return
        }
        val mine = releaseSink(s)
        if (!mine) return
        val note = if (sent) "no END within ${timing.endWaitMs / 1000} s of STOP" else "stopped by closing the stream (no Bluetooth)"
        withContext(NonCancellable + io) { finishTake(s, JournalState.INTERRUPTED, if (sent) "unknown" else "client_lost", note, null, toReady = false) }
        val cur = conn ?: return
        cur.expected = true
        closeQuietly(cur.socket)
        cur.closed.await()
        conn = null
        if (!reconnect) return
        notice(
            if (sent) {
                "The device did not confirm the end of the take; it was kept as interrupted."
            } else {
                "Without Bluetooth the take was stopped by closing the connection; it was kept as interrupted."
            },
        )
        if (!reconnect()) {
            teardown()
            fail(Failure("Lost the connection to the recorder", "The stream could not be opened again after the take. Try again."), "")
        }
    }

    private suspend fun doClose() {
        closing = true
        try {
            conn?.takeIf { it.closed.isCompleted }?.let { dead -> cutTake(dead, dead.closed.await()) }
            if (synchronized(lock) { sink } != null) doStop(reconnect = false)
            val c = conn
            c?.expected = true
            val sent = if (deviceOpened) {
                try {
                    session.closeRecorderSession()
                    true
                } catch (e: DeviceError) {
                    Log.i(TAG, "CLOSE over BLE failed: ${e.message}")
                    false
                }
            } else {
                false
            }
            // The device drains, then ends the stream with a FIN and waits 1 s for ours.
            if (c != null && sent) withTimeoutOrNull(timing.closeWaitMs) { c.closed.await() }
            // Without CLOSE the device ends the session itself once the stream is gone
            // (WAIT_CLIENT runs out after 2 minutes): nothing to send later.
            teardown()
            _state.update {
                it.copy(
                    phase = RecordPhase.DONE, recordingId = null, stalled = false, error = null,
                    notice = if (sent || c == null) it.notice else Notice("Bluetooth is gone: the device ends the session by itself within 2 minutes."),
                )
            }
            deviceOpened = false
        } finally {
            closing = false
        }
    }

    // ---- the stream ----

    private fun readLoop(c: Connection) {
        val buf = ByteArray(READ_CHUNK)
        var cause: Throwable? = null
        try {
            val input = c.socket.getInputStream()
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (_: SocketTimeoutException) {
                    onQuiet(c)
                    continue
                }
                if (n < 0) break
                c.lastDataMs = clock.elapsedMs()
                onBytes(c, buf, n)
            }
        } catch (e: IOException) {
            cause = e
        } catch (e: Throwable) {
            c.expected = true       // a bug, not a lost connection: the guard fails the session
            cause = e
            throw e
        } finally {
            closeQuietly(c.socket)
            c.closed.complete(cause)
        }
        if (!c.expected) scope.launch(lossJobs + guard) { ops.withLock { onConnectionLost(c, cause) } }
    }

    private fun onQuiet(c: Connection) {
        val quiet = clock.elapsedMs() - c.lastDataMs
        val running = synchronized(lock) { sink?.answered == true }
        if (!running) return
        if (quiet >= timing.stallFailMs) throw StallException(quiet)
        publish(c, force = true)
    }

    private fun onBytes(c: Connection, buf: ByteArray, n: Int) {
        val start = c.decoder.offset
        val skipped = c.decoder.stats.skippedBytes
        val records = c.decoder.feed(buf, 0, n)
        if (records.any { it is StreamRecord.HelloOk }) c.taken.complete(Unit)
        val s: TakeSink
        val end: StreamRecord.End?
        val upto: Int
        synchronized(lock) {
            s = sink ?: return
            s.skippedBytes += c.decoder.stats.skippedBytes - skipped
            end = records.firstOrNull { it is StreamRecord.End } as StreamRecord.End?
            // The take's bytes end with its END; between takes the stream carries nothing.
            upto = if (end != null) (end.end - start).toInt().coerceIn(0, n) else n
        }
        // Outside the lock the operations take: the append syncs the journal once a second.
        val failed = try {
            s.writer.append(buf, 0, upto)
            null
        } catch (e: IOException) {
            e
        }
        var started = false
        synchronized(lock) {
            // Cut meanwhile (cutTake, teardown): its journal is finished, these bytes came after.
            if (sink !== s) return
            for (r in records) {
                if (end != null && r.offset > end.offset) break
                s.tracker.add(r)
                s.preview.add(r)
                if (r is StreamRecord.Session && s.sessionSeen.complete(Unit)) started = true
            }
            if (failed != null || end != null) sink = null
        }
        // The phone's time of the take's start is when its SESSION came (a pairing prompt or a
        // slow answer can hold TAKE_START's answer), which pairs it with SESSION.start_us.
        if (started) {
            try {
                s.writer.update { it.copy(startedUtc = RecordingIds.utc(clock.now())) }
            } catch (e: IOException) {
                Log.w(TAG, "take.json: ${e.message}")
            }
        }
        if (failed != null) {
            // READY again, also from STOPPING (the END it waits for no longer counts), with the notice.
            notice("The phone could not store the recording (${failed.message}). The take was stopped.", error = true)
            finishTake(s, JournalState.INTERRUPTED, "unknown", "the phone could not store it: ${failed.message}", null, toReady = true)
            scope.launch(guard) { quietly { session.stopTake() } }
        } else if (end != null) {
            finishTake(s, JournalState.COMPLETE, reasonName(end.reason), null, end, toReady = true)
        }
        publish(c, force = end != null)
    }

    /** The live view, at most every publishMs. */
    private fun publish(c: Connection, force: Boolean = false) {
        val now = clock.elapsedMs()
        if (!force && now - c.lastPublishMs < timing.publishMs) return
        c.lastPublishMs = now
        val live = synchronized(lock) {
            val s = sink ?: return
            val st = s.tracker.stats
            val bytes = s.writer.bytes
            s.rate.addLast(now to bytes)
            while (s.rate.size > 1 && now - s.rate.first().first > RATE_WINDOW_MS) s.rate.removeFirst()
            val (t0, b0) = s.rate.first()
            val kbps = if (now > t0) (bytes - b0) / 1.024f / (now - t0) else 0f
            val levels = s.preview.takeLevels()
            if (levels.any) s.levels = levels
            LiveView(
                elapsedS = st.elapsedUs / 1e6,
                sparkline = s.preview.sparkline(),
                levels = s.levels,
                kbPerS = kbps,
                bytes = bytes,
                imuHz = st.imuRateHz,
                audioHz = st.audioRateHz,
                missingRecords = st.missingRecords,
                imuGapSamples = st.imuGapSamples,
                audioGapFrames = st.audioGapFrames,
                deviceDropped = st.device?.droppedRecords ?: 0,
                quietMs = now - c.lastDataMs,
            )
        }
        _state.update { it.copy(live = live, stalled = live.quietMs >= timing.stallWarnMs) }
    }

    private fun finishTake(s: TakeSink, state: JournalState, reason: String, note: String?, end: StreamRecord.End?, toReady: Boolean) {
        val st = s.tracker.stats
        val stats = JournalStats(
            records = st.records, missingRecords = st.missingRecords, imuSamples = st.imuSamples,
            imuGapSamples = st.imuGapSamples, audioFrames = st.audioFrames, audioGapFrames = st.audioGapFrames,
            skippedBytes = synchronized(lock) { s.skippedBytes }, deviceUs = st.elapsedUs.coerceAtLeast(0),
        )
        try {
            s.writer.finish(state, reason, note, stats, clock.now())
        } catch (e: IOException) {
            Log.w(TAG, "closing the journal failed", e)
        }
        val endText = when {
            end != null -> RecReason.fromWire(end.reason)?.text ?: "ended"
            else -> "interrupted: ${note ?: reason}"
        }
        val summary = TakeSummary(s.writer.journal.recordingId, st.elapsedUs.coerceAtLeast(0) / 1e6, s.writer.bytes, end != null, endText, st.missingRecords)
        s.ended.complete(end)
        _state.update { cur ->
            val back = toReady && (cur.phase == RecordPhase.RECORDING || (cur.phase == RecordPhase.STOPPING && !closing))
            cur.copy(
                phase = if (back) RecordPhase.READY else cur.phase,
                recordingId = null,
                takes = cur.takes + 1,
                lastTake = summary,
                stalled = false,
                notice = if (end != null && end.reason != RecReason.USER.wire && cur.phase == RecordPhase.RECORDING) {
                    Notice("The device ended the take: $endText.")
                } else {
                    cur.notice
                },
            )
        }
    }

    /** The stream ended without us closing it: the take (if any) is cut; reconnect if the session is still there. */
    private suspend fun onConnectionLost(c: Connection, cause: Throwable?) {
        if (conn !== c) return
        val s = cutTake(c, cause)
        val st = session.recorder.value
        val ended = st != null && st.read && (st.state == RecState.IDLE || st.state == RecState.CLOSING)
        if (s != null) notice("The connection to the device dropped. The take was cut short and kept as interrupted.", error = true)
        if (!ended && _state.value.phase.active && reconnect()) return
        teardown()
        if (ended || (cause == null && s == null)) {
            deviceOpened = false
            val why = st?.reason?.takeIf { ended && it != RecReason.NONE && it != RecReason.USER }?.text
            _state.update {
                it.copy(phase = RecordPhase.DONE, recordingId = null, stalled = false, notice = Notice(if (why != null) "The device ended the session: $why." else "The device ended the recording session."))
            }
        } else {
            fail(Failure("Lost the connection to the recorder", if (s != null) "The take was cut short and kept as interrupted." else "The stream connection dropped."), "")
        }
    }

    /** [c] is gone: the take on it (if any) is kept as interrupted, whatever cancels the caller. */
    private suspend fun cutTake(c: Connection, cause: Throwable?): TakeSink? = withContext(NonCancellable + io) {
        conn = null
        val s = detachSink()
        if (s != null) {
            val note = when (cause) {
                null -> "the device ended the stream"
                is StallException -> "the stream stalled (${cause.message})"
                else -> "the connection dropped (${cause.message})"
            }
            finishTake(s, JournalState.INTERRUPTED, "client_lost", note, null, toReady = false)
        }
        s
    }

    /** The same session again: the link (re-joining if needed), HELLO, READY. */
    private suspend fun reconnect(): Boolean {
        val ep = endpoint ?: return false
        repeat(timing.reconnectAttempts) {
            try {
                connectStream(ep)
                _state.update { it.copy(phase = RecordPhase.READY, error = null) }
                return true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.i(TAG, "reconnect: ${e.message}")
                conn?.let { closeQuietly(it.socket) }
                conn = null
                delay(timing.reconnectDelayMs)
            }
        }
        return false
    }

    private suspend fun teardown() {
        conn?.let {
            it.expected = true
            closeQuietly(it.socket)
        }
        conn = null
        endpoint = null
        link?.close()
        link = null
        val s = detachSink()
        if (s != null) {
            withContext(NonCancellable + io) { finishTake(s, JournalState.INTERRUPTED, "client_lost", "the session ended", null, toReady = false) }
        }
    }

    // ---- helpers ----

    private fun fail(e: Exception, title: String) {
        val error = when (e) {
            is Failure -> RecordError(e.title, e.message.orEmpty())
            else -> RecordError(title, errorText(e))
        }
        _state.update { it.copy(phase = RecordPhase.FAILED, error = error, recordingId = null, stalled = false) }
    }

    /** Lets go of [s] if it is still the take's sink; false if something else did first. */
    private fun releaseSink(s: TakeSink): Boolean = synchronized(lock) { (sink === s).also { if (it) sink = null } }

    private fun detachSink(): TakeSink? = synchronized(lock) { sink.also { sink = null } }

    private fun notice(text: String, error: Boolean = false) = _state.update { it.copy(notice = Notice(text, error)) }

    private fun errorText(e: Exception): String = when (e) {
        is DeviceError.Att -> when (e.error) {
            AttError.REC_STATE -> "The recorder cannot do that now: a firmware update or a calibration runs, or the last session is still closing. Try again in a moment."
            else -> e.message.orEmpty()
        }
        else -> e.message ?: e.toString()
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: DeviceError) {
            Log.i(TAG, "ignored: ${e.message}")
        }
    }

    private fun closeQuietly(s: Socket) {
        try {
            s.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val TAG = "RecordingController"
        const val READ_CHUNK = 64 * 1024
        const val RATE_WINDOW_MS = 1_000L
        const val NOT_READY = "The recorder is not ready for a take yet. Try again in a moment."

        /** END.reason as dbrec names it (meta.take.end_reason). */
        fun reasonName(code: Int): String = RecReason.fromWire(code)?.name?.lowercase() ?: "unknown"
    }
}
