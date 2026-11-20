package app.shilex.android.tools

import android.content.Intent
import android.net.Uri
import app.shilex.android.Diag
import app.shilex.android.assistant.ActionStatus
import app.shilex.android.assistant.AgentActionResult
import app.shilex.android.assistant.InstalledAppChecker
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import app.shilex.android.control.ScreenElement
import app.shilex.android.data.AutomationCategory
import app.shilex.android.data.Settings as AppSettings
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Explicit Google Play Store automation workflow states. */
enum class StoreFlowState {
    IDLE,
    CHECKING_INSTALLED,
    OPENING_STORE,
    SEARCHING,
    WAITING_FOR_RESULTS,
    RESULTS_VISIBLE,
    OPENING_APP_PAGE,
    APP_PAGE_VISIBLE,
    INSTALL_CONFIRMATION,
    INSTALLING,
    VERIFYING_INSTALLATION,
    COMPLETED,
    FAILED
}

/**
 * Pure Google Play Store listing & button state recognizer (unit-tested without Android).
 */
object PlayStoreMatcher {
    enum class ButtonState {
        Install,
        Open,
        Update,
        InstallingOrPending,
        PaidOrBlocked,
        None,
    }

    data class ScreenDecision(
        val buttonState: ButtonState,
        val actionElement: ScreenElement? = null,
        val matchedListingElement: ScreenElement? = null,
        val reason: String = "",
        val ambiguousListings: List<ScreenElement> = emptyList(),
        val isVerifiedInstalledUi: Boolean = false,
        val requiresUserChoice: Boolean = false,
    )

    private val PRICE_OR_BUY_RE = Regex("""(?:₹|rs\.?|inr|\$|€|£)\s*\d|\bbuy\b""", RegexOption.IGNORE_CASE)
    private val INSTALLING_RE = Regex("""\b(installing|pending|waiting\b.*|downloading|cancel|uninstalling)\b|%\s*$|डाउनलोड हो रहा है|इंस्टॉल हो रहा है|लंबित""", RegexOption.IGNORE_CASE)
    private val INSTALL_BTN_RE = Regex("""^(?:install(?:\s*[·•(-]\s*free\)?)?|free\s+install|get|इंस्टॉल(?: करें)?)$""", RegexOption.IGNORE_CASE)
    private val OPEN_BTN_RE = Regex("""^(?:open|play|खोलें)$""", RegexOption.IGNORE_CASE)
    private val UPDATE_BTN_RE = Regex("""^(?:update|अपडेट(?: करें)?)$""", RegexOption.IGNORE_CASE)
    private val INSTALLED_BADGE_RE = Regex("""^(?:installed|uninstall)$""", RegexOption.IGNORE_CASE)
    private val AUTH_OR_AGE_RE = Regex("""\b(sign in|verify|age|parental|ask to buy|add a payment method|complete account setup|continue payment|password)\b""", RegexOption.IGNORE_CASE)
    private val SEARCH_BAR_RE = Regex(
        """\b(search\s+apps(?:\s*(?:&|and)\s*games)?|search\s+google\s+play|search\s+for\s+apps)\b|ऐप्लिकेशन\s*(?:और|&)\s*गेम\s*खोजें|प्ले स्टोर में खोजें""",
        RegexOption.IGNORE_CASE,
    )
    private val DETAILS_CUES = Regex(
        """\b(about this app|about this game|ratings and reviews|ratings & reviews|developer contact|data safety|reviews|whats new|what's new)\b|इस ऐप्लिकेशन के बारे में|इस गेम के बारे में|डेटा सुरक्षा|रेटिंग और समीक्षाएं""",
        RegexOption.IGNORE_CASE,
    )
    private val RESULTS_CUES = Regex(
        """\b(results for|similar apps|you might also like|sponsored|top charts|filters|see more results|search results)\b|खोज परिणाम|खोज के नतीजे|समान ऐप्लिकेशन""",
        RegexOption.IGNORE_CASE,
    )
    private val SPONSORED_CUE = Regex("""\b(sponsored|advertisement|ad)\b""", RegexOption.IGNORE_CASE)

