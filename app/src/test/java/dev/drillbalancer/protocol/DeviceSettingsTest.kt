package dev.drillbalancer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DeviceSettingsTest {
    // settings_to_json(settings_defaults()), as test_settings.c expects it.
    private val defaultsJson =
        "{\"tol\":0.5,\"near\":2,\"target\":45,\"mode\":\"wall\",\"bright\":100,\"dim_bright\":20," +
            "\"dim_s\":30,\"sleep_s\":120,\"off_min\":60,\"ble\":true,\"stale_s\":60,\"dot_inv\":false," +
            "\"cal\":{\"mount\":false,\"accel\":false},\"ver\":1}"

    private val d = DeviceSettings.DEFAULTS

    private fun parse(json: String) = (DeviceSettings.parse(json) as Decoded.Ok).value

    // ---- parsing ----

    @Test
    fun parsesDeviceDefaults() {
        assertEquals(d, parse(defaultsJson))
        assertEquals(d, (DeviceSettings.parse(defaultsJson.encodeToByteArray()) as Decoded.Ok).value)
    }

    @Test
    fun parsesNonDefaultDeviceOutput() {
        // %.6g / %.9g output of settings_to_json, calibrations stored
        val json = "{\"tol\":0.0512345678,\"near\":19.987654,\"target\":-89.5,\"mode\":\"angle\",\"bright\":10," +
            "\"dim_bright\":1,\"dim_s\":600,\"sleep_s\":3600,\"off_min\":0,\"ble\":false,\"stale_s\":600," +
            "\"dot_inv\":true,\"cal\":{\"mount\":true,\"accel\":true},\"ver\":1}"
        val s = parse(json)
        assertEquals(0.0512345678f, s.toleranceDeg)
        assertEquals(19.987654f, s.nearDeg)
        assertEquals(-89.5f, s.targetDeg)
        assertEquals(LevelMode.ANGLE, s.mode)
        assertEquals(CalState(mount = true, accel = true), s.cal)
        assertFalse(s.bleEnabled)
        assertTrue(s.dotInvert)
        assertEquals(0, s.offAfterMin)
        assertTrue(s.isValid)
    }

    @Test
    fun ignoresUnknownKeysAndTrailingNul() {
        val json = defaultsJson.removeSuffix("}") + ",\"future\":[1,2],\"x\":{\"y\":1}}\u0000"
        assertEquals(d, parse(json))
    }

    @Test
    fun olderFirmwareWithoutDotInvParses() {
        assertEquals(d, parse(defaultsJson.replace("\"dot_inv\":false,", "")))
    }

    @Test
    fun rejectsBrokenJson() {
        for (bad in listOf(
            "", "[]", "{\"tol\":0.5", "not json",
            defaultsJson.replace("\"mode\":\"wall\"", "\"mode\":\"ceiling\""),
            defaultsJson.replace("\"bright\":100,", ""),     // a writable key missing
            defaultsJson.replace("\"tol\":0.5", "\"tol\":null"),
        )) {
            assertTrue(bad, DeviceSettings.parse(bad) is Decoded.Invalid)
        }
    }

    // ---- validation: settings_validate() at every limit ----

    private fun assertInvalid(s: DeviceSettings, vararg fields: SettingsField) =
        assertEquals(s.toString(), fields.toSet(), s.invalidFields())

    @Test
    fun toleranceLimits() {
        assertInvalid(d.copy(toleranceDeg = 0.05f))
        assertInvalid(d.copy(toleranceDeg = 0.049999997f), SettingsField.TOL)
        assertInvalid(d.copy(toleranceDeg = 2f, nearDeg = 2f))
        assertInvalid(d.copy(toleranceDeg = 5f, nearDeg = 5f))
        assertInvalid(d.copy(toleranceDeg = 5.0000005f, nearDeg = 6f), SettingsField.TOL)
        assertInvalid(d.copy(toleranceDeg = Float.NaN), SettingsField.TOL, SettingsField.NEAR)
        assertInvalid(d.copy(toleranceDeg = Float.POSITIVE_INFINITY), SettingsField.TOL, SettingsField.NEAR)
    }

    @Test
    fun nearLimits() {
        assertInvalid(d.copy(toleranceDeg = 0.5f, nearDeg = 0.5f))
        assertInvalid(d.copy(toleranceDeg = 0.5f, nearDeg = 0.49999997f), SettingsField.NEAR)
        assertInvalid(d.copy(nearDeg = 20f))
        assertInvalid(d.copy(nearDeg = 20.000002f), SettingsField.NEAR)
        assertInvalid(d.copy(nearDeg = Float.NaN), SettingsField.NEAR)
        // test_settings.c: {"near":0.4} with tol 0.5 is a range error
        assertInvalid(d.copy(nearDeg = 0.4f), SettingsField.NEAR)
    }

    @Test
    fun targetLimits() {
        assertInvalid(d.copy(targetDeg = 90f))
        assertInvalid(d.copy(targetDeg = -90f))
        assertInvalid(d.copy(targetDeg = 90.00001f), SettingsField.TARGET)
        assertInvalid(d.copy(targetDeg = -90.00001f), SettingsField.TARGET)
        assertInvalid(d.copy(targetDeg = 91f), SettingsField.TARGET)
        assertInvalid(d.copy(targetDeg = Float.NEGATIVE_INFINITY), SettingsField.TARGET)
    }

    @Test
    fun brightnessLimits() {
        assertInvalid(d.copy(brightnessPct = 10, dimBrightnessPct = 10))
        assertInvalid(d.copy(brightnessPct = 9, dimBrightnessPct = 1), SettingsField.BRIGHT)
        assertInvalid(d.copy(brightnessPct = 101), SettingsField.BRIGHT)
        assertInvalid(d.copy(brightnessPct = 356), SettingsField.BRIGHT)
        assertInvalid(d.copy(dimBrightnessPct = 1))
        assertInvalid(d.copy(dimBrightnessPct = 0), SettingsField.DIM_BRIGHT)
        assertInvalid(d.copy(brightnessPct = 20, dimBrightnessPct = 20))
        assertInvalid(d.copy(brightnessPct = 20, dimBrightnessPct = 21), SettingsField.DIM_BRIGHT)
    }

    @Test
    fun timeLimits() {
        assertInvalid(d.copy(dimAfterS = 5))
        assertInvalid(d.copy(dimAfterS = 4), SettingsField.DIM_S)
        assertInvalid(d.copy(dimAfterS = 600, sleepAfterS = 600))
        assertInvalid(d.copy(dimAfterS = 601, sleepAfterS = 601), SettingsField.DIM_S)
        assertInvalid(d.copy(sleepAfterS = 30))
        assertInvalid(d.copy(sleepAfterS = 29), SettingsField.SLEEP_S)
        assertInvalid(d.copy(sleepAfterS = 3600))
        assertInvalid(d.copy(sleepAfterS = 3601), SettingsField.SLEEP_S)
        assertInvalid(d.copy(sleepAfterS = 65656), SettingsField.SLEEP_S)
        assertInvalid(d.copy(offAfterMin = 0))
        assertInvalid(d.copy(offAfterMin = 1440))
        assertInvalid(d.copy(offAfterMin = 1441), SettingsField.OFF_MIN)
        assertInvalid(d.copy(offAfterMin = -1), SettingsField.OFF_MIN)
        assertInvalid(d.copy(yawStaleS = 10))
        assertInvalid(d.copy(yawStaleS = 9), SettingsField.STALE_S)
        assertInvalid(d.copy(yawStaleS = 600))
        assertInvalid(d.copy(yawStaleS = 601), SettingsField.STALE_S)
    }

    @Test
    fun fixupPushesDependentFields() {
        val s = d.copy(toleranceDeg = 3f, nearDeg = 2f, brightnessPct = 15, dimBrightnessPct = 20, dimAfterS = 200, sleepAfterS = 120)
        assertFalse(s.isValid)
        val f = s.fixup()
        assertEquals(3f, f.nearDeg)
        assertEquals(15, f.dimBrightnessPct)
        assertEquals(200, f.sleepAfterS)
        assertTrue(f.isValid)
        // Consistent settings are left alone; fixup never repairs a single field's range.
        assertEquals(d, d.fixup())
        assertEquals(setOf(SettingsField.TOL), d.copy(toleranceDeg = 0.01f).fixup().invalidFields())
    }

    // ---- partial updates ----

    @Test
    fun updateContainsOnlyChangedKeys() {
        val edited = d.copy(toleranceDeg = 0.3f, mode = LevelMode.FLOOR)
        val u = SettingsUpdate.diff(d, edited)
        assertEquals(setOf(SettingsField.TOL, SettingsField.MODE), u.fields)
        assertEquals("{\"tol\":0.3,\"mode\":\"floor\"}", u.toJson())
        assertTrue(SettingsUpdate.diff(d, d).isEmpty)
        assertEquals("{}", SettingsUpdate.diff(d, d).toJson())
    }

    @Test
    fun everyFieldEncodes() {
        val edited = d.copy(
            toleranceDeg = 1.25f, nearDeg = 7.5f, targetDeg = -30f, mode = LevelMode.ANGLE, brightnessPct = 80,
            dimBrightnessPct = 5, dimAfterS = 45, sleepAfterS = 300, offAfterMin = 0, bleEnabled = false, yawStaleS = 120,
            dotInvert = true,
        )
        assertEquals(
            "{\"tol\":1.25,\"near\":7.5,\"target\":-30,\"mode\":\"angle\",\"bright\":80,\"dim_bright\":5," +
                "\"dim_s\":45,\"sleep_s\":300,\"off_min\":0,\"ble\":false,\"stale_s\":120,\"dot_inv\":true}",
            SettingsUpdate.diff(d, edited).toJson(),
        )
        // The update parses back onto the defaults as the edit (what the device stores).
        assertEquals(edited, SettingsUpdate.diff(d, edited).applyTo(d))
    }

    @Test
    fun updateKeepsFieldsChangedOnTheDevice() {
        // The phone edited brightness; meanwhile the device switched to floor mode.
        val base = d
        val edited = base.copy(brightnessPct = 60)
        val onDevice = base.copy(mode = LevelMode.FLOOR)
        val stored = SettingsUpdate.diff(base, edited).applyTo(onDevice)
        assertEquals(LevelMode.FLOOR, stored.mode)
        assertEquals(60, stored.brightnessPct)
    }

    @Test
    fun fixedUpCrossFieldsAreSent() {
        // Raising the tolerance above near pushes near too, so both keys go out together
        // (test_settings.c: {"tol":3} alone is a range error, {"tol":3,"near":4} is fine).
        val edited = d.copy(toleranceDeg = 3f).fixup()
        assertEquals("{\"tol\":3,\"near\":3}", SettingsUpdate.diff(d, edited).toJson())
    }

    @Test
    fun zeroAndSignedZeroAreNotAChange() {
        val a = d.copy(targetDeg = 0f)
        assertTrue(SettingsUpdate.diff(a, a.copy(targetDeg = -0f)).isEmpty)
        assertEquals("{\"target\":0}", SettingsUpdate.diff(d, a).toJson())
    }

    // ---- float formatting ----

    @Test
    fun floatsFormatShortAndExact() {
        val cases = mapOf(
            0.3f to "0.3", 0.5f to "0.5", 2f to "2", 45f to "45", -89.5f to "-89.5", 0.05f to "0.05",
            0.1f to "0.1", 0.15f to "0.15", 1e-4f to "0.0001", 20f to "20", 100f to "100", 0f to "0",
            0.0512345678f to "0.05123457", 19.987654f to "19.987654", 0.1f + 0.2f to "0.3",
            Math.nextUp(0.15f) to "0.15000002", Float.NaN to "null",
        )
        for ((v, s) in cases) assertEquals("formatFloat($v)", s, formatFloat(v))
    }

    @Test
    fun floatsRoundTripThroughDoubleLikeCjson() {
        // cJSON parses a double, settings.c casts it to float.
        val rnd = Random(1)
        repeat(20000) {
            val v = when (it % 3) {
                0 -> rnd.nextFloat() * 5f
                1 -> rnd.nextFloat() * 180f - 90f
                else -> Float.fromBits(rnd.nextInt()).takeIf { f -> f.isFinite() } ?: 1f
            }
            val s = formatFloat(v)
            assertEquals(s, v, s.toFloat())
            assertEquals(s, v, s.toDouble().toFloat())
            assertFalse(s, 'E' in s || 'e' in s)
        }
    }

    @Test
    fun steppedValuesStayExact() {
        // A UI stepping by 0.05 in float lands on values like 0.15000002: still exact.
        var v = 0.05f
        repeat(99) {
            v += 0.05f
            assertEquals(v, formatFloat(v).toFloat())
        }
    }
}
