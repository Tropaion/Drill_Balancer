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
     * For good once a refused permission lost its rationale, or when this request and the
     * previous one ([previousSilent]) were both refused with no rationale either side:
     * Android answered without asking.
     */
    fun deniedForGood(refused: Set<String>, before: Set<String>, after: Set<String>, previousSilent: Boolean): Boolean =
        refused.isNotEmpty() && refused.none { it in after } && (refused.any { it in before } || previousSilent)

    /** Refused with no rationale before or after: a dismissed dialog, or a permanent denial. */
    fun silent(refused: Set<String>, before: Set<String>, after: Set<String>): Boolean =
        refused.isNotEmpty() && refused.none { it in before || it in after }
}
