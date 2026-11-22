package app.shilex.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.shilex.android.Branding
import app.shilex.android.control.ControlService

/**
 * Liquid Glass Control Card: Informs and guides the user to grant phone control safely.
 */
@Composable
fun ControlCard(modifier: Modifier = Modifier, showWhenOn: Boolean = false) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(controlEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = controlEnabled(context)
        onPauseOrDispose { }
    }
    if (enabled) {
        if (showWhenOn) {
            InfoRow("Phone control", "On. Send, call, delete and pay still wait for your Allow.") {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open", color = ElectricCyan) }
            }
        }
        return
    }

    val glassEnabled = currentLiquidGlassEnabled()
    val shape = RoundedCornerShape(if (glassEnabled) 24.dp else 16.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassSurfaceGradient)
            .border(
                1.dp,
                if (glassEnabled) {
                    Brush.verticalGradient(
                        listOf(
                            GlassBorderColor,
                            ElectricCyan.copy(alpha = 0.25f),
                            GlassBorderSubtle,
                        ),
                    )
                } else {
                    GlassBorderGradient
                },
                shape,
            )
            .padding(16.dp),
    ) {
        Text("Phone control is off", color = ElectricCyan, style = MaterialTheme.typography.titleMedium)
        Text(
            "Turn it on so ${Branding.ASSISTANT_NAME} can read your screen and tap, type and scroll " +
                "when you ask, e.g. play a song or reply in WhatsApp. Send, call, delete and pay " +
                "always wait for you to tap Allow. Banking, UPI, password and Settings apps are never read.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row {
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(50),
            ) { Text("Turn on", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Switch greyed out?", color = Muted) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(6.dp))
            Text(
                "If the switch is grey: App info → ⋮ (top right) → Allow restricted settings, then try again.",
                color = Muted,
                fontSize = 11.sp,
            )
        }
    }
}

private fun controlEnabled(context: Context): Boolean {
    if (ControlService.instance != null) return true
    val wanted = ComponentName(context, ControlService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(wanted, ignoreCase = true) }
}
