package dev.drillbalancer.upload

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** DataStore "upload": the sealed token ([TokenVault]) and the upload settings ([UploadSettingsStore]). */
val Context.uploadDataStore: DataStore<Preferences> by preferencesDataStore(name = "upload")

/** [data] sealed with [iv] (AES-GCM: the ciphertext with its 128-bit tag). */
class Sealed(val iv: ByteArray, val data: ByteArray) {
    override fun toString(): String = "Sealed(${data.size} bytes)"
}

/** Authenticated encryption for the token (the Android Keystore in the app, a software key in tests). */
interface AeadCipher {
    fun seal(plain: ByteArray): Sealed
    fun open(sealed: Sealed): ByteArray
}

/** AES-256-GCM with [key]: a fresh IV from the cipher for every seal. */
class GcmCipher(private val key: () -> SecretKey) : AeadCipher {
    override fun seal(plain: ByteArray): Sealed {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        return Sealed(c.iv, c.doFinal(plain))
    }

    override fun open(sealed: Sealed): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.iv))
        return c.doFinal(sealed.data)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * The token's AES-256 key in the AndroidKeyStore: made once, never leaves the keystore, no user
 * authentication (the upload worker reads the token in the background). Not testable on the JVM
 * (Robolectric has no AndroidKeyStore): tests give GcmCipher a software key.
 */
object KeystoreKey {
    private const val STORE = "AndroidKeyStore"
    const val ALIAS = "drillbalancer.upload.token"

    @Synchronized
    fun get(alias: String = ALIAS): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return gen.generateKey()
    }
}

/** What the screens may know of the token: whether there is one, never its value. */
data class TokenState(
    val set: Boolean = false,
    val rejected: Boolean = false,         // GitHub answered 401: the queue waits for a new token
)

/** What [TokenStore.read] found. */
sealed interface TokenRead {
    /** No token is set (or it was sealed under a key that is gone: it is then removed). */
    data object None : TokenRead

    /** A token is set but could not be opened this time (the keystore failed): try again later. */
    data object Unreadable : TokenRead

    /** The token, and [id]: which stored token it is (a new one has another), for [TokenStore.reject] and [TokenStore.accepted]. */
    class Ok(val token: String, val id: String) : TokenRead {
        override fun toString(): String = "TokenRead.Ok"
    }
}

/** The upload token, write-only for the UI (TokenVault; a fake in the ViewModel tests). */
interface TokenStore {
    val state: Flow<TokenState>

    /** Keeps [token] (trimmed) in place of the one before; clears [TokenState.rejected]. IllegalArgumentException when it is no token (the message never holds it). */
    suspend fun set(token: String)
    suspend fun remove()

    /** The token for a request. */
    suspend fun read(): TokenRead

    /**
     * GitHub rejected the token [id] (401): marked rejected only while it is still the one
     * stored, so a 401 for a token replaced meanwhile does not mark the new one. True when
     * it marked it.
     */
    suspend fun reject(id: String): Boolean

    /** GitHub accepted the token [id]: a rejected mark on it (a 401 that has gone away) is cleared. True when it cleared one. */
    suspend fun accepted(id: String): Boolean

    companion object {
        private val TOKEN = Regex("^[A-Za-z0-9_]{20,255}$")

        /** Why [token] is not a GitHub token, or null when it looks like one. */
        fun problem(token: String): String? = when {
            token.isBlank() -> "Paste the token first."
            !TOKEN.matches(token.trim()) -> "That is not a GitHub token: it is 20 to 255 letters, digits and _ (a fine-grained one starts with github_pat_)."
            else -> null
        }
    }
}

/**
 * The upload token sealed with [cipher] (in the app: AES-256-GCM under a key in the
 * AndroidKeyStore, [KeystoreKey]); ciphertext and IV in DataStore "upload". The plain token
 * exists only while a request is made: it is never logged, shown or kept anywhere else. The
 * cipher runs on [io] (a keystore operation, or making the key, can take a while: never on the
 * main thread the screens call from).
 */
class TokenVault(private val store: DataStore<Preferences>, private val cipher: AeadCipher, private val io: CoroutineDispatcher = Dispatchers.IO) : TokenStore {
    // the DataStore holds the upload settings too: their edits are no new state
    override val state: Flow<TokenState> = store.data.map { p -> TokenState(p[CT] != null && p[IV] != null, p[REJECTED] == true) }.distinctUntilChanged()

    override suspend fun set(token: String) {
        TokenStore.problem(token)?.let { throw IllegalArgumentException(it) }
        val s = withContext(io) { cipher.seal(token.trim().toByteArray(Charsets.UTF_8)) }
        store.edit {
            it[CT] = b64(s.data)
            it[IV] = b64(s.iv)
            it.remove(REJECTED)
        }
    }

    override suspend fun remove() {
        store.edit { it.clearToken() }
    }

    // The IV names the stored token: every seal has a fresh one.
    override suspend fun read(): TokenRead {
        val p = store.data.first()
        val ct = p[CT] ?: return TokenRead.None
        val iv = p[IV] ?: return TokenRead.None
        return try {
            TokenRead.Ok(String(withContext(io) { cipher.open(Sealed(unb64(iv), unb64(ct))) }, Charsets.UTF_8), iv)
        } catch (_: AEADBadTagException) {
            // sealed under a key that is gone (the keystore was reset: a new key was made)
            store.edit { if (it[IV] == iv) it.clearToken() }
            TokenRead.None
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TokenRead.Unreadable    // the keystore failed this time; the token stays
        }
    }

    override suspend fun reject(id: String): Boolean {
        var marked = false
        store.edit {
            if (it[CT] != null && it[IV] == id) {
                it[REJECTED] = true
                marked = true
            }
        }
        return marked
    }

    override suspend fun accepted(id: String): Boolean {
        var cleared = false
        store.edit {
            if (it[IV] == id && it[REJECTED] == true) {
                it.remove(REJECTED)
                cleared = true
            }
        }
        return cleared
    }

    override fun toString(): String = "TokenVault"

    private companion object {
        val CT = stringPreferencesKey("token_ct")
        val IV = stringPreferencesKey("token_iv")
        val REJECTED = booleanPreferencesKey("token_rejected")

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

        fun MutablePreferences.clearToken() {
            remove(CT)
            remove(IV)
            remove(REJECTED)
        }
    }
}
