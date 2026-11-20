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

    // Numbers from the last ui_read, so "tap 4" means what the model just saw.
    @Volatile private var lastNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
    @Volatile private var lastMeta: Map<Int, NodeMeta> = emptyMap()
    @Volatile private var lastElementsList: List<ScreenElement> = emptyList()

    override fun onServiceConnected() {
        instance = this
        Diag.i("control: on")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
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

    fun foregroundPackage(): String = activeRoot()?.packageName?.toString().orEmpty()

    /** Finds the primary active non-JARVIS application or dialog root across windows. */
    private fun activeRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        val rootPkg = root?.packageName?.toString().orEmpty()
        val appWindows = runCatching {
            windows.filter { win ->
                win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    win.root?.packageName?.toString()?.let { it.isNotEmpty() && it != packageName } == true
            }.sortedByDescending { it.layer }
        }.getOrDefault(emptyList())
        if (root != null && rootPkg.isNotEmpty() && rootPkg != packageName) return root
        return appWindows.firstOrNull()?.root ?: root
    }

    /** True when any visible app window shows a password, PIN, MPIN, OTP, or CVV prompt/field. */
    fun isSensitiveAuthActive(): Boolean {
        val root = activeRoot() ?: return false
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (!node.isVisibleToUser) return false
            if (isNodeDataSensitive(node)) return false
            val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            if (node.isPassword || ControlRules.isSensitiveAuthCue(ownLabel(node), viewId, node.isPassword)) return true
            for (i in 0 until node.childCount) {
                if (node.getChild(i)?.let(::visit) == true) return true
            }
            return false
        }
        return visit(root)
    }

    /** Reads the app in front. Blocked apps, sensitive PIN/OTP/password screens, and system-restricted nodes come back without contents. */
    fun snapshot(allowPlayStore: Boolean = false): Snapshot? {
        val root = activeRoot() ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val dm = runCatching { resources.displayMetrics }.getOrNull()
        val screenW = dm?.widthPixels ?: 0
        val screenH = dm?.heightPixels ?: 0
        if (ControlRules.blocked(pkg, allowPlayStoreAutomation = allowPlayStore)) {
            lastNodes = emptyMap()
            lastMeta = emptyMap()
            lastElementsList = emptyList()
            return Snapshot(pkg, label, emptyList(), false, allowPlayStore = allowPlayStore, screenWidthPx = screenW, screenHeightPx = screenH)
        }
        if (isNodeDataSensitive(root)) {
            lastNodes = emptyMap()
            lastMeta = emptyMap()
            lastElementsList = emptyList()
            return Snapshot(pkg, label, emptyList(), false, restrictedBySystem = true, allowPlayStore = allowPlayStore, screenWidthPx = screenW, screenHeightPx = screenH)
        }
        val elements = mutableListOf<ScreenElement>()
        val assistantElements = mutableListOf<AssistantScreenElement>()
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
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
                    nodes[number] = node
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
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, insideTappable || tappable) }
                if (sensitiveAuth) return
            }
        }
        visit(root, false)
        if (sensitiveAuth) {
            lastNodes = emptyMap()
            lastMeta = emptyMap()
            lastElementsList = emptyList()
            return Snapshot(pkg, label, emptyList(), false, sensitiveAuth = true, allowPlayStore = allowPlayStore, screenWidthPx = screenW, screenHeightPx = screenH)
        }
        lastNodes = nodes
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
    }

    private fun isNodeDataSensitive(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && node.isAccessibilityDataSensitive

    /** Returns the cached node if still attached, else recovers a matching node by metadata on screen. */
    fun node(number: Int): AccessibilityNodeInfo? {
        val existing = lastNodes[number]
        if (existing != null && runCatching { existing.refresh() }.getOrDefault(false) && existing.isVisibleToUser) {
            return existing
        }
        val meta = lastMeta[number] ?: return null
        val recovered = recoverNode(meta)
        if (recovered != null) {
            Diag.i("control: recovered stale node #$number (${meta.label})")
        }
        return recovered
    }

    private fun recoverNode(meta: NodeMeta): AccessibilityNodeInfo? {
        val root = activeRoot() ?: return null
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
                best = n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
        }
        visit(root)
        return best
    }

    /** The first visible node (screen order) that matches [test]. */
    fun findFirst(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = activeRoot() ?: return null
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.isVisibleToUser || isNodeDataSensitive(node)) return null
            if (test(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)?.let { return it }
            return null
        }
        return visit(root)
    }

    fun labelOf(node: AccessibilityNodeInfo): String {
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        return ownLabel(node).ifEmpty { childText(node) }.ifEmpty { cleanViewIdLabel(viewId) }
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
        return tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    fun longClick(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isLongClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) return true
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) return false
        return swipe(bounds.exactCenterX(), bounds.exactCenterY(), bounds.exactCenterX(), bounds.exactCenterY(), durationMs = 800)
    }

    fun type(node: AccessibilityNodeInfo, text: String, submitIme: Boolean = false): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val typed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (typed && submitIme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return typed
    }

    fun focusedField(): AccessibilityNodeInfo? =
        activeRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    /** Scrolls the primary vertical scrollable area (never mistaking narrow story strips for the main feed). */
    fun scroll(down: Boolean): Boolean {
        val root = activeRoot() ?: return false
        val boxes = mutableListOf<Pair<InstagramMatcher.ScrollableBox, AccessibilityNodeInfo>>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isScrollable && node.isVisibleToUser) {
                val r = Rect().also(node::getBoundsInScreen)
                val vid = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
                boxes += InstagramMatcher.ScrollableBox(
                    id = boxes.size,
                    widthPx = r.width(),
                    heightPx = r.height(),
                    viewId = vid,
                ) to node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
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
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(::visit)?.let { return it }
            return node
        }
        // Top window first, so JARVIS's own overlay (Allow / Stop) is found when it is there.
        return windows.sortedByDescending { it.layer }.firstNotNullOfOrNull { it.root?.let(::visit) }
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
            for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
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
