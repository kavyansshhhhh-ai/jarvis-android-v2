package app.shilex.android.tools

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import app.shilex.android.Branding
import app.shilex.android.assistant.ActionStatus
import app.shilex.android.assistant.AgentActionResult
import app.shilex.android.assistant.AgentRecoveryController
import app.shilex.android.assistant.ScreenState
import app.shilex.android.control.ControlRules
import app.shilex.android.control.ControlService
import app.shilex.android.control.InstagramMatcher
import app.shilex.android.control.ScreenRecovery
import app.shilex.android.control.ScreenText
import app.shilex.android.control.ScreenVision
import app.shilex.android.control.SightAim
import app.shilex.android.control.WhatsAppWorkflow
import app.shilex.android.data.AutomationCategory
import app.shilex.android.data.KeyVault
import app.shilex.android.data.Settings as AppSettings
import app.shilex.android.task.ActiveTaskContext
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Phone control through Accessibility plus Scoped-Storage Gallery deletion.
 * Supports Instagram control disambiguation, honors Settings automation categories cleanly,
 * and strictly preserves money/credential/destructive/finance protections.
 */
object ScreenTools {
    fun all(): List<PhoneTool> = listOf(
        UiRead,
        UiDescribeScreen,
        UiClick,
        UiTapSeen,
        UiType,
        UiScroll,
        UiKey,
        HandToUser,
        *GalleryTools.all().toTypedArray(),
    )

    private fun control(): Pair<ControlService?, JSONObject?> {
        val service = ControlService.instance
            ?: return null to Results.failed(
                "control_off",
                "Phone control is off. Tell the user to turn it on: open ${Branding.ASSISTANT_NAME} and tap Phone control.",
            )
        if (service.overlay.stopped) {
            return null to Results.failed("stopped_by_user", "The user pressed Stop. Don't control the phone until they ask again.")
        }
        return service to null
    }

    private fun allowPlayStore(host: ToolHost): Boolean =
        AppSettings(host.context).isAutomationEnabled(AutomationCategory.PlayStoreNavigation)

    private fun screenContextHints(snap: ControlService.Snapshot): String = buildString {
        if (snap.packageName == InstagramMatcher.INSTAGRAM_PACKAGE) {
            val screenState = InstagramMatcher.detectScreen(snap.elements, snap.meta, snap.screenHeightPx)
            append("Instagram screen: ").append(screenState.name).append('\n')
            val controls = listOfNotNull(
                (InstagramMatcher.resolveControl(snap.elements, snap.meta, InstagramMatcher.TargetIntent.Messages, snap.screenHeightPx) as? InstagramMatcher.MatchOutcome.Matched)?.let { "Messages=#${it.element.number}" },
                (InstagramMatcher.resolveControl(snap.elements, snap.meta, InstagramMatcher.TargetIntent.Composer, snap.screenHeightPx) as? InstagramMatcher.MatchOutcome.Matched)?.let { "Composer=#${it.element.number}" },
                (InstagramMatcher.resolveControl(snap.elements, snap.meta, InstagramMatcher.TargetIntent.DmSend, snap.screenHeightPx) as? InstagramMatcher.MatchOutcome.Matched)?.let { "DmSend=#${it.element.number}" },
                (InstagramMatcher.resolveControl(snap.elements, snap.meta, InstagramMatcher.TargetIntent.Search, snap.screenHeightPx) as? InstagramMatcher.MatchOutcome.Matched)?.let { "Search=#${it.element.number}" },
                (InstagramMatcher.resolveControl(snap.elements, snap.meta, InstagramMatcher.TargetIntent.Reels, snap.screenHeightPx) as? InstagramMatcher.MatchOutcome.Matched)?.let { "Reels=#${it.element.number}" },
                (InstagramMatcher.resolveControl(snap.elements, snap.meta, InstagramMatcher.TargetIntent.Create, snap.screenHeightPx) as? InstagramMatcher.MatchOutcome.Matched)?.let { "Create=#${it.element.number}" },
                (InstagramMatcher.resolveControl(snap.elements, snap.meta, InstagramMatcher.TargetIntent.Share, snap.screenHeightPx) as? InstagramMatcher.MatchOutcome.Matched)?.let { "Share=#${it.element.number}" },
            )
            if (controls.isNotEmpty()) {
                append("Detected Instagram controls: ").append(controls.joinToString(", ")).append('\n')
            }
        } else if (snap.packageName == ControlRules.PLAY_STORE_PACKAGE) {
            PlayStoreMatcher.findSearchInput(snap.elements)?.let {
                append("Play Store Search=#").append(it.number).append('\n')
            }
        }
    }

