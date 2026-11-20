package app.shilex.android.orb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryOrbPolicyTest {
    @Test
    fun overlayPermissionAloneNeverShowsOrb() {
        assertFalse(
            TemporaryOrbPolicy.shouldShow(
                assistantEventActive = false,
                enabled = true,
                hasOverlayPermission = true,
                appVisible = false,
                sensitiveScreen = false,
            ),
        )
    }

    @Test
    fun orbShowsOnlyDuringAnEnabledSafeBackgroundAssistantEvent() {
        assertTrue(
            TemporaryOrbPolicy.shouldShow(
                assistantEventActive = true,
                enabled = true,
                hasOverlayPermission = true,
                appVisible = false,
                sensitiveScreen = false,
            ),
        )
        assertFalse(
            TemporaryOrbPolicy.shouldShow(
                assistantEventActive = true,
                enabled = true,
                hasOverlayPermission = false,
                appVisible = false,
                sensitiveScreen = false,
            ),
        )
        assertFalse(
            TemporaryOrbPolicy.shouldShow(
                assistantEventActive = true,
                enabled = true,
                hasOverlayPermission = true,
                appVisible = true,
                sensitiveScreen = false,
            ),
        )
        assertFalse(
            TemporaryOrbPolicy.shouldShow(
                assistantEventActive = true,
                enabled = true,
                hasOverlayPermission = true,
                appVisible = false,
                sensitiveScreen = true,
            ),
        )
    }
}
