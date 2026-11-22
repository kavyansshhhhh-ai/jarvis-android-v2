package app.shilex.android.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.shilex.android.permissions.PermissionActionKind
import app.shilex.android.permissions.PermissionCategory
import app.shilex.android.permissions.PermissionId
import app.shilex.android.permissions.PermissionRegistry
import app.shilex.android.permissions.PermissionSnapshot
import app.shilex.android.permissions.PermissionStateItem
import app.shilex.android.permissions.PermissionStatus

/**
 * Unified Liquid Glass Permission Center backed strictly by [PermissionRegistry].
 * Used by both the dedicated Settings -> Permissions screen and first-launch Onboarding.
 */
@Composable
fun PermissionCenterPanel(
    compact: Boolean = false,
    showBatchAllowBar: Boolean = true,
    onSnapshotChanged: (PermissionSnapshot) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = remember(context) { PermissionRegistry.findActivity(context) }
    var snapshot by remember { mutableStateOf(PermissionRegistry.snapshot(context, activity)) }

    val refresh: () -> Unit = {
        val next = PermissionRegistry.snapshot(context, activity)
        snapshot = next
        onSnapshotChanged(next)
    }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refresh()
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (snapshot.revokedItems.isNotEmpty()) {
            GlassCard(shape = RoundedCornerShape(20.dp), verticalPadding = 12) {
                Text(
                    "Permission turned off in Android Settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = CoralError,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${snapshot.revokedItems.joinToString { it.spec.title }} was disabled. Unrelated features continue working normally; tap Open Settings to re-enable it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (showBatchAllowBar) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${snapshot.grantedCount} of ${snapshot.actionableTotalCount} permissions granted",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan,
                )
                if (snapshot.missingRuntime.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            val perms = snapshot.missingRuntime
                                .mapNotNull { it.spec.manifestPermission }
                                .toTypedArray()
                            if (perms.isNotEmpty()) {
                                PermissionRegistry.markRequested(context, *perms)
                                runtimeLauncher.launch(perms)
                            }
                        },
                    ) {
                        Text("Allow runtime permissions", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        GlassCard(shape = RoundedCornerShape(22.dp), verticalPadding = if (compact) 8 else 12) {
            snapshot.deduplicatedItems.forEachIndexed { idx, item ->
                if (idx > 0) GlassDivider()
                PermissionItemRow(
                    item = item,
                    onPrimaryAction = {
                        when (item.actionKind) {
                            PermissionActionKind.AllowRuntime -> {
                                val perm = item.spec.manifestPermission
                                if (perm != null) {
                                    PermissionRegistry.markRequested(context, perm)
                                    runtimeLauncher.launch(arrayOf(perm))
                                } else {
                                    launchSafeSettings(context, PermissionRegistry.settingsIntent(context, item.spec.id))
                                }
                            }
                            PermissionActionKind.OpenSettings,
                            PermissionActionKind.ManageSettings -> {
                                launchSafeSettings(context, PermissionRegistry.settingsIntent(context, item.spec.id))
                            }
                            PermissionActionKind.None -> Unit
                        }
                    },
                    onRestrictedHelp = {
                        launchSafeSettings(context, PermissionRegistry.appDetailsIntent(context))
                    },
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionStateItem,
    onPrimaryAction: () -> Unit,
    onRestrictedHelp: () -> Unit,
) {
    val statusColor = permissionStatusColor(item.status)
    val typeTag = when (item.spec.category) {
        PermissionCategory.Runtime -> "Runtime permission"
        PermissionCategory.SpecialSetting -> "Android setting"
        PermissionCategory.SystemPicker -> "System Picker"
    }
    val showRestrictedHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !item.isReady &&
        (item.spec.id == PermissionId.AccessibilityControl || item.spec.id == PermissionId.NotificationAccess)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            GlassRowIcon(
                icon = iconForPermission(item.spec.id),
                tint = if (item.isReady) ElectricCyan else statusColor,
                accentGlow = item.status == PermissionStatus.Granted,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        item.spec.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = PureWhite,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Pill(item.status.label, statusColor)
                }
                Text(
                    "$typeTag • ${item.spec.dependentFeature}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    item.statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        if (item.actionKind != PermissionActionKind.None || showRestrictedHint) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 46.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (item.actionKind) {
                    PermissionActionKind.AllowRuntime -> {
                        Button(
                            onClick = onPrimaryAction,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text(item.actionLabel, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    PermissionActionKind.OpenSettings -> {
                        OutlinedButton(
                            onClick = onPrimaryAction,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(item.actionLabel, color = ElectricCyan, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    PermissionActionKind.ManageSettings -> {
                        TextButton(onClick = onPrimaryAction) {
                            Text(item.actionLabel, color = Muted, fontWeight = FontWeight.Medium)
                        }
                    }
                    PermissionActionKind.None -> Unit
                }

                if (showRestrictedHint) {
                    TextButton(onClick = onRestrictedHelp) {
                        Text("Greyed out? App Info", color = Muted)
                    }
                }
            }
        }

        if (showRestrictedHint) {
            Text(
                "Android 13+ tip: If the toggle is greyed out, open App Info → ⋮ (top right) → Allow restricted settings.",
                style = MaterialTheme.typography.labelSmall,
                color = Muted.copy(alpha = 0.85f),
                modifier = Modifier
                    .padding(start = 46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GlassSurfaceVariant)
                    .border(0.6.dp, GlassBorderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun permissionStatusColor(status: PermissionStatus): Color = when (status) {
    PermissionStatus.Granted -> ElectricCyan
    PermissionStatus.NotGranted -> AmberWarning
    PermissionStatus.NeedsSetup -> AmberWarning
    PermissionStatus.RestrictedByAndroid -> CoralError
    PermissionStatus.NotRequired -> SlateMuted
}

private fun iconForPermission(id: PermissionId): ImageVector = when (id) {
    PermissionId.Microphone -> MicIcon
    PermissionId.Notifications -> Icons.Filled.Notifications
    PermissionId.Contacts -> Icons.Filled.Person
    PermissionId.PhoneCalls -> Icons.Filled.Phone
    PermissionId.GalleryMedia -> Icons.Filled.Star
    PermissionId.Calendar -> Icons.Filled.DateRange
    PermissionId.AccessibilityControl -> Icons.Filled.Build
    PermissionId.NotificationAccess -> Icons.Filled.Notifications
    PermissionId.Overlay -> Icons.Filled.Star
    PermissionId.BatteryOptimization -> Icons.Filled.Refresh
    PermissionId.ExactAlarms -> Icons.Filled.Settings
    PermissionId.FilesAndPhotosPicker -> Icons.Filled.Info
}

fun launchSafeSettings(context: Context, intent: Intent) {
    val launched = runCatching { context.startActivity(intent) }.isSuccess
    if (!launched) {
        runCatching { context.startActivity(PermissionRegistry.appDetailsIntent(context)) }
    }
}
