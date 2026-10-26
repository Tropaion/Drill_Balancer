package dev.drillbalancer.recorder

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val Context.recorderStore: DataStore<Preferences> by preferencesDataStore(name = "recorder")

/** Where the setup is remembered (DataStore "recorder"; in memory in tests). */
interface SetupPrefs {
    val setup: Flow<SessionSetup>
    suspend fun save(setup: SessionSetup)
}

/** DataStore "recorder": the last session setup, and the install id (made once, never derived from the phone). */
class SetupStore(context: Context) : SetupPrefs {
    private val store = context.applicationContext.recorderStore
    private val idLock = Mutex()

    override val setup: Flow<SessionSetup> = store.data.map { p -> decode(p[KEY_SETUP]) }

    override suspend fun save(setup: SessionSetup) {
        store.edit { it[KEY_SETUP] = encode(setup) }
    }

    suspend fun installId(): String = idLock.withLock {
        store.data.first()[KEY_INSTALL]?.takeIf { it.length == RecordingIds.INSTALL_ID_LEN }
            ?: RecordingIds.newInstallId().also { id -> store.edit { it[KEY_INSTALL] = id } }
    }

    companion object {
        private val KEY_SETUP = stringPreferencesKey("setup")
        private val KEY_INSTALL = stringPreferencesKey("install_id")
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** The setup as it is kept: JSON of the sanitized setup. */
        fun encode(setup: SessionSetup): String = json.encodeToString(SessionSetup.serializer(), setup.sanitized())

        /**
         * A kept setup; keys it lacks (an older app's: drill_type, motor) take their defaults
         * (unknown), and one that cannot be read is the default setup.
         */
        fun decode(text: String?): SessionSetup =
            text?.let { runCatching { json.decodeFromString(SessionSetup.serializer(), it) }.getOrNull() } ?: SessionSetup()
    }
}
