package dev.drillbalancer.ui.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionOutcomeTest {
    private val p = setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
    private val none = emptySet<String>()
    private val dismissed = 2_000L   // a dialog was shown and dismissed
    private val instant = 120L       // Android answered without a dialog

    @Test
    fun dismissedFirstDialogCanBeShownAgain() {
        // Tapped outside the dialog on first launch: no rationale before or after.
        assertFalse(PermissionOutcome.deniedForGood(p, before = none, after = none, dismissed, silentBefore = 0))
        assertTrue(PermissionOutcome.silent(p, none, none))
    }

    @Test
    fun dismissedTwiceCanBeShownAgain() {
        // Dismissed, Allow tapped again, dismissed again: Android still shows the dialog.
        assertFalse(PermissionOutcome.deniedForGood(p, before = none, after = none, dismissed, silentBefore = 1))
    }

    @Test
    fun secondDenialIsForGood() {
        // Android 11+: the first "Don't allow" brings a rationale, the second takes it away
        // (Android 8 to 10: "Don't ask again").
        assertFalse(PermissionOutcome.deniedForGood(p, before = none, after = p, dismissed, silentBefore = 0))
        assertFalse(PermissionOutcome.silent(p, none, p))
        assertTrue(PermissionOutcome.deniedForGood(p, before = p, after = none, dismissed, silentBefore = 0))
        // Dismissed instead: the rationale stays.
        assertFalse(PermissionOutcome.deniedForGood(p, before = p, after = p, dismissed, silentBefore = 0))
    }

    @Test
    fun answeredWithoutAskingIsForGood() {
        // Denied for good before this screen: the system refuses at once, without a dialog.
        assertTrue(PermissionOutcome.deniedForGood(p, before = none, after = none, instant, silentBefore = 0))
        // A rationale after the request means a dialog was shown, however fast.
        assertFalse(PermissionOutcome.deniedForGood(p, before = none, after = p, instant, silentBefore = 0))
    }

    @Test
    fun slowSilentRefusalsEndUpForGood() {
        // A phone too slow for the timing: the third silent refusal in a row is for good.
        assertFalse(PermissionOutcome.deniedForGood(p, before = none, after = none, dismissed, silentBefore = 1))
        assertTrue(PermissionOutcome.deniedForGood(p, before = none, after = none, dismissed, silentBefore = 2))
    }

    @Test
    fun grantedIsNeverDenied() {
        assertFalse(PermissionOutcome.deniedForGood(none, before = p, after = none, instant, silentBefore = 5))
        assertFalse(PermissionOutcome.silent(none, none, none))
    }
}
