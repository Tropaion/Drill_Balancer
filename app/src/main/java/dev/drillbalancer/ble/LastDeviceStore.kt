package dev.drillbalancer.ble

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bleStore: DataStore<Preferences> by preferencesDataStore(name = "ble")

/** The address of the last device connected to, shown first on the connect screen. */
class LastDeviceStore(context: Context) {
    private val store = context.applicationContext.bleStore

    val address: Flow<String?> = store.data.map { it[KEY_ADDRESS] }

    suspend fun set(address: String) {
        store.edit { it[KEY_ADDRESS] = address }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("last_address")
    }
}
