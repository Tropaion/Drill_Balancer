package dev.drillbalancer.protocol

import androidx.compose.ui.graphics.toArgb
import dev.drillbalancer.ui.settings.DIM_PRESETS_S
import dev.drillbalancer.ui.settings.OFF_PRESETS_MIN
import dev.drillbalancer.ui.settings.SLEEP_PRESETS_S
import dev.drillbalancer.ui.theme.Amber
import dev.drillbalancer.ui.theme.Blue
import dev.drillbalancer.ui.theme.Button
import dev.drillbalancer.ui.theme.Dark
import dev.drillbalancer.ui.theme.Green
import dev.drillbalancer.ui.theme.Grey
import dev.drillbalancer.ui.theme.Red
import dev.drillbalancer.ui.theme.StatusColors
import dev.drillbalancer.ui.theme.White
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Every protocol constant of the app against the firmware's C sources (like
// db_client.py's self_check), so a change on either side fails here first.

class FirmwareConformanceTest {
    private val proto = "components/ble/include/ble_protocol.h"
    private val otaH = "components/ota/include/ota_proto.h"
    private val settingsH = "components/settings/include/settings.h"
    private val settingsC = "components/settings/src/settings.c"

    private fun <K> assertSameMap(what: String, expected: Map<K, Long>, actual: Map<K, Long>) =
        assertEquals("$what (C header vs Kotlin)", expected, actual)

    private fun defines(path: String, prefix: String) =
        FirmwareSources.intDefines(path).filterKeys { it.startsWith(prefix) }

    @Test
    fun uuidBaseAndIds() {
        val text = FirmwareSources.text(proto)
        assertTrue("DB_UUID_STR base", "#define DB_UUID_STR(x) \"d71b\" x \"-8a3e-4f6c-b5d2-1c9e0a7f4b63\"" in text)
        val ids = mapOf(
            "DB_SVC_MAIN" to Uuids.DB_SVC_MAIN, "DB_CHR_ANGLE" to Uuids.DB_CHR_ANGLE,
            "DB_CHR_CONFIG" to Uuids.DB_CHR_CONFIG, "DB_CHR_COMMAND" to Uuids.DB_CHR_COMMAND,
            "DB_CHR_STATUS" to Uuids.DB_CHR_STATUS, "DB_SVC_OTA" to Uuids.DB_SVC_OTA,
            "DB_CHR_OTA_CTRL" to Uuids.DB_CHR_OTA_CTRL, "DB_CHR_OTA_DATA" to Uuids.DB_CHR_OTA_DATA,
            "DB_SVC_REC" to Uuids.DB_SVC_REC, "DB_CHR_REC_CTRL" to Uuids.DB_CHR_REC_CTRL,
            "DB_CHR_REC_STATE" to Uuids.DB_CHR_REC_STATE,
        )
        val c = FirmwareSources.intDefines(proto).filterKeys { it.startsWith("DB_SVC_") || it.startsWith("DB_CHR_") }
        assertSameMap("UUID ids", c, ids.mapValues { it.value.toLong() })
        assertEquals(Uuids.db(0x0002), Uuids.CHR_ANGLE)
        assertEquals(Uuids.db(0x0102), Uuids.CHR_OTA_DATA)
        assertEquals(Uuids.db(0x0202), Uuids.CHR_REC_STATE)

        // DB_UUID128_INIT(lo, hi): NimBLE little-endian byte order with x in bytes 12, 13.
        val m = Regex("""BLE_UUID128_INIT\((.*?0xd7)\)""", RegexOption.DOT_MATCHES_ALL).find(FirmwareSources.code(proto))!!
        val parts = m.groupValues[1].replace("\\", " ").split(',').map { it.trim() }
        assertEquals(16, parts.size)
        val u = Uuids.db(0x0201)
        val be = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        val le = be.reversedArray()
        for ((i, p) in parts.withIndex()) {
            val want = when (p) {
                "(lo)" -> 0x01
                "(hi)" -> 0x02
                else -> p.removePrefix("0x").toInt(16)
            }
            assertEquals("UUID128 byte $i", want, le[i].toInt() and 0xFF)
        }
        assertTrue("advertised name prefix", "name \"${Uuids.NAME_PREFIX}XXXX\"" in FirmwareSources.text(proto))
    }

