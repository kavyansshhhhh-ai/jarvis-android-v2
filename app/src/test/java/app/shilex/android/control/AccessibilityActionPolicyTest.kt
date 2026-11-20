package app.shilex.android.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityActionPolicyTest {
    @Test
    fun inputMethodWindowIsNotMistakenForTheForegroundApp() {
        assertTrue(AccessibilityActionPolicy.shouldUseActiveRoot("com.example.app", "app.jarvis", false))
        assertFalse(AccessibilityActionPolicy.shouldUseActiveRoot("com.keyboard", "app.jarvis", true))
        assertFalse(AccessibilityActionPolicy.shouldUseActiveRoot("app.jarvis", "app.jarvis", false))
    }

    @Test
    fun staleTargetsCannotCrossIntoAnotherForegroundApp() {
        assertTrue(AccessibilityActionPolicy.belongsToCurrentPackage("com.example.app", "com.example.app"))
        assertFalse(AccessibilityActionPolicy.belongsToCurrentPackage("com.example.app", "com.other.app"))
        assertFalse(AccessibilityActionPolicy.belongsToCurrentPackage("", "com.example.app"))
    }

    @Test
    fun onlyVisibleEnabledControlsCanBeActedOn() {
        assertTrue(AccessibilityActionPolicy.canClick(isVisible = true, isEnabled = true))
        assertFalse(AccessibilityActionPolicy.canClick(isVisible = false, isEnabled = true))
        assertFalse(AccessibilityActionPolicy.canClick(isVisible = true, isEnabled = false))
        assertTrue(AccessibilityActionPolicy.canType(isEditable = true, isVisible = true, isEnabled = true))
        assertFalse(AccessibilityActionPolicy.canType(isEditable = false, isVisible = true, isEnabled = true))
    }
}
