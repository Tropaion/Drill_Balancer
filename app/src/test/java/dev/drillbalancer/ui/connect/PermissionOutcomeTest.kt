package dev.drillbalancer.ui.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionOutcomeTest {
    private val p = setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
    private val none = emptySet<String>()

    @Test
    fun dismissedFirstDialogCanBeShownAgain() {
        // Tapped outside the dialog on first launch: no rationale before or after.
        assertFalse(PermissionOutcome.deniedForGood(p, before = none, after = none, previousSilent = false))
        assertTrue(PermissionOutcome.silent(p, none, none))
    }

    @Test
    fun secondDenialIsForGood() {
        // Android 11+: the first "Don't allow" brings a rationale, the second takes it away
        // (Android 8 to 10: "Don't ask again").
        assertFalse(PermissionOutcome.deniedForGood(p, before = none, after = p, previousSilent = false))
        assertFalse(PermissionOutcome.silent(p, none, p))
        assertTrue(PermissionOutcome.deniedForGood(p, before = p, after = none, previousSilent = false))
        // Dismissed instead: the rationale stays.
        assertFalse(PermissionOutcome.deniedForGood(p, before = p, after = p, previousSilent = false))
    }

    @Test
    fun answeredWithoutAskingTwiceIsForGood() {
        // Denied for good before this screen: the system refuses without a dialog.
        assertTrue(PermissionOutcome.deniedForGood(p, before = none, after = none, previousSilent = true))
    }

    @Test
    fun grantedIsNeverDenied() {
        assertFalse(PermissionOutcome.deniedForGood(none, before = p, after = none, previousSilent = true))
        assertFalse(PermissionOutcome.silent(none, none, none))
    }
}
