package dev.drillbalancer.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "updates")

/** The versions whose update notice the user dismissed: only a newer one shows again. */
class UpdateStore(private val store: DataStore<Preferences>) {
    val dismissedFirmware: Flow<String?> = store.data.map { it[KEY_FIRMWARE] }
    val dismissedApp: Flow<String?> = store.data.map { it[KEY_APP] }

    suspend fun dismissFirmware(version: String) {
        store.edit { it[KEY_FIRMWARE] = version }
    }

    suspend fun dismissApp(version: String) {
        store.edit { it[KEY_APP] = version }
    }

    private companion object {
        val KEY_FIRMWARE = stringPreferencesKey("dismissed_firmware")
        val KEY_APP = stringPreferencesKey("dismissed_app")
    }
}