    enum class PlayStoreScreenState {
        HomeScreen,
        SearchInput,
        SearchResults,
        AppDetails,
        InstallingOrProgress,
        AuthOrRestrictedDialog,
        Unknown,
    }

    fun detectScreenState(elements: List<ScreenElement>): PlayStoreScreenState {
        if (isPaidOrRestricted(elements) != null) return PlayStoreScreenState.AuthOrRestrictedDialog
        val allLabels = elements.joinToString(" ") { "${it.label} ${it.hint}" }
        val hasDetailsCue = DETAILS_CUES.containsMatchIn(allLabels)
        val hasSearchResultsCue = RESULTS_CUES.containsMatchIn(allLabels)
        val hasSearchInput = findSearchInput(elements) != null
        val installingBtn = elements.any { el ->
            el.isEnabled && (el.role == "button" || el.role == "text") && INSTALLING_RE.containsMatchIn(el.label.trim())
        }

        return when {
            installingBtn -> PlayStoreScreenState.InstallingOrProgress
            hasDetailsCue -> PlayStoreScreenState.AppDetails
            hasSearchResultsCue -> PlayStoreScreenState.SearchResults
            hasSearchInput -> PlayStoreScreenState.HomeScreen
            elements.any { el -> el.role == "field" && el.isEnabled } -> PlayStoreScreenState.SearchInput
            else -> PlayStoreScreenState.Unknown
        }
    }

    fun isResultsVisible(elements: List<ScreenElement>, requestedApp: String? = null): Boolean {
        val state = detectScreenState(elements)
        if (state == PlayStoreScreenState.SearchResults || state == PlayStoreScreenState.AppDetails) return true
        val wanted = requestedApp?.let(::normalize).orEmpty()
        if (wanted.isNotEmpty() && elements.any { element ->
                val label = normalize(element.label)
                element.role != "field" && label.isNotEmpty() &&
                    (label == wanted || label.startsWith(wanted))
            }
        ) return true
        return false
    }

    /** A search request is submitted at most once for one automation run. */
    fun canSubmitSearch(
        searchSubmitted: Boolean,
        resultsVisible: Boolean,
        screenState: PlayStoreScreenState,
        attempts: Int,
    ): Boolean = !searchSubmitted && !resultsVisible && attempts == 0 &&
        screenState in setOf(PlayStoreScreenState.HomeScreen, PlayStoreScreenState.SearchInput)

    fun shouldStopAfterRepeatedFailure(
        unchangedPolls: Int,
        actionAttempted: Boolean,
        installTapped: Boolean,
        decision: ScreenDecision,
    ): Boolean = unchangedPolls >= 4 && actionAttempted && !installTapped &&
        (decision.buttonState == ButtonState.Install ||
            (decision.buttonState == ButtonState.None && decision.ambiguousListings.isEmpty()))

    fun normalize(text: String): String = AppCatalog.compactKey(text)

    fun isPaidOrRestricted(elements: List<ScreenElement>): String? {
        for (el in elements) {
            val txt = "${el.label} ${el.value}".trim()
            if (AUTH_OR_AGE_RE.containsMatchIn(txt)) {
                return "Google Play asks for account, age, payment, or parental verification (${el.label}). Handing control to the user."
            }
            if (el.role == "button" && PRICE_OR_BUY_RE.containsMatchIn(txt)) {
                return "This app requires payment or purchase (${el.label}); JARVIS will not auto-purchase apps."
            }
        }
        return null
    }

    /** Finds a visible Play Store search input field or search bar button cleanly. */
    fun findSearchInput(elements: List<ScreenElement>): ScreenElement? =
        elements.firstOrNull { el ->
            (el.role == "field" || el.role == "button") &&
                el.isEnabled &&
                (SEARCH_BAR_RE.containsMatchIn(el.label) || SEARCH_BAR_RE.containsMatchIn(el.hint))
        }

