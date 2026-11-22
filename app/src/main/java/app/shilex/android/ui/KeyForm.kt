package app.shilex.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Liquid Glass Key Form: The user's own Gemini key, checked with Google before it's saved.
 */
@Composable
fun KeyForm(onSave: suspend (String) -> String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Keys are made in Google AI Studio. The free tier has usage limits; paid use is billed to your Google account. " +
                "The key is checked with Google, then kept encrypted on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_PAGE)))
            },
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            Text("Get a key at Google AI Studio →", color = ElectricCyan, fontWeight = FontWeight.SemiBold)
        }

        OutlinedTextField(
            value = key,
            onValueChange = { key = it; problem = null },
            label = { Text("Gemini API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = GlassBorderSubtle,
                focusedLabelColor = ElectricCyan,
                unfocusedLabelColor = Muted,
                focusedTextColor = PureWhite,
                unfocusedTextColor = PureWhite,
                cursorColor = ElectricCyan,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        problem?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = CoralError, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !checking && key.isNotBlank(),
            onClick = {
                checking = true
                scope.launch {
                    problem = onSave(key)
                    checking = false
                }
            },
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = ElectricCyan.copy(alpha = 0.22f),
                disabledContentColor = Muted,
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                if (checking) "Checking with Google…" else "Check and save",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private const val KEY_PAGE = "https://aistudio.google.com/apikey"
