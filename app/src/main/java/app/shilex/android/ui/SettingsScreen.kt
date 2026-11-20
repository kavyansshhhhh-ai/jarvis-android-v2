package app.shilex.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.audio.VoiceClassifier
import app.shilex.android.data.Settings as AppSettings
import app.shilex.android.data.AppearanceMode
import app.shilex.android.memory.MemoryStore
import app.shilex.android.orb.FloatingOrb
import app.shilex.android.orb.TinyWakeDotPreview
import app.shilex.android.permissions.PermissionId
import app.shilex.android.permissions.PermissionRegistry
import app.shilex.android.permissions.PermissionStatus
import app.shilex.android.wake.WakeDotSize
import app.shilex.android.wake.WakeIndicatorController
import app.shilex.android.wake.WakeState
import app.shilex.android.wake.WakeWordModel
import kotlin.math.roundToInt

private enum class SettingsFolder {
    Main,
    Permissions,
    JarvisVoice,
}

/**
 * AMOLED Smoked Liquid Glass Settings Screen with unified Permissions & JARVIS VOICE
 * folder sub-screens and continuous live Wake Dot size slider.
 */
@Composable
fun SettingsScreen(assistant: AssistantViewModel) {
    var activeFolder by rememberSaveable { mutableStateOf(SettingsFolder.Main) }
    BackHandler(enabled = activeFolder != SettingsFolder.Main) { activeFolder = SettingsFolder.Main }

    AnimatedContent(
        targetState = activeFolder,
        transitionSpec = {
            if (targetState != SettingsFolder.Main) {
                (slideInHorizontally { it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 10 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 10 } + fadeOut())
            }
        },
        label = "settingsFolderNav",
    ) { folder ->
        when (folder) {
            SettingsFolder.Permissions -> PermissionsSubScreen(
                assistant = assistant,
                onBack = { activeFolder = SettingsFolder.Main },
            )
            SettingsFolder.JarvisVoice -> JarvisVoiceSubScreen(
                assistant = assistant,
                onBack = { activeFolder = SettingsFolder.Main },
            )
            SettingsFolder.Main -> SettingsMainContent(
                assistant = assistant,
                onOpenPermissions = { activeFolder = SettingsFolder.Permissions },
                onOpenJarvisVoice = { activeFolder = SettingsFolder.JarvisVoice },
            )
        }
    }
}

@Composable
private fun PermissionsSubScreen(
    assistant: AssistantViewModel,
    onBack: () -> Unit,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val bottomPadding = floatingDockContentBottomPadding()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "perm_header") {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .glassPressScale(interaction)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x1CFFFFFF))
                            .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(50))
                            .clickable(interactionSource = interaction, indication = null, onClick = onBack)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Settings",
                            tint = PureWhite,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("Settings", style = MaterialTheme.typography.labelLarge, color = PureWhite)
                    }
                }

                Text(
                    "Permissions",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Manage every Android runtime permission, system setting, and automation category in one place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item(key = "perm_automation") {
            Section("Automation Permissions") {
                app.shilex.android.data.AutomationCategory.entries.forEachIndexed { idx, cat ->
                    if (idx > 0) GlassDivider()
                    var enabled by remember { mutableStateOf(assistant.isAutomationEnabled(cat)) }
                    SwitchRow(
                        title = cat.title,
                        detail = cat.summary,
                        checked = enabled,
                    ) { checked ->
                        enabled = checked
                        assistant.setAutomationEnabled(cat, checked)
                    }
                }
            }
        }

        item(key = "perm_system") {
            PermissionCenterPanel(compact = false, showBatchAllowBar = true)
        }
    }
}

