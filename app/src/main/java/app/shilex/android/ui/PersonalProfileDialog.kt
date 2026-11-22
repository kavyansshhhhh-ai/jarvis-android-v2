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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.shilex.android.data.PersonalProfile

/**
 * Smoked Liquid Glass Personal Profile editor modal stored only on this phone.
 */
@Composable
fun PersonalProfileDialog(
    initial: PersonalProfile,
    onSave: (PersonalProfile) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val setOverlayActive = LocalModalOverlayActive.current
    DisposableEffect(Unit) {
        setOverlayActive(true)
        onDispose { setOverlayActive(false) }
    }

    var preferredName by remember { mutableStateOf(initial.preferredName) }
    var addressAs by remember { mutableStateOf(initial.addressAs) }
    var favoriteSongs by remember { mutableStateOf(initial.favoriteSongs.joinToString(", ")) }
    var favoriteArtists by remember { mutableStateOf(initial.favoriteArtists.joinToString(", ")) }
    var favoriteGenres by remember { mutableStateOf(initial.favoriteGenres.joinToString(", ")) }
    var preferredMusicApp by remember { mutableStateOf(initial.preferredMusicApp) }
    var frequentApps by remember { mutableStateOf(initial.frequentApps.joinToString(", ")) }
    var preferredLanguage by remember { mutableStateOf(initial.preferredLanguage) }
    var responseStyle by remember { mutableStateOf(initial.responseStyle) }
    var customNotes by remember { mutableStateOf(initial.customNotes) }

    fun splitCsv(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val glassEnabled = currentLiquidGlassEnabled()
        val shape = RoundedCornerShape(if (glassEnabled) 28.dp else 18.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .clip(shape)
                .background(GlassSurfaceGradient)
                .border(1.dp, GlassBorderGradient, shape)
                .padding(20.dp),
        ) {
            Text(
                "Personal Profile",
                style = MaterialTheme.typography.titleLarge,
                color = PureWhite,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Saved privately on this phone only. Helps JARVIS play your favourite songs, pick your music app, and match your style.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProfileField("Preferred name", "e.g. Alex", preferredName) { preferredName = it }
                ProfileField("Address you as", "e.g. Boss, Sir, or first name", addressAs) { addressAs = it }
                ProfileField("Favourite songs (comma-separated)", "e.g. Kesariya, Blinding Lights", favoriteSongs) { favoriteSongs = it }
                ProfileField("Favourite artists (comma-separated)", "e.g. Arijit Singh, The Weeknd", favoriteArtists) { favoriteArtists = it }
                ProfileField("Favourite genres", "e.g. Lo-fi, Indie, Sufi", favoriteGenres) { favoriteGenres = it }
                ProfileField("Preferred music app", "e.g. Spotify or YouTube Music", preferredMusicApp) { preferredMusicApp = it }
                ProfileField("Frequent apps", "e.g. WhatsApp, Spotify, Maps", frequentApps) { frequentApps = it }
                ProfileField("Preferred language", "e.g. Hinglish, English, Hindi", preferredLanguage) { preferredLanguage = it }
                ProfileField("Response style", "e.g. Short and direct", responseStyle) { responseStyle = it }
                ProfileField("Other harmless preferences", "Anything helpful to remember", customNotes) { customNotes = it }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (!initial.isEmpty) {
                    TextButton(
                        onClick = {
                            onClear()
                            onDismiss()
                        },
                    ) {
                        Text("Clear", color = CoralError, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Spacer(Modifier.height(1.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Muted)
                    }
                    Button(
                        onClick = {
                            onSave(
                                PersonalProfile(
                                    preferredName = preferredName,
                                    addressAs = addressAs,
                                    favoriteSongs = splitCsv(favoriteSongs),
                                    favoriteArtists = splitCsv(favoriteArtists),
                                    favoriteGenres = splitCsv(favoriteGenres),
                                    preferredMusicApp = preferredMusicApp,
                                    frequentApps = splitCsv(frequentApps),
                                    preferredLanguage = preferredLanguage,
                                    responseStyle = responseStyle,
                                    customNotes = customNotes,
                                ),
                            )
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Muted.copy(alpha = 0.6f)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = PureWhite,
            unfocusedTextColor = PureWhite,
            focusedBorderColor = ElectricCyan,
            unfocusedBorderColor = GlassBorderSubtle,
            focusedLabelColor = ElectricCyan,
            unfocusedLabelColor = Muted,
            cursorColor = ElectricCyan,
            focusedContainerColor = GlassSurfaceHighlight,
            unfocusedContainerColor = GlassSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
