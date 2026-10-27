package dev.drillbalancer.upload

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Where and how recordings are uploaded. */
data class UploadSettings(
    val repo: String = DEFAULT_REPO,       // owner/name of the private data repo
    val wifiOnly: Boolean = true,          // uploads wait for an unmetered network
    val keepLocal: Boolean = false,        // false: stream.dbr is deleted after a successful upload
    val muteAudio: Boolean = false,        // the audio is muted while the motor is off (AudioMute)
) {
    companion object {
        const val DEFAULT_REPO = "Tropaion/Drill_Balancer_Data"
    }
}

/** The upload settings (DataStore "upload"; in memory in the ViewModel tests). */
interface UploadPrefs {
    val settings: Flow<UploadSettings>
    suspend fun update(change: (UploadSettings) -> UploadSettings)
    suspend fun current(): UploadSettings = settings.first()
}

/** [UploadSettings] in DataStore "upload" (next to the sealed token). */
class UploadSettingsStore(private val store: DataStore<Preferences>) : UploadPrefs {
    // the DataStore holds the sealed token too: its edits are no new settings
    override val settings: Flow<UploadSettings> = store.data.map(::read).distinctUntilChanged()

    override suspend fun update(change: (UploadSettings) -> UploadSettings) {
        store.edit { p ->
            val next = change(read(p))
            require(HttpGitHubApi.parseRepo(next.repo) != null) { "not owner/name" }
            p[REPO] = next.repo.trim()
            p[WIFI_ONLY] = next.wifiOnly
            p[KEEP_LOCAL] = next.keepLocal
            p[MUTE] = next.muteAudio
        }
    }

    private companion object {
        fun read(p: Preferences) = UploadSettings(
            repo = p[REPO]?.takeIf { HttpGitHubApi.parseRepo(it) != null } ?: UploadSettings.DEFAULT_REPO,
            wifiOnly = p[WIFI_ONLY] ?: true,
            keepLocal = p[KEEP_LOCAL] ?: false,
            muteAudio = p[MUTE] ?: false,
        )

        val REPO = stringPreferencesKey("repo")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val KEEP_LOCAL = booleanPreferencesKey("keep_local")
        val MUTE = booleanPreferencesKey("mute_audio")
    }
}
