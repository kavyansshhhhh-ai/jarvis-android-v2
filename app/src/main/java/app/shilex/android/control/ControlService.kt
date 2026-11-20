package app.shilex.android.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.shilex.android.Diag
import app.shilex.android.assistant.ScreenElement as AssistantScreenElement
import app.shilex.android.assistant.ScreenState
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * JARVIS's phone control (Android Accessibility). It only acts when a tool asks, never on
 * its own; risky taps wait for the user's Allow on [ControlOverlay]. Nothing it reads is
 * stored or logged.
 */
class ControlService : AccessibilityService() {
    // Lazy: Android can destroy the service before it ever connects.
    private val overlayLazy = lazy { ControlOverlay(this) }
    val overlay: ControlOverlay by overlayLazy

    data class NodeMeta(
        val number: Int,
        val viewId: String,
        val label: String,
        val role: String,
        val packageName: String,
        val bounds: Rect,
        val isEnabled: Boolean = true,
        val isSelected: Boolean = false,
        val isChecked: Boolean = false,
        val isFocused: Boolean = false,
        val isEditable: Boolean = false,
        val hint: String = "",
        val className: String = "",
    )

    sealed interface ScreenshotCapture {
        data class Ok(val bitmap: Bitmap) : ScreenshotCapture
        data object SecureBlocked : ScreenshotCapture
        data class Unavailable(val reason: String) : ScreenshotCapture
    }

    // Numbered metadata from the last ui_read; AccessibilityNodeInfo instances are never cached
    // across screen snapshots because they become stale and retain the view hierarchy.
    @Volatile private var lastMeta: Map<Int, NodeMeta> = emptyMap()
    @Volatile private var lastElementsList: List<ScreenElement> = emptyList()

    override fun onServiceConnected() {
        instance = this
        Diag.i("control: on")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> app.shilex.android.orb.FloatingOrb.sync(this)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        lastMeta = emptyMap()
        lastElementsList = emptyList()
        if (overlayLazy.isInitialized()) overlay.hideAll()
        Diag.i("control: off")
        super.onDestroy()
    }

    class Snapshot(
        val packageName: String,
        val appLabel: String,
        val elements: List<ScreenElement>,
        val more: Boolean,
        val sensitiveAuth: Boolean = false,
        val restrictedBySystem: Boolean = false,
        val meta: Map<Int, NodeMeta> = emptyMap(),
        val signature: String = "",
        val allowPlayStore: Boolean = false,
        val screenWidthPx: Int = 0,
        val screenHeightPx: Int = 0,
        val screenState: ScreenState? = null,
    ) {
        val blocked get() = ControlRules.blocked(packageName, allowPlayStoreAutomation = allowPlayStore)
        val upiApp get() = ControlRules.isSupportedUpiApp(packageName)
        val financeApp get() = ControlRules.isFinanceApp(packageName)
    }

    /** Captures a fresh [ScreenState] directly from the live Accessibility hierarchy. */
    fun captureScreenState(allowPlayStore: Boolean = false): ScreenState? =
        snapshot(allowPlayStore = allowPlayStore)?.screenState

    fun lastElements(): List<ScreenElement> = lastElementsList

    fun lastMetadata(): Map<Int, NodeMeta> = lastMeta

    fun foregroundPackage(): String {
        val root = activeRoot() ?: return ""
        return try {
            root.packageName?.toString().orEmpty()
        } finally {
            recycleNode(root)
        }
    }

