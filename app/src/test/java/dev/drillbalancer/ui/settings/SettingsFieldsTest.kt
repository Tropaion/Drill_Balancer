package dev.drillbalancer.ui.settings

import dev.drillbalancer.protocol.DeviceSettings
import dev.drillbalancer.protocol.SettingsField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// The steps must match the device's settings screen (ui_settings_step, test_ui_logic.c).
class SettingsFieldsTest {
    private val d = DeviceSettings.DEFAULTS

    private fun DeviceSettings.steps(f: SettingsField, dir: Int, n: Int) = (1..n).fold(this) { s, _ -> s.step(f, dir) }

    @Test
    fun toleranceUsesFineThenCoarseSteps() {
        assertEquals(0.55f, d.step(SettingsField.TOL, 1).toleranceDeg)
        assertEquals(1.0f, d.copy(toleranceDeg = 0.95f).step(SettingsField.TOL, 1).toleranceDeg)
        assertEquals(1.25f, d.copy(toleranceDeg = 1.0f).step(SettingsField.TOL, 1).toleranceDeg)
        assertEquals(1.0f, d.copy(toleranceDeg = 1.25f).step(SettingsField.TOL, -1).toleranceDeg)
        assertEquals(0.95f, d.copy(toleranceDeg = 1.0f).step(SettingsField.TOL, -1).toleranceDeg)
        // clamped at both ends, and off-grid values snap onto the grid
        assertEquals(0.05f, d.copy(toleranceDeg = 0.05f).step(SettingsField.TOL, -1).toleranceDeg)
        assertEquals(5.0f, d.steps(SettingsField.TOL, 1, 40).toleranceDeg)
        assertEquals(0.35f, d.copy(toleranceDeg = 0.33f).step(SettingsField.TOL, 1).toleranceDeg)
        assertEquals(0.3f, d.copy(toleranceDeg = 0.33f).step(SettingsField.TOL, -1).toleranceDeg)
    }

    @Test
    fun toleranceRaisesNear() {
        val s = d.copy(toleranceDeg = 1.75f, nearDeg = 2.0f).step(SettingsField.TOL, 1).step(SettingsField.TOL, 1)
        assertEquals(2.25f, s.toleranceDeg)
        assertEquals(2.25f, s.nearDeg)
        // near cannot go below the tolerance
        assertEquals(2.25f, s.step(SettingsField.NEAR, -1).nearDeg)
    }

    @Test
    fun presetsAndLimits() {
        assertEquals(45, d.step(SettingsField.DIM_S, 1).dimAfterS)
        assertEquals(20, d.step(SettingsField.DIM_S, -1).dimAfterS)
        assertEquals(90, d.step(SettingsField.SLEEP_S, -1).sleepAfterS)
        // sleep cannot go below dim: from 30 s down it stays at the dim time
        assertEquals(30, d.copy(sleepAfterS = 30).step(SettingsField.SLEEP_S, -1).sleepAfterS)
        // dim above sleep pushes sleep up
        val s = d.copy(dimAfterS = 120, sleepAfterS = 120).step(SettingsField.DIM_S, 1)
        assertEquals(180, s.dimAfterS)
        assertEquals(180, s.sleepAfterS)
        assertEquals(0, d.copy(offAfterMin = 15).step(SettingsField.OFF_MIN, -1).offAfterMin)
        assertEquals(1440, d.copy(offAfterMin = 1440).step(SettingsField.OFF_MIN, 1).offAfterMin)
        assertEquals(10, d.copy(yawStaleS = 10).step(SettingsField.STALE_S, -1).yawStaleS)
        assertEquals(-90f, d.steps(SettingsField.TARGET, -1, 200).targetDeg)
        assertEquals(1, d.copy(dimBrightnessPct = 5).step(SettingsField.DIM_BRIGHT, -1).dimBrightnessPct)
        // brightness down to 10 % pulls the dimmed brightness with it
        assertEquals(10, d.steps(SettingsField.BRIGHT, -1, 10).dimBrightnessPct)
    }