    @Test
    fun standardServicesInBleService() {
        val declared = Regex("""BLE_UUID16_DECLARE\((0x[0-9A-Fa-f]{4})\)""")
            .findAll(FirmwareSources.code("components/ble/src/ble_service.c"))
            .map { Uuids.sig(it.groupValues[1].substring(2).toInt(16)) }.toSet()
        val kotlin = setOf(
            Uuids.SVC_DEVICE_INFO, Uuids.CHR_MANUFACTURER, Uuids.CHR_MODEL, Uuids.CHR_FIRMWARE_REV,
            Uuids.SVC_BATTERY, Uuids.CHR_BATTERY_LEVEL,
        )
        assertEquals(kotlin, declared)
    }

    @Test
    fun flagBits() {
        assertSameMap(
            "DB_ANGLE_FLAG_*", defines(proto, "DB_ANGLE_FLAG_"),
            mapOf(
                "DB_ANGLE_FLAG_ON_TARGET" to AngleFlags.ON_TARGET, "DB_ANGLE_FLAG_NEAR" to AngleFlags.NEAR,
                "DB_ANGLE_FLAG_YAW_VALID" to AngleFlags.YAW_VALID, "DB_ANGLE_FLAG_YAW_STALE" to AngleFlags.YAW_STALE,
                "DB_ANGLE_FLAG_ZERO_SET" to AngleFlags.ZERO_SET, "DB_ANGLE_FLAG_STILL" to AngleFlags.STILL,
                "DB_ANGLE_FLAG_IMU_OK" to AngleFlags.IMU_OK,
            ).mapValues { it.value.toLong() },
        )
        assertSameMap(
            "DB_STATUS_FLAG_*", defines(proto, "DB_STATUS_FLAG_"),
            mapOf(
                "DB_STATUS_FLAG_BATTERY_VALID" to StatusFlags.BATTERY_VALID, "DB_STATUS_FLAG_CHARGING" to StatusFlags.CHARGING,
                "DB_STATUS_FLAG_CHARGE_DONE" to StatusFlags.CHARGE_DONE, "DB_STATUS_FLAG_VBUS" to StatusFlags.VBUS,
                "DB_STATUS_FLAG_MOUNT_CAL" to StatusFlags.MOUNT_CAL, "DB_STATUS_FLAG_ACCEL_CAL" to StatusFlags.ACCEL_CAL,
                "DB_STATUS_FLAG_CALIBRATING" to StatusFlags.CALIBRATING,
                "DB_STATUS_FLAG_RECORDING" to StatusFlags.RECORDING,
            ).mapValues { it.value.toLong() },
        )
    }

    @Test
    fun commandOpcodes() {
        val kotlin = mapOf(
            "DB_CMD_ZERO" to Command.Zero, "DB_CMD_CLEAR_ZERO" to Command.ClearZero,
            "DB_CMD_SET_MODE" to Command.SetMode(LevelMode.WALL),
            "DB_CMD_MOUNT_CAL_START" to Command.MountCalStart, "DB_CMD_MOUNT_CAL_FINISH" to Command.MountCalFinish,
            "DB_CMD_ACCEL_CAL_START" to Command.AccelCalStart, "DB_CMD_ACCEL_CAL_FINISH" to Command.AccelCalFinish,
            "DB_CMD_CAL_CANCEL" to Command.CalCancel, "DB_CMD_SLEEP" to Command.Sleep,
            "DB_CMD_POWER_OFF" to Command.PowerOff, "DB_CMD_REBOOT" to Command.Reboot,
            "DB_CMD_FACTORY_RESET" to Command.FactoryReset,
        ).mapValues { (it.value.encode()[0].toInt() and 0xFF).toLong() } +
            ("DB_CMD_FACTORY_RESET_CONFIRM" to CommandOpcode.FACTORY_RESET_CONFIRM.toLong())
        assertSameMap("DB_CMD_*", defines(proto, "DB_CMD_"), kotlin)
        assertEquals(CommandOpcode.FACTORY_RESET_CONFIRM, Command.FactoryReset.encode()[1].toInt() and 0xFF)
    }

