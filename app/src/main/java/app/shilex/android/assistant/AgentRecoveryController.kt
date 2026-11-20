package app.shilex.android.assistant

/**
 * Prevents duplicate clicks on already-reached screens, enforces bounded recovery retries,
 * detects ping-pong navigation cycles (A → B → A), and resets automatically when the screen,
 * target destination, or user command changes.
 */
class AgentRecoveryController(private val maxRetries: Int = 2) {
    private val lock = Any()
    private val attemptedActions = mutableSetOf<String>()
    private var activeScreenKey: String = ""
    private var activeDestinationKey: String = ""
    private var activeCommandKey: String = ""
    private var retriesUsed: Int = 0

    // Cycle detection: track last N semantic screen identifiers to spot A→B→A patterns.
    private val screenHistory = ArrayDeque<String>(CYCLE_WINDOW + 1)
    private var cycleDetected: Boolean = false

    fun shouldSkipDuplicate(
        actionId: String,
        currentScreen: String,
        expectedDestination: String,
    ): Boolean = synchronized(lock) {
        if (currentScreen.isNotBlank() &&
            expectedDestination.isNotBlank() &&
            currentScreen.equals(expectedDestination, ignoreCase = true)
        ) {
            return@synchronized true
        }
        val key = "${actionId.trim().lowercase()}|${currentScreen.trim().lowercase()}|${expectedDestination.trim().lowercase()}"
        !attemptedActions.add(key)
    }

    fun canRetry(retryCount: Int): Boolean = retryCount < maxRetries

    fun currentRetryCount(): Int = synchronized(lock) { retriesUsed }

    fun nextRetry(): Boolean = synchronized(lock) {
        if (retriesUsed >= maxRetries) {
            false
        } else {
            retriesUsed++
            true
        }
    }

    /** True if a navigation ping-pong cycle was detected and the agent should stop retrying. */
    fun isCycleDetected(): Boolean = synchronized(lock) { cycleDetected }

    fun resetCycle() = synchronized(lock) { cycleDetected = false }

    /**
     * Records a semantic screen key (package:screenName without volatile ephemeral suffixes).
     * Detects ping-pong cycles within the last [CYCLE_WINDOW] screen changes.
     * Resets attemptedActions and retry counter when the screen genuinely changes.
     */
    fun onScreenChanged(screenKey: String) = synchronized(lock) {
        // Normalize to package:label — strip volatile signature hashes after the 2nd colon
        val normalized = normalizeScreenKey(screenKey)
        if (normalized.isNotEmpty() && normalized != activeScreenKey) {
            activeScreenKey = normalized

            // Record screen in history (keep last CYCLE_WINDOW entries)
            screenHistory.addLast(normalized)
            while (screenHistory.size > CYCLE_WINDOW) screenHistory.removeFirst()

            // Detect ping-pong: if any screen appears ≥ 2 times in the recent history
            if (screenHistory.size >= CYCLE_WINDOW) {
                val counts = screenHistory.groupingBy { it }.eachCount()
                if (counts.any { it.value >= 2 }) {
                    cycleDetected = true
                }
            }

            attemptedActions.clear()
            retriesUsed = 0
        }
    }

    fun onDestinationChanged(destinationKey: String) = synchronized(lock) {
        val normalized = destinationKey.trim().lowercase()
        if (normalized != activeDestinationKey) {
            activeDestinationKey = normalized
            attemptedActions.clear()
            retriesUsed = 0
        }
    }

    fun onNewUserCommand(command: String) = synchronized(lock) {
        val normalized = command.trim().lowercase()
        if (normalized != activeCommandKey) {
            activeCommandKey = normalized
            attemptedActions.clear()
            retriesUsed = 0
            screenHistory.clear()
            cycleDetected = false
        }
    }

    fun reset() = synchronized(lock) {
        attemptedActions.clear()
        activeScreenKey = ""
        activeDestinationKey = ""
        activeCommandKey = ""
        retriesUsed = 0
        screenHistory.clear()
        cycleDetected = false
    }

    companion object {
        private const val CYCLE_WINDOW = 6

        /**
         * Strips volatile suffixes so minor element changes on the same screen don't appear
         * as a full screen change. Keeps package + screen-label components only.
         * Input format: "com.example.app:ScreenTitle:volatileHash..."
         */
        fun normalizeScreenKey(raw: String): String {
            val parts = raw.trim().lowercase().split(":")
            return when {
                parts.size >= 2 -> "${parts[0]}:${parts[1]}"
                else -> raw.trim().lowercase()
            }
        }
    }
}
