package dev.drillbalancer.ble

import dev.drillbalancer.protocol.RecorderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * rec_state in the order the link delivered it. Notifications are applied on the BLE thread
 * as they come; a read's answer comes on that thread in order with them, but its caller
 * resumes later, when a newer notification may already be applied. So the read [stamp]s the
 * moment its answer arrived (on the BLE thread) and its value counts only if nothing was
 * [set] after that ([answered]). Thread-safe.
 */
internal class RecStateOrder {
    private val _value = MutableStateFlow<RecorderStatus?>(null)
    val value: StateFlow<RecorderStatus?> = _value.asStateFlow()

    private var version = 0L

    /** A notification, or the link's own change (set up, gone): always the newest. */
    @Synchronized
    fun set(st: RecorderStatus?) {
        version++
        _value.value = st
    }

    /** Where the order stood when a read's answer arrived. */
    @Synchronized
    fun stamp(): Long = version

    /** The read's answer [st], stamped at [at]: what rec_state is now (a newer notification wins). */
    @Synchronized
    fun answered(st: RecorderStatus, at: Long): RecorderStatus {
        if (version == at) {
            _value.value = st
            return st
        }
        return _value.value?.takeIf { it.read } ?: st
    }
}