    /** Inspects visible Play Store listing or search result elements for [requestedApp]. */
    fun analyzeScreen(requestedApp: String, elements: List<ScreenElement>): ScreenDecision {
        val restrictedReason = isPaidOrRestricted(elements)
        if (restrictedReason != null) {
            return ScreenDecision(ButtonState.PaidOrBlocked, reason = restrictedReason)
        }

        val wantedCompact = normalize(requestedApp)
        val wantedTokens = requestedApp.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }

        val installingBtn = elements.firstOrNull { el ->
            el.isEnabled && (el.role == "button" || el.role == "text") && INSTALLING_RE.containsMatchIn(el.label.trim())
        }
        if (installingBtn != null) {
            return ScreenDecision(ButtonState.InstallingOrPending, actionElement = installingBtn)
        }

        val installBtn = elements.firstOrNull { el ->
            el.role == "button" && el.isEnabled && INSTALL_BTN_RE.containsMatchIn(el.label.trim())
        }
        val openBtn = elements.firstOrNull { el ->
            el.role == "button" && el.isEnabled && OPEN_BTN_RE.containsMatchIn(el.label.trim())
        }
        val updateBtn = elements.firstOrNull { el ->
            el.role == "button" && el.isEnabled && UPDATE_BTN_RE.containsMatchIn(el.label.trim())
        }
        val hasInstalledBadge = elements.any { el ->
            INSTALLED_BADGE_RE.containsMatchIn(el.label.trim())
        }

        val scoredListings = rankMatchingListings(wantedCompact, wantedTokens, elements)
        val listingMatch = scoredListings.firstOrNull()?.first
        val screenState = detectScreenState(elements)
        if (listingMatch != null && elements.any { SPONSORED_CUE.containsMatchIn("${it.label} ${it.hint}") }) {
            return ScreenDecision(
                buttonState = ButtonState.None,
                matchedListingElement = listingMatch,
                reason = "Google Play shows sponsored content and the listing cannot be safely distinguished. Ask the user which result to open.",
                ambiguousListings = scoredListings.map { it.first },
                requiresUserChoice = true,
            )
        }

        if (installBtn != null) {
            // Search cards can contain their own Install buttons. Open the exact, non-ambiguous
            // listing first so an unrelated/advertised card's action can never be selected.
            if (screenState != PlayStoreScreenState.AppDetails) {
                return if (scoredListings.size > 1 && scoredListings[0].second == scoredListings[1].second) {
                    val topScore = scoredListings[0].second
                    ScreenDecision(
                        ButtonState.None,
                        ambiguousListings = scoredListings.filter { it.second == topScore }.map { it.first },
                        reason = "Multiple Play Store results match \"$requestedApp\".",
                    )
                } else {
                    ScreenDecision(
                        ButtonState.None,
                        matchedListingElement = listingMatch,
                        reason = "Open the matching listing and verify its details before installing.",
                    )
                }
            }
            val isGoodMatch = listingMatch != null ||
                elements.any {
                    val comp = normalize(it.label)
                    val baseComp = normalize(it.label.split(Regex("[-:|•,]")).first().trim())
                    comp == wantedCompact || baseComp == wantedCompact || comp.startsWith(wantedCompact)
                }
            return if (isGoodMatch) {
                ScreenDecision(ButtonState.Install, actionElement = installBtn, matchedListingElement = listingMatch)
            } else {
                ScreenDecision(
                    ButtonState.None,
                    matchedListingElement = null,
                    reason = "The visible Play Store page doesn't clearly match \"$requestedApp\".",
                )
            }
        }
        if (openBtn != null && (listingMatch != null || hasInstalledBadge)) {
            return ScreenDecision(
                buttonState = ButtonState.Open,
                actionElement = openBtn,
                matchedListingElement = listingMatch,
                isVerifiedInstalledUi = hasInstalledBadge || listingMatch != null,
            )
        }
        if (updateBtn != null && (listingMatch != null || hasInstalledBadge)) {
            return ScreenDecision(
                buttonState = ButtonState.Update,
                actionElement = updateBtn,
                matchedListingElement = listingMatch,
                isVerifiedInstalledUi = true,
            )
        }

