package dev.drillbalancer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** The latest release; an asset is null when the release does not have it. */
data class ReleaseInfo(val version: ReleaseVersion, val firmware: ReleaseAsset?, val apk: ReleaseAsset?)

sealed interface ReleaseResult {
    data class Found(val info: ReleaseInfo) : ReleaseResult

    /** GitHub has no release, or its tag is no version. */
    data object None : ReleaseResult

    /** Offline, rate limited, or an answer that makes no sense. */
    data object Failed : ReleaseResult
}

fun interface ReleaseSource {
    suspend fun latest(): ReleaseResult
}

/**
 * GitHub's latest release, which leaves out drafts and prereleases. A public repository needs no
 * login, and this client never has one: it is separate from the data repository's HttpGitHubApi
 * so the upload token cannot reach it.
 */
class HttpReleaseSource(
    private val baseUrl: String = "https://api.github.com",
    private val repo: String = RELEASE_REPO,
) : ReleaseSource {
    override suspend fun latest(): ReleaseResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL("${baseUrl.trimEnd('/')}/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> parse(connection.inputStream.use { it.readBytes() }.decodeToString())
                    HttpURLConnection.HTTP_NOT_FOUND -> ReleaseResult.None
                    else -> ReleaseResult.Failed
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            ReleaseResult.Failed
        } catch (_: SerializationException) {
            ReleaseResult.Failed
        }
    }

    private fun parse(text: String): ReleaseResult {
        val release = JSON.decodeFromString<ReleaseDto>(text)
        val version = ReleaseVersion.parse(release.tag) ?: return ReleaseResult.None
        fun asset(name: String) = release.assets.firstOrNull { it.name == name }?.let { ReleaseAsset(it.name, it.url, it.size) }
        return ReleaseResult.Found(ReleaseInfo(version, asset("drillbalancer-$version.bin"), asset("drillbalancer-$version.apk")))
    }

    @Serializable
    private data class ReleaseDto(@SerialName("tag_name") val tag: String, val assets: List<AssetDto> = emptyList())

    @Serializable
    private data class AssetDto(val name: String, val size: Long, @SerialName("browser_download_url") val url: String)

    private companion object {
        const val TIMEOUT_MS = 10_000
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