    private suspend fun afterAction(
        service: ControlService,
        did: String,
        beforeSig: String = "",
        allowPlayStore: Boolean = false,
    ): JSONObject {
        service.overlay.showActive()
        delay(SETTLE_MS)
        val fg = service.foregroundPackage()
        val snap = service.snapshot(allowPlayStore = allowPlayStore) ?: return if (ControlRules.isFinanceApp(fg)) {
            Results.done(
                "$did This banking or UPI screen blocked Accessibility inspection for security. " +
                    "Hand control back to the user with hand_to_user and explain why.",
            )
        } else {
            Results.done("$did The screen couldn't be read.")
        }
        if (snap.blocked) return Results.done("$did Now in ${snap.appLabel}, which ${Branding.ASSISTANT_NAME} doesn't read.")
        if (snap.sensitiveAuth) {
            return Results.done(
                "$did Now on a PIN, MPIN, OTP, CVV, or password screen in ${snap.appLabel}. " +
                    "Stop here, never read or enter credentials, and call hand_to_user so the user finishes this step.",
            )
        }
        if (snap.financeApp && (snap.elements.isEmpty() || snap.restrictedBySystem)) {
            return Results.done(
                "$did ${snap.appLabel} or Android blocked Accessibility inspection on this screen for security. " +
                    "Hand control back to the user with hand_to_user and explain why.",
            )
        }
        ActiveTaskContext.updateScreen(snap.appLabel, snap.signature.take(80))
        val transitionNote = when {
            beforeSig.isEmpty() -> ""
            snap.signature != beforeSig -> "(Screen updated.) "
            else -> "(Screen stayed the same.) "
        }
        val summary = if (snap.financeApp) ControlRules.extractTransactionSummary(snap.elements) else ""
        val hints = screenContextHints(snap)
        val prefix = if (summary.isNotEmpty()) "$did $transitionNote Summary on screen: $summary\n" else "$did $transitionNote"
        return Results.done(prefix + hints + "Screen now:\n" + ScreenText.describe(snap.appLabel, snap.elements, snap.more))
    }