        // On search results list: check if ambiguous top candidates have identical confidence
        if (scoredListings.size > 1 && scoredListings[0].second == scoredListings[1].second) {
            val topScore = scoredListings[0].second
            val tied = scoredListings.filter { it.second == topScore }.map { it.first }
            return ScreenDecision(
                buttonState = ButtonState.None,
                matchedListingElement = listingMatch,
                ambiguousListings = tied,
                reason = "Multiple Play Store results match \"$requestedApp\".",
            )
        }

        if (listingMatch != null) {
            return ScreenDecision(ButtonState.None, matchedListingElement = listingMatch)
        }
        return ScreenDecision(ButtonState.None, reason = "No matching result for \"$requestedApp\" is visible yet.")
    }

    /** Computes the explicit [StoreFlowState] for the current Play Store UI decision and progress. */
    fun resolveFlowState(
        decision: ScreenDecision,
        alreadyInstalled: Boolean = false,
        inPlayStore: Boolean = true,
        hasSearchInput: Boolean = false,
        searchSubmitted: Boolean = false,
        tappedInstall: Boolean = false,
        confirmedInstall: Boolean = false,
        verifiedInstalledAfterAction: Boolean = false,
        timedOut: Boolean = false,
    ): StoreFlowState = when {
        alreadyInstalled || verifiedInstalledAfterAction -> StoreFlowState.COMPLETED
        decision.buttonState == ButtonState.PaidOrBlocked -> StoreFlowState.FAILED
        timedOut -> StoreFlowState.FAILED
        !inPlayStore -> StoreFlowState.OPENING_STORE
        decision.buttonState == ButtonState.Open || decision.buttonState == ButtonState.Update -> StoreFlowState.COMPLETED
        decision.buttonState == ButtonState.InstallingOrPending -> StoreFlowState.VERIFYING_INSTALLATION
        decision.buttonState == ButtonState.Install -> when {
            tappedInstall -> StoreFlowState.INSTALLING
            confirmedInstall -> StoreFlowState.INSTALLING
            else -> StoreFlowState.APP_PAGE_VISIBLE
        }
        decision.ambiguousListings.size > 1 -> StoreFlowState.RESULTS_VISIBLE
        decision.matchedListingElement != null -> StoreFlowState.RESULTS_VISIBLE
        searchSubmitted -> StoreFlowState.WAITING_FOR_RESULTS
        hasSearchInput -> StoreFlowState.SEARCHING
        else -> StoreFlowState.IDLE
    }

    private fun rankMatchingListings(
        wantedCompact: String,
        wantedTokens: List<String>,
        elements: List<ScreenElement>,
    ): List<Pair<ScreenElement, Int>> {
        if (wantedCompact.isEmpty()) return emptyList()
        return elements.mapNotNull { el ->
            val lbl = el.label.trim()
            if (lbl.isEmpty() ||
                SPONSORED_CUE.containsMatchIn("${el.label} ${el.hint}") ||
                INSTALL_BTN_RE.matches(lbl) ||
                OPEN_BTN_RE.matches(lbl) ||
                UPDATE_BTN_RE.matches(lbl) ||
                INSTALLED_BADGE_RE.matches(lbl) ||
                SEARCH_BAR_RE.containsMatchIn(lbl) ||
                lbl.equals("Search", ignoreCase = true) ||
                el.role == "field"
            ) {
                return@mapNotNull null
            }
            val comp = normalize(lbl)
            val baseTitle = lbl.split(Regex("[-:|•,]")).first().trim()
            val baseComp = normalize(baseTitle)
            var score = 0
            when {
                comp == wantedCompact -> score += 100
                baseComp == wantedCompact -> score += 95
                comp.startsWith(wantedCompact) -> score += 75
                wantedCompact in comp -> score += 50
                wantedTokens.isNotEmpty() && wantedTokens.all { it in lbl.lowercase() } -> score += 40
            }
            if (score > 0 && el.role == "button") score += 10
            if (score > 0) el to score else null
        }.sortedByDescending { it.second }
    }
}

