package app.shilex.android.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.shilex.android.Branding
import app.shilex.android.assistant.Line
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.UiState
import app.shilex.android.learn.HabitKeys
import app.shilex.android.local.ActivityLog
import app.shilex.android.local.LocalCommand
import app.shilex.android.local.LocalPhase
import app.shilex.android.local.MediaKey
import app.shilex.android.permissions.PermissionId
import app.shilex.android.permissions.PermissionRegistry
import app.shilex.android.task.Task
import app.shilex.android.wake.WakeWordModel

private data class Status(val title: String, val hint: String, val badge: String, val orb: Phase, val live: Boolean)

private fun status(state: UiState, local: LocalPhase, wakeOn: Boolean): Status = when {
    local == LocalPhase.Listening -> Status("Listening", "Say a command. Simple ones run directly on this phone.", "On phone", Phase.Listening, true)
    local == LocalPhase.Doing -> Status("Working", "Executing on this phone…", "On phone", Phase.Connecting, true)
    local == LocalPhase.Speaking -> Status("Speaking", "Answered on this phone.", "On phone", Phase.Speaking, true)
    state.phase == Phase.Connecting -> Status("Thinking", "Connecting to Gemini intelligence…", "Gemini", Phase.Connecting, true)
    state.phase == Phase.Processing -> Status("Preparing voice", "Applying your selected voice…", "JARVIS", Phase.Connecting, true)
    state.phase == Phase.Listening -> Status("Listening", "Talk any time, in English, Hindi or Hinglish.", "Gemini", Phase.Listening, true)
    state.phase == Phase.Speaking -> Status("Speaking", "Tap the orb to cut in.", "Gemini", Phase.Speaking, true)
    else -> Status(
        "Standby",
        state.problem ?: if (wakeOn) "Say \"${WakeWordModel.PHRASE}\", or tap the mic." else "Tap the mic to talk.",
        if (wakeOn) "Wake word on" else "Wake word off",
        Phase.Off,
        false,
    )
}

/**
 * AMOLED Liquid Glass Home Screen for JARVIS with permanent text chat & speech-to-text input.
 */
