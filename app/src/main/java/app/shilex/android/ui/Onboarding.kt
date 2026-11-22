package app.shilex.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.assistant.Phase
import app.shilex.android.permissions.OnboardingPermissionGuide
import app.shilex.android.permissions.PermissionActionKind
import app.shilex.android.permissions.PermissionId
import app.shilex.android.permissions.PermissionRegistry
import app.shilex.android.permissions.PermissionStatus
import app.shilex.android.wake.WakeWordModel

private enum class Step { Welcome, Permissions, Key, LocalCloud, Wake, Done }

/**
 * Guided AMOLED Liquid Glass Onboarding Flow for JARVIS.
 * First launch starts at Welcome -> Guided Permission Setup -> Gemini Key -> Privacy Overview -> Wake -> Done.
 */
@Composable
fun Onboarding(assistant: AssistantViewModel, hasKey: Boolean, onFinished: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(if (assistant.onboarded) Step.Key.ordinal else Step.Welcome.ordinal) }
    val step = Step.entries[index]
    val next: () -> Unit = { if (index < Step.entries.lastIndex) index++ else onFinished() }

    Column(
        Modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(horizontal = 24.dp),
    ) {
        Progress(index, Modifier.padding(top = 24.dp, bottom = 12.dp))
        AnimatedContent(step, label = "onboardingStep", modifier = Modifier.weight(1f)) { shown ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 16.dp, bottom = 24.dp),
            ) {
                when (shown) {
                    Step.Welcome -> Page(
                        Branding.ASSISTANT_NAME,
                        "A private voice assistant for your phone, in English, Hindi, and Hinglish. Next, we'll set up only the Android permissions JARVIS genuinely uses.",
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                            Orb(Phase.Listening, Modifier.size(190.dp))
                        }
                        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalPadding = 14) {
                            Text(
                                "Transparent setup",
                                style = MaterialTheme.typography.titleMedium,
                                color = PureWhite,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "• Microphone & Notifications enable voice chat and hands-free wake word.\n" +
                                    "• Contacts, Calendar, Accessibility, Overlay, and Notification Access are optional and only unlock their matching features.\n" +
                                    "• Photos & Files always use Android's System Picker on demand.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Primary("Start Setup", next)
                    }

                    Step.Permissions -> Page(
                        "Permissions Setup",
                        "Allow the permissions you want JARVIS to use. Optional permissions can be skipped any time—every other feature continues working.",
                    ) {
                        PermissionsOnboardingStep(onContinue = next)
                    }

                    Step.Key -> Page(
                        "Your Gemini key",
                        "${Branding.ASSISTANT_NAME} uses Google Gemini, with your own key, for anything the phone can't handle locally.",
                    ) {
                        if (hasKey) {
                            GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                Text("Your key is saved and verified.", color = ElectricCyan, style = MaterialTheme.typography.titleMedium)
                            }
                            Primary("Continue", next)
                        } else {
                            KeyForm(onSave = { key -> assistant.saveKey(key).also { if (it == null) next() } })
                        }
                    }

                    Step.LocalCloud -> Page("What runs where", "Simple commands never leave the phone. The rest goes to Gemini.") {
                        Section(null) {
                            InfoRow("On this phone", "Volume, music, flashlight, opening apps, time, battery, timers and alarms. No internet, no Gemini.") {
                                Pill("Local", ElectricCyan)
                            }
                            InfoRow("With Gemini", "Questions, calls and messages, songs by name, your screen. Needs internet and uses your key.") {
                                Pill("Gemini", AmberWarning)
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Primary("Continue", next)
                    }

                    Step.Wake -> Page("Hands-free", "Say \"${WakeWordModel.PHRASE}\" (or \"${WakeWordModel.ALT_PHRASE}\") instead of touching the phone. Audio stays offline on this phone until you say it.") {
                        val context = LocalContext.current
                        val micGranted = PermissionRegistry.isGrantedNow(context, PermissionId.Microphone)
                        if (assistant.wakeWordAvailable && micGranted) {
                            var on by remember { mutableStateOf(assistant.wakeWordOn) }
                            Section(null) {
                                SwitchRow("Wake word (${WakeWordModel.PHRASE})", "Uses minimal battery with a quiet-room gate while awaiting phrase.", on) {
                                    on = it
                                    assistant.setWakeWord(it)
                                }
                                if (assistant.wakeOnnxAvailable) {
                                    VoiceSetupRow(assistant.voiceSaved, assistant::recordVoiceSample, assistant::saveVoice, assistant::forgetVoice)
                                }
                            }
                        } else {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (assistant.wakeWordAvailable) {
                                        "Microphone permission was not granted yet. You can still type to JARVIS, and allow Microphone later in Settings → Permissions."
                                    } else {
                                        "Offline speech model unavailable."
                                    },
                                    color = Muted,
                                )
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        Primary("Continue", next)
                    }

                    Step.Done -> Page("You're set", "Say \"${WakeWordModel.PHRASE}\" or tap the mic. Settings → Permissions lets you review or adjust access any time.") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                            Orb(Phase.Off, Modifier.size(160.dp))
                        }
                        Primary("Start", onFinished)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionsOnboardingStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { PermissionRegistry.findActivity(context) }
    var snapshot by remember { mutableStateOf(PermissionRegistry.snapshot(context, activity)) }
    val guide = remember(snapshot) { OnboardingPermissionGuide.analyze(snapshot) }

    val refresh: () -> Unit = {
        snapshot = PermissionRegistry.snapshot(context, activity)
    }

    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refresh()
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Checklist summary header card
        GlassCard(shape = RoundedCornerShape(22.dp), verticalPadding = 14) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Setup checklist",
                    style = MaterialTheme.typography.titleMedium,
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold,
                )
                Pill(
                    text = "${snapshot.grantedCount}/${snapshot.actionableTotalCount} granted",
                    color = if (guide.allEssentialReady) ElectricCyan else AmberWarning,
                )
            }
            Text(
                "Essential: ${guide.essentialGranted}/${guide.essentialTotal} ready • Optional: ${guide.optionalGranted}/${guide.optionalTotal} ready",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                snapshot.deduplicatedItems
                    .filter { it.status != PermissionStatus.NotRequired }
                    .forEach { item ->
                        Pill(
                            text = "${item.spec.title}: ${item.status.label}",
                            color = permissionStatusColor(item.status),
                            dot = item.status == PermissionStatus.Granted,
                        )
                    }
            }
        }

        // Guided next step card (uses official runtime dialog or official Android settings screen)
        val nextRuntimePerms = guide.nextRuntimeBatch.mapNotNull { it.spec.manifestPermission }
        if (nextRuntimePerms.isNotEmpty()) {
            GlassCard(shape = RoundedCornerShape(22.dp), verticalPadding = 14) {
                Text(
                    "Step 1: Runtime permissions",
                    style = MaterialTheme.typography.titleMedium,
                    color = ElectricCyan,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Android will show standard confirmation dialogs for: ${guide.nextRuntimeBatch.joinToString { it.spec.title }}. Each unlocks only its matching features.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Button(
                    onClick = {
                        val perms = nextRuntimePerms.toTypedArray()
                        PermissionRegistry.markRequested(context, *perms)
                        batchLauncher.launch(perms)
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow via Android Dialog", fontWeight = FontWeight.Bold)
                }
            }
        } else if (guide.nextSpecialSetting != null) {
            val nextSpecial = guide.nextSpecialSetting
            GlassCard(shape = RoundedCornerShape(22.dp), verticalPadding = 14) {
                Text(
                    "Next recommended setup: ${nextSpecial.spec.title}",
                    style = MaterialTheme.typography.titleMedium,
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Android requires enabling this manually in System Settings. JARVIS checks again automatically as soon as you return.\nNeeded for: ${nextSpecial.spec.dependentFeature}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                OutlinedButton(
                    onClick = {
                        launchSafeSettings(context, PermissionRegistry.settingsIntent(context, nextSpecial.spec.id))
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (nextSpecial.actionKind == PermissionActionKind.OpenSettings) {
                            "Open ${nextSpecial.spec.title} Settings"
                        } else {
                            "Manage ${nextSpecial.spec.title}"
                        },
                        color = ElectricCyan,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Denied / permanently denied guidance (never loops auto-prompts)
        if (guide.blockedOrDeniedItems.isNotEmpty()) {
            GlassCard(shape = RoundedCornerShape(22.dp), verticalPadding = 14) {
                Text(
                    "Some features need permission",
                    style = MaterialTheme.typography.titleSmall,
                    color = AmberWarning,
                    fontWeight = FontWeight.SemiBold,
                )
                guide.blockedOrDeniedItems.forEach { denied ->
                    Text(
                        "• ${denied.spec.title}: needed for ${denied.spec.dependentFeature}. ${
                            if (denied.permanentlyDenied) "Denied permanently in Android — use Open Settings below to allow." else "Tap Allow below or enable later in Settings → Permissions."
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (guide.blockedOrDeniedItems.any { it.permanentlyDenied || it.restrictedByAndroid }) {
                    TextButton(
                        onClick = { launchSafeSettings(context, PermissionRegistry.appDetailsIntent(context)) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text("Open App Settings", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Complete unified checklist with live Allow / Open Settings buttons
        PermissionCenterPanel(
            compact = true,
            showBatchAllowBar = false,
            onSnapshotChanged = { snapshot = it },
        )

        Spacer(Modifier.height(6.dp))
        Primary(
            label = when {
                guide.allActionableReady -> "Continue"
                guide.allEssentialReady -> "Continue to JARVIS Setup"
                else -> "Continue with Limited Features"
            },
            onClick = onContinue,
        )
    }
}

@Composable
private fun Page(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.displaySmall,
        color = PureWhite,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    )
    Text(
        detail,
        style = MaterialTheme.typography.bodyLarge,
        color = Muted,
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
    )
    Column(content = content)
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    val glassEnabled = currentLiquidGlassEnabled()
    val shape = remember(glassEnabled) { RoundedCornerShape(if (glassEnabled) 50 else 18) }
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Button(
        onClick = onClick,
        interactionSource = interaction,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = ElectricCyan,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .glassPressScale(interaction)
            .shadow(
                elevation = if (glassEnabled) 8.dp else 4.dp,
                shape = shape,
                ambientColor = ElectricCyan,
                spotColor = ElectricCyan,
            )
            .border(
                1.dp,
                GlassBorderGradient,
                shape,
            ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Progress(index: Int, modifier: Modifier) {
    val inactiveBrush = Brush.horizontalGradient(listOf(GlassBorderSubtle, GlassBorderSubtle))
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Step.entries.forEach {
            val active = it.ordinal <= index
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (active) {
                            Brush.horizontalGradient(listOf(ElectricCyan, NeonViolet))
                        } else {
                            inactiveBrush
                        },
                    ),
            )
        }
    }
}
