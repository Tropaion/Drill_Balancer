package dev.drillbalancer.recorder

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Ids of the dataset contract. A recording (one take) is `YYYYMMDDTHHMMSSZ_<install4>_<rand4>`,
 * a session (one workpiece, many takes; it decides the train/val/test split) is
 * `YYYYMMDDTHHMM_<install4>`. install4 are the first 4 characters of a random per-install id
 * (base32 a-z2-7), never anything derived from the phone.
 */
object RecordingIds {
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    private const val ALNUM = "0123456789abcdefghijklmnopqrstuvwxyz"
    private val RECORDING = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val SESSION = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm").withZone(ZoneOffset.UTC)

    /** meta.v1.schema.json $defs.recording_id */
    val RECORDING_ID = Regex("^[0-9]{8}T[0-9]{6}Z_[a-z2-7]{4}_[0-9a-z]{4}$")

    /** meta.v1.schema.json session_id */
    val SESSION_ID = Regex("^[0-9A-Za-z_-]{4,64}$")

    const val INSTALL_ID_LEN = 16

    fun newInstallId(random: Random = Random.Default): String =
        String(CharArray(INSTALL_ID_LEN) { BASE32[random.nextInt(BASE32.length)] })

    fun install4(installId: String): String = installId.take(4)

    fun recordingId(now: Instant, installId: String, random: Random = Random.Default): String =
        "${RECORDING.format(now)}_${install4(installId)}_${String(CharArray(4) { ALNUM[random.nextInt(ALNUM.length)] })}"

    fun sessionId(now: Instant, installId: String): String = "${SESSION.format(now)}_${install4(installId)}"

    /** TAKE_START's take_id: any u32 but 0. */
    fun takeId(random: Random = Random.Default): Long = random.nextLong(1, 0x1_0000_0000L)

    /** ISO 8601 UTC with milliseconds, as meta.start_utc. */
    fun utc(t: Instant): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(t.toEpochMilli()))
}
