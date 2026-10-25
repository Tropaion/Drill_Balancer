package dev.drillbalancer.ui.connect

/**
 * Whether a permission request was refused for good, so only the app settings can grant
 * it. Android shows no rationale both before a first request and after a permanent denial,
 * and a dialog dismissed by tapping outside it or Back refuses without counting as a
 * denial. Each set holds the permissions with a rationale to show ([before] the request,
 * [after] it).
 */
object PermissionOutcome {
    /**
     * Faster than a dialog can be shown and dismissed: Android answered without asking.
     * Its own round trip takes a few hundred milliseconds at most.
     */
    const val ANSWERED_WITHOUT_ASKING_MS = 500L

    /** Silent refusals in a row that count as for good whatever they took: a slow phone. */
    const val SILENT_IN_A_ROW = 3

    /**
     * For good once a refused permission lost its rationale, or when this request was
     * refused with no rationale either side ([silent]) and either came back within
     * [ANSWERED_WITHOUT_ASKING_MS] of the launch ([elapsedMs]; a dismissed dialog takes
     * longer) or is the [SILENT_IN_A_ROW]th such refusal in a row ([silentBefore] before it).
     */
    fun deniedForGood(refused: Set<String>, before: Set<String>, after: Set<String>, elapsedMs: Long, silentBefore: Int): Boolean {
        if (refused.isEmpty() || refused.any { it in after }) return false
        if (refused.any { it in before }) return true
        return elapsedMs < ANSWERED_WITHOUT_ASKING_MS || silentBefore + 1 >= SILENT_IN_A_ROW
    }

    /** Refused with no rationale before or after: a dismissed dialog, or a permanent denial. */
    fun silent(refused: Set<String>, before: Set<String>, after: Set<String>): Boolean =
        refused.isNotEmpty() && refused.none { it in before || it in after }
}