/**
 * Google Play Store search & supported free-app installation workflow.
 * Never installs from third-party APK sites; respects paid/age/auth confirmation dialogs;
 * never claims an app is installed unless Android's PackageManager verifies it.
 */
object PlayStoreTools {
    val SearchInstall: PhoneTool = SearchInstallTool

    private object SearchInstallTool : PhoneTool {
        override val name = "playstore_search_install"

        override fun declaration() = Schema.function(
            name,
            "Searches Google Play Store for an app, verifies via PackageManager if it is already installed, checks the exact listing, requests confirmation before free Install, and verifies installation afterward.",
            Schema.string("app_name", "Exact app name to find/install on Google Play Store.", required = true),
            Schema.string("action", "install (default) or open_listing.", choices = listOf("install", "open_listing")),
            Schema.string("package_name", "Optional known verified package ID to check before opening Play Store. Never guess package IDs."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val appName = args.text("app_name")
            if (appName.isEmpty()) return Results.failed("no_app", "Ask which app to look up on Google Play Store.")
            val action = args.text("action").ifEmpty { "install" }
            val explicitPkg = args.text("package_name").takeIf { '.' in it }

            var flowState = StoreFlowState.CHECKING_INSTALLED
            val installerChecker = InstalledAppChecker(host.context)
            host.recoveryController.onDestinationChanged("playstore:$appName")

            // 1. Check live installed apps via PackageManager BEFORE touching Play Store
            if (explicitPkg != null && installerChecker.isInstalled(explicitPkg)) {
                flowState = StoreFlowState.COMPLETED
                val verified = AgentActionResult(
                    status = ActionStatus.ALREADY_COMPLETED,
                    message = "$appName ($explicitPkg) is already installed on this phone. Ask if the user wants to open it.",
                    expectedDestination = explicitPkg,
                    actualDestination = explicitPkg,
                )
                Diag.i("playstore: state=$flowState pkg=$explicitPkg")
                return Results.done(verified.message)
            }

            val beforeApps = InstalledAppsRegistry.apps(host.context, forceRefresh = true)
            val alreadyInstalled = AppCatalog.find(appName, beforeApps, includeNonLaunchable = false)
            val exactMatched = alreadyInstalled.firstOrNull {
                AppCatalog.compactKey(it.label) == AppCatalog.compactKey(appName) &&
                    installerChecker.isInstalled(it.packageName)
            }
            if (exactMatched != null) {
                flowState = StoreFlowState.COMPLETED
                val verified = AgentActionResult(
                    status = ActionStatus.ALREADY_COMPLETED,
                    message = "${exactMatched.label} (${exactMatched.packageName}) is already installed on this phone. Ask if the user wants to open it.",
                    expectedDestination = exactMatched.packageName,
                    actualDestination = exactMatched.packageName,
                )
                Diag.i("playstore: state=$flowState pkg=${exactMatched.packageName}")
                return Results.done(verified.message)
            }

            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.PlayStoreNavigation)) {
                flowState = StoreFlowState.FAILED
                return Results.failed(
                    "automation_disabled",
                    "Play Store navigation is turned off in Settings → Permissions. Ask the user to enable it or install manually.",
                )
            }