    @Test
    fun attErrors() {
        val app = AttError.entries.filter { it.code >= 0x80 }.associate { "DB_ATT_ERR_${it.name}" to it.code.toLong() }
        assertSameMap("DB_ATT_ERR_*", defines(proto, "DB_ATT_ERR_"), app)
        val codec = FirmwareSources.intDefines("components/ble/src/ble_codec.h")
        assertEquals(AttError.INVALID_LENGTH.code.toLong(), codec["BLE_CODEC_ATT_INVALID_LEN"])
        // The standard codes the header names.
        val text = FirmwareSources.text(proto)
        for (e in listOf(AttError.INSUFFICIENT_AUTHENTICATION, AttError.INSUFFICIENT_ENCRYPTION, AttError.INSUFFICIENT_KEY_SIZE, AttError.INVALID_LENGTH)) {
            assertTrue("0x%02X in ble_protocol.h".format(e.code), "0x%02X".format(e.code) in text)
        }
    }

    @Test
    fun packetLayouts() {
        assertEquals(
            listOf(
                "uint8_t" to "version", "uint8_t" to "mode", "uint8_t" to "flags", "uint8_t" to "seq",
                "int16_t" to "elevation_cdeg", "int16_t" to "yaw_cdeg", "int16_t" to "bubble_x_cdeg",
                "int16_t" to "bubble_y_cdeg", "int16_t" to "deviation_cdeg",
            ),
            FirmwareSources.packedStruct(proto, "db_angle_packet_t"),
        )
        assertEquals(
            listOf(
                "uint8_t" to "version", "uint8_t" to "battery_pct", "uint16_t" to "battery_mv", "uint8_t" to "flags",
                "uint8_t" to "power_state", "uint8_t" to "cal_kind", "uint8_t" to "cal_samples",
                "uint8_t" to "cal_coverage", "int8_t" to "cal_last_result",
            ),
            FirmwareSources.packedStruct(proto, "db_status_packet_t"),
        )
        val text = FirmwareSources.text(proto)
        assertTrue("} db_angle_packet_t;          // ${AnglePacket.SIZE} bytes" in text)
        assertTrue("} db_status_packet_t;         // ${StatusPacket.SIZE} bytes" in text)
        assertTrue("#define PACKET_VERSION ${AnglePacket.VERSION}u" in FirmwareSources.text("components/ble/src/ble_codec.c"))
        assertEquals(AnglePacket.VERSION, StatusPacket.VERSION)
    }

    @Test
    fun otaProtocol() {
        assertSameMap(
            "OTA opcodes", defines(otaH, "OTA_OP_") + defines(otaH, "OTA_MSG_"),
            mapOf(
                "OTA_OP_START" to OtaOp.START, "OTA_OP_FINISH" to OtaOp.FINISH, "OTA_OP_ABORT" to OtaOp.ABORT,
                "OTA_MSG_REPLY" to OtaOp.MSG_REPLY, "OTA_MSG_ACK" to OtaOp.MSG_ACK, "OTA_MSG_NACK" to OtaOp.MSG_NACK,
            ).mapValues { it.value.toLong() },
        )
        assertEquals(OtaOp.DATA_OVERHEAD.toLong(), FirmwareSources.intDefines(otaH)["OTA_DATA_OVERHEAD"])
        assertEquals(
            FirmwareSources.enum(otaH, "ota_status_t"),
            OtaStatus.entries.associate { "OTA_STATUS_${it.name}" to it.code },
        )
        assertTrue("#define START_LEN ${OtaOp.START_LEN}u" in FirmwareSources.text("components/ota/src/ota_proto.c"))
    }