    private fun appLabel(service: ControlService, pkg: String): String =
        if (pkg.isEmpty()) "this app" else runCatching {
            service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    private fun blocked(app: String) = Results.failed(
        "blocked",
        "${Branding.ASSISTANT_NAME} doesn't read or touch $app (passwords, authenticators, PIN components, app store or settings). The user does this part.",
    )

    private fun financeDeclined(app: String) = Results.failed(
        "not_allowed",
        "The user didn't allow assistance in $app. Stop and let the user handle it directly.",
    )

    private fun sensitiveAuth(what: String) = Results.failed(
        "sensitive_auth",
        "${Branding.ASSISTANT_NAME} stops before PIN, MPIN, OTP, CVV, or password steps ($what) and never reads, stores, or enters credentials. " +
            "Explain this and call hand_to_user so the user completes it directly.",
    )

    private fun accessibilityRestricted(app: String) = Results.failed(
        "accessibility_restricted",
        "$app or Android blocked Accessibility access on this screen for security. Never bypass secure windows—hand control back to the user with hand_to_user and explain why.",
    )

    private fun payment(label: String, summary: String = "") = Results.failed(
        "payment_is_users",
        buildString {
            append("\"${ScreenText.shorten(label.ifEmpty { "This" })}\" authorizes payment. ")
            if (summary.isNotEmpty()) append("Transaction summary: $summary. ")
            append("Stop here: tell the user the summary, ask them to tap Pay and enter their UPI PIN, OTP, or password directly in the official app, and call hand_to_user.")
        },
    )

    private object UiRead : PhoneTool {
        override val name = "ui_read"

        override fun declaration() = Schema.function(
            name,
            "Reads the app on screen as a numbered list of buttons, fields and text. Use before tapping.",
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.ScreenReading)) {
                return Results.failed("automation_disabled", "Screen reading is turned off in Settings → Permissions.")
            }
            val (service, problem) = control()
            service ?: return problem!!
            val allowPs = allowPlayStore(host)
            val fg = service.foregroundPackage()
            val snap = service.snapshot(allowPlayStore = allowPs) ?: return if (ControlRules.isFinanceApp(fg)) {
                accessibilityRestricted(appLabel(service, fg))
            } else {
                Results.failed("no_screen", "The screen couldn't be read.")
            }
            if (snap.packageName == host.context.packageName) {
                return Results.failed(
                    "own_screen",
                    "The user is looking at ${Branding.ASSISTANT_NAME} itself. Ask them to open the app they mean, or offer open_app.",
                )
            }
            if (snap.blocked) return blocked(snap.appLabel)
            if (snap.sensitiveAuth) return sensitiveAuth(snap.appLabel)
            if (snap.financeApp && !host.authorizeFinance(snap.packageName, snap.appLabel)) {
                return financeDeclined(snap.appLabel)
            }
            if (snap.financeApp && (snap.elements.isEmpty() || snap.restrictedBySystem)) {
                return accessibilityRestricted(snap.appLabel)
            }
            ActiveTaskContext.updateScreen(snap.appLabel, snap.signature.take(80))
            host.recoveryController.onScreenChanged("${snap.packageName}:${snap.appLabel}")
            val summary = if (snap.financeApp) ControlRules.extractTransactionSummary(snap.elements) else ""
            val hints = screenContextHints(snap)
            val body = ScreenText.describe(snap.appLabel, snap.elements, snap.more)
            return Results.done((if (summary.isNotEmpty()) "Summary on screen: $summary\n" else "") + hints + body)
        }
    }

    private object UiDescribeScreen : PhoneTool {
        override val name = "ui_describe_screen"

        override fun declaration() = Schema.function(
            name,
            "Looks at and describes a photo, image, or visual content currently visible on screen (e.g. a WhatsApp photo). " +
                "Asks user permission first; never works on banking, UPI, password, or FLAG_SECURE protected screens (offers system Photo Picker instead).",
            Schema.string("question", "What the user wants to know about the photo or screen."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val pkg = service.foregroundPackage()
            val fgLabel = appLabel(service, pkg)
            val question = args.text("question")
            if (pkg == host.context.packageName) {
                return Results.failed("own_screen", "The user is looking at ${Branding.ASSISTANT_NAME}. Ask them to open the photo first, or pick a photo.")
            }
            if (ControlRules.blocked(pkg)) return blocked(fgLabel)
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            if (ControlRules.isFinanceApp(pkg)) {
                return Results.failed(
                    "screenshot_restricted_finance",
                    "For security, ${Branding.ASSISTANT_NAME} never captures screenshots of $fgLabel.",
                )
            }
            val key = KeyVault(host.context).read(KeyVault.GEMINI_KEY)
                ?: return Results.failed("no_key", "Understanding photos needs the Gemini API key in Settings.")
            if (pkg !in GALLERY_PACKAGES && !service.overlay.confirm("Inspect visible image in $fgLabel with Gemini Vision")) {
                return Results.failed("cancelled", "The user didn't allow sharing the visible screen.")
            }
            return when (val shot = service.captureScreenshotResult()) {
                is ControlService.ScreenshotCapture.Ok -> {
                    when (val outcome = ScreenVision(host.http, key).describeBitmap(shot.bitmap, question)) {
                        is ScreenVision.Outcome.Described -> Results.done(outcome.summary)
                        is ScreenVision.Outcome.Failed -> Results.failed("vision_failed", outcome.message)
                    }
                }
                ControlService.ScreenshotCapture.SecureBlocked -> {
                    host.requestPhotoPick(question)
                    Results.failed(
                        "secure_screenshot_blocked",
                        "$fgLabel blocks screenshots on this screen for privacy. ${Branding.ASSISTANT_NAME} opened Android's Photo Picker so the user can choose the saved photo safely if they want.",
                    )
                }
                is ControlService.ScreenshotCapture.Unavailable -> {
                    host.requestPhotoPick(question)
                    Results.failed(
                        "screenshot_unavailable",
                        "${shot.reason} Opened Android's Photo Picker so the user can select a photo directly.",
                    )
                }
            }
        }
    }

    private object UiClick : PhoneTool {
        override val name = "ui_click"

        override fun declaration() = Schema.function(
            name,
            "Taps an element from the last ui_read, by number (preferred) or exact label. Send, call, " +
                "delete, book and sensitive payment-prep taps show Allow when needed; final pay, buy, and PIN/OTP/password taps are refused.",
            Schema.integer("number", "Element number from ui_read."),
            Schema.string("label", "The element's label, if no number."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.ScreenInteraction)) {
                return Results.failed("automation_disabled", "Screen interaction is turned off in Settings → Permissions.")
            }
            val (service, problem) = control()
            service ?: return problem!!
            val allowPs = allowPlayStore(host)
            val requestedLabel = args.text("label")
            val explicitNumber = args.optInt("number", 0)
            val preSnap = service.snapshot(allowPlayStore = allowPs)
            if (preSnap != null) {
                host.recoveryController.onScreenChanged("${preSnap.packageName}:${preSnap.appLabel}")
            }

            // Short-circuit Instagram navigation commands when already at the requested destination.
            if (preSnap != null && preSnap.packageName == InstagramMatcher.INSTAGRAM_PACKAGE && requestedLabel.isNotEmpty()) {
                val navIntent = InstagramMatcher.inferIntent(requestedLabel)
                val navDestination = navIntent?.let(InstagramMatcher::destinationForIntent)
                val isNavDestinationIntent = navIntent != null && navIntent != InstagramMatcher.TargetIntent.Composer && navIntent != InstagramMatcher.TargetIntent.DmSend
                if (isNavDestinationIntent && navDestination != null &&
                    InstagramMatcher.isAlreadyAtDestination(preSnap.screenState, preSnap.elements, preSnap.meta, navDestination, preSnap.screenHeightPx)
                ) {
                    val alreadyResult = AgentActionResult(
                        status = ActionStatus.ALREADY_COMPLETED,
                        message = "Instagram is already on ${navDestination.name}; skipped duplicate tap.",
                        expectedDestination = navDestination.name,
                        actualDestination = navDestination.name,
                        verifiedScreenState = preSnap.screenState,
                    )
                    return Results.done(alreadyResult.message)
                }
            }

            val resolved = target(service, args, allowPs, host.recoveryController) ?: return Results.failed(
                "not_found",
                "No confident matching accessible element on screen now. Call ui_read and pick an exact number (or use ui_tap_seen ONLY for unlabeled icons).",
            )
            resolved.second?.let { return it }
            val target = resolved.first!!
            var targetNeedsRelease = true
            var pendingActionNode: AccessibilityNodeInfo? = null
            try {
            val pkg = target.packageName?.toString().orEmpty()
            val name = appLabel(service, pkg)
            if (ControlRules.blocked(pkg, allowPlayStoreAutomation = allowPs)) return blocked(name)
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            if (ControlRules.isFinanceApp(pkg) && !host.authorizeFinance(pkg, name)) {
                return financeDeclined(name)
            }
            val label = service.labelOf(target)
            if (!target.isEnabled) {
                return Results.failed(
                    "disabled",
                    "\"${ScreenText.shorten(label.ifEmpty { "That control" })}\" is currently disabled on screen. Refresh with ui_read or complete the prerequisite field first.",
                )
            }
            val viewId = target.viewIdResourceName.orEmpty()
            if (ControlRules.isCredentialAction(label, viewId, target.isPassword)) {
                return sensitiveAuth(ScreenText.shorten(label.ifEmpty { "credential control" }))
            }

            val igExpectedIntent = if (pkg == InstagramMatcher.INSTAGRAM_PACKAGE) {
                InstagramMatcher.inferIntent(requestedLabel.ifEmpty { label })
            } else {
                null
            }
            val expectedDestKey = igExpectedIntent?.name ?: label
            val currentScreenKey = "${pkg}:${preSnap?.appLabel.orEmpty()}"
            val actionId = if (explicitNumber > 0) "click:#$explicitNumber" else "click:${requestedLabel.lowercase()}"
            if (host.recoveryController.isCycleDetected()) {
                return Results.failed("navigation_cycle", "A navigation loop was detected (bouncing between screens). Stop retrying and ask the user what to do next.")
            }
            if (host.recoveryController.shouldSkipDuplicate(actionId, currentScreenKey, expectedDestKey)) {
                return Results.done("Skipped duplicate action on \"${ScreenText.shorten(label.ifEmpty { requestedLabel })}\" on the unchanged screen.")
            }

            val summary = if (ControlRules.isFinanceApp(pkg)) ControlRules.extractTransactionSummary(service.lastElements()) else ""
            val risk = ControlRules.clickRisk(label, viewId, pkg)
            if (risk == ControlRules.Risk.Money) return payment(label, summary)
            val settings = AppSettings(host.context)
            val skipOutgoingPrompt = risk == ControlRules.Risk.Outgoing &&
                !ControlRules.isFinanceApp(pkg) &&
                settings.isAutomationEnabled(AutomationCategory.ScreenInteraction) &&
                (pkg != ControlRules.PLAY_STORE_PACKAGE || allowPs) &&
                (pkg !in WhatsAppWorkflow.WHATSAPP_PACKAGES || settings.isAutomationEnabled(AutomationCategory.WhatsAppMessages))
            if (risk != ControlRules.Risk.Safe && !skipOutgoingPrompt) {
                val shortLabel = ScreenText.shorten(label.ifEmpty { "this button" })
                val what = if (summary.isNotEmpty()) "Tap \"$shortLabel\" ($summary)" else "Tap \"$shortLabel\""
                if (!service.overlay.confirm(what)) {
                    return Results.failed("cancelled", "The user didn't allow it. Don't try again unless they ask.")
                }
            }
            val beforeSnap = service.snapshot(allowPlayStore = allowPs)
            val beforeSig = beforeSnap?.signature.orEmpty()
            if (beforeSnap != null && beforeSnap.packageName != pkg) {
                return Results.failed(
                    "wrong_screen",
                    "Screen switched to ${beforeSnap.appLabel} before tapping. Read the screen again and continue safely.",
                )
            }
            val actionNode = (if (explicitNumber > 0) service.node(explicitNumber) else null) ?: target
            pendingActionNode = actionNode
            if (!actionNode.isEnabled) {
                return Results.failed("disabled", "\"${ScreenText.shorten(label)}\" became disabled before tapping.")
            }
            pendingActionNode = null
            if (actionNode === target) targetNeedsRelease = false
            if (!service.click(actionNode)) {
                return if (ControlRules.isFinanceApp(pkg)) {
                    accessibilityRestricted(name)
                } else {
                    Results.failed("tap_failed", "The tap didn't go through.")
                }
            }
            if (igExpectedIntent != null &&
                igExpectedIntent != InstagramMatcher.TargetIntent.Composer &&
                igExpectedIntent != InstagramMatcher.TargetIntent.DmSend &&
                igExpectedIntent != InstagramMatcher.TargetIntent.Share
            ) {
                return verifyAndRecoverInstagramClick(
                    service = service,
                    did = "Tapped \"${ScreenText.shorten(label)}\".",
                    expectedIntent = igExpectedIntent,
                    beforeSig = beforeSig,
                    recovery = host.recoveryController,
                    allowPlayStore = allowPs,
                )
            }
            return afterAction(service, "Tapped \"${ScreenText.shorten(label)}\".", beforeSig = beforeSig, allowPlayStore = allowPs)
            } finally {
                pendingActionNode?.let { if (it !== target) service.releaseNode(it) }
                if (targetNeedsRelease) service.releaseNode(target)
            }
        }

        private suspend fun verifyAndRecoverInstagramClick(
            service: ControlService,
            did: String,
            expectedIntent: InstagramMatcher.TargetIntent,
            beforeSig: String,
            recovery: AgentRecoveryController,
            allowPlayStore: Boolean,
        ): JSONObject {
            service.overlay.showActive()
            delay(SETTLE_MS)
            var snap = service.snapshot(allowPlayStore = allowPlayStore)
                ?: return Results.failed("no_screen", "$did The screen couldn't be read afterward.")
            var verification = ScreenRecovery.verifyDestinationReached(
                expectedIntent = expectedIntent,
                elements = snap.elements,
                meta = snap.meta,
                screenState = snap.screenState,
                screenHeightPx = snap.screenHeightPx,
                retryCount = recovery.currentRetryCount(),
            )
            while (verification.status == ActionStatus.WRONG_SCREEN &&
                snap.packageName == InstagramMatcher.INSTAGRAM_PACKAGE &&
                recovery.nextRetry()
            ) {
                val decision = ScreenRecovery.evaluateInstagramAction(
                    intent = expectedIntent,
                    elements = snap.elements,
                    meta = snap.meta,
                    screenHeightPx = snap.screenHeightPx,
                    recoveryAttempts = recovery.currentRetryCount(),
                )
                when (decision) {
                    is ScreenRecovery.RecoveryDecision.NavigateToControl -> {
                        val node = service.node(decision.elementNumber) ?: break
                        if (!service.click(node)) break
                    }
                    is ScreenRecovery.RecoveryDecision.RecoverBackAndRecheck -> {
                        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) break
                    }
                    ScreenRecovery.RecoveryDecision.Proceed,
                    is ScreenRecovery.RecoveryDecision.AskUser -> break
                }
                delay(SETTLE_MS)
                snap = service.snapshot(allowPlayStore = allowPlayStore) ?: break
                verification = ScreenRecovery.verifyDestinationReached(
                    expectedIntent = expectedIntent,
                    elements = snap.elements,
                    meta = snap.meta,
                    screenState = snap.screenState,
                    screenHeightPx = snap.screenHeightPx,
                    retryCount = recovery.currentRetryCount(),
                )
            }

            if (snap.sensitiveAuth) {
                return Results.done(
                    "$did Now on a PIN, MPIN, OTP, CVV, or password screen in ${snap.appLabel}. " +
                        "Stop here, never read or enter credentials, and call hand_to_user so the user finishes this step.",
                )
            }
            ActiveTaskContext.updateScreen(snap.appLabel, snap.signature.take(80))
            recovery.onScreenChanged("${snap.packageName}:${snap.appLabel}")
            val statusPrefix = if (verification.isSuccessful) {
                "$did Verified: ${verification.message} "
            } else {
                "$did Verification note: ${verification.message} "
            }
            val transitionNote = when {
                beforeSig.isEmpty() -> ""
                snap.signature != beforeSig -> "(Screen updated.) "
                else -> "(Screen stayed the same.) "
            }
            val hints = screenContextHints(snap)
            return Results.done(statusPrefix + transitionNote + hints + "Screen now:\n" + ScreenText.describe(snap.appLabel, snap.elements, snap.more))
        }

        private suspend fun target(
            service: ControlService,
            args: JSONObject,
            allowPs: Boolean,
            recovery: AgentRecoveryController,
        ): Pair<AccessibilityNodeInfo?, JSONObject?>? {
            val number = args.optInt("number", 0)
            if (number > 0) return service.node(number)?.let { it to null }
            val label = args.text("label")
            val fg = service.foregroundPackage()
            var snap = service.snapshot(allowPlayStore = allowPs) ?: return if (ControlRules.isFinanceApp(fg)) {
                null to accessibilityRestricted(appLabel(service, fg))
            } else {
                null
            }
            if (snap.blocked) return null to blocked(snap.appLabel)
            if (snap.sensitiveAuth) return null to sensitiveAuth(snap.appLabel)
            if (snap.financeApp && (snap.elements.isEmpty() || snap.restrictedBySystem)) {
                return null to accessibilityRestricted(snap.appLabel)
            }
            if (snap.packageName == InstagramMatcher.INSTAGRAM_PACKAGE) {
                val igIntent = InstagramMatcher.inferIntent(label)
                if (igIntent != null) {
                    val decision = ScreenRecovery.evaluateInstagramAction(
                        intent = igIntent,
                        elements = snap.elements,
                        meta = snap.meta,
                        screenHeightPx = snap.screenHeightPx,
                        recoveryAttempts = recovery.currentRetryCount(),
                    )
                    if (decision is ScreenRecovery.RecoveryDecision.RecoverBackAndRecheck && recovery.nextRetry()) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(SETTLE_MS)
                        snap = service.snapshot(allowPlayStore = allowPs) ?: return null
                        if (snap.packageName != InstagramMatcher.INSTAGRAM_PACKAGE) {
                            return null to Results.failed("wrong_screen", "Left Instagram while recovering screen; call ui_read to re-orient.")
                        }
                    } else if (decision is ScreenRecovery.RecoveryDecision.AskUser) {
                        return null to Results.failed("wrong_screen", decision.reason)
                    }
                    when (val res = InstagramMatcher.resolveControl(snap.elements, snap.meta, igIntent, snap.screenHeightPx)) {
                        is InstagramMatcher.MatchOutcome.Matched -> return service.node(res.element.number)?.let { it to null }
                        is InstagramMatcher.MatchOutcome.Ambiguous -> return null to Results.ask(
                            "Multiple Instagram controls look like \"$label\". Ask which one.",
                            res.candidates.take(5).map { "${it.number}. ${it.label}" },
                        )
                        InstagramMatcher.MatchOutcome.NotFound -> Unit
                    }
                }
                when (val recipient = InstagramMatcher.findRecipientMatch(snap.elements, snap.meta, label)) {
                    is InstagramMatcher.MatchOutcome.Matched -> return service.node(recipient.element.number)?.let { it to null }
                    is InstagramMatcher.MatchOutcome.Ambiguous -> return null to Results.ask(
                        "Multiple Instagram chats or accounts match \"$label\". Ask which one.",
                        recipient.candidates.take(5).map { "${it.number}. ${it.label}" },
                    )
                    InstagramMatcher.MatchOutcome.NotFound -> Unit
                }
            }
            val matches = ScreenText.find(snap.elements, label)
            return when {
                matches.isEmpty() -> null
                matches.size > 1 -> null to Results.ask(
                    "Several elements match. Ask which, or use a number.",
                    matches.take(6).map { "${it.number}. ${it.label}" },
                )
                else -> service.node(matches.single().number)?.let { it to null }
            }
        }
    }

    private object UiType : PhoneTool {
        override val name = "ui_type"

        override fun declaration() = Schema.function(
            name,
            "Types text into an ordinary non-sensitive field (replacing what's there). Doesn't send; never types PINs, OTPs, CVVs, or passwords.",
            Schema.string("text", "Exactly what to type, in the user's words.", required = true),
            Schema.integer("number", "Field number from ui_read; empty for the focused field."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.ScreenInteraction)) {
                return Results.failed("automation_disabled", "Screen interaction is turned off in Settings → Permissions.")
            }
            val (service, problem) = control()
            service ?: return problem!!
            val allowPs = allowPlayStore(host)
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            val number = args.optInt("number", 0)
            var field = if (number > 0) service.node(number) else service.focusedField()
            if (field != null && (!field.isEditable || !field.isEnabled)) {
                service.releaseNode(field)
                field = null
            }
            if (field == null && number == 0) {
                // On Instagram DM thread, resolve composer ("Message...") explicitly instead of typing blindly
                val snap = service.snapshot(allowPlayStore = allowPs)
                if (snap != null && snap.packageName == InstagramMatcher.INSTAGRAM_PACKAGE) {
                    val igScreen = InstagramMatcher.detectScreen(snap.elements, snap.meta, snap.screenHeightPx)
                    if (igScreen == InstagramMatcher.InstagramScreenState.DirectThread) {
                        val comp = InstagramMatcher.resolveControl(snap.elements, snap.meta, InstagramMatcher.TargetIntent.Composer, snap.screenHeightPx)
                        if (comp is InstagramMatcher.MatchOutcome.Matched) {
                            val candidate = service.node(comp.element.number)
                            if (candidate != null && candidate.isEditable && candidate.isEnabled) {
                                field = candidate
                            } else if (candidate != null) {
                                service.releaseNode(candidate)
                            }
                        }
                    } else if (igScreen == InstagramMatcher.InstagramScreenState.Reels || igScreen == InstagramMatcher.InstagramScreenState.ShareSheet) {
                        return Results.failed("wrong_screen", "Instagram is on ${igScreen.name}, not a chat or search input. Open the right chat first.")
                    }
                }
            }
            var fieldNeedsRelease = field != null
            try {
            val activeField = field ?: return Results.failed("no_field", "No editable text field selected. Call ui_read and give the field's number.")
            val pkg = activeField.packageName?.toString().orEmpty()
            val name = appLabel(service, pkg)
            if (ControlRules.blocked(pkg, allowPlayStoreAutomation = allowPs)) return blocked(name)
            if (ControlRules.isFinanceApp(pkg) && !host.authorizeFinance(pkg, name)) {
                return financeDeclined(name)
            }
            val label = service.labelOf(activeField)
            val viewId = activeField.viewIdResourceName.orEmpty()
            if (ControlRules.isCredentialAction(label, viewId, activeField.isPassword)) {
                return sensitiveAuth(ScreenText.shorten(label.ifEmpty { "credential field" }))
            }
            val beforeSig = service.snapshot(allowPlayStore = allowPs)?.signature.orEmpty()
            fieldNeedsRelease = false // type() consumes the owned AccessibilityNodeInfo on every path.
            if (!service.type(activeField, args.text("text"))) {
                return if (ControlRules.isFinanceApp(pkg)) {
                    accessibilityRestricted(name)
                } else {
                    Results.failed("type_failed", "Typing didn't work in that field.")
                }
            }
            return afterAction(service, "Typed it.", beforeSig = beforeSig, allowPlayStore = allowPs)
            } finally {
                if (fieldNeedsRelease) field?.let(service::releaseNode)
            }
        }
    }

    private object UiTapSeen : PhoneTool {
        override val name = "ui_tap_seen"

        override fun declaration() = Schema.function(
            name,
            "Taps something by looking at the screen ONLY when ui_read cannot identify it (image buttons, canvases). Never runs on banking/UPI screens; pay and PIN/OTP/password taps are refused.",
            Schema.string("target", "What to tap, as seen: e.g. \"the red play button in the middle\".", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.ScreenInteraction)) {
                return Results.failed("automation_disabled", "Screen interaction is turned off in Settings → Permissions.")
            }
            val (service, problem) = control()
            service ?: return problem!!
            val target = args.text("target").ifEmpty { return Results.failed("no_target", "Say what to tap.") }
            val pkg = service.foregroundPackage()
            val fgLabel = appLabel(service, pkg)
            if (pkg == host.context.packageName) return Results.failed("own_screen", "That's ${Branding.ASSISTANT_NAME} itself.")
            if (ControlRules.blocked(pkg)) return blocked(fgLabel)
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            if (ControlRules.isFinanceApp(pkg)) {
                return Results.failed(
                    "screenshot_restricted_finance",
                    "For security, ${Branding.ASSISTANT_NAME} never captures or uploads screenshots of $fgLabel. Use ui_read / ui_click if available, or call hand_to_user so the user does this step.",
                )
            }
            if (ControlRules.isCredentialAction(target, "")) return sensitiveAuth(target)
            val key = KeyVault(host.context).read(KeyVault.GEMINI_KEY)
                ?: return Results.failed("no_key", "Looking at the screen needs the Gemini key.")
            val shot = service.screenshot() ?: return Results.failed(
                "no_sight",
                "This screen can't be looked at (protected, or Android older than 11). The user taps this part.",
            )
            val seen = when (val found = SightAim(host.http, key).locate(shot, target)) {
                is SightAim.Outcome.Found -> found
                SightAim.Outcome.NotFound -> return Results.failed("not_seen", "Couldn't see \"$target\" on screen. Say what you see instead.")
                is SightAim.Outcome.Failed -> return Results.failed("sight_failed", found.message)
            }
            val node = service.nodeAt(seen.x, seen.y)
            val there = node?.packageName?.toString().orEmpty()
            val label = node?.let(service::labelOf).orEmpty()
            val nodeId = node?.viewIdResourceName.orEmpty()
            val isPassword = node?.isPassword == true
            node?.let(service::releaseNode)
            if (there == host.context.packageName) return Results.failed("own_screen", "That spot is ${Branding.ASSISTANT_NAME}'s own button.")
            if (ControlRules.blocked(there) || ControlRules.isFinanceApp(there)) return blocked(appLabel(service, there))
            if (ControlRules.isCredentialAction(label, nodeId, isPassword) ||
                ControlRules.isCredentialAction(seen.sees, "")
            ) {
                return sensitiveAuth(ScreenText.shorten(label.ifEmpty { seen.sees }.ifEmpty { target }))
            }
            val effectivePkg = there.ifEmpty { pkg }
            val risk = listOf(
                ControlRules.clickRisk(target, "", effectivePkg),
                ControlRules.clickRisk(seen.sees, "", effectivePkg),
                ControlRules.clickRisk(label, nodeId, effectivePkg),
            ).maxBy { it.ordinal }
            val name = ScreenText.shorten(label.ifEmpty { seen.sees }.ifEmpty { target })
            if (risk == ControlRules.Risk.Money) return payment(name)
            if (risk != ControlRules.Risk.Safe && !service.overlay.confirm("Tap \"$name\"")) {
                return Results.failed("cancelled", "The user didn't allow it. Don't try again unless they ask.")
            }
            if (!service.tapAt(seen.x, seen.y)) return Results.failed("tap_failed", "The tap didn't go through.")
            return afterAction(service, "Tapped \"$name\" (by sight).")
        }
    }

    private object HandToUser : PhoneTool {
        override val name = "hand_to_user"

        override fun declaration() = Schema.function(
            name,
            "Gives one step to the user and waits (up to 2.5 min) until they tap Done: final payment authorization, a UPI PIN, MPIN, " +
                "password, OTP, CVV, or a screen that restricts Accessibility. Then returns the resulting non-sensitive status screen.",
            Schema.string("step", "The step in a few words, e.g. \"Tap Pay and enter your UPI PIN\".", required = true),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val step = ScreenText.shorten(args.text("step").ifEmpty { "Finish this step" })
            if (!service.overlay.handover(step)) {
                return Results.failed("stopped", "The user stopped or didn't finish. Ask what they'd like; don't carry on by yourself.")
            }
            val allowPs = allowPlayStore(host)
            val pkg = service.foregroundPackage()
            if (pkg == host.context.packageName || ControlRules.blocked(pkg, allowPlayStoreAutomation = allowPs)) {
                return Results.done("The user tapped Done. The screen now is one ${Branding.ASSISTANT_NAME} doesn't read; ask what's next if unsure.")
            }
            if (service.isSensitiveAuthActive()) {
                return Results.done(
                    "The user tapped Done, though a PIN, OTP, or password screen is still open. " +
                        "Ask them to finish or cancel first before checking the result.",
                )
            }
            return afterAction(service, "The user tapped Done.", allowPlayStore = allowPs)
        }
    }

    private object UiScroll : PhoneTool {
        override val name = "ui_scroll"

        override fun declaration() = Schema.function(
            name,
            "Scrolls the screen to show more.",
            Schema.string("direction", "down shows what's further down.", required = true, choices = listOf("down", "up")),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            if (!AppSettings(host.context).isAutomationEnabled(AutomationCategory.ScreenInteraction)) {
                return Results.failed("automation_disabled", "Screen interaction is turned off in Settings → Permissions.")
            }
            val (service, problem) = control()
            service ?: return problem!!
            val allowPs = allowPlayStore(host)
            val pkg = service.foregroundPackage()
            val name = appLabel(service, pkg)
            if (ControlRules.blocked(pkg, allowPlayStoreAutomation = allowPs)) return blocked(name)
            if (service.isSensitiveAuthActive()) return sensitiveAuth("authentication screen")
            if (ControlRules.isFinanceApp(pkg) && !host.authorizeFinance(pkg, name)) {
                return financeDeclined(name)
            }
            val beforeSig = service.snapshot(allowPlayStore = allowPs)?.signature.orEmpty()
            if (!service.scroll(down = args.text("direction") != "up")) return Results.failed("no_scroll", "Nothing to scroll here.")
            return afterAction(service, "Scrolled.", beforeSig = beforeSig, allowPlayStore = allowPs)
        }
    }

    private object UiKey : PhoneTool {
        override val name = "ui_key"

        override fun declaration() = Schema.function(
            name,
            "Presses a phone key: back, home, recents, notifications (pull down the shade), quick_settings (Quick Settings panel).",
            Schema.string("key", "Which key.", required = true, choices = KEYS.keys.toList()),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val (service, problem) = control()
            service ?: return problem!!
            val allowPs = allowPlayStore(host)
            val action = KEYS[args.text("key")] ?: return Results.failed("bad_key", "Use back, home, recents, notifications or quick_settings.")
            if (!service.performGlobalAction(action)) return Results.failed("key_failed", "The phone didn't take that key.")
            return afterAction(service, "Pressed ${args.text("key")}.", allowPlayStore = allowPs)
        }

        private val KEYS = mapOf(
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
        )
    }

    private val GALLERY_PACKAGES = setOf(
        "com.google.android.apps.photos",
        "com.miui.gallery",
        "com.sec.android.gallery3d",
        "com.oneplus.gallery",
        "com.coloros.gallery3d",
        "com.oplus.gallery",
        "com.vivo.gallery",
        "com.android.gallery3d",
        "com.motorola.MotGallery2",
        "com.simplemobiletools.gallery",
        "com.simplemobiletools.gallery.pro",
    )

    private const val SETTLE_MS = 900L
}
