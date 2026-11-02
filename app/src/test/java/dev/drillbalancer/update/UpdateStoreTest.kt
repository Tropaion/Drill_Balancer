package dev.drillbalancer.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun remembersEachKindApart() = runTest {
        val store = UpdateStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("a.preferences_pb") })
        store.dismissFirmware("0.2.0")
        assertEquals("0.2.0", store.dismissedFirmware.first())
        assertNull(store.dismissedApp.first())
        store.dismissApp("0.3.0")
        assertEquals("0.3.0", store.dismissedApp.first())
        assertEquals("0.2.0", store.dismissedFirmware.first())
    }
}
