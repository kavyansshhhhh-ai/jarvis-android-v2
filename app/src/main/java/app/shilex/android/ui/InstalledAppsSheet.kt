package app.shilex.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.shilex.android.tools.AppCatalog
import app.shilex.android.tools.InstalledAppInfo

/**
 * Smoked Liquid Glass A-Z Installed Apps browser modal allowing instant search and app launch.
 */
@Composable
fun InstalledAppsDialog(
    apps: List<InstalledAppInfo>,
    onRefresh: () -> Unit,
    onLaunch: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val setOverlayActive = LocalModalOverlayActive.current
    DisposableEffect(Unit) {
        setOverlayActive(true)
        onDispose { setOverlayActive(false) }
    }

    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val shown = remember(apps, query, showSystem) {
        AppCatalog.search(query, apps, includeNonLaunchable = false)
            .filter { showSystem || !it.systemApp }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val glassEnabled = currentLiquidGlassEnabled()
        val shape = remember(glassEnabled) { RoundedCornerShape(if (glassEnabled) 28.dp else 18.dp) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 18.dp, vertical = 20.dp)
                .clip(shape)
                .background(GlassSurfaceGradient)
                .border(1.dp, GlassBorderGradient, shape)
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Installed Apps (A–Z)",
                        style = MaterialTheme.typography.titleLarge,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${shown.size} launchable apps",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Row {
                    TextButton(onClick = onRefresh) {
                        Text("Refresh", color = ElectricCyan)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Muted)
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search installed apps…", color = Muted.copy(alpha = 0.65f)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PureWhite,
                    unfocusedTextColor = PureWhite,
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = GlassBorderSubtle,
                    cursorColor = ElectricCyan,
                    focusedContainerColor = GlassSurfaceHighlight,
                    unfocusedContainerColor = GlassSurfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showSystem = !showSystem }) {
                    Text(
                        if (showSystem) "Hide system apps" else "Show system apps",
                        color = if (showSystem) ElectricCyan else Muted,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(shown, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                if (onLaunch(app.packageName)) onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = PureWhite,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                        Text("Open", color = ElectricCyan, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
