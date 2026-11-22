package app.shilex.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.shilex.android.wake.VoiceProfile
import app.shilex.android.wake.VoiceSetup
import app.shilex.android.wake.WakeWordModel
import kotlinx.coroutines.launch

/**
 * Liquid Glass Voice Setup Dialog: "Teach my voice" calibration over a blurred dark scrim.
 */
@Composable
fun VoiceSetupDialog(
    record: suspend (speakNow: () -> Unit) -> VoiceSetup.Sample,
    onSave: (List<FloatArray>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val samples = remember { mutableStateListOf<FloatArray>() }
    var status by remember { mutableStateOf("Tap Record, wait for \"Say it now\", then say \"${WakeWordModel.PHRASE}\" normally.") }
    var busy by remember { mutableStateOf(false) }
    val done = samples.size >= VoiceProfile.SAMPLES
    val setModalActive = LocalModalOverlayActive.current

    DisposableEffect(Unit) {
        setModalActive(true)
        onDispose { setModalActive(false) }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val glassEnabled = currentLiquidGlassEnabled()
        val modalShape = RoundedCornerShape(if (glassEnabled) 28.dp else 18.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .shadow(if (glassEnabled) 24.dp else 10.dp, modalShape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(modalShape)
                .background(GlassSurfaceGradient)
                .border(1.dp, GlassBorderGradient, modalShape)
                .padding(22.dp),
        ) {
            Text(
                "Teach ${WakeWordModel.PHRASE} your voice",
                style = MaterialTheme.typography.titleLarge,
                color = PureWhite,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Pill(
                text = "Sample ${minOf(samples.size + 1, VoiceProfile.SAMPLES)} of ${VoiceProfile.SAMPLES}",
                color = ElectricCyan,
                dot = false,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                status,
                color = PureWhite,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Kept only on this phone as speech features, not recordings. You can forget it any time.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(enabled = !busy, onClick = onDismiss) {
                    Text("Cancel", color = Muted, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(8.dp))
                if (done) {
                    Button(
                        onClick = {
                            onSave(samples.toList())
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = MaterialTheme.colorScheme.onPrimary),
                        shape = RoundedCornerShape(50),
                    ) { Text("Save", fontWeight = FontWeight.SemiBold) }
                } else {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            status = "Get ready…"
                            scope.launch {
                                val sample = record { status = "Say \"${WakeWordModel.PHRASE}\" now" }
                                busy = false
                                status = when (sample) {
                                    is VoiceSetup.Sample.Ok -> {
                                        samples += sample.window
                                        if (samples.size >= VoiceProfile.SAMPLES) {
                                            "Got all ${VoiceProfile.SAMPLES}. Tap Save."
                                        } else {
                                            "Got it. Tap Record for the next one."
                                        }
                                    }
                                    is VoiceSetup.Sample.Problem -> sample.message
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = MaterialTheme.colorScheme.onPrimary),
                        shape = RoundedCornerShape(50),
                    ) { Text(if (busy) "Listening…" else "Record", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** Under the wake-word switch: teach / redo / forget the user's voice. */
@Composable
fun VoiceSetupRow(
    saved: Boolean,
    record: suspend (speakNow: () -> Unit) -> VoiceSetup.Sample,
    onSave: (List<FloatArray>) -> Unit,
    onForget: () -> Unit,
) {
    var hasVoice by remember { mutableStateOf(saved) }
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (hasVoice) "Your voice profile is active." else "Not waking for you? Teach it your voice.",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { open = true }) {
            Text(if (hasVoice) "Redo" else "Teach my voice", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
        }
        if (hasVoice) {
            TextButton(onClick = {
                onForget()
                hasVoice = false
            }) { Text("Forget", color = Muted) }
        }
    }
    if (open) {
        VoiceSetupDialog(
            record = record,
            onSave = {
                onSave(it)
                hasVoice = true
            },
            onDismiss = { open = false },
        )
    }
}
