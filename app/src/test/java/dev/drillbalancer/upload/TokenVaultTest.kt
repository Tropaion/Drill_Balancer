package dev.drillbalancer.upload

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * TokenVault with a software AES-256 key in place of the AndroidKeyStore one (Robolectric has
 * no AndroidKeyStore; the GCM code is the app's), and UploadSettingsStore, on DataStore files.
 */
class TokenVaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file by lazy { File(tmp.root, "upload.preferences_pb") }
    private val store: DataStore<Preferences> by lazy { PreferenceDataStoreFactory.create(scope = scope) { file } }

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = key()
    private val vault by lazy { TokenVault(store, GcmCipher { key }) }
    private val token = FakeGitHub.TOKEN

    @After
    fun close() = scope.cancel()

    @Test
    fun theTokenIsSealedAndReadBack() = runBlocking {
        assertEquals(TokenState(), vault.state.first())
        assertNull(vault.token())
        vault.set("  $token\n")
        assertEquals(TokenState(set = true), vault.state.first())
        assertEquals(token, vault.token())
        // only ciphertext on storage
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(token.takeLast(12)))
        assertFalse(token in vault.toString())
    }

    @Test
    fun everySealHasItsOwnIv() {
        val c = GcmCipher { key }
        val a = c.seal(token.toByteArray())
        val b = c.seal(token.toByteArray())
        assertEquals(12, a.iv.size)
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.data.contentEquals(b.data))
        assertEquals(token, String(c.open(a)))
        assertFalse(token in a.toString())
        // a changed byte: refused (GCM's tag)
        val bad = Sealed(a.iv, a.data.copyOf().also { it[0] = (it[0] + 1).toByte() })
        try {
            c.open(bad)
            fail("no AEADBadTagException")
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }

    private suspend fun idOf(v: TokenVault = vault) = (v.read() as TokenRead.Ok).id

    @Test
    fun rejectRemoveReplace() = runBlocking {
        assertFalse("no token: nothing to reject", vault.reject("x"))
        assertFalse(vault.state.first().rejected)
        vault.set(token)
        val id = idOf()
        assertTrue(vault.reject(id))
        assertEquals(TokenState(set = true, rejected = true), vault.state.first())
        assertEquals("a rejected token is still there", token, vault.token())
        vault.set("fake_token_" + "b".repeat(40))
        assertEquals("a new token clears it", TokenState(set = true), vault.state.first())
        assertEquals("fake_token_" + "b".repeat(40), vault.token())
        vault.remove()
        assertEquals(TokenState(), vault.state.first())
        assertNull(vault.token())
        assertEquals(TokenRead.None, vault.read())
    }

    /**
     * A 401 for the token an upload read before the user replaced it does not mark the new one
     * rejected; GitHub accepting a token clears its mark, only that token's.
     */
    @Test
    fun aRejectIsForTheTokenThatFailed() = runBlocking {
        vault.set(token)
        val old = idOf()
        vault.set("fake_token_" + "c".repeat(40))
        val new = idOf()
        assertNotEquals(old, new)
        assertFalse(vault.reject(old))
        assertEquals(TokenState(set = true), vault.state.first())
        assertTrue(vault.reject(new))
        assertFalse(vault.accepted(old))
        assertTrue(vault.state.first().rejected)
        assertTrue(vault.accepted(new))
        assertEquals(TokenState(set = true), vault.state.first())
        assertFalse("nothing to clear", vault.accepted(new))
        assertFalse(token in TokenRead.Ok(token, new).toString())
    }

    @Test
    fun whatIsNoTokenIsRefusedWithoutEchoingIt() = runBlocking {
        for (bad in listOf("", "   ", "short", "fake_token_with space inside 1234567890", "x".repeat(256), "fake_ä0123456789012345678")) {
            try {
                vault.set(bad)
                fail("took $bad")
            } catch (e: IllegalArgumentException) {
                if (bad.isNotBlank()) assertFalse(bad in e.message!!)
            }
        }
        assertEquals(TokenState(), vault.state.first())
        assertNull(TokenStore.problem(token))
        assertNull(TokenStore.problem("fake_classic_" + "a".repeat(30)))
    }

    @Test
    fun aTokenSealedUnderAKeyThatIsGoneIsDropped() = runBlocking {
        vault.set(token)
        val other = TokenVault(store, GcmCipher { key() })     // the keystore was reset: a new key
        assertEquals(TokenRead.None, other.read())
        assertEquals(TokenState(), other.state.first())
        // the keystore failing once is no reason to drop it
        vault.set(token)
        val failing = TokenVault(store, object : AeadCipher {
            override fun seal(plain: ByteArray) = throw IllegalStateException("keystore busy")
            override fun open(sealed: Sealed) = throw IllegalStateException("keystore busy")
        })
        assertNull(failing.token())
        assertEquals("not \"no token\": try again later", TokenRead.Unreadable, failing.read())
        assertEquals(TokenState(set = true), failing.state.first())
        assertEquals(token, vault.token())
    }

    /** The cipher (the keystore: an AES-GCM seal, making the key) runs on the vault's IO dispatcher, never on the caller's (the screens: main). */
    @Test
    fun theCipherRunsOffTheCallersThread() = runBlocking {
        val io = Executors.newSingleThreadExecutor { Thread(it, "vault-io") }
        try {
            val threads = Collections.synchronizedList(ArrayList<String>())
            val gcm = GcmCipher { key }
            val v = TokenVault(store, object : AeadCipher {
                override fun seal(plain: ByteArray) = gcm.seal(plain).also { threads += Thread.currentThread().name.substringBefore(" @") }
                override fun open(sealed: Sealed) = gcm.open(sealed).also { threads += Thread.currentThread().name.substringBefore(" @") }
            }, io.asCoroutineDispatcher())
            v.set(token)
            assertEquals(token, v.token())
            assertEquals(listOf("vault-io", "vault-io"), threads.toList())
        } finally {
            io.shutdown()
        }
    }

    @Test
    fun uploadSettingsDefaultsAndChanges() = runBlocking {
        val s = UploadSettingsStore(store)
        assertEquals(UploadSettings("Tropaion/Drill_Balancer_Data", wifiOnly = true, keepLocal = false, muteAudio = false), s.settings.first())
        s.update { it.copy(repo = " me/data ", wifiOnly = false, keepLocal = true, muteAudio = true) }
        assertEquals(UploadSettings("me/data", wifiOnly = false, keepLocal = true, muteAudio = true), s.current())
        try {
            s.update { it.copy(repo = "not a repo") }
            fail()
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("me/data", s.current().repo)
        // the token beside them is not touched
        vault.set(token)
        s.update { it.copy(wifiOnly = true) }
        assertEquals(token, vault.token())
        assertNotEquals(s.current(), UploadSettings())
    }
}

/** The token for a request; null when none is set or it cannot be read now. */
private suspend fun TokenStore.token(): String? = (read() as? TokenRead.Ok)?.token