    /** Finds the primary active non-JARVIS application or dialog root across windows. */
    private fun activeRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString().orEmpty()
        val visibleWindows = runCatching { windows.sortedByDescending { it.layer } }.getOrDefault(emptyList())
        val activeWindowType = visibleWindows.firstOrNull { win ->
            windowRootPackage(win) == rootPkg
        }?.type
        val appWindows = visibleWindows.filter { win ->
            win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                windowRootPackage(win)?.let { it.isNotEmpty() && it != packageName } == true
        }
        val useActiveRoot = root != null && AccessibilityActionPolicy.shouldUseActiveRoot(
                rootPackage = rootPkg,
                ownPackage = packageName,
                isInputMethodWindow = activeWindowType == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            )
        val selected = when {
            useActiveRoot -> root
            appWindows.isNotEmpty() -> appWindows.first().root
            activeWindowType == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> null
            else -> root
        }
        visibleWindows.forEach(::recycleWindow)
        if (root != null && selected !== root) recycleNode(root)
        return selected
    }

    private fun windowRootPackage(window: android.view.accessibility.AccessibilityWindowInfo): String? {
        val root = runCatching { window.root }.getOrNull() ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            recycleNode(root)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleWindow(window: android.view.accessibility.AccessibilityWindowInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { window.recycle() }
        }
    }

    /** True when any visible app window shows a password, PIN, MPIN, OTP, or CVV prompt/field. */
    fun isSensitiveAuthActive(): Boolean {
        val root = activeRoot() ?: return false
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (!node.isVisibleToUser) return false
            if (isNodeDataSensitive(node)) return true
            val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            if (node.isPassword || ControlRules.isSensitiveAuthCue(ownLabel(node), viewId, node.isPassword)) return true
            return visitChildren(node, visit = ::visit)
        }
        return try {
            visit(root)
        } finally {
            recycleNode(root)
        }
    }

    /** Reads the app in front. Blocked apps, sensitive PIN/OTP/password screens, and system-restricted nodes come back without contents. */
    fun snapshot(allowPlayStore: Boolean = false): Snapshot? {
        val root = activeRoot()
        if (root == null) {
            lastMeta = emptyMap()
            lastElementsList = emptyList()
            return null
        }
        try {
        val pkg = root.packageName?.toString().orEmpty()
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val dm = runCatching { resources.displayMetrics }.getOrNull()
        val screenW = dm?.widthPixels ?: 0
        val screenH = dm?.heightPixels ?: 0
        if (ControlRules.blocked(pkg, allowPlayStoreAutomation = allowPlayStore)) {
            lastMeta = emptyMap()
            lastElementsList = emptyList()
            return Snapshot(pkg, label, emptyList(), false, allowPlayStore = allowPlayStore, screenWidthPx = screenW, screenHeightPx = screenH)
        }
        if (isNodeDataSensitive(root)) {
            lastMeta = emptyMap()
            lastElementsList = emptyList()
            return Snapshot(pkg, label, emptyList(), false, restrictedBySystem = true, allowPlayStore = allowPlayStore, screenWidthPx = screenW, screenHeightPx = screenH)
        }
        val elements = mutableListOf<ScreenElement>()
        val assistantElements = mutableListOf<AssistantScreenElement>()
        val metaMap = mutableMapOf<Int, NodeMeta>()
        var more = false
        var sensitiveAuth = false
        var restrictedBySystem = false
        fun visit(node: AccessibilityNodeInfo, insideTappable: Boolean) {
            if (sensitiveAuth || !node.isVisibleToUser) return
            if (isNodeDataSensitive(node)) {
                restrictedBySystem = true
                return
            }
            val rawFullId = node.viewIdResourceName.orEmpty()
            val rawViewId = rawFullId.substringAfterLast('/')
            val cleanViewId = cleanViewIdLabel(rawViewId)
            val own = ownLabel(node)
            val hint = nonBlank(node.hintText)
            if (node.isPassword || ControlRules.isSensitiveAuthCue(own, rawViewId, node.isPassword)) {
                sensitiveAuth = true
                return
            }
            if (node.isScrollable) more = true
            val bounds = Rect().also(node::getBoundsInScreen)
            val nodeTextRaw = nonBlank(node.text)
            val nodeDescRaw = nonBlank(node.contentDescription)
            val sensitiveField = ControlRules.isSensitiveCredentialField(
                own.ifEmpty { nodeTextRaw },
                rawViewId,
                node.isPassword,
            )
            if (!sensitiveField &&
                (nodeTextRaw.isNotEmpty() || nodeDescRaw.isNotEmpty() || node.isClickable || rawFullId.isNotEmpty()) &&
                assistantElements.size < MAX_SCREEN_STATE_ELEMENTS
            ) {
                assistantElements += AssistantScreenElement(
                    text = nodeTextRaw,
                    description = nodeDescRaw,
                    resourceId = rawFullId,
                    className = node.className?.toString().orEmpty(),
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                    bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                )
            }
            val tappable = node.isClickable || node.isEditable || node.isCheckable
            val include = when {
                tappable -> true
                own.isNotEmpty() && !insideTappable -> true
                else -> false
            }
            if (include && elements.size < ScreenText.MAX_ELEMENTS) {
                val text = own.ifEmpty { childText(node) }.ifEmpty { hint }.ifEmpty { cleanViewId }
                if (text.isNotEmpty() || node.isEditable) {
                    val number = elements.size + 1
                    val resolvedRole = role(node)
                    val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
                    val value = if (node.isEditable && !showingHint && !ControlRules.isSensitiveCredentialField(text, rawViewId, node.isPassword)) {
                        nonBlank(node.text)
                    } else {
                        ""
                    }
                    elements += ScreenElement(
                        number = number,
                        role = resolvedRole,
                        label = text.ifEmpty { "Text field" },
                        value = value,
                        isEnabled = node.isEnabled,
                        isSelected = node.isSelected,
                        hint = hint,
                    )
                    metaMap[number] = NodeMeta(
                        number = number,
                        viewId = rawViewId,
                        label = text.ifEmpty { "Text field" },
                        role = resolvedRole,
                        packageName = node.packageName?.toString().orEmpty(),
                        bounds = bounds,
                        isEnabled = node.isEnabled,
                        isSelected = node.isSelected,
                        isChecked = node.isChecked,
                        isFocused = node.isFocused,
                        isEditable = node.isEditable,
                        hint = hint,
                        className = node.className?.toString().orEmpty(),
                    )
                }
            }
            visitChildren(node) { child ->
                visit(child, insideTappable || tappable)
                sensitiveAuth
            }
        }
        visit(root, false)
        if (sensitiveAuth) {
            lastMeta = emptyMap()
            lastElementsList = emptyList()
            return Snapshot(pkg, label, emptyList(), false, sensitiveAuth = true, allowPlayStore = allowPlayStore, screenWidthPx = screenW, screenHeightPx = screenH)
        }
        lastMeta = metaMap
        lastElementsList = elements.toList()
        val sig = "$pkg:${elements.size}:${elements.take(12).joinToString("|") { "${it.label}=${it.value}" }}"
        val freshScreenState = ScreenState(
            packageName = pkg,
            screenTitle = label,
            elements = assistantElements.toList(),
            capturedAt = System.currentTimeMillis(),
        )
        return Snapshot(
            pkg,
            label,
            elements,
            more || elements.size >= ScreenText.MAX_ELEMENTS,
            restrictedBySystem = restrictedBySystem && elements.isEmpty(),
            meta = metaMap,
            signature = sig,
            allowPlayStore = allowPlayStore,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
            screenState = freshScreenState,
        )
        } finally {
            recycleNode(root)
        }
    }

    private fun isNodeDataSensitive(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && node.isAccessibilityDataSensitive

    private inline fun visitChildren(
        node: AccessibilityNodeInfo,
        reverse: Boolean = false,
        visit: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        val indexes = if (reverse) (node.childCount - 1 downTo 0) else (0 until node.childCount)
        for (index in indexes) {
            val child = node.getChild(index) ?: continue
            val finished = try {
                visit(child)
            } finally {
                recycleNode(child)
            }
            if (finished) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { node.recycle() }
        }
    }

    /** Releases a node returned by findFirst, findAll, node, focusedField, or nodeAt. */
    fun releaseNode(node: AccessibilityNodeInfo) = recycleNode(node)

    /** Resolves a fresh node from the current hierarchy using the last read's metadata. */
    fun node(number: Int): AccessibilityNodeInfo? {
        val meta = lastMeta[number] ?: return null
        val recovered = recoverNode(meta)
        if (recovered != null) {
            Diag.i("control: resolved current node #$number (${meta.label})")
        }
        return recovered
    }

    private fun recoverNode(meta: NodeMeta): AccessibilityNodeInfo? {
        val root = activeRoot() ?: return null
        try {
        if (!AccessibilityActionPolicy.belongsToCurrentPackage(meta.packageName, root.packageName?.toString().orEmpty())) {
            Diag.w("control: refusing stale node #${meta.number} from ${meta.packageName} on ${root.packageName}")
            return null
        }
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val candidateRect = Rect()
        fun score(n: AccessibilityNodeInfo): Int {
            val viewId = n.viewIdResourceName?.substringAfterLast('/').orEmpty()
            val lbl = ownLabel(n).ifEmpty { childText(n) }.ifEmpty { nonBlank(n.hintText) }.ifEmpty { cleanViewIdLabel(viewId) }
            val r = role(n)
            var s = 0
            if (meta.viewId.isNotEmpty() && viewId == meta.viewId) s += 4
            if (meta.label.isNotEmpty() && lbl.equals(meta.label, ignoreCase = true)) s += 3
            if (r == meta.role) s += 1
            if (n.isEditable == meta.isEditable) s += 1
            if (n.isSelected == meta.isSelected) s += 1
            if (!n.isEnabled && meta.isEnabled) s -= 2
            n.getBoundsInScreen(candidateRect)
            if (!meta.bounds.isEmpty && Rect.intersects(meta.bounds, candidateRect)) s += 2
            return s
        }
        fun visit(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser || isNodeDataSensitive(n)) return
            val s = score(n)
            if (s > bestScore && s >= 5) {
                bestScore = s
                best?.let(::recycleNode)
                best = AccessibilityNodeInfo.obtain(n)
            }
            visitChildren(n) { child ->
                visit(child)
                false
            }
        }
        visit(root)
        return best
        } finally {
            recycleNode(root)
        }
    }

    /** The first visible node (screen order) that matches [test]. */
    fun findFirst(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = activeRoot() ?: return null
        try {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.isVisibleToUser || isNodeDataSensitive(node)) return null
            if (test(node)) return AccessibilityNodeInfo.obtain(node)
            var found: AccessibilityNodeInfo? = null
            visitChildren(node) { child ->
                found = visit(child)
                found != null
            }
            return found
        }
        return visit(root)
        } finally {
            recycleNode(root)
        }
    }

    /** Returns owned copies of every matching visible node; callers must release each result. */
    fun findAll(test: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val root = activeRoot() ?: return emptyList()
        val matches = mutableListOf<AccessibilityNodeInfo>()
        try {
            fun visit(node: AccessibilityNodeInfo) {
                if (!node.isVisibleToUser || isNodeDataSensitive(node)) return
                if (test(node)) matches += AccessibilityNodeInfo.obtain(node)
                visitChildren(node) { child ->
                    visit(child)
                    false
                }
            }
            visit(root)
            return matches
        } catch (t: Throwable) {
            matches.forEach(::recycleNode)
            throw t
        } finally {
            recycleNode(root)
        }
    }

    fun labelOf(node: AccessibilityNodeInfo): String {
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        return ownLabel(node).ifEmpty { childText(node) }.ifEmpty { cleanViewIdLabel(viewId) }
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var ownsTarget = false
        try {
            if (!AccessibilityActionPolicy.canClick(node.isVisibleToUser, node.isEnabled)) return false
            while (target != null && !target.isClickable) {
                val parent = target.parent
                if (ownsTarget) target?.let(::recycleNode)
                target = parent
                ownsTarget = parent != null
            }
            if (target != null && !AccessibilityActionPolicy.canClick(target.isVisibleToUser, target.isEnabled)) return false
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            // If accessibility does not expose ACTION_CLICK, tap the resolved clickable parent,
            // whose bounds cover the actual control, rather than the possibly tiny child label.
            val bounds = Rect().also { target?.getBoundsInScreen(it) ?: node.getBoundsInScreen(it) }
            if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
            return tap(bounds.exactCenterX(), bounds.exactCenterY())
        } finally {
            if (ownsTarget) target?.let(::recycleNode)
            recycleNode(node)
        }
    }

    fun longClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var ownsTarget = false
        try {
            if (!AccessibilityActionPolicy.canClick(node.isVisibleToUser, node.isEnabled)) return false
            while (target != null && !target.isLongClickable) {
                val parent = target.parent
                if (ownsTarget) target?.let(::recycleNode)
                target = parent
                ownsTarget = parent != null
            }
            if (target != null && !AccessibilityActionPolicy.canClick(target.isVisibleToUser, target.isEnabled)) return false
            if (target?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) return true
            val bounds = Rect().also { target?.getBoundsInScreen(it) ?: node.getBoundsInScreen(it) }
            if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
            return swipe(bounds.exactCenterX(), bounds.exactCenterY(), bounds.exactCenterX(), bounds.exactCenterY(), durationMs = 800)
        } finally {
            if (ownsTarget) target?.let(::recycleNode)
            recycleNode(node)
        }
    }

    fun type(node: AccessibilityNodeInfo, text: String, submitIme: Boolean = false): Boolean {
        try {
            if (!AccessibilityActionPolicy.canType(node.isEditable, node.isVisibleToUser, node.isEnabled)) return false
            val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || node.isFocused
            if (!focused) Diag.w("control: field declined explicit focus; attempting supported set-text action")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val typed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            val verified = typed && runCatching {
                node.refresh() && node.text?.toString() == text
            }.getOrDefault(false)
            val submitted = when {
                !verified -> false
                !submitIme -> true
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }.getOrDefault(false)
                else -> false
            }
            if (typed && !verified) Diag.w("control: set-text action was not reflected in the editable field")
            if (verified && submitIme && !submitted) Diag.w("control: text was set but the field did not accept the submit action")
            return verified && submitted
        } finally {
            recycleNode(node)
        }
    }

    fun focusedField(): AccessibilityNodeInfo? = activeRoot()?.let { root ->
        try {
            val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (field != null && !field.isEditable) {
                recycleNode(field)
                null
            } else {
                field
            }
        } finally {
            recycleNode(root)
        }
    }

    /** Scrolls the primary vertical scrollable area (never mistaking narrow story strips for the main feed). */
    fun scroll(down: Boolean): Boolean {
        val root = activeRoot() ?: return false
        val boxes = mutableListOf<Pair<InstagramMatcher.ScrollableBox, AccessibilityNodeInfo>>()
        try {
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isScrollable && node.isVisibleToUser) {
                    val r = Rect().also(node::getBoundsInScreen)
                    val vid = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
                    boxes += InstagramMatcher.ScrollableBox(
                        id = boxes.size,
                        widthPx = r.width(),
                        heightPx = r.height(),
                        viewId = vid,
                    ) to AccessibilityNodeInfo.obtain(node)
                }
                visitChildren(node) { child ->
                    visit(child)
                    false
                }
            }
            visit(root)
            val pickedId = InstagramMatcher.pickMainVerticalScrollContainer(boxes.map { it.first })
            val best = pickedId?.let { id -> boxes.firstOrNull { it.first.id == id }?.second }
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (best?.performAction(action) == true) return true
            val metrics = resources.displayMetrics
            val x = metrics.widthPixels / 2f
            val (from, to) = if (down) metrics.heightPixels * 0.7f to metrics.heightPixels * 0.3f else metrics.heightPixels * 0.3f to metrics.heightPixels * 0.7f
            return swipe(x, from, x, to)
        } finally {
            boxes.forEach { recycleNode(it.second) }
            recycleNode(root)
        }
    }

    /** Captures the screen and preserves whether Android refused due to a FLAG_SECURE window. */
    suspend fun captureScreenshotResult(): ScreenshotCapture {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotCapture.Unavailable("Screenshots require Android 11 or newer.")
        }
        return suspendCancellableCoroutine { done ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val shot = result.hardwareBuffer.use { buffer ->
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        }
                        if (shot != null) {
                            done.resume(ScreenshotCapture.Ok(shot))
                        } else {
                            done.resume(ScreenshotCapture.Unavailable("The screenshot bitmap was empty."))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Diag.w("control: screenshot refused ($errorCode)")
                        if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                            done.resume(ScreenshotCapture.SecureBlocked)
                        } else {
                            done.resume(ScreenshotCapture.Unavailable("Screenshot unavailable (error $errorCode)."))
                        }
                    }
                },
            )
        }
    }

    /**
     * The screen as a software bitmap, or null when Android refuses: before Android 11, or a
     * protected window (banking, payments and password screens come back as an error).
     */
    suspend fun screenshot(): Bitmap? = (captureScreenshotResult() as? ScreenshotCapture.Ok)?.bitmap

    /** Taps a screen point (click-by-sight). Callers check [nodeAt] and the risk first. */
    fun tapAt(x: Float, y: Float): Boolean = !overlay.confirming && tap(x, y)

    /** The deepest visible node under a screen point, if the app exposes one there. */
    fun nodeAt(x: Float, y: Float): AccessibilityNodeInfo? {
        val point = Rect()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.isVisibleToUser) return null
            node.getBoundsInScreen(point)
            if (!point.contains(x.toInt(), y.toInt())) return null
            var found: AccessibilityNodeInfo? = null
            visitChildren(node, reverse = true) { child ->
                found = visit(child)
                found != null
            }
            return found ?: AccessibilityNodeInfo.obtain(node)
        }
        // Top window first, so JARVIS's own overlay (Allow / Stop) is found when it is there.
        val visibleWindows = runCatching { windows.sortedByDescending { it.layer } }.getOrDefault(emptyList())
        try {
            for (window in visibleWindows) {
                val root = runCatching { window.root }.getOrNull() ?: continue
                val found = try {
                    visit(root)
                } finally {
                    recycleNode(root)
                }
                if (found != null) return found
            }
            return null
        } finally {
            visibleWindows.forEach(::recycleWindow)
        }
    }

    /** Waits until [packageName] is in front (after opening an app). */
    suspend fun waitFor(packageName: String, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (foregroundPackage() == packageName) return true
            delay(250)
        }
        return false
    }

    private fun tap(x: Float, y: Float) = swipe(x, y, x, y, durationMs = 60)

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun nonBlank(cs: CharSequence?): String = cs?.toString()?.trim().orEmpty()

    private fun ownLabel(node: AccessibilityNodeInfo): String {
        val txt = nonBlank(node.text)
        val desc = nonBlank(node.contentDescription)
        val hint = nonBlank(node.hintText)
        return if (node.isEditable) {
            hint.ifEmpty { desc }.ifEmpty { txt }
        } else {
            txt.ifEmpty { desc }.ifEmpty { hint }
        }
    }

    private fun cleanViewIdLabel(viewId: String): String = viewId
        .substringAfterLast('/')
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("^(btn|iv|tv|et|ic|img)\\s+", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun childText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        fun visit(n: AccessibilityNodeInfo) {
            if (parts.size >= 3) return
            ownLabel(n).takeIf { it.isNotEmpty() && n !== node }?.let { parts += it }
            visitChildren(n) { child ->
                visit(child)
                parts.size >= 3
            }
        }
        visit(node)
        return parts.joinToString(" · ")
    }

    private fun role(node: AccessibilityNodeInfo): String = when {
        node.isEditable -> "field"
        node.isCheckable -> if (node.isChecked) "switch on" else "switch off"
        node.isClickable -> "button"
        else -> "text"
    }

    companion object {
        private const val MAX_SCREEN_STATE_ELEMENTS = 100

        @Volatile var instance: ControlService? = null
            private set
    }
}