    @Test
    fun everyStepStaysValid() {
        var s = d
        for (f in NUMERIC_FIELDS) for (dir in listOf(1, -1)) repeat(60) {
            s = s.step(f, dir)
            assertTrue("$f $dir $s", s.isValid)
        }
    }

    @Test
    fun rangeAgreesWithTheValidation() {
        // range() (entry dialog) and invalidFields() (Save) encode the same firmware rules.
        val rnd = Random(3)
        fun f(lo: Float, hi: Float) = lo + rnd.nextFloat() * (hi - lo)
        fun i(lo: Int, hi: Int) = rnd.nextInt(lo, hi + 1)
        repeat(2000) {
            val s = d.copy(
                toleranceDeg = f(-1f, 7f), nearDeg = f(-1f, 25f), targetDeg = f(-100f, 100f),
                brightnessPct = i(-5, 110), dimBrightnessPct = i(-5, 110),
                dimAfterS = i(0, 700), sleepAfterS = i(0, 4000), offAfterMin = i(-5, 1500), yawStaleS = i(0, 700),
            )
            val outside = NUMERIC_FIELDS.filterTo(mutableSetOf()) { s.rawValue(it) !in s.range(it) }
            assertEquals("$s", s.invalidFields(), outside)
        }
    }

    @Test
    fun typedValues() {
        assertNull(d.entryError(SettingsField.TOL, "0,3"))
        assertEquals(0.3f, parseEntry("0,3"))
        assertEquals("Enter a value from 0.05 to 5.00 °.", d.entryError(SettingsField.TOL, "7.5"))
        assertEquals("Enter a value from 0.50 to 20.00 °.", d.entryError(SettingsField.NEAR, "0.4"))
        assertEquals("Enter a whole number.", d.entryError(SettingsField.DIM_S, "12.5"))
        assertEquals("Enter a number.", d.entryError(SettingsField.BRIGHT, "abc"))
        assertEquals("Enter a number.", d.entryError(SettingsField.TARGET, "NaN"))
        assertNull(d.entryError(SettingsField.TARGET, "-90"))
        assertEquals(d.copy(sleepAfterS = 600, dimAfterS = 600), d.with(SettingsField.DIM_S, 600f))
    }

    @Test
    fun typedAnglesAreRoundedToWhatTheScreenShows() {
        assertEquals(0.33f, d.with(SettingsField.TOL, 0.333f).toleranceDeg)
        assertEquals(2.47f, d.with(SettingsField.NEAR, 2.4689f).nearDeg)
        assertEquals(12.3f, d.with(SettingsField.TARGET, 12.34f).targetDeg)
        assertEquals(-12.4f, d.with(SettingsField.TARGET, -12.36f).targetDeg)
        // An off-grid tolerance set by another client: the near range reads as displayed,
        // and its own lower end is accepted (fixup() then lifts near to the tolerance).
        val off = d.copy(toleranceDeg = 0.333f)
        assertEquals("Enter a value from 0.33 to 20.00 °.", off.entryError(SettingsField.NEAR, "0.2"))
        assertNull(off.entryError(SettingsField.NEAR, "0,33"))
        val s = off.with(SettingsField.NEAR, 0.33f)
        assertTrue(s.isValid)
        assertEquals(0.333f, s.nearDeg)
    }

    @Test
    fun displayLikeTheDevice() {
        assertEquals("0.50°", d.display(SettingsField.TOL))
        assertEquals("45.0°", d.display(SettingsField.TARGET))
        assertEquals("2 min", d.display(SettingsField.SLEEP_S))
        assertEquals("1 h", d.display(SettingsField.OFF_MIN))
        assertEquals("Never", d.copy(offAfterMin = 0).display(SettingsField.OFF_MIN))
        assertEquals("45 s", d.copy(dimAfterS = 45).display(SettingsField.DIM_S))
    }
}
