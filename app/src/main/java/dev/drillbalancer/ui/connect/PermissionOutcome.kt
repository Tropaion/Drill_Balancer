package dev.drillbalancer.ui.connect

/** What the permission panel offers. */
enum class PermissionPrompt {
    ASK,                // Allow: Android shows its dialog
    ASK_OR_SETTINGS,    // Allow, and the app settings in case Android no longer asks
    SETTINGS,           // refused for good: only the app settings can grant it
}

/**
 * What to offer after a permission request was refused. Android shows no rationale both
 * before a first request and after a permanent denial, and a dialog dismissed by tapping
 * outside it or Back refuses without counting as a denial, so a refusal with no rationale
 * either side ("silent") is either. It keeps Allow and adds the app settings, which work in
 * both cases; only a sure sign of a permanent denial leaves the settings alone. Each set
 * holds the permissions with a rationale to show ([before] the request, [after] it).
 */
object PermissionOutcome {
    /**
     * Faster than a dialog can be shown and dismissed: Android answered without asking.
     * Its own round trip takes a few hundred milliseconds at most; a slower one only keeps
     * Allow next to the settings.
     */
    const val ANSWERED_WITHOUT_ASKING_MS = 500L

    /**
     * SETTINGS once a refused permission lost its rationale (Android 11+: the second "Don't
     * allow"), or a silent refusal came back within [ANSWERED_WITHOUT_ASKING_MS] of the launch
     * ([elapsedMs]). ASK_OR_SETTINGS for a slower silent refusal; ASK otherwise.
     */
    fun after(refused: Set<String>, before: Set<String>, after: Set<String>, elapsedMs: Long): PermissionPrompt = when {
        refused.isEmpty() || refused.any { it in after } -> PermissionPrompt.ASK
        refused.any { it in before } || elapsedMs < ANSWERED_WITHOUT_ASKING_MS -> PermissionPrompt.SETTINGS
        else -> PermissionPrompt.ASK_OR_SETTINGS
    }
}