            val pm = host.context.packageManager
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://search?q=${Uri.encode(appName)}&c=apps"),
            ).setPackage(ControlRules.PLAY_STORE_PACKAGE)
            val searchSubmittedOnLaunch = marketIntent.resolveActivity(pm) != null
            val launchIntent: Intent = if (searchSubmittedOnLaunch) {
                marketIntent
            } else {
                pm.getLaunchIntentForPackage(ControlRules.PLAY_STORE_PACKAGE)
                    ?: return Results.failed("play_store_missing", "Google Play Store isn't available on this phone.")
            }
            val control = ControlService.instance?.takeIf { !it.overlay.stopped }
                ?: return host.openApp(
                    launchIntent,
                    "Google Play Store",
                    "Google Play Store will open on \"$appName\" after you speak so the user can check and tap Install.",
                )

            flowState = StoreFlowState.OPENING_STORE
            val knownPackagesBefore = beforeApps.map { it.packageName }.toSet()
            return host.openApp(
                launchIntent,
                "Google Play Store",
                "Opening Google Play Store to check \"$appName\" and start installation if available.",
            ) {
                automateInstall(
                    service = control,
                    host = host,
                    appName = appName,
                    wantInstall = action == "install",
                    knownPackagesBefore = knownPackagesBefore,
                    explicitPackage = explicitPkg,
                    searchSubmittedOnLaunch = searchSubmittedOnLaunch,
                )
            }
        }

        private suspend fun automateInstall(
            service: ControlService,
            host: ToolHost,
            appName: String,
            wantInstall: Boolean,
            knownPackagesBefore: Set<String>,
            explicitPackage: String?,
            searchSubmittedOnLaunch: Boolean,
        ): String {
            var flowState = StoreFlowState.OPENING_STORE
            if (!service.waitFor(ControlRules.PLAY_STORE_PACKAGE, WAIT_MS)) {
                flowState = StoreFlowState.FAILED
                Diag.w("playstore: state=$flowState reason=wait_timeout")
                return "Google Play Store didn't open in time; ask the user to check Play Store."
            }

            val checker = InstalledAppChecker(host.context)
            var tappedInstall = false
            var confirmedInstall = false
            var typedSearch = searchSubmittedOnLaunch
            var consecutiveSearches = 0
            var actionAttempted = false
            var unchangedScreenCount = 0
            var lastScreenSig = ""

            for (poll in 0 until POLL_STEPS) {
                delay(STEP_DELAY_MS)
                val snap = service.snapshot(allowPlayStore = true) ?: continue
                if (snap.packageName != ControlRules.PLAY_STORE_PACKAGE) {
                    flowState = StoreFlowState.OPENING_STORE
                    continue
                }

                if (snap.signature.isNotEmpty() && snap.signature == lastScreenSig) {
                    unchangedScreenCount++
                } else {
                    unchangedScreenCount = 0
                    lastScreenSig = snap.signature
                }

                host.recoveryController.onScreenChanged("${snap.packageName}:${snap.signature.take(32)}")
                if (snap.sensitiveAuth) {
                    flowState = StoreFlowState.FAILED
                    return "Google Play Store asked for password or account verification. Hand control to the user."
                }

                val currentScreenState = PlayStoreMatcher.detectScreenState(snap.elements)
                val resultsAlreadyVisible = PlayStoreMatcher.isResultsVisible(snap.elements, appName)
                val decision = PlayStoreMatcher.analyzeScreen(appName, snap.elements)
                val searchEl = PlayStoreMatcher.findSearchInput(snap.elements)

                if (PlayStoreMatcher.shouldStopAfterRepeatedFailure(
                        unchangedPolls = unchangedScreenCount,
                        actionAttempted = actionAttempted,
                        installTapped = tappedInstall,
                        decision = decision,
                    )
                ) {
                    Diag.w("playstore: same state after a failed action for $unchangedScreenCount polls; stopping")
                    break
                }

                flowState = PlayStoreMatcher.resolveFlowState(
                    decision = decision,
                    alreadyInstalled = false,
                    inPlayStore = true,
                    hasSearchInput = searchEl != null,
                    searchSubmitted = typedSearch || resultsAlreadyVisible,
                    tappedInstall = tappedInstall,
                    confirmedInstall = confirmedInstall,
                    verifiedInstalledAfterAction = false,
                )

                when (decision.buttonState) {
                    PlayStoreMatcher.ButtonState.PaidOrBlocked -> {
                        flowState = StoreFlowState.FAILED
                        return decision.reason
                    }
                    PlayStoreMatcher.ButtonState.Open -> {
                        flowState = StoreFlowState.VERIFYING_INSTALLATION
                        val installed = findVerifiedInstalledApp(host, checker, appName, knownPackagesBefore, explicitPackage)
                        if (installed != null) {
                            flowState = StoreFlowState.COMPLETED
                            return "Verified: ${installed.label} (${installed.packageName}) is installed on this phone."
                        }
                        if (decision.isVerifiedInstalledUi) {
                            flowState = StoreFlowState.COMPLETED
                            return "Google Play Store shows \"$appName\" is installed (Open button visible)."
                        }
                        return "Google Play Store shows an Open button for \"$appName\"."
                    }
                    PlayStoreMatcher.ButtonState.InstallingOrPending -> {
                        flowState = StoreFlowState.VERIFYING_INSTALLATION
                        val newlyAdded = findVerifiedInstalledApp(host, checker, appName, knownPackagesBefore, explicitPackage)
                        if (newlyAdded != null) {
                            flowState = StoreFlowState.COMPLETED
                            return "Verified: ${newlyAdded.label} (${newlyAdded.packageName}) finished installing from Google Play Store."
                        }
                        if (!tappedInstall) {
                            return "Google Play Store is already downloading/installing \"$appName\"."
                        }
                    }
                    PlayStoreMatcher.ButtonState.Install -> {
                        flowState = StoreFlowState.APP_PAGE_VISIBLE
                        if (!wantInstall) {
                            flowState = StoreFlowState.COMPLETED
                            return "Opened the Google Play Store listing for \"$appName\". Ask the user if they want to tap Install."
                        }
                        val installActionId = "ps_install_${PlayStoreMatcher.normalize(appName)}"
                        if (!tappedInstall && !host.recoveryController.shouldSkipDuplicate(installActionId, flowState.name, StoreFlowState.INSTALLING.name)) {
                            flowState = StoreFlowState.INSTALL_CONFIRMATION
                            if (!confirmedInstall) {
                                confirmedInstall = service.overlay.confirm("Install \"$appName\" from Google Play Store")
                                if (!confirmedInstall) {
                                    flowState = StoreFlowState.FAILED
                                    return "Installation of \"$appName\" was cancelled before tapping Install."
                                }
                            }
                            val node = decision.actionElement?.number?.let(service::node)
                            if (node != null) {
                                actionAttempted = true
                                if (service.click(node)) {
                                    tappedInstall = true
                                    flowState = StoreFlowState.INSTALLING
                                    service.overlay.showActive()
                                    Diag.i("playstore: state=$flowState tapped Install for $appName")
                                }
                            }
                        }
                    }
                    PlayStoreMatcher.ButtonState.Update -> {
                        flowState = StoreFlowState.COMPLETED
                        return "\"$appName\" is already on this phone with an Update available in Google Play Store."
                    }
                    PlayStoreMatcher.ButtonState.None -> {
                        if (decision.requiresUserChoice) return decision.reason
                        if (decision.ambiguousListings.size > 1) {
                            flowState = StoreFlowState.RESULTS_VISIBLE
                            val options = decision.ambiguousListings.take(3).joinToString(", ") { it.label }
                            return "Multiple Play Store listings match \"$appName\" ($options). Ask the user which one to open."
                        }
                        val listingEl = decision.matchedListingElement
                        val listingActionId = "ps_open_listing_${listingEl?.number ?: 0}"
                        if (listingEl != null &&
                            !host.recoveryController.shouldSkipDuplicate(listingActionId, flowState.name, StoreFlowState.OPENING_APP_PAGE.name)
                        ) {
                            val node = service.node(listingEl.number)
                            if (node != null) {
                                actionAttempted = true
                                if (service.click(node)) {
                                    flowState = StoreFlowState.OPENING_APP_PAGE
                                    Diag.i("playstore: state=$flowState opened listing ${listingEl.label}")
                                }
                            }
                        } else if (PlayStoreMatcher.canSubmitSearch(
                                searchSubmitted = typedSearch,
                                resultsVisible = resultsAlreadyVisible,
                                screenState = currentScreenState,
                                attempts = consecutiveSearches,
                            ) && searchEl != null &&
                            !host.recoveryController.shouldSkipDuplicate("ps_search_${PlayStoreMatcher.normalize(appName)}", flowState.name, StoreFlowState.WAITING_FOR_RESULTS.name)
                        ) {
                            val searchNode = service.node(searchEl.number)
                            if (searchNode != null) {
                                actionAttempted = true
                                consecutiveSearches++
                                flowState = StoreFlowState.SEARCHING
                                if (searchNode.isEditable) {
                                    if (service.type(searchNode, appName, submitIme = true)) {
                                        typedSearch = true
                                        flowState = StoreFlowState.WAITING_FOR_RESULTS
                                        Diag.i("playstore: state=$flowState typed query $appName")
                                    }
                                } else if (service.click(searchNode)) {
                                    delay(500)
                                    service.focusedField()?.let { field ->
                                        if (service.type(field, appName, submitIme = true)) {
                                            typedSearch = true
                                            flowState = StoreFlowState.WAITING_FOR_RESULTS
                                            Diag.i("playstore: state=$flowState opened search & typed $appName")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            flowState = StoreFlowState.VERIFYING_INSTALLATION
            val newlyAdded = findVerifiedInstalledApp(host, checker, appName, knownPackagesBefore, explicitPackage)
            if (newlyAdded != null) {
                flowState = StoreFlowState.COMPLETED
                return "Verified: ${newlyAdded.label} (${newlyAdded.packageName}) is now installed."
            }
            return if (tappedInstall) {
                "Tapped Install for \"$appName\" in Google Play Store; installation is still in progress and not yet verified by Android."
            } else {
                flowState = StoreFlowState.FAILED
                "Opened Google Play Store search for \"$appName\", though JARVIS couldn't verify an unambiguous free Install button automatically. Ask the user to check the listing."
            }
        }

        private fun findVerifiedInstalledApp(
            host: ToolHost,
            checker: InstalledAppChecker,
            appName: String,
            knownPackagesBefore: Set<String>,
            explicitPackage: String?,
        ): InstalledAppInfo? {
            if (explicitPackage != null && checker.isInstalled(explicitPackage)) {
                val refreshed = InstalledAppsRegistry.refresh(host.context)
                return refreshed.firstOrNull { it.packageName == explicitPackage }
                    ?: InstalledAppInfo(label = appName, packageName = explicitPackage, launchable = true)
            }
            val now = InstalledAppsRegistry.refresh(host.context)
            val newMatches = now.filter { it.packageName !in knownPackagesBefore }
            val candidate = AppCatalog.find(appName, newMatches, includeNonLaunchable = true).firstOrNull()
                ?: AppCatalog.find(appName, now, includeNonLaunchable = false)
                    .firstOrNull { AppCatalog.compactKey(it.label) == AppCatalog.compactKey(appName) }
            return candidate?.takeIf { checker.isInstalled(it.packageName) }
        }

        private const val WAIT_MS = 8_000L
        private const val STEP_DELAY_MS = 900L
        private const val POLL_STEPS = 12
    }
}