@Composable
fun HomeScreen(
    state: UiState,
    localPhase: LocalPhase,
    level: Float,
    task: Task?,
    activity: List<ActivityLog.Entry>,
    wakeOn: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInterrupt: () -> Unit,
    onMicDenied: () -> Unit,
    onPermissionAnswered: (Boolean) -> Unit,
    onQuick: (LocalCommand) -> String,
    onSeeActivity: () -> Unit,
    favourites: List<LocalCommand> = emptyList(),
    voiceIndicator: app.shilex.android.wake.VoiceIndicatorSnapshot = app.shilex.android.wake.VoiceIndicatorSnapshot(),
    onSendText: (String) -> Unit,
) {
    val context = LocalContext.current
    var inputText by rememberSaveable { mutableStateOf("") }
    var isListeningSpeech by remember { mutableStateOf(false) }

    val speechRecognizer = remember {
        runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
    }
    DisposableEffect(speechRecognizer) {
        onDispose { speechRecognizer?.destroy() }
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStart() else onMicDenied()
    }
    val askForTool = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onPermissionAnswered)
    LaunchedEffect(state.permissionNeeded) {
        state.permissionNeeded?.let { permission ->
            PermissionRegistry.markRequested(context, permission)
            askForTool.launch(permission)
        }
    }
    val startListening = {
        val granted = PermissionRegistry.isGrantedNow(context, PermissionId.Microphone)
        if (granted) {
            onStart()
        } else {
            PermissionRegistry.markRequested(context, Manifest.permission.RECORD_AUDIO)
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val startSpeechRecognition = {
        val granted = PermissionRegistry.isGrantedNow(context, PermissionId.Microphone)
        if (!granted) {
            PermissionRegistry.markRequested(context, Manifest.permission.RECORD_AUDIO)
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            runCatching {
                if (isListeningSpeech) {
                    speechRecognizer?.stopListening()
                    isListeningSpeech = false
                } else {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) { isListeningSpeech = true }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { isListeningSpeech = false }
                        override fun onError(error: Int) { isListeningSpeech = false }
                        override fun onResults(results: Bundle?) {
                            isListeningSpeech = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                inputText = matches[0]
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                inputText = matches[0]
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    speechRecognizer?.startListening(intent)
                    isListeningSpeech = true
                }
            }
        }
    }

    val now = status(state, localPhase, wakeOn)
    val problem = state.problem != null && !now.live
    val inConversation = state.phase != Phase.Off || state.lines.isNotEmpty()
    val recentTask = task?.takeIf { now.live || System.currentTimeMillis() - it.at < TASK_SHOWN_MS }

    Column(
        Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        // Floating Top Liquid Glass Header Bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    Branding.ASSISTANT_NAME,
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                )
                if (voiceIndicator.visible &&
                    voiceIndicator.state != app.shilex.android.wake.VoiceIndicatorState.OFF &&
                    voiceIndicator.state != app.shilex.android.wake.VoiceIndicatorState.IDLE_TIMEOUT
                ) {
                    AnimatedVoiceIndicatorDot(
                        snapshot = voiceIndicator,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(7.dp)
                            .background(if (now.live) ElectricCyan else Color(0x38FFFFFF), CircleShape)
                            .then(
                                if (now.live) {
                                    Modifier.shadow(4.dp, CircleShape, ambientColor = ElectricCyan, spotColor = ElectricCyan)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
            Pill(now.badge, if (now.live) ElectricCyan else Muted)
        }

        // Main content area: Scrollable conversation / standby view, leaving space above the bottom chat input bar
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (inConversation) {
                // Conversation mode: compact Orb + live chat scroll
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Orb(
                        phase = now.orb,
                        level = level,
                        modifier = Modifier
                            .size(134.dp)
                            .semantics { contentDescription = "${Branding.ASSISTANT_NAME}: ${now.title}" }
                            .clickable(remember { MutableInteractionSource() }, indication = null) {
                                when {
                                    state.phase == Phase.Speaking || state.phase == Phase.Processing || localPhase == LocalPhase.Speaking -> onInterrupt()
                                    state.phase == Phase.Off && localPhase == LocalPhase.Idle -> startListening()
                                    else -> Unit
                                }
                            },
                    )
                }

                StatusHeader(now, problem)
                recentTask?.let { TaskCard(it, Modifier.padding(bottom = 8.dp)) }
                ChatConversation(state.lines, Modifier.fillMaxWidth().weight(1f))
            } else {
                // Standby / Quick action mode with virtualized smooth scrolling
                val standbyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
                LazyColumn(
                    state = standbyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    item(key = "home_orb") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp, bottom = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val orbInteraction = remember { MutableInteractionSource() }
                            Orb(
                                phase = now.orb,
                                level = level,
                                modifier = Modifier
                                    .size(200.dp)
                                    .semantics { contentDescription = "${Branding.ASSISTANT_NAME}: ${now.title}" }
                                    .clickable(orbInteraction, indication = null) {
                                        when {
                                            state.phase == Phase.Speaking || state.phase == Phase.Processing || localPhase == LocalPhase.Speaking -> onInterrupt()
                                            state.phase == Phase.Off && localPhase == LocalPhase.Idle -> startListening()
                                            else -> Unit
                                        }
                                    },
                            )
                        }
                    }

                    item(key = "home_status") {
                        StatusHeader(now, problem)
                    }

                    if (recentTask != null) {
                        item(key = "home_task") {
                            TaskCard(recentTask, Modifier.padding(bottom = 12.dp))
                        }
                    }

                    item(key = "home_quick") {
                        QuickActions(onQuick, favourites)
                    }

                    item(key = "home_recent") {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            RecentActivitySnippet(activity.take(2), onSeeActivity)
                        }
                    }
                }
            }
        }

        // Permanent Message Input Bar at the bottom (anchored above navigation dock)
        ChatInputBar(
            text = inputText,
            onTextChanged = { inputText = it },
            onSend = {
                val msg = inputText.trim()
                if (msg.isNotEmpty()) {
                    inputText = ""
                    onSendText(msg)
                }
            },
            onMicClick = { startSpeechRecognition() },
            isListening = isListeningSpeech,
            live = now.live,
            onStopLive = onStop,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 80.dp),
        )
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit,
    isListening: Boolean,
    live: Boolean,
    onStopLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .shadow(12.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(GlassSurfaceGradient)
            .border(1.dp, GlassBorderGradient, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val micInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .glassPressScale(micInteraction)
                .size(42.dp)
                .clip(CircleShape)
                .background(if (isListening) CoralError.copy(alpha = 0.25f) else Color(0x1EFFFFFF))
                .border(0.8.dp, if (isListening) CoralError else Color(0x33FFFFFF), CircleShape)
                .clickable(interactionSource = micInteraction, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMicClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MicIcon,
                contentDescription = if (isListening) "Listening..." else "Speech to text",
                tint = if (isListening) CoralError else ElectricCyan,
                modifier = Modifier.size(20.dp),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(
                    text = if (isListening) "Listening to speech..." else "Ask JARVIS or type message...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = PureWhite),
                cursorBrush = SolidColor(ElectricCyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (text.isNotBlank()) {
                        onSend()
                    }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val sendInteraction = remember { MutableInteractionSource() }
        val canSend = text.isNotBlank()
        Box(
            modifier = Modifier
                .glassPressScale(sendInteraction)
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    brush = if (live) LiveStopButtonGradient
                    else if (canSend) TalkButtonGradient
                    else SolidColor(Color(0x14FFFFFF)),
                )
                .border(
                    0.8.dp,
                    if (live) CoralError.copy(alpha = 0.6f)
                    else if (canSend) ElectricCyan.copy(alpha = 0.6f)
                    else Color(0x22FFFFFF),
                    CircleShape,
                )
                .clickable(
                    interactionSource = sendInteraction,
                    indication = null,
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (live) {
                        onStopLive()
                    } else if (canSend) {
                        onSend()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (live) Icons.Filled.Close else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (live) "Stop" else "Send",
                tint = if (live) PureWhite else if (canSend) Color(0xFF001E26) else Muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun StatusHeader(now: Status, problem: Boolean) {
    AnimatedContent(
        targetState = now.title,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
        label = "statusTitle",
    ) { title ->
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = PureWhite,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        now.hint,
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem) CoralError else Muted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
    )
}

/** One-tap phone actions styled as frosted smoked-glass capsule pills with spring press feedback. */
@Composable
private fun QuickActions(onQuick: (LocalCommand) -> String, favourites: List<LocalCommand>) {
    var torchOn by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    val learned = favourites.mapNotNull { command -> HabitKeys.key(command)?.let { HabitKeys.describe(it).replaceFirstChar(Char::uppercase) to { command } } }
    val defaults = listOf(
        (if (torchOn) "Torch off" else "Torch") to {
            torchOn = !torchOn
            LocalCommand.Torch(torchOn)
        },
        "Battery" to { LocalCommand.Battery },
        "Time" to { LocalCommand.Time },
        "Pause" to { LocalCommand.Media(MediaKey.Pause) },
    )
    val actions = (learned + defaults.filter { d -> learned.none { it.first.startsWith(d.first, ignoreCase = true) } }).take(4)

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            actions.forEach { (label, command) ->
                val shape = RoundedCornerShape(50)
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .glassPressScale(interaction)
                        .clip(shape)
                        .background(GlassSurfaceGradient)
                        .border(0.9.dp, GlassBorderGradient, shape)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { answer = onQuick(command()) }
                        .padding(vertical = 12.dp, horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = PureWhite,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
        answer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = ElectricCyan,
                modifier = Modifier.padding(top = 8.dp, start = 6.dp),
            )
        }
    }
}

/** Recent activity snippet with smoked frosted Liquid Glass card. */
@Composable
private fun RecentActivitySnippet(entries: List<ActivityLog.Entry>, onSeeAll: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Recent activity",
            style = MaterialTheme.typography.titleSmall,
            color = Muted,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.15.sp,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        TextButton(onClick = onSeeAll) {
            Text("See all", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
        }
    }
    if (entries.isEmpty()) {
        Text(
            "Nothing yet. What you ask shows up here.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(start = 6.dp, top = 2.dp),
        )
    } else {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { idx, entry ->
                ActivityRow(entry)
                if (idx < entries.lastIndex) {
                    GlassDivider()
                }
            }
        }
    }
}

/** Modern conversation stream with smoked frosted glass AI bubbles. */
@Composable
private fun ChatConversation(lines: List<Line>, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex)
    }
    val userShape = remember { RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp) }
    val aiShape = remember { RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp) }
    LazyColumn(
        modifier = modifier.padding(vertical = 4.dp),
        state = list,
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lines, key = { it.id }) { line ->
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (line.fromUser) {
                    Box(
                        Modifier
                            .clip(userShape)
                            .background(brush = UserChatBubbleGradient)
                            .border(1.dp, Color(0x66FFFFFF), userShape)
                            .padding(horizontal = 15.dp, vertical = 10.dp),
                    ) {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .clip(aiShape)
                            .background(GlassSurfaceGradient)
                            .border(1.dp, GlassBorderGradient, aiShape)
                            .padding(horizontal = 15.dp, vertical = 10.dp),
                    ) {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = PureWhite,
                            lineHeight = 21.sp,
                        )
                    }
                }
            }
        }
    }
}

private const val TASK_SHOWN_MS = 2 * 60_000L