    @Test
    fun firmwareImageChecks() {
        val update = FirmwareSources.text("components/ota/src/ota_update.c")
        // 24-byte image header + 8-byte segment header, as FirmwareImage.DESC_OFFSET assumes.
        assertTrue(
            "#define DESC_OFFSET (sizeof(esp_image_header_t) + sizeof(esp_image_segment_header_t))" in update,
        )
        assertEquals(32, FirmwareImage.DESC_OFFSET)
        assertTrue("project_name check", "strncmp(desc.project_name, running->project_name" in update)
        assertTrue("project(${FirmwareImage.PROJECT_NAME})" in FirmwareSources.text("CMakeLists.txt"))

        val sizes = FirmwareSources.text("partitions.csv").lines()
            .map { line -> line.split(',').map { it.trim() } }
            .filter { it.size >= 5 && it[1] == "app" }
            .associate { it[0] to partitionSize(it[4]) }
        assertEquals(mapOf("ota_0" to FirmwareImage.OTA_SLOT_SIZE, "ota_1" to FirmwareImage.OTA_SLOT_SIZE), sizes)
    }

    private fun partitionSize(s: String): Int = when {
        s.endsWith("M") -> s.dropLast(1).toInt() * 1024 * 1024
        s.endsWith("K") -> s.dropLast(1).toInt() * 1024
        s.startsWith("0x") -> s.substring(2).toInt(16)
        else -> s.toInt()
    }

    @Test
    fun settingsLimits() {
        val f = FirmwareSources.floatDefines(settingsH)
        assertEquals(SettingsLimits.TOL_MIN_DEG, f["SETTINGS_TOL_MIN_DEG"])
        assertEquals(SettingsLimits.TOL_MAX_DEG, f["SETTINGS_TOL_MAX_DEG"])
        assertEquals(SettingsLimits.NEAR_MAX_DEG, f["SETTINGS_NEAR_MAX_DEG"])
        assertEquals(SettingsLimits.TARGET_MAX_DEG, f["SETTINGS_TARGET_MAX_DEG"])
        val i = FirmwareSources.intDefines(settingsH).filterKeys { it.startsWith("SETTINGS_") && it != "SETTINGS_JSON_MAX" }
        assertSameMap(
            "SETTINGS_* int limits", i,
            mapOf(
                "SETTINGS_SCHEMA_VERSION" to SettingsLimits.SCHEMA_VERSION,
                "SETTINGS_BRIGHT_MIN_PCT" to SettingsLimits.BRIGHT_MIN_PCT, "SETTINGS_BRIGHT_MAX_PCT" to SettingsLimits.BRIGHT_MAX_PCT,
                "SETTINGS_DIM_BRIGHT_MIN_PCT" to SettingsLimits.DIM_BRIGHT_MIN_PCT,
                "SETTINGS_DIM_MIN_S" to SettingsLimits.DIM_MIN_S, "SETTINGS_DIM_MAX_S" to SettingsLimits.DIM_MAX_S,
                "SETTINGS_SLEEP_MAX_S" to SettingsLimits.SLEEP_MAX_S, "SETTINGS_OFF_MAX_MIN" to SettingsLimits.OFF_MAX_MIN,
                "SETTINGS_STALE_MIN_S" to SettingsLimits.STALE_MIN_S, "SETTINGS_STALE_MAX_S" to SettingsLimits.STALE_MAX_S,
            ).mapValues { it.value.toLong() },
        )
        // Every float limit of the header is covered above (plus the mount offset, not a setting).
        assertEquals(
            setOf("SETTINGS_TOL_MIN_DEG", "SETTINGS_TOL_MAX_DEG", "SETTINGS_NEAR_MAX_DEG", "SETTINGS_TARGET_MAX_DEG", "SETTINGS_MAX_MOUNT_OFFSET_DEG"),
            f.keys,
        )
    }

