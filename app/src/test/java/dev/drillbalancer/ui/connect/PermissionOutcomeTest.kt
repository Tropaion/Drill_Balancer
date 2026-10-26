package dev.drillbalancer.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionOutcomeTest {
    private val p = setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
    private val none = emptySet<String>()
    private val dismissed = 2_000L   // a dialog was shown and dismissed
    private val slow = 900L          // Android answered without a dialog, on a slow phone
    private val instant = 120L       // Android answered without a dialog

    private fun after(before: Set<String>, after: Set<String>, elapsedMs: Long, refused: Set<String> = p) =
        PermissionOutcome.after(refused, before, after, elapsedMs)

    @Test
    fun silentRefusalOffersBoth() {
        // Tapped outside the first dialog, or denied for good on a phone too slow for the
        // timing: no rationale either side. Allow shows the dialog again in the first case,
        // the app settings work in both.
        assertEquals(PermissionPrompt.ASK_OR_SETTINGS, after(none, none, dismissed))
        assertEquals(PermissionPrompt.ASK_OR_SETTINGS, after(none, none, slow))
    }

    @Test
    fun secondDenialIsForGood() {
        // Android 11+: the first "Don't allow" brings a rationale, the second takes it away
        // (Android 8 to 10: "Don't ask again").
        assertEquals(PermissionPrompt.ASK, after(none, p, dismissed))
        assertEquals(PermissionPrompt.SETTINGS, after(p, none, dismissed))
        // Dismissed instead: the rationale stays.
        assertEquals(PermissionPrompt.ASK, after(p, p, dismissed))
    }

    @Test
    fun answeredWithoutAskingIsForGood() {
        // Denied for good before this screen: the system refuses at once, without a dialog.
        assertEquals(PermissionPrompt.SETTINGS, after(none, none, instant))
        // A rationale after the request means a dialog was shown, however fast.
        assertEquals(PermissionPrompt.ASK, after(none, p, instant))
    }

    @Test
    fun grantedAsksNothing() {
        assertEquals(PermissionPrompt.ASK, after(p, none, instant, refused = none))
    }
}
