package app.shilex.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shilex.android.local.ActivityLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SingleCardShape = RoundedCornerShape(24.dp)
private val FirstCardShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
private val LastCardShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

/** What the assistant did recently, newest first; kept in memory only. */
@Composable
fun ActivityScreen(entries: List<ActivityLog.Entry>, onClear: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Activity",
                style = MaterialTheme.typography.headlineSmall,
                color = PureWhite,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("Clear", color = CoralError, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            "What was done, not what was said. Cleared when the app closes.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(bottom = 14.dp),
        )

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .padding(bottom = 96.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlassCard(horizontalPadding = 24, verticalPadding = 20) {
                    Text(
                        "Nothing yet. What you ask shows up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                    )
                }
            }
            return
        }

        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
        val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.ENGLISH) }
        val bottomPadding = floatingDockContentBottomPadding()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            itemsIndexed(
                items = entries,
                key = { index, entry -> "${entry.at}_${entry.local}_${entry.what.hashCode()}_$index" },
            ) { idx, entry ->
                val isFirst = idx == 0
                val isLast = idx == entries.lastIndex
                val rowShape = when {
                    isFirst && isLast -> SingleCardShape
                    isFirst -> FirstCardShape
                    isLast -> LastCardShape
                    else -> RectangleShape
                }
                val sideColor = GlassBorderSubtle
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(rowShape)
                        .background(GlassSurfaceGradient)
                        .drawBehind {
                            val strokePx = 1.dp.toPx()
                            // Side hairline strokes keep contiguous card silhouette without double seams
                            drawLine(sideColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = strokePx)
                            drawLine(sideColor, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = strokePx)
                            if (isFirst) {
                                drawLine(Color(0x47FFFFFF), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = strokePx)
                            }
                            if (isLast) {
                                drawLine(Color(0x28FFFFFF), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = strokePx)
                            }
                        }
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = if (isFirst) 6.dp else 0.dp,
                            bottom = if (isLast) 6.dp else 0.dp,
                        ),
                ) {
                    ActivityRow(entry = entry, timeFormat = timeFormatter)
                    if (!isLast) {
                        GlassDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityRow(entry: ActivityLog.Entry, timeFormat: SimpleDateFormat? = null) {
    val formatter = timeFormat ?: remember { SimpleDateFormat("h:mm a", Locale.ENGLISH) }
    val formattedTime = remember(entry.at, formatter) {
        formatter.format(Date(entry.at))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.what,
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                entry.result,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Pill(
                if (entry.local) "On phone" else "Gemini",
                if (entry.local) ElectricCyan else AmberWarning,
                dot = false,
            )
            Text(
                formattedTime,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