    @Test
    fun settingsKeysAndModeNames() {
        // The key table in the settings.h header comment.
        val keys = Regex("""^//\s+"(\w+)"\s+(float|string|int|bool)\b""", RegexOption.MULTILINE)
            .findAll(FirmwareSources.text(settingsH)).map { it.groupValues[1] }.toList()
        assertEquals(SettingsField.entries.map { it.key }, keys)
        // The keys settings.c reads on write, in its order.
        val read = Regex("""READ\(read_\w+\(o, "(\w+)"""").findAll(FirmwareSources.code(settingsC)).map { it.groupValues[1] }.toList()
        assertEquals(SettingsField.entries.map { it.key }, read)

        val names = Regex("""\[(LEVEL_MODE_\w+)\] = "(\w+)"""").findAll(FirmwareSources.code(settingsC))
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(LevelMode.entries.associate { "LEVEL_MODE_${it.name}" to it.key }, names)
        val serial = LevelMode.serializer().descriptor
        for (m in LevelMode.entries) assertEquals(m.key, serial.getElementName(m.ordinal))
    }

    @Test
    fun settingsDefaults() {
        val body = Regex("""settings_t settings_defaults\(void\)\s*\{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(FirmwareSources.code(settingsC))!!.groupValues[1]
        val v = Regex("""\.(\w+)\s*=\s*([^,\n]+),""").findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }
        val d = DeviceSettings.DEFAULTS
        assertEquals(v["tolerance_deg"]!!.removeSuffix("f").toFloat(), d.toleranceDeg)
        assertEquals(v["near_deg"]!!.removeSuffix("f").toFloat(), d.nearDeg)
        assertEquals(v["target_deg"]!!.removeSuffix("f").toFloat(), d.targetDeg)
        assertEquals(v["mode"], "LEVEL_MODE_${d.mode.name}")
        assertEquals(v["brightness_pct"]!!.toInt(), d.brightnessPct)
        assertEquals(v["dim_brightness_pct"]!!.toInt(), d.dimBrightnessPct)
        assertEquals(v["dim_after_s"]!!.toInt(), d.dimAfterS)
        assertEquals(v["sleep_after_s"]!!.toInt(), d.sleepAfterS)
        assertEquals(v["off_after_min"]!!.toInt(), d.offAfterMin)
        assertEquals(v["ble_enabled"]!!.toBoolean(), d.bleEnabled)
        assertEquals(v["yaw_stale_s"]!!.toInt(), d.yawStaleS)
        assertEquals(v["mount_cal_valid"]!!.toBoolean(), d.cal.mount)
        assertEquals(v["accel_cal_valid"]!!.toBoolean(), d.cal.accel)
        assertTrue(d.isValid)
    }

    @Test
    fun defaultJsonFromFirmwareTestParsesToDefaults() {
        // The exact output test_settings.c expects from settings_to_json(defaults).
        val fn = Regex("""test_to_json_defaults\(void\)\s*\{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(FirmwareSources.text("test_host/settings/test_settings.c"))!!.groupValues[1]
        val call = fn.substringAfter("TEST_ASSERT_EQUAL_STRING(").substringBefore("buf);")
        val json = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(call).joinToString("") { it.groupValues[1].replace("\\\"", "\"") }
        assertTrue(json, json.startsWith("{\"tol\":0.5,"))
        assertEquals(DeviceSettings.DEFAULTS, (DeviceSettings.parse(json) as Decoded.Ok).value)
    }

    @Test
    fun levelAndCalibrationEnums() {
        val level = "components/level/include/level.h"
        val accel = "components/level/include/accel_cal.h"
        val app = "components/app_core/include/app_state.h"
        assertEquals(
            FirmwareSources.enum(level, "level_mode_t") - "LEVEL_MODE_COUNT",
            LevelMode.entries.associate { "LEVEL_MODE_${it.name}" to it.wire },
        )
        assertEquals(LevelMode.entries.size, FirmwareSources.enum(level, "level_mode_t")["LEVEL_MODE_COUNT"])
        assertEquals(
            FirmwareSources.enum(level, "level_status_t"),
            LevelStatus.entries.associate { "LEVEL_STATUS_${it.name}" to it.ordinal },
        )
        assertEquals(
            FirmwareSources.enum(level, "level_cal_result_t"),
            MountCalResult.entries.associate { "LEVEL_CAL_${it.name}" to it.code },
        )
        assertEquals(
            FirmwareSources.enum(accel, "accel_cal_result_t"),
            SensorCalResult.entries.associate { "ACCEL_CAL_${it.name}" to it.code },
        )
        assertSameMap(
            "ACCEL_CAL_DIR_*", defines(accel, "ACCEL_CAL_DIR_"),
            CoverageDir.entries.associate { "ACCEL_CAL_DIR_${it.name}" to it.bit.toLong() } +
                ("ACCEL_CAL_DIR_ALL" to CoverageDir.ALL.toLong()),
        )
        assertEquals(
            FirmwareSources.enum(app, "app_power_t"),
            PowerState.entries.associate { "APP_POWER_${it.name}" to it.wire },
        )
        assertEquals(
            FirmwareSources.enum(app, "app_cal_kind_t"),
            CalKind.entries.associate { "APP_CAL_${it.name}" to it.wire },
        )
        assertSameMap(
            "APP_CAL_RESULT_*", defines(app, "APP_CAL_RESULT_"),
            mapOf(
                "APP_CAL_RESULT_NONE" to CalResult.NONE, "APP_CAL_RESULT_NOT_STORED" to CalResult.NOT_STORED,
                "APP_CAL_RESULT_OK_MOUNT_DROPPED" to CalResult.OK_MOUNT_DROPPED,
            ).mapValues { it.value.toLong() },
        )
    }

    @Test
    fun poseLabelsMatchTheDeviceTiles() {
        val tiles = Regex("""\{(ACCEL_CAL_DIR_\w+), "([^"]+)"\}""")
            .findAll(FirmwareSources.code("components/ui/src/ui_logic.c"))
            .map { it.groupValues[1].removePrefix("ACCEL_CAL_DIR_") to it.groupValues[2] }.toList()
        assertEquals(CoverageDir.entries.map { it.name to it.label }, tiles)
    }

    @Test
    fun settingsStepPresetsMatchTheDeviceScreen() {
        val presets = Regex("""static\s+const\s+uint16_t\s+(\w+_PRESETS_\w+)\[\]\s*=\s*\{([^}]*)\}""")
            .findAll(FirmwareSources.code("components/ui/src/ui_logic.c"))
            .associate { m -> m.groupValues[1] to m.groupValues[2].split(',').map { it.trim().toInt() } }
        assertEquals(
            mapOf(
                "DIM_PRESETS_S" to DIM_PRESETS_S.toList(),
                "SLEEP_PRESETS_S" to SLEEP_PRESETS_S.toList(),
                "OFF_PRESETS_MIN" to OFF_PRESETS_MIN.toList(),
            ),
            presets,
        )
    }

    @Test
    fun colorsMatchTheDevicePalette() {
        val c = FirmwareSources.intDefines("components/ui/src/ui_priv.h").filterKeys { it.startsWith("UI_C_") }
        val app = mapOf(
            "UI_C_GREEN" to Green, "UI_C_AMBER" to Amber, "UI_C_WHITE" to White, "UI_C_GREY" to Grey,
            "UI_C_DARK" to Dark, "UI_C_BUTTON" to Button, "UI_C_RED" to Red, "UI_C_BLUE" to Blue,
        )
        assertSameMap("palette", app.mapValues { c.getValue(it.key) }, app.mapValues { (it.value.toArgb() and 0xFFFFFF).toLong() })
        // The level colours on the live screen are the device's.
        assertEquals(listOf(Green, Amber, White), StatusColors().let { listOf(it.onTarget, it.near, it.off) })
    }

    @Test
    fun recorderService() {
        val rec = defines(proto, "DB_REC_")
        assertSameMap(
            "DB_REC_OP_* and limits",
            rec.filterKeys { k -> listOf("DB_REC_STATE_", "DB_REC_REASON_", "DB_REC_FLAG_").none { k.startsWith(it) } },
            mapOf(
                "DB_REC_OP_OPEN" to RecOpcode.OPEN, "DB_REC_OP_CLOSE" to RecOpcode.CLOSE,
                "DB_REC_OP_TAKE_START" to RecOpcode.TAKE_START, "DB_REC_OP_STOP" to RecOpcode.STOP,
                "DB_REC_OP_MARK" to RecOpcode.MARK, "DB_REC_TAKE_START_LEN" to RecOpcode.TAKE_START_LEN,
                "DB_REC_MARK_TEXT_MAX" to RecOpcode.MARK_TEXT_MAX, "DB_REC_GAIN_MAX_DB" to RecOpcode.GAIN_MAX_DB,
                "DB_REC_MAX_S_DEFAULT" to RecOpcode.MAX_S_DEFAULT, "DB_REC_MAX_S_LIMIT" to RecOpcode.MAX_S_LIMIT,
                "DB_REC_MIC_1" to RecOpcode.MIC_1, "DB_REC_MIC_2" to RecOpcode.MIC_2,
            ).mapValues { it.value.toLong() } + RecProfile.entries.associate { "DB_REC_PROFILE_${it.name}" to it.wire.toLong() },
        )
        assertSameMap("DB_REC_STATE_*", defines(proto, "DB_REC_STATE_"), RecState.entries.associate { "DB_REC_STATE_${it.name}" to it.wire.toLong() })
        assertSameMap("DB_REC_REASON_*", defines(proto, "DB_REC_REASON_"), RecReason.entries.associate { "DB_REC_REASON_${it.name}" to it.wire.toLong() })
        assertSameMap(
            "DB_REC_FLAG_*", defines(proto, "DB_REC_FLAG_"),
            mapOf(
                "DB_REC_FLAG_NO_AP" to RecFlags.NO_AP, "DB_REC_FLAG_CLIENT" to RecFlags.CLIENT, "DB_REC_FLAG_AUDIO" to RecFlags.AUDIO,
                "DB_REC_FLAG_IMU_LOST" to RecFlags.IMU_LOST, "DB_REC_FLAG_BATTERY_LOW" to RecFlags.BATTERY_LOW,
            ).mapValues { it.value.toLong() },
        )
        // The stream's reasons and profiles are the same numbers.
        val stream = FirmwareSources.intDefines("components/recorder/include/rec_stream.h")
        for (r in RecReason.entries) assertEquals(r.name, r.wire.toLong(), stream["REC_REASON_${r.name}"])
        for (p in RecProfile.entries) assertEquals(p.name, p.wire.toLong(), stream["REC_PROFILE_${p.name}"])

        val (fields, size) = FirmwareSources.layout(proto, "db_rec_state_packet_t")
        assertEquals(
            listOf(
                "uint8_t version @0", "uint8_t state @1", "uint8_t reason @2", "uint8_t flags @3", "uint32_t take_id @4",
                "uint16_t elapsed_s @8", "uint16_t max_s @10", "uint32_t sent_kib @12", "uint32_t dropped @16",
                "uint8_t ring_pct @20", "uint8_t profile @21", "uint8_t channel @22", "uint8_t reserved @23",
                "uint16_t port @24", "uint8_t ip[4] @26", "char ssid[16] @30", "char pass[24] @46", "uint8_t token[16] @70",
            ),
            fields,
        )
        assertEquals(RecorderStatus.SIZE, size)
        assertTrue("} db_rec_state_packet_t;      // ${RecorderStatus.SIZE} bytes" in FirmwareSources.text(proto))
        assertTrue("_Static_assert(sizeof(db_rec_state_packet_t) == ${RecorderStatus.SIZE}" in FirmwareSources.text("components/ble/src/ble_codec.c"))

        // rec_ctrl is written on an authenticated link, rec_state read on one and notified.
        val table = FirmwareSources.code("components/ble/src/ble_service.c")
        fun flags(chr: String) = Regex("""\{\.uuid = &$chr\.u,[^}]*?\.flags = ([^,]+),""").find(table)?.groupValues?.get(1)
        assertEquals("BLE_GATT_CHR_F_WRITE | SEC_WRITE", flags("UUID_CHR_REC_CTRL"))
        assertEquals("BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY | SEC_READ", flags("UUID_CHR_REC_STATE"))
        // The ATT codes rec_ctrl answers with, besides its own.
        val text = FirmwareSources.text(proto)
        assertTrue("rec_ctrl also answers 0x84 (unknown op), 0x85 (TAKE_START or MARK argument" in text)
    }

    @Test
    fun byteHelpersAgreeWithNio() {
        // u16/u32 helpers agree with java.nio little-endian.
        val b = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putShort(0x1234).putInt(0x89ABCDEF.toInt()).array()
        assertEquals(0x1234, b.u16(0))
        assertEquals(0x89ABCDEFL, b.u32(2))
        val out = ByteArray(6).also { it.put16(0, 0x1234); it.put32(2, 0x89ABCDEFL) }
        assertArrayEquals(b, out)
    }
}
