package dev.drillbalancer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import kotlin.coroutines.coroutineContext

internal const val USER_AGENT = "DrillBalancer-app"
const val MAX_APK_BYTES = 200L * 1024 * 1024
const val RELEASE_REPO = "Tropaion/Drill_Balancer"
const val RELEASE_URL_PREFIX = "https://github.com/$RELEASE_REPO/releases/download/"

/** The message says what went wrong, for the update notice. */
class DownloadException(message: String) : Exception(message)

/**
 * A release asset, written to a stream. Only from this repository's releases; GitHub then
 * redirects to a storage host that changes, so the redirect target is not checked. No request
 * carries credentials. Exactly [ReleaseAsset.size] bytes count as complete, which also caps what
 * a wrong answer can write.
 */
class ReleaseDownloader(private val urlPrefix: String = RELEASE_URL_PREFIX) {
    suspend fun fetch(asset: ReleaseAsset, maxBytes: Long, out: OutputStream, onProgress: (percent: Int) -> Unit) = withContext(Dispatchers.IO) {
        if (!isRelease(asset.url)) throw DownloadException("This download is not from the project's releases.")
        if (asset.size !in 1..maxBytes) throw DownloadException("This download has an unexpected size.")
        try {
            val connection = URL(asset.url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw DownloadException("The download failed (HTTP $code).")
                connection.inputStream.use { copy(it, asset.size, out, onProgress) }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            throw DownloadException("The download failed. Check the connection and try again.")
        }
    }

    private suspend fun copy(input: InputStream, size: Long, out: OutputStream, onProgress: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var percent = -1
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > size) throw DownloadException("This download is larger than expected.")
            write(out, buffer, n)
            val now = (total * 100 / size).toInt()
            if (now != percent) {
                percent = now
                onProgress(now)
            }
        }
        if (total != size) throw DownloadException("The download was cut off. Please try again.")
    }

    private fun write(out: OutputStream, buffer: ByteArray, n: Int) {
        try {
            out.write(buffer, 0, n)
        } catch (_: IOException) {
            throw DownloadException("There is not enough storage for this download.")
        }
    }

    // Normalized: dot segments must not lead out of the prefix.
    private fun isRelease(url: String) = try {
        URI(url).normalize().toString().startsWith(urlPrefix)
    } catch (_: URISyntaxException) {
        false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 8192
    }
}
