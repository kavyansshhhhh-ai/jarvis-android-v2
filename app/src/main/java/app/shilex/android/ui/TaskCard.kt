package app.shilex.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.shilex.android.task.Risk
import app.shilex.android.task.Task
import app.shilex.android.task.TaskState

/**
 * Liquid Glass Task Card: In-progress tasks, risk indicators, and real-time step execution.
 */
@Composable
fun TaskCard(task: Task, modifier: Modifier = Modifier) {
    val (label, color) = stateLabel(task.state)
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
                            color.copy(alpha = 0.35f),
                            GlassBorderSubtle,
                        ),
                    )
                } else {
                    GlassBorderGradient
                },
                shape,
            )
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.title,
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
                modifier = Modifier.weight(1f),
            )
            if (task.risk >= Risk.High) {
                Pill(
                    text = if (task.risk == Risk.Financial) "Payment: you" else "Needs you",
                    color = AmberWarning,
                    modifier = Modifier.padding(end = 6.dp),
                    dot = false,
                )
            }
            Pill(label, color)
        }

        task.steps.forEachIndexed { i, step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 1.dp),
            ) {
                StepMark(step.done)
                Text(
                    "${i + 1}. ${step.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (step.done == false) CoralError else if (step.done == true) PureWhite else ElectricCyan,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        if (task.detail.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(GlassSurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(task.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun StepMark(done: Boolean?) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        when (done) {
            null -> CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = ElectricCyan,
            )
            true -> Box(
                Modifier
                    .size(9.dp)
                    .background(ElectricCyan, CircleShape)
                    .shadow(elevation = 2.dp, shape = CircleShape, ambientColor = ElectricCyan, spotColor = ElectricCyan),
            )
            false -> Box(
                Modifier
                    .size(9.dp)
                    .background(CoralError, CircleShape),
            )
        }
    }
}

@Composable
private fun stateLabel(state: TaskState): Pair<String, Color> = when (state) {
    TaskState.Understanding -> "Understanding" to ElectricCyan
    TaskState.Planning -> "Needs your answer" to AmberWarning
    TaskState.AwaitingConfirmation -> "Awaiting your OK" to AmberWarning
    TaskState.Executing -> "Working" to ElectricCyan
    TaskState.WaitingForYou -> "Your turn" to AmberWarning
    TaskState.Completed -> "Done" to ElectricCyan
    TaskState.Failed -> "Didn't work" to CoralError
}