@Composable
private fun SettingsMainContent(
    assistant: AssistantViewModel,
    onOpenPermissions: () -> Unit,
    onOpenJarvisVoice: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { PermissionRegistry.findActivity(context) }
    val wakeOn by assistant.wakeWord.collectAsStateWithLifecycle()
    val wakeState by assistant.wakeState.collectAsStateWithLifecycle()
    val wakeDotSize by assistant.wakeDotSizeState.collectAsStateWithLifecycle()
    val profile by assistant.personalProfile.collectAsStateWithLifecycle()
    val installedApps by assistant.installedApps.collectAsStateWithLifecycle()

    var permissionSnapshot by remember { mutableStateOf(PermissionRegistry.snapshot(context, activity)) }
    var showProfileEditor by remember { mutableStateOf(false) }
    var showAppsBrowser by remember { mutableStateOf(false) }
    var localOn by remember { mutableStateOf(assistant.localCommands) }
    var networkOn by remember { mutableStateOf(assistant.networkSpeech) }
    var engine by remember { mutableStateOf(assistant.speechEngine) }
    var dotOn by remember { mutableStateOf(assistant.wakeIndicator) }
    var dotDurationMs by remember { mutableLongStateOf(assistant.wakeDotDurationMs) }
    var appearanceMode by remember { mutableStateOf(AppSettings(context).appearanceMode) }

    val micGranted = permissionSnapshot.deduplicatedItems
        .firstOrNull { it.spec.id == PermissionId.Microphone }
        ?.status == PermissionStatus.Granted
    val overlayAllowed = permissionSnapshot.deduplicatedItems
        .firstOrNull { it.spec.id == PermissionId.Overlay }
        ?.status == PermissionStatus.Granted

    if (showProfileEditor) {
        PersonalProfileDialog(
            initial = profile,
            onSave = assistant::savePersonalProfile,
            onClear = assistant::clearPersonalProfile,
            onDismiss = { showProfileEditor = false },
        )
    }
    if (showAppsBrowser) {
        InstalledAppsDialog(
            apps = installedApps,
            onRefresh = assistant::refreshInstalledApps,
            onLaunch = assistant::launchInstalledApp,
            onDismiss = { showAppsBrowser = false },
        )
    }

    LifecycleResumeEffect(Unit) {
        permissionSnapshot = PermissionRegistry.snapshot(context, activity)
        engine = assistant.speechEngine
        assistant.refreshWakeState()
        assistant.refreshTtsVoices()
        onPauseOrDispose { }
    }

    val openAction = { action: String ->
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val bottomPadding = floatingDockContentBottomPadding()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(top = 14.dp)) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Customize intelligence, audio response, and privacy boundaries.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item(key = "appearance") {
            Section("Appearance") {
                Text(
                    "Choose how JARVIS looks across chat, settings, dialogs, and overlays.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppearanceMode.entries.forEach { mode ->
                        val selected = appearanceMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) ElectricCyan.copy(alpha = 0.15f) else GlassSurfaceVariant)
                                .border(
                                    1.dp,
                                    if (selected) ElectricCyan.copy(alpha = 0.62f) else GlassBorderSubtle,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable {
                                    appearanceMode = mode
                                    AppSettings(context).appearanceMode = mode
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                mode.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) ElectricCyan else PureWhite,
                            )
                        }
                    }
                }
            }
        }

        // Top Assistant Identity Hero Card
        item(key = "hero") {
            GlassCard(shape = RoundedCornerShape(26.dp), verticalPadding = 14) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(10.dp, CircleShape, ambientColor = ElectricCyan, spotColor = NeonViolet)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    ElectricCyan.copy(alpha = 0.85f),
                                    NeonViolet.copy(alpha = 0.85f),
                                ),
                            ),
                        )
                        .border(1.dp, Color(0x80FFFFFF), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MicIcon,
                        contentDescription = null,
                        tint = Color(0xFF001A22),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Branding.ASSISTANT_NAME,
                        style = MaterialTheme.typography.titleLarge,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (wakeOn && micGranted) "Hands-free Wake Word • On-Phone & Gemini" else "Tap to Talk • On-Phone & Gemini",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Pill(
                    text = if (wakeOn && micGranted) "Active" else "Standby",
                    color = if (wakeOn && micGranted) ElectricCyan else SlateMuted,
                )
            }
            }
        }

        // Single Dedicated Permissions Folder (opens full Permissions screen)
        item(key = "permissions") {
            Section("Permissions") {
                val interaction = remember { MutableInteractionSource() }
                val pendingCount = (permissionSnapshot.actionableTotalCount - permissionSnapshot.grantedCount).coerceAtLeast(0)
                val allEssentialOk = permissionSnapshot.essentialPending.isEmpty()
                val statusBadgeColor = when {
                    pendingCount == 0 -> ElectricCyan
                    !allEssentialOk -> CoralError
                    else -> AmberWarning
                }
                val statusBadgeLabel = when {
                    pendingCount == 0 -> "All granted"
                    else -> "$pendingCount need setup"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPressScale(interaction)
                        .clickable(interactionSource = interaction, indication = null, onClick = onOpenPermissions)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassRowIcon(
                        icon = Icons.Filled.Lock,
                        tint = ElectricCyan,
                        accentGlow = allEssentialOk,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Permissions",
                            style = MaterialTheme.typography.titleMedium,
                            color = PureWhite,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${permissionSnapshot.grantedCount} of ${permissionSnapshot.actionableTotalCount} granted • Microphone, Notifications, Accessibility, Overlay, Battery & more",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Pill(statusBadgeLabel, statusBadgeColor, dot = pendingCount == 0)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open Permissions",
                        tint = Muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Dedicated JARVIS VOICE Folder (opens clean Female Voices / Male Voices folder)
        item(key = "jarvis_voice") {
            val hindiCatalog by assistant.speaker.hindiCatalog.collectAsStateWithLifecycle()
            val savedVoiceId by assistant.ttsVoiceIdState.collectAsStateWithLifecycle()
            val savedGenderFilter by assistant.ttsGenderFilterState.collectAsStateWithLifecycle()
            val effectiveVoiceId = remember(savedVoiceId, hindiCatalog, savedGenderFilter) {
                VoiceClassifier.resolveEffectiveVoiceId(
                    savedVoiceId = savedVoiceId,
                    catalog = hindiCatalog,
                    preferMale = savedGenderFilter == AppSettings.GENDER_MALE,
                )
            }
            val activeVoiceName = remember(effectiveVoiceId, hindiCatalog) {
                (hindiCatalog.femaleVoices + hindiCatalog.maleVoices)
                    .firstOrNull { it.id == effectiveVoiceId }
                    ?.displayName
                    ?: "Default Hindi voice"
            }
            Section("JARVIS VOICE") {
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPressScale(interaction)
                    .clickable(interactionSource = interaction, indication = null, onClick = onOpenJarvisVoice)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassRowIcon(
                    icon = Icons.Filled.Star,
                    tint = ElectricCyan,
                    accentGlow = true,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "JARVIS VOICE",
                        style = MaterialTheme.typography.titleMedium,
                        color = PureWhite,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Female Voices & Male Voices • $activeVoiceName",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Pill("Hindi", ElectricCyan, dot = false)
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open JARVIS VOICE",
                    tint = Muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            }
        }

        item(key = "profile") {
            Section("Personal Profile") {
            val songSummary = if (profile.favoriteSongs.isEmpty()) "No favourite songs saved" else profile.favoriteSongs.joinToString(", ")
            val musicAppText = profile.preferredMusicApp.ifBlank { "Default (YouTube Music / Spotify)" }
            val identityLine = listOfNotNull(
                profile.preferredName.takeIf { it.isNotBlank() },
                profile.addressAs.takeIf { it.isNotBlank() }?.let { "call you \"$it\"" },
            ).joinToString(" • ").ifEmpty { "Set preferred name, music taste, and response style" }
            InfoRow(
                "Your preferences",
                "$identityLine\nSongs: $songSummary • Music app: $musicAppText",
                icon = Icons.Filled.Star,
                iconTint = ElectricCyan,
            ) {
                TextButton(onClick = { showProfileEditor = true }) {
                    Text(if (profile.isEmpty) "Set up" else "Edit", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
            }
        }

        item(key = "gemini") {
            Section("Gemini Intelligence") {
            InfoRow(
                "API key",
                "Your own key, encrypted with Android Keystore on this phone. Used only for requests the phone can't handle.",
                icon = Icons.Filled.Lock,
                iconTint = ElectricCyan,
            ) {
                TextButton(onClick = assistant::forgetKey) {
                    Text("Replace", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
            GlassDivider()
            InfoRow(
                "Default assistant",
                "Long-press Home or swipe from a corner to talk.",
                icon = Icons.Filled.Phone,
                iconTint = PureWhite,
            ) {
                TextButton(onClick = { openAction(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) }) {
                    Text("Open", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
            }
        }

        item(key = "feedback") {
            Section("Conversation & Feedback") {
            var talkOn by remember { mutableStateOf(assistant.talkOver) }
            SwitchRow(
                "Talk over ${Branding.ASSISTANT_NAME}",
                "Speak while it answers to cut it off. An echo canceller on this phone removes its own voice first.",
                talkOn,
                icon = Icons.Filled.Phone,
            ) {
                talkOn = it
                assistant.talkOver = it
            }
            GlassDivider()
            var waveOn by remember { mutableStateOf(assistant.waveToStop) }
            SwitchRow(
                "Wave to interrupt",
                "Wave a hand over the phone while ${Branding.ASSISTANT_NAME} speaks to cut it off instantly.",
                waveOn,
                icon = Icons.Filled.Star,
            ) {
                waveOn = it
                assistant.waveToStop = it
            }
            }
        }

        item(key = "floating_presence") {
            Section("Floating Presence") {
            val prefs = remember { AppSettings(context) }
            var orbOn by remember { mutableStateOf(prefs.floatingOrb && overlayAllowed) }
            LaunchedEffect(overlayAllowed) {
                orbOn = prefs.floatingOrb && overlayAllowed
            }
            SwitchRow(
                "Use ${Branding.ASSISTANT_NAME} over any app",
                if (overlayAllowed) {
                    "A temporary orb appears only while ${Branding.ASSISTANT_NAME} is active. Tap to talk, double-tap to type, long-press to close."
                } else {
                    "Display Over Other Apps is off. Turning this on opens Permissions so you can allow it."
                },
                orbOn,
                icon = Icons.Filled.Star,
            ) { on ->
                if (on && !overlayAllowed) {
                    prefs.floatingOrb = true
                    orbOn = false
                    onOpenPermissions()
                } else {
                    prefs.floatingOrb = on
                    orbOn = on && overlayAllowed
                    FloatingOrb.sync(context)
                }
            }
            }
        }

        item(key = "wake_word") {
            Section("Wake Word") {
            SwitchRow(
                "Enable wake word",
                if (micGranted) {
                    "Hands-free voice activation without opening ${Branding.ASSISTANT_NAME} or tapping the mic."
                } else {
                    "Needs Microphone permission. Turn on to open Permissions and allow Microphone."
                },
                wakeOn && micGranted,
                enabled = assistant.wakeWordAvailable,
                icon = MicIcon,
            ) { on ->
                if (on && !micGranted) {
                    onOpenPermissions()
                } else {
                    assistant.setWakeWord(on)
                }
            }
            GlassDivider()
            InfoRow(
                "Active phrase",
                "Primary: \"${WakeWordModel.PHRASE}\" (also recognizes \"${WakeWordModel.ALT_PHRASE}\").",
            ) {
                Pill(WakeWordModel.PHRASE, ElectricCyan, dot = false)
            }
            GlassDivider()
            val (statusLabel, statusDetail, statusColor) = wakeStatusUi(wakeOn, micGranted, wakeState)
            InfoRow("Background listening", statusDetail) {
                Pill(statusLabel, statusColor)
            }
            GlassDivider()
            SwitchRow(
                "Wake activation dot",
                "Shows a subtle ~${wakeDotSize.coreDiameterDp}dp top-center Liquid Glass dot for ${formatSeconds(dotDurationMs)} when \"${WakeWordModel.PHRASE}\" triggers.",
                dotOn,
            ) { on ->
                dotOn = on
                assistant.wakeIndicator = on
            }

            if (dotOn) {
                GlassDivider()
                WakeDotSizeSliderRow(
                    currentSize = wakeDotSize,
                    onSizeChanged = { chosen -> assistant.wakeDotSize = chosen },
                )
                GlassDivider()
                OptionChoiceRow(
                    title = "Display duration",
                    detail = "How long the dot fades in, pulses, and fades out (default 2.8s).",
                    options = WakeIndicatorController.DURATION_PRESETS_MS.map { it to formatSeconds(it) },
                    selected = dotDurationMs,
                    onSelect = { chosenMs ->
                        dotDurationMs = chosenMs
                        assistant.wakeDotDurationMs = chosenMs
                    },
                )
            }

            GlassDivider()
            InfoRow(
                "Battery & microphone usage",
                "Processed 100% offline on this phone. A quiet-room sound gate sleeps the speech decoder when idle to minimize CPU and battery. No continuous audio is saved or sent to Gemini until \"${WakeWordModel.PHRASE}\" is detected, and a visible notification remains active.",
            )

            if (assistant.wakeOnnxAvailable && micGranted) {
                GlassDivider()
                VoiceSetupRow(
                    saved = assistant.voiceSaved,
                    record = assistant::recordVoiceSample,
                    onSave = assistant::saveVoice,
                    onForget = assistant::forgetVoice,
                )
            }
            }
        }

        item(key = "local_commands") {
            Section("Local Commands") {
            SwitchRow(
                "Handle simple commands on phone",
                "Volume, music, flashlight, apps, time, timers and alarms run here, without Gemini.",
                localOn,
                icon = Icons.Filled.Build,
            ) {
                localOn = it
                assistant.localCommands = it
            }
            GlassDivider()
            SwitchRow(
                "Use phone speech service",
                "Better Hinglish, with offline fallbacks.",
                networkOn,
                enabled = localOn,
            ) {
                networkOn = it
                assistant.networkSpeech = it
                engine = assistant.speechEngine
            }
            GlassDivider()
            InfoRow("Speech heard by", engineText(engine))
            }
        }

        item(key = "adaptive_learning") {
            Section("Adaptive Learning") {
            var learnOn by remember { mutableStateOf(assistant.learning) }
            var learned by remember { mutableStateOf(assistant.learnedSummary()) }
            SwitchRow(
                "Learn from how I use ${Branding.ASSISTANT_NAME}",
                "Kept only on this phone: which commands you use and when, and your phrases once understood.",
                learnOn,
                icon = Icons.Filled.Settings,
            ) {
                learnOn = it
                assistant.learning = it
            }
            GlassDivider()
            InfoRow("What it knows", learned) {
                TextButton(onClick = {
                    assistant.forgetLearned()
                    learned = assistant.learnedSummary()
                }) { Text("Forget", color = CoralError) }
            }
            }
        }

        item(key = "installed_apps") {
            Section("Installed Apps Discovery") {
            val launchCount = installedApps.count { it.launchable }
            InfoRow(
                "All installed apps (A–Z)",
                "$launchCount launchable apps indexed (${installedApps.size} total including system components). Refreshes automatically when apps are installed or removed.",
                icon = Icons.Filled.Build,
                iconTint = ElectricCyan,
            ) {
                TextButton(
                    onClick = {
                        assistant.refreshInstalledApps()
                        showAppsBrowser = true
                    },
                ) {
                    Text("Browse A–Z", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
            }
            }
        }

        item(key = "privacy") {
            Section("Privacy Boundaries") {
            InfoRow(
                "What leaves the phone",
                "The wake word and local commands stay on this phone. Gemini receives only requests the phone cannot resolve locally. Diagnostic logs never store audio.",
                icon = Icons.Filled.Lock,
                iconTint = ElectricCyan,
            )
            GlassDivider()
            InfoRow("Standby", "Between requests only the wake word runs; Gemini is completely disconnected.")
            GlassDivider()
            val memory = remember { MemoryStore(context) }
            var notes by remember { mutableStateOf(memory.notes().size) }
            LifecycleResumeEffect(Unit) {
                notes = memory.notes().size
                onPauseOrDispose { }
            }
            InfoRow(
                "What ${Branding.ASSISTANT_NAME} remembers",
                "$notes note(s) stored locally on this phone only.",
            ) {
                if (notes > 0) {
                    TextButton(onClick = { memory.clearNotes().also { notes = 0 } }) {
                        Text("Forget all", color = CoralError)
                    }
                }
            }
            }
        }

        item(key = "about") {
            Section("About") {
                InfoRow(
                    Branding.ASSISTANT_NAME,
                    "Version ${versionName(context)} • GNU GPL v3.0",
                    icon = Icons.Filled.Info,
                    iconTint = Muted,
                )
            }
        }
    }
}

@Composable
private fun WakeDotSizeSliderRow(
    currentSize: WakeDotSize,
    onSizeChanged: (WakeDotSize) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(currentSize.coreDiameterDp.toFloat()) }
    LaunchedEffect(currentSize.coreDiameterDp) {
        if (sliderValue.roundToInt() != currentSize.coreDiameterDp) {
            sliderValue = currentSize.coreDiameterDp.toFloat()
        }
    }

    val previewSize = remember(sliderValue) { WakeDotSize.fromSlider(sliderValue) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Wake Dot Size: ${previewSize.coreDiameterDp}dp",
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
                fontWeight = FontWeight.SemiBold,
            )
            Pill("${WakeDotSize.MIN_DP}dp – ${WakeDotSize.MAX_DP}dp", ElectricCyan, dot = false)
        }

        Text(
            text = "Adjust continuously between ${WakeDotSize.MIN_DP}dp and ${WakeDotSize.MAX_DP}dp. Halo (~${previewSize.glowDiameterDp}dp) scales proportionally.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        // Immediate real-size Liquid Glass dot preview on dark OLED backdrop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(98.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(OledBlack)
                .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            TinyWakeDotPreview(dotSize = previewSize)
            Text(
                text = "Live preview • ${previewSize.coreDiameterDp}dp",
                style = MaterialTheme.typography.labelSmall,
                color = Muted.copy(alpha = 0.72f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
            )
        }

        Slider(
            value = sliderValue,
            onValueChange = { raw ->
                val clamped = WakeDotSize.clampFloat(raw)
                sliderValue = clamped
                onSizeChanged(WakeDotSize.fromSlider(clamped))
            },
            onValueChangeFinished = {
                val snapped = WakeDotSize.fromSlider(sliderValue)
                sliderValue = snapped.coreDiameterDp.toFloat()
                onSizeChanged(snapped)
            },
            valueRange = WakeDotSize.MIN_DP.toFloat()..WakeDotSize.MAX_DP.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = PureWhite,
                activeTrackColor = ElectricCyan,
                inactiveTrackColor = Color(0x2EFFFFFF),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Wake Dot Size"
                    stateDescription = "${previewSize.coreDiameterDp}dp"
                },
        )
    }
}

@Composable
private fun wakeStatusUi(wakeOn: Boolean, micGranted: Boolean, state: WakeState): Triple<String, String, Color> = when {
    !micGranted -> Triple("Permission off", "Microphone permission is off. Manage in Settings → Permissions.", AmberWarning)
    !wakeOn -> Triple("Off", "Turn on wake word above to listen hands-free.", SlateMuted)
    state == WakeState.Listening -> Triple("Active", "Listening offline in background for \"${WakeWordModel.PHRASE}\".", ElectricCyan)
    state == WakeState.Starting -> Triple("Starting", "Preparing offline wake-word detector…", ElectricCyan)
    state == WakeState.PausedSession -> Triple("In session", "Paused while your voice conversation runs; resumes automatically.", ElectricCyan)
    state == WakeState.PausedMicBusy -> Triple("Mic busy", "Another app is using the microphone; resumes when free.", AmberWarning)
    state == WakeState.PausedInCall -> Triple("In call", "Paused during a call; resumes automatically.", AmberWarning)
    state == WakeState.PermissionNeeded -> Triple("Blocked", "Allow Microphone in Settings → Permissions.", CoralError)
    state == WakeState.Unavailable -> Triple("Unavailable", "Offline wake-word model could not load.", CoralError)
    else -> Triple("Standby", "Enabled. Open or unlock phone to start background service.", SlateMuted)
}

private fun engineText(engine: String?) = when (engine) {
    "on-device" -> "The phone's on-device recogniser. No internet needed."
    "offline" -> "Offline model on this phone (Vosk). No internet needed; best with English words."
    "network" -> "The phone's speech service (usually Google). Needs internet."
    else -> "Nothing local is available, so Gemini hears every request."
}

private fun formatSeconds(ms: Long): String =
    String.format(java.util.Locale.US, "%.1fs", ms / 1000f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionChoiceRow(
    title: String,
    detail: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = PureWhite)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val shape = RoundedCornerShape(50)
                val interaction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .glassPressScale(interaction)
                        .clip(shape)
                        .background(
                            if (isSelected) {
                                Brush.horizontalGradient(listOf(ElectricCyan.copy(alpha = 0.22f), NeonViolet.copy(alpha = 0.16f)))
                            } else {
                                Brush.horizontalGradient(listOf(Color(0x16FFFFFF), Color(0x0CFFFFFF)))
                            },
                        )
                        .border(
                            0.9.dp,
                            if (isSelected) ElectricCyan.copy(alpha = 0.65f) else Color(0x26FFFFFF),
                            shape,
                        )
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(value) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) ElectricCyan else Muted,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.1.0"
