package dev.drillbalancer.recorder.label

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/*
 * The auto-detector's parameters: a byte copy of the dataset contract's
 * dbdata/contract/autodetect_params.json (schema dbauto.params/1), shipped as the asset
 * autodetect_params.json (AutoDetectParamsTest checks that it is the same file). Every key of the
 * file has its field here and an unknown key fails to load, so the port cannot silently miss a
 * parameter the reference reads. events.json records the version and the file's sha256.
 */

@Serializable
data class DetectParams(
    val schema: String,
    val version: String,
    val note: String = "",
    @SerialName("frame_ms") val frameMs: Double,
    val features: FeatureParams,
    val noise: NoiseParams,
    val motor: MotorParams,
    val spinup: SpinupParams,
    val contact: ContactParams,
    @SerialName("load_end") val loadEnd: LoadEndParams,
    val restart: RestartParams,
    val stuck: StuckParams,
    val kickback: KickbackParams,
    val wobble: SuggestParams,
    val wander: WanderParams,
    val speech: SpeechParams,
    val confidence: ConfidenceParams,
) {
    /** events.json detector.version and meta.review.detector: "dbauto/1.1.0". */
    val detector: String get() = "$DETECTOR/$version"

    companion object {
        const val DETECTOR = "dbauto"
        const val SCHEMA = "dbauto.params/1"
        const val ASSET = "autodetect_params.json"

        private val json = Json { ignoreUnknownKeys = false }

        /** The parameters in [raw] (the file's bytes) and their sha256. */
        fun load(raw: ByteArray): LoadedParams {
            val p = json.decodeFromString(serializer(), raw.decodeToString())
            require(p.schema == SCHEMA) { "not a $SCHEMA file" }
            return LoadedParams(p, sha256(raw))
        }

        /** sha256 of the params file with CRLF normalised to LF (a Windows checkout hashes the same). */
        fun sha256(raw: ByteArray): String {
            val out = java.io.ByteArrayOutputStream(raw.size)
            var i = 0
            while (i < raw.size) {
                if (raw[i] == '\r'.code.toByte() && i + 1 < raw.size && raw[i + 1] == '\n'.code.toByte()) i++
                out.write(raw[i].toInt())
                i++
            }
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** The parameters with the sha256 of the file they came from. */
class LoadedParams(val p: DetectParams, val sha256: String)

@Serializable
data class FeatureParams(
    @SerialName("hp_hz") val hpHz: Double,
    @SerialName("thrust_band_hz") val thrustBandHz: List<Double>,
    @SerialName("wobble_band_hz") val wobbleBandHz: List<Double>,
    @SerialName("wobble_window_ms") val wobbleWindowMs: Double,
    @SerialName("fft_window") val fftWindow: Int,
    @SerialName("fft_size") val fftSize: Int,
    @SerialName("f0_range_hz") val f0RangeHz: List<Double>,
    @SerialName("hps_harmonics") val hpsHarmonics: Int,
    @SerialName("prominence_bins") val prominenceBins: Int,
    @SerialName("voiced_min") val voicedMin: Double,
    @SerialName("smooth_z_frames") val smoothZFrames: Int,
    @SerialName("cut_band_hz") val cutBandHz: List<Double>,
    @SerialName("speech_band_hz") val speechBandHz: List<Double>,
    @SerialName("flux_band_hz") val fluxBandHz: List<Double>,
    @SerialName("hammer_window_ms") val hammerWindowMs: Double,
    @SerialName("hammer_rate_hz") val hammerRateHz: List<Double>,
    @SerialName("eps_g2") val epsG2: Double,
    @SerialName("eps_fs2") val epsFs2: Double,
    @SerialName("smooth_e_frames") val smoothEFrames: Int,
)

@Serializable
data class NoiseParams(
    @SerialName("block_ms") val blockMs: Double,
    @SerialName("floor_pct") val floorPct: Double,
    @SerialName("spread_min_db") val spreadMinDb: Double,
)

@Serializable
data class MotorParams(
    @SerialName("on_z") val onZ: Double,
    @SerialName("on_ms") val onMs: Double,
    @SerialName("off_z") val offZ: Double,
    @SerialName("off_ms") val offMs: Double,
    @SerialName("back_z") val backZ: Double,
    @SerialName("off_f0_drop") val offF0Drop: Double,
    @SerialName("off_hold_ms") val offHoldMs: Double,
    @SerialName("off_search_ms") val offSearchMs: Double,
    @SerialName("off_drop_db") val offDropDb: Double,
    @SerialName("off_level_db") val offLevelDb: Double,
    @SerialName("conf_scale_z") val confScaleZ: Double,
    @SerialName("off_f0_ms") val offF0Ms: Double,
    @SerialName("conf_ms") val confMs: Double,
)

@Serializable
data class SpinupParams(
    @SerialName("min_ms") val minMs: Double,
    @SerialName("max_ms") val maxMs: Double,
    @SerialName("stable_ms") val stableMs: Double,
    @SerialName("stable_f0") val stableF0: Double,
    @SerialName("stable_db") val stableDb: Double,
    @SerialName("ref_ms") val refMs: Double,
    @SerialName("voiced_frac") val voicedFrac: Double,
    @SerialName("fallback_ms") val fallbackMs: Double,
)

@Serializable
data class ContactWeights(val f0: Double, val cut: Double, val imu: Double, val thrust: Double, val hammer: Double)

@Serializable
data class ContactParams(
    @SerialName("f0_drop") val f0Drop: Double,
    @SerialName("cut_db") val cutDb: Double,
    @SerialName("imu_db") val imuDb: Double,
    @SerialName("thrust_g") val thrustG: Double,
    val hammer: Double,
    val weights: ContactWeights,
    @SerialName("ratio_max") val ratioMax: Double,
    val score: Double,
    @SerialName("hold_ms") val holdMs: Double,
    @SerialName("smooth_frames") val smoothFrames: Int,
    @SerialName("back_frac") val backFrac: Double,
    @SerialName("conf_scale") val confScale: Double,
)

@Serializable
data class LoadEndParams(
    @SerialName("level_ms") val levelMs: Double,
    @SerialName("level_gap_ms") val levelGapMs: Double,
    @SerialName("min_sag") val minSag: Double,
    @SerialName("min_cut_db") val minCutDb: Double,
    val recover: Double,
    @SerialName("hold_ms") val holdMs: Double,
    val start: Double,
    @SerialName("window_ms") val windowMs: List<Double>,
    @SerialName("imp_g") val impG: Double,
    @SerialName("flux_ratio") val fluxRatio: Double,
    @SerialName("gyro_dps") val gyroDps: Double,
    @SerialName("imp_onset_frac") val impOnsetFrac: Double,
    @SerialName("audio_onset_ratio") val audioOnsetRatio: Double,
    @SerialName("audio_base_ms") val audioBaseMs: Double,
    @SerialName("audio_imu_agree_ms") val audioImuAgreeMs: Double,
    @SerialName("conf_scale") val confScale: Double,
    @SerialName("audio_search_ms") val audioSearchMs: List<Double>,
)

@Serializable
data class RestartParams(
    @SerialName("spinup_ms") val spinupMs: Double,
    @SerialName("check_ms") val checkMs: Double,
    val score: Double,
)

@Serializable
data class StuckParams(
    @SerialName("f0_drop") val f0Drop: Double,
    @SerialName("imu_db") val imuDb: Double,
    @SerialName("smooth_frames") val smoothFrames: Int,
    @SerialName("hold_ms") val holdMs: Double,
    @SerialName("back_drop") val backDrop: Double,
    @SerialName("back_db") val backDb: Double,
    @SerialName("conf_scale") val confScale: Double,
)

@Serializable
data class KickbackParams(
    @SerialName("rate_dps") val rateDps: Double,
    @SerialName("roll_deg") val rollDeg: Double,
    @SerialName("roll_ms") val rollMs: Double,
    @SerialName("back_dps") val backDps: Double,
    @SerialName("refractory_ms") val refractoryMs: Double,
    @SerialName("hole_after_ms") val holeAfterMs: Double,
    @SerialName("conf_scale") val confScale: Double,
)

@Serializable
data class SuggestParams(
    @SerialName("rms_dps") val rmsDps: Double,
    @SerialName("min_ms") val minMs: Double,
    @SerialName("confidence_max") val confidenceMax: Double,
    @SerialName("conf_scale") val confScale: Double,
)

@Serializable
data class WanderParams(
    val deg: Double,
    @SerialName("after_contact_ms") val afterContactMs: Double,
    @SerialName("confidence_max") val confidenceMax: Double,
    @SerialName("conf_scale") val confScale: Double,
)

@Serializable
data class SpeechParams(
    @SerialName("window_ms") val windowMs: Double,
    @SerialName("hop_ms") val hopMs: Double,
    @SerialName("mod_hz") val modHz: List<Double>,
    @SerialName("mod_frac") val modFrac: Double,
    val z: Double,
    @SerialName("confidence_max") val confidenceMax: Double,
    @SerialName("conf_scale") val confScale: Double,
    @SerialName("motor_pad_ms") val motorPadMs: Double,
)

@Serializable
data class ConfidenceParams(
    val disagree: Double,
    @SerialName("restart_retract") val restartRetract: Double,
    @SerialName("open_motor_on") val openMotorOn: Double,
)
