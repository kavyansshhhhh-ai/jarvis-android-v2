package app.shilex.android.control

/** Pure guards for resolving and acting on accessibility nodes from a recent screen read. */
internal object AccessibilityActionPolicy {
    fun shouldUseActiveRoot(rootPackage: String, ownPackage: String, isInputMethodWindow: Boolean): Boolean =
        rootPackage.isNotEmpty() && rootPackage != ownPackage && !isInputMethodWindow

    fun belongsToCurrentPackage(snapshotPackage: String, currentPackage: String): Boolean =
        snapshotPackage.isNotEmpty() && snapshotPackage == currentPackage

    fun canClick(isVisible: Boolean, isEnabled: Boolean): Boolean = isVisible && isEnabled

    fun canType(isEditable: Boolean, isVisible: Boolean, isEnabled: Boolean): Boolean =
        isEditable && isVisible && isEnabled
}
