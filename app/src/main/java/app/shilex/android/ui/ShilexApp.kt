package app.shilex.android.ui

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.assistant.UiState
import app.shilex.android.assistant.VoiceService
import app.shilex.android.local.ActivityLog
import app.shilex.android.local.LocalPhase
import app.shilex.android.orb.TinyWakeDot
import app.shilex.android.task.Task
import app.shilex.android.wake.WakeIndicator
import app.shilex.android.wake.WakeIndicatorController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Automations("Automations", Icons.Filled.Star),
    Activity("Activity", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun ShilexApp(assistant: AssistantViewModel) {
    val state by assistant.state.collectAsStateWithLifecycle()
    val localPhase by assistant.localPhase.collectAsStateWithLifecycle()
    val activity by assistant.activity.collectAsStateWithLifecycle()
    val wakeWord by assistant.wakeWord.collectAsStateWithLifecycle()
    val wakeDot by assistant.wakeDot.collectAsStateWithLifecycle()
    val wakeDotSize by assistant.wakeDotSizeState.collectAsStateWithLifecycle()
    val level by assistant.level.collectAsStateWithLifecycle()
    val task by assistant.task.collectAsStateWithLifecycle()
    val voiceIndicator by assistant.voiceIndicator.collectAsStateWithLifecycle()
    var onboarded by rememberSaveable { mutableStateOf(assistant.onboarded) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var modalOverlayActive by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        assistant.onPhotoPicked(uri)
    }

    val galleryDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        assistant.onGalleryDeleteResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(state.photoPickRequestId) {
        if (state.photoPickRequestId > 0L) {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    LaunchedEffect(state.galleryDeleteRequestId, state.galleryDeleteIntentSender) {
        val sender = state.galleryDeleteIntentSender
        if (state.galleryDeleteRequestId > 0L && sender != null) {
            val request = IntentSenderRequest.Builder(sender).build()
            galleryDeleteLauncher.launch(request)
        }
    }

    LaunchedEffect(wakeDot.visible, wakeDot.triggerId, wakeDot.durationMs) {
        if (wakeDot.visible) {
            delay(WakeIndicatorController.cleanupDelayMs(wakeDot.durationMs))
            WakeIndicator.dismiss()
        }
    }

    ShilexTheme {
        val appContext = LocalContext.current
        val glassEnabled = currentLiquidGlassEnabled()
        val lowRamDevice = remember(appContext) {
            appContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        }
        CompositionLocalProvider(LocalModalOverlayActive provides { modalOverlayActive = it }) {
            Surface(
                color = OledBlack,
                contentColor = PureWhite,
                modifier = Modifier.fillMaxSize(),
            ) {
                val blurEnabled = glassEnabled && !lowRamDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                val modalBlur by animateDpAsState(
                    targetValue = if (modalOverlayActive && blurEnabled) 14.dp else 0.dp,
                    animationSpec = tween(durationMillis = 220),
                    label = "modalBlur",
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (modalBlur > 0.dp) Modifier.blur(modalBlur) else Modifier),
                ) {
                    Content(
                        assistant = assistant,
                        state = state,
                        localPhase = localPhase,
                        level = level,
                        task = task,
                        activity = activity,
                        wakeWord = wakeWord,
                        voiceIndicator = voiceIndicator,
                        onboarded = onboarded,
                        onOnboarded = { onboarded = true },
                        tab = tab,
                        setTab = { tab = it },
                    )
                    if (wakeDot.visible && assistant.wakeIndicator) {
                        TinyWakeDot(
                            triggerId = wakeDot.triggerId,
                            durationMs = wakeDot.durationMs,
                            dotSize = wakeDotSize,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 6.dp),
                        )
                    } else if (voiceIndicator.visible &&
                        voiceIndicator.state != app.shilex.android.wake.VoiceIndicatorState.OFF &&
                        voiceIndicator.state != app.shilex.android.wake.VoiceIndicatorState.IDLE_TIMEOUT
                    ) {
                        AnimatedVoiceIndicatorDot(
                            snapshot = voiceIndicator,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(
    assistant: AssistantViewModel,
    state: UiState,
    localPhase: LocalPhase,
    level: Float,
    task: Task?,
    activity: List<ActivityLog.Entry>,
    wakeWord: Boolean,
    voiceIndicator: app.shilex.android.wake.VoiceIndicatorSnapshot,
    onboarded: Boolean,
    onOnboarded: () -> Unit,
    tab: Int,
    setTab: (Int) -> Unit,
) {
    if (!onboarded || !state.hasKey) {
        Box(Modifier.fillMaxSize().background(OledBlack).safeDrawingPadding()) {
            Onboarding(assistant, state.hasKey) {
                assistant.onboarded = true
                onOnboarded()
            }
        }
        return
    }

    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(OledBlack),
    ) {
        val safeTab = tab.coerceIn(0, Tab.entries.lastIndex)
        val saveableStateHolder = rememberSaveableStateHolder()

        // Full-bleed scrollable content with short GPU-composited crossfade and preserved tab state
        Crossfade(
            targetState = safeTab,
            animationSpec = tween(durationMillis = 100),
            modifier = Modifier.fillMaxSize(),
            label = "tabCrossfade",
        ) { activeTabIndex ->
            saveableStateHolder.SaveableStateProvider(activeTabIndex) {
                when (Tab.entries[activeTabIndex]) {
                    Tab.Home -> HomeScreen(
                        state = state,
                        localPhase = localPhase,
                        level = level,
                        task = task,
                        activity = activity,
                        wakeOn = assistant.wakeWordAvailable && wakeWord && VoiceService.hasMicPermission(context),
                        onStart = assistant::start,
                        onStop = assistant::stop,
                        onInterrupt = assistant::interrupt,
                        onMicDenied = assistant::micUnavailable,
                        onPermissionAnswered = assistant::permissionAnswered,
                        onQuick = assistant::quick,
                        onSeeActivity = { setTab(Tab.Activity.ordinal) },
                        favourites = remember(activity.size) { assistant.favourites() },
                        voiceIndicator = voiceIndicator,
                        onSendText = assistant::sendTextMessage,
                    )
                    Tab.Automations -> AutomationsScreen()
                    Tab.Activity -> ActivityScreen(activity, assistant::clearActivity)
                    Tab.Settings -> SettingsScreen(assistant)
                }
            }
        }

        // Interactive Finger-Tracking Floating Liquid Glass Capsule Navigation Dock
        FloatingLiquidGlassDock(
            selectedTab = safeTab,
            onSelectTab = { next ->
                val clamped = next.coerceIn(0, Tab.entries.lastIndex)
                if (clamped != safeTab) setTab(clamped)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = FloatingDockOuterVerticalMargin),
        )
    }
}

@Composable
private fun FloatingLiquidGlassDock(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glassEnabled = currentLiquidGlassEnabled()
    val dockShape = remember(glassEnabled) { RoundedCornerShape(if (glassEnabled) 34.dp else 22.dp) }
    val lensShape = remember(glassEnabled) { RoundedCornerShape(if (glassEnabled) 28.dp else 18.dp) }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val cyanAccent = ElectricCyan
    val violetAccent = NeonViolet
    val onBg = PureWhite
    val borderColor = GlassBorderColor

    val rimBrush = remember(glassEnabled, onBg) {
        if (glassEnabled) {
            Brush.verticalGradient(
                listOf(
                    onBg.copy(alpha = 0.11f),
                    Color.Transparent,
                    onBg.copy(alpha = 0.04f),
                ),
            )
        } else {
            Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
        }
    }
    val lensIdleBrush = remember(glassEnabled, cyanAccent, onBg) {
        if (glassEnabled) {
            Brush.verticalGradient(
                listOf(
                    onBg.copy(alpha = 0.16f),
                    onBg.copy(alpha = 0.07f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    cyanAccent.copy(alpha = 0.16f),
                    cyanAccent.copy(alpha = 0.12f),
                ),
            )
        }
    }
    val lensActiveBrush = remember(glassEnabled, cyanAccent, onBg) {
        if (glassEnabled) {
            Brush.verticalGradient(
                listOf(
                    onBg.copy(alpha = 0.24f),
                    onBg.copy(alpha = 0.09f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    cyanAccent.copy(alpha = 0.22f),
                    cyanAccent.copy(alpha = 0.16f),
                ),
            )
        }
    }
    val lensRadialIdleBrush = remember(glassEnabled, cyanAccent, violetAccent) {
        if (glassEnabled) {
            Brush.radialGradient(
                listOf(
                    cyanAccent.copy(alpha = 0.14f),
                    violetAccent.copy(alpha = 0.08f),
                    Color.Transparent,
                ),
            )
        } else {
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
        }
    }
    val lensRadialActiveBrush = remember(glassEnabled, cyanAccent, violetAccent) {
        if (glassEnabled) {
            Brush.radialGradient(
                listOf(
                    cyanAccent.copy(alpha = 0.24f),
                    violetAccent.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
            )
        } else {
            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
        }
    }
    val lensBorderBrush = remember(glassEnabled, borderColor, cyanAccent) {
        if (glassEnabled) {
            Brush.verticalGradient(
                listOf(
                    borderColor.copy(alpha = 0.90f),
                    borderColor.copy(alpha = 0.25f),
                    borderColor.copy(alpha = 0.45f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    cyanAccent.copy(alpha = 0.45f),
                    cyanAccent.copy(alpha = 0.45f),
                ),
            )
        }
    }

    var isPressed by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var hoverTabState by remember { mutableStateOf<Int?>(null) }

    // Stationary pointer/layout container (never scaled mid-gesture to avoid pointer coordinate jitter)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FloatingDockHeight)
            .shadow(
                elevation = if (glassEnabled) 18.dp else 8.dp,
                shape = dockShape,
                ambientColor = Color.Black.copy(alpha = 0.45f),
                spotColor = Color.Black.copy(alpha = 0.45f),
            )
            .clip(dockShape),
    ) {

        // Dock tint + optional Liquid Glass specular rim
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(GlassDockSurfaceGradient)
                .background(rimBrush)
                .border(1.dp, GlassDockBorderGradient, dockShape),
        )

        val count = Tab.entries.size
        val innerInsetDp = 6.dp
        val density = LocalDensity.current
        val innerInsetPx = with(density) { innerInsetDp.toPx() }
        val padPx = with(density) { 2.dp.toPx() }
        val cancelUpwardPx = with(density) { 48.dp.toPx() }

        var dockWidthPx by remember { mutableIntStateOf(0) }
        val stripWidthPx = (dockWidthPx.toFloat() - innerInsetPx * 2f).coerceAtLeast(1f)
        val slotWidthPx = (stripWidthPx / count).coerceAtLeast(1f)
        val slotWidthDp = with(density) { slotWidthPx.toDp() }

        val currentSelectedTab by rememberUpdatedState(selectedTab)
        val currentOnSelectTab by rememberUpdatedState(onSelectTab)
        val currentSlotWidthPx by rememberUpdatedState(slotWidthPx)
        val currentInnerInsetPx by rememberUpdatedState(innerInsetPx)
        val currentCancelUpwardPx by rememberUpdatedState(cancelUpwardPx)

        val lensX = remember(count) { Animatable(0f) }
        val dragOffsetState = remember(count) { mutableFloatStateOf(0f) }
        var pendingTargetTab by remember { mutableStateOf<Int?>(null) }
        var settlingTargetTab by remember { mutableStateOf<Int?>(null) }
        var isSettlingFromDrag by remember { mutableStateOf(false) }
        var layoutSnapped by remember { mutableStateOf(false) }
        var activeLensJob by remember { mutableStateOf<Job?>(null) }

        LaunchedEffect(selectedTab, dockWidthPx) {
            if (dockWidthPx <= 0) return@LaunchedEffect
            if (pendingTargetTab == selectedTab) {
                pendingTargetTab = null
            }
            if (!layoutSnapped) {
                activeLensJob?.cancel()
                isSettlingFromDrag = false
                val snapOffset = LiquidDockMath.offsetForTab(currentSelectedTab, slotWidthPx, count)
                lensX.snapTo(snapOffset)
                dragOffsetState.floatValue = snapOffset
                layoutSnapped = true
            } else if (!isDragging && !isPressed) {
                if (settlingTargetTab == selectedTab) {
                    // Already springing to this destination from finger release
                    settlingTargetTab = null
                } else {
                    settlingTargetTab = null
                    activeLensJob?.cancel()
                    activeLensJob = coroutineScope.launch {
                        lensX.animateTo(
                            targetValue = LiquidDockMath.offsetForTab(selectedTab, slotWidthPx, count),
                            animationSpec = spring(
                                dampingRatio = 0.84f,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }
                }
            }
        }

        val activeDisplayTab = when {
            isDragging -> hoverTabState ?: selectedTab
            pendingTargetTab != null -> pendingTargetTab ?: selectedTab
            else -> selectedTab
        }

        val lensScale by animateFloatAsState(
            targetValue = if (isPressed || isDragging) 1.04f else 1f,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium),
            label = "lensScale",
        )

        // Interactive pointer layer without subcomposition overhead
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (size.width != dockWidthPx) {
                        dockWidthPx = size.width
                    }
                }
                .pointerInput(count) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slotW = currentSlotWidthPx
                        val insetPx = currentInnerInsetPx
                        val session = DockGestureSession(
                            downX = down.position.x,
                            downY = down.position.y,
                            initialSelectedTab = currentSelectedTab,
                            slotWidthPx = slotW,
                            innerInsetPx = insetPx,
                            tabCount = count,
                            touchSlopPx = viewConfiguration.touchSlop,
                            cancelUpwardDistancePx = currentCancelUpwardPx,
                        )

                        // Cancel any in-flight resting spring immediately when user touches dock
                        activeLensJob?.cancel()
                        activeLensJob = null
                        isSettlingFromDrag = false
                        isPressed = true
                        pendingTargetTab = null
                        settlingTargetTab = null
                        var settleJobLaunched = false

                        var releaseX = down.position.x
                        var releaseY = down.position.y
                        var releaseConsumed = false

                        var holdJob: Job? = coroutineScope.launch {
                            delay(LiquidDockMath.HOLD_TIMEOUT_MS)
                            if (session.cancelled || !isPressed) return@launch
                            val heldTab = session.onHoldActivated()
                            dragOffsetState.floatValue = session.latestDragOffsetPx
                            hoverTabState = heldTab
                            isDragging = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    session.onCancel()
                                    break
                                }
                                releaseX = change.position.x
                                releaseY = change.position.y
                                if (!change.pressed) {
                                    releaseConsumed = change.isConsumed
                                    if (!releaseConsumed) change.consume()
                                    break
                                }

                                when (
                                    val outcome = session.onMove(
                                        x = change.position.x,
                                        y = change.position.y,
                                        consumedByOther = change.isConsumed,
                                    )
                                ) {
                                    DockGestureSession.MoveOutcome.Pending -> Unit
                                    DockGestureSession.MoveOutcome.Cancelled -> {
                                        holdJob?.cancel()
                                        holdJob = null
                                        break
                                    }
                                    is DockGestureSession.MoveOutcome.Dragging -> {
                                        change.consume()
                                        holdJob?.cancel()
                                        holdJob = null
                                        // Pure placement-phase state write: 0 coroutines & 0 recompositions while sliding
                                        dragOffsetState.floatValue = outcome.offsetPx
                                        if (!isDragging) isDragging = true
                                        if (outcome.crossedTabBoundary || hoverTabState != outcome.hoverTab) {
                                            hoverTabState = outcome.hoverTab
                                            if (outcome.crossedTabBoundary) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                }
                            }

                            holdJob?.cancel()
                            holdJob = null

                            val wasDragging = isDragging
                            val startOffsetForSettle = if (wasDragging) dragOffsetState.floatValue else lensX.value

                            val release = session.onUp(
                                upX = releaseX,
                                upY = releaseY,
                                consumedByOther = releaseConsumed,
                                latestSelectedTab = currentSelectedTab,
                            )

                            val targetTab = when (release) {
                                is DockGestureSession.ReleaseOutcome.Select -> release.tabIndex
                                is DockGestureSession.ReleaseOutcome.Cancelled -> release.restoreTab
                            }

                            // 1. Switch destination IMMEDIATELY on release, independent of indicator spring
                            if (release is DockGestureSession.ReleaseOutcome.Select) {
                                settlingTargetTab = release.tabIndex
                                if (release.changed) {
                                    pendingTargetTab = release.tabIndex
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOnSelectTab(release.tabIndex)
                                }
                            } else {
                                settlingTargetTab = null
                                pendingTargetTab = null
                            }

                            if (wasDragging) isSettlingFromDrag = true
                            isPressed = false
                            isDragging = false
                            hoverTabState = null

                            // 2. Decorative lens spring settles cleanly in background without blocking UI navigation
                            val destinationOffset = LiquidDockMath.offsetForTab(targetTab, slotW, count)
                            activeLensJob?.cancel()
                            settleJobLaunched = true
                            activeLensJob = coroutineScope.launch {
                                try {
                                    if (wasDragging) {
                                        lensX.snapTo(startOffsetForSettle)
                                    }
                                } finally {
                                    isSettlingFromDrag = false
                                }
                                lensX.animateTo(
                                    targetValue = destinationOffset,
                                    animationSpec = spring(
                                        dampingRatio = 0.84f,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        } finally {
                            holdJob?.cancel()
                            holdJob = null
                            if (!settleJobLaunched) isSettlingFromDrag = false
                            isPressed = false
                            isDragging = false
                            hoverTabState = null
                        }
                    }
                },
        ) {
            // Luminous frosted Liquid Glass capsule lens behind hovered/active tab (GPU-composited translation)
            if (dockWidthPx > 0) {
                val activeLens = isPressed || isDragging
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width((slotWidthDp - 4.dp).coerceAtLeast(1.dp))
                        .height(54.dp)
                        .graphicsLayer {
                            val xPx = if (isDragging || isSettlingFromDrag) dragOffsetState.floatValue else lensX.value
                            translationX = xPx + innerInsetPx + padPx
                            scaleX = lensScale
                            scaleY = lensScale
                        }
                        .clip(lensShape)
                        .background(if (activeLens) lensActiveBrush else lensIdleBrush)
                        .background(if (activeLens) lensRadialActiveBrush else lensRadialIdleBrush)
                        .border(0.9.dp, lensBorderBrush, lensShape),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = innerInsetDp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { item ->
                    val selected = activeDisplayTab == item.ordinal
                    val contentScale by animateFloatAsState(
                        targetValue = if (selected) 1.05f else 0.96f,
                        animationSpec = spring(
                            dampingRatio = 0.78f,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "tabScale",
                    )
                    val tint by animateColorAsState(
                        targetValue = if (selected) PureWhite else SlateMuted,
                        animationSpec = tween(140),
                        label = "tabTint",
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                scaleX = contentScale
                                scaleY = contentScale
                            }
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                this.selected = (selectedTab == item.ordinal)
                                contentDescription = item.label
                                onClick {
                                    if (item.ordinal != currentSelectedTab) {
                                        currentOnSelectTab(item.ordinal)
                                    }
                                    true
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(21.dp),
                        )
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
