package app.shilex.android.ui

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.audio.BundledHindiVoices
import app.shilex.android.audio.PreviewLanguage
import app.shilex.android.audio.PreviewPlayback
import app.shilex.android.audio.TtsVoiceInfo
import app.shilex.android.audio.VoiceClassifier
import app.shilex.android.audio.VoiceGender
import app.shilex.android.audio.VoicePreviewState
import app.shilex.android.data.Settings
import java.util.Locale

/**
 * Clean Liquid Glass "JARVIS VOICE" settings folder sub-screen containing ONLY:
 * - Female Voices
 * - Male Voices
 * backed strictly by genuine offline Hindi voices without technical jargon.
 */
@Composable
fun JarvisVoiceSubScreen(
    assistant: AssistantViewModel,
    onBack: () -> Unit,
) {
    val speaker = remember { assistant.speaker }
    val hindiCatalog by speaker.hindiCatalog.collectAsStateWithLifecycle()
    val isReady by speaker.isTtsReadyState.collectAsStateWithLifecycle()
    val voiceError by speaker.ttsError.collectAsStateWithLifecycle()
    val previewPlayback by speaker.previewPlayback.collectAsStateWithLifecycle()
    val isMediaMuted by speaker.isMediaMuted.collectAsStateWithLifecycle()

    val speakResponses by assistant.speakResponsesState.collectAsStateWithLifecycle()
    val rawSelectedVoiceId by assistant.ttsVoiceIdState.collectAsStateWithLifecycle()
    val genderFilter by assistant.ttsGenderFilterState.collectAsStateWithLifecycle()
    val savedSpeechRate by assistant.ttsSpeechRateState.collectAsStateWithLifecycle()
    val savedPitch by assistant.ttsPitchState.collectAsStateWithLifecycle()

    var speechRate by remember { mutableFloatStateOf(savedSpeechRate) }
    var pitch by remember { mutableFloatStateOf(savedPitch) }
    var previewLanguage by rememberSaveable { mutableStateOf(PreviewLanguage.HINDI) }

    LaunchedEffect(savedSpeechRate) { speechRate = savedSpeechRate }
    LaunchedEffect(savedPitch) { pitch = savedPitch }

    val effectiveSelectedId = remember(rawSelectedVoiceId, hindiCatalog, genderFilter) {
        VoiceClassifier.resolveEffectiveVoiceId(
            savedVoiceId = rawSelectedVoiceId,
            catalog = hindiCatalog,
            preferMale = genderFilter == Settings.GENDER_MALE,
        )
    }
    val selectedIsBundled = remember(effectiveSelectedId) {
        BundledHindiVoices.find(effectiveSelectedId) != null
    }

    LifecycleResumeEffect(Unit) {
        assistant.refreshTtsVoices()
        onPauseOrDispose {
            speaker.stopPreview()
        }
    }

    DisposableEffect(speaker) {
        onDispose {
            speaker.stopPreview()
        }
    }

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    LaunchedEffect(voiceError) {
        if (voiceError != null) {
            listState.animateScrollToItem(0)
        }
    }
    val bottomPadding = floatingDockContentBottomPadding()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "header") {
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
                            .background(GlassSurfaceHighlight)
                            .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(50))
                            .clickable(interactionSource = interaction, indication = null) {
                                speaker.stopPreview()
                                onBack()
                            }
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
                    text = "JARVIS VOICE",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Choose your preferred voice for ${Branding.ASSISTANT_NAME} and test Hindi or English previews.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        item(key = "preview_sample") {
            GlassCard(verticalPadding = 12) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Preview sample language",
                                style = MaterialTheme.typography.titleMedium,
                                color = PureWhite,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = VoiceClassifier.previewSampleText(previewLanguage),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewLanguage.entries.forEach { lang ->
                            val selected = previewLanguage == lang
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) ElectricCyan.copy(alpha = 0.16f) else GlassSurfaceHighlight.copy(alpha = 0.7f))
                                    .border(
                                        0.9.dp,
                                        if (selected) ElectricCyan.copy(alpha = 0.65f) else GlassBorderSubtle,
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable {
                                        if (previewLanguage != lang) {
                                            previewLanguage = lang
                                            if (previewPlayback.state != VoicePreviewState.Idle) {
                                                speaker.stopPreview()
                                            }
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = lang.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) ElectricCyan else PureWhite,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isMediaMuted) {
            item(key = "muted_notice") {
                GlassCard(verticalPadding = 12) {
                    Text(
                        text = "Media volume is muted or set to zero. Turn up media volume on your device to hear voice previews.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberWarning,
                    )
                }
            }
        }

        if (voiceError != null) {
            item(key = "error") {
                GlassCard(verticalPadding = 14) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = voiceError ?: "Voice playback is unavailable right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CoralError,
                        )
                        OutlinedButton(
                            onClick = { speaker.retryTts { assistant.refreshTtsVoices() } },
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Retry", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // 1. Female Voices (genuine available Hindi female voices, max 5)
        item(key = "female") {
            Section("Female Voices") {
                VoiceChoiceList(
                    voices = hindiCatalog.femaleVoices,
                    accent = SoftPink,
                    emptyMessage = if (isReady) {
                        "No female Hindi voices found on this phone."
                    } else {
                        "Loading available Hindi voices…"
                    },
                    selectedVoiceId = effectiveSelectedId,
                    previewPlayback = previewPlayback,
                    onSelectVoice = { chosen ->
                        speaker.stopPreview()
                        assistant.ttsGenderFilter = Settings.GENDER_FEMALE
                        assistant.ttsVoiceId = chosen.id
                    },
                    onPreviewVoice = { id ->
                        speaker.previewVoice(
                            voiceId = id,
                            language = previewLanguage,
                            speechRateOverride = speechRate,
                        )
                    },
                    onStopPreview = { speaker.stopPreview() },
                )
            }
        }

        // 2. Male Voices (genuine available Hindi male voices, max 5)
        item(key = "male") {
            Section("Male Voices") {
                VoiceChoiceList(
                    voices = hindiCatalog.maleVoices,
                    accent = ElectricCyan,
                    emptyMessage = if (isReady) {
                        "No male Hindi voices found on this phone."
                    } else {
                        "Loading available Hindi voices…"
                    },
                    selectedVoiceId = effectiveSelectedId,
                    previewPlayback = previewPlayback,
                    onSelectVoice = { chosen ->
                        speaker.stopPreview()
                        assistant.ttsGenderFilter = Settings.GENDER_MALE
                        assistant.ttsVoiceId = chosen.id
                    },
                    onPreviewVoice = { id ->
                        speaker.previewVoice(
                            voiceId = id,
                            language = previewLanguage,
                            speechRateOverride = speechRate,
                        )
                    },
                    onStopPreview = { speaker.stopPreview() },
                )
            }
        }

        item(key = "controls") {
            GlassCard(verticalPadding = 12) {
                SwitchRow(
                    title = "Speak replies",
                    detail = "Speak ${Branding.ASSISTANT_NAME} answers aloud.",
                    checked = speakResponses,
                    icon = Icons.Filled.Star,
                    onChange = { on -> assistant.speakResponses = on },
                )
                GlassDivider()
                VoiceControlSlider(
                    title = "Voice speed",
                    formattedValue = String.format(Locale.US, "%.2fx", speechRate),
                    value = speechRate,
                    valueRange = 0.5f..2.0f,
                    accent = ElectricCyan,
                    onValueChange = { rate -> speechRate = rate },
                    onValueChangeFinished = { assistant.ttsSpeechRate = speechRate },
                )
                if (!selectedIsBundled) {
                    GlassDivider()
                    VoiceControlSlider(
                        title = "Voice pitch",
                        formattedValue = String.format(Locale.US, "%.2fx", pitch),
                        value = pitch,
                        valueRange = 0.5f..1.5f,
                        accent = NeonViolet,
                        onValueChange = { p -> pitch = p },
                        onValueChangeFinished = { assistant.ttsPitch = pitch },
                    )
                }
                GlassDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            speaker.stopPreview()
                            assistant.restoreDefaultTtsVoice()
                        },
                    ) {
                        Text("Reset voice", color = Muted, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceChoiceList(
    voices: List<TtsVoiceInfo>,
    accent: Color,
    emptyMessage: String,
    selectedVoiceId: String?,
    previewPlayback: PreviewPlayback,
    onSelectVoice: (TtsVoiceInfo) -> Unit,
    onPreviewVoice: (String) -> Unit,
    onStopPreview: () -> Unit,
) {
    if (voices.isEmpty()) {
        Text(
            text = emptyMessage,
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }

    val shape = remember { RoundedCornerShape(16.dp) }
    val pillShape = remember { RoundedCornerShape(50) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        voices.forEach { voice ->
            val isSelected = selectedVoiceId == voice.id
            val isCurrentVoice = previewPlayback.voiceId == voice.id
            val isLoading = isCurrentVoice && previewPlayback.state == VoicePreviewState.Loading
            val isPlaying = isCurrentVoice && previewPlayback.state == VoicePreviewState.Playing
            val rowInteraction = remember(voice.id) { MutableInteractionSource() }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (isSelected) accent.copy(alpha = 0.14f) else GlassSurfaceHighlight.copy(alpha = 0.7f))
                    .border(
                        0.8.dp,
                        if (isSelected) accent.copy(alpha = 0.6f) else GlassBorderSubtle,
                        shape,
                    )
                    .clickable(
                        interactionSource = rowInteraction,
                        indication = null,
                    ) { onSelectVoice(voice) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) PureWhite else PureWhite.copy(alpha = 0.9f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                    when {
                        isPlaying -> {
                            Text(
                                text = "Playing ${previewPlayback.language.label.lowercase(Locale.ROOT)} preview…",
                                style = MaterialTheme.typography.labelSmall,
                                color = CoralError,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        isLoading -> {
                            Text(
                                text = "Preparing ${previewPlayback.language.label.lowercase(Locale.ROOT)} voice…",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        isSelected -> {
                            Text(
                                text = "Selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    val selectInteraction = remember(voice.id) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .glassPressScale(selectInteraction)
                            .clip(pillShape)
                            .background(accent.copy(alpha = 0.16f))
                            .border(0.8.dp, accent.copy(alpha = 0.5f), pillShape)
                            .clickable(
                                interactionSource = selectInteraction,
                                indication = null,
                            ) { onSelectVoice(voice) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Select",
                            style = MaterialTheme.typography.labelMedium,
                            color = PureWhite,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))

                val previewInteraction = remember(voice.id) { MutableInteractionSource() }
                val previewBoxBg = when {
                    isPlaying -> CoralError.copy(alpha = 0.2f)
                    isLoading -> ElectricCyan.copy(alpha = 0.18f)
                    else -> GlassSurfaceHighlight
                }
                val previewBoxBorder = when {
                    isPlaying -> CoralError.copy(alpha = 0.6f)
                    isLoading -> ElectricCyan.copy(alpha = 0.6f)
                    else -> GlassBorderSubtle
                }

                Box(
                    modifier = Modifier
                        .glassPressScale(previewInteraction)
                        .clip(pillShape)
                        .background(previewBoxBg)
                        .border(0.8.dp, previewBoxBorder, pillShape)
                        .clickable(
                            interactionSource = previewInteraction,
                            indication = null,
                        ) {
                            if (isLoading || isPlaying) onStopPreview() else onPreviewVoice(voice.id)
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isLoading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.6.dp,
                                    color = ElectricCyan,
                                )
                                Text(
                                    text = "Loading…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        isPlaying -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Stop preview",
                                    tint = CoralError,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Stop",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CoralError,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        else -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Play preview",
                                    tint = accent,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Play",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PureWhite,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceControlSlider(
    title: String,
    formattedValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
            )
            Pill(formattedValue, accent, dot = false)
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { raw ->
                onValueChange(raw.coerceIn(valueRange.start, valueRange.endInclusive))
            },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = PureWhite,
                activeTrackColor = accent,
                inactiveTrackColor = GlassBorderColor.copy(alpha = 0.72f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = title
                    stateDescription = formattedValue
                },
        )
    }
}
