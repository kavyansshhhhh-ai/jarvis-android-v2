package app.shilex.android.orb

/** Visibility gate for the event-driven overlay; permission alone never makes it visible. */
internal object TemporaryOrbPolicy {
    fun shouldShow(
        assistantEventActive: Boolean,
        enabled: Boolean,
        hasOverlayPermission: Boolean,
        appVisible: Boolean,
        sensitiveScreen: Boolean,
    ): Boolean = assistantEventActive && enabled && hasOverlayPermission && !appVisible && !sensitiveScreen
}
