package my.noveldokusha.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import my.noveldokusha.core.appPreferences.AppPreferences

@Composable
internal fun SettingsAudiobook(
    prefs: AppPreferences,
) {
    val voiceId by prefs.AUDIOBOOK_TTS_VOICE_ID.flow()
        .collectAsState(initial = prefs.AUDIOBOOK_TTS_VOICE_ID.value)
    val engine by prefs.AUDIOBOOK_TTS_VOICE_ENGINE.flow()
        .collectAsState(initial = prefs.AUDIOBOOK_TTS_VOICE_ENGINE.value)
    val speed by prefs.AUDIOBOOK_TTS_VOICE_SPEED.flow()
        .collectAsState(initial = prefs.AUDIOBOOK_TTS_VOICE_SPEED.value)
    val pitch by prefs.AUDIOBOOK_TTS_VOICE_PITCH.flow()
        .collectAsState(initial = prefs.AUDIOBOOK_TTS_VOICE_PITCH.value)
    val output by prefs.AUDIOBOOK_OUTPUT_FORMAT.flow()
        .collectAsState(initial = prefs.AUDIOBOOK_OUTPUT_FORMAT.value)
    val visual by prefs.AUDIOBOOK_VISUAL_URI.flow()
        .collectAsState(initial = prefs.AUDIOBOOK_VISUAL_URI.value)
    val context = LocalContext.current

    val visualPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            prefs.AUDIOBOOK_VISUAL_URI.value = uri.toString()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Audiobook / audio download", style = MaterialTheme.typography.titleMedium)
        Text(
            "Defaults used when generating merged chapter audio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = voiceId,
            onValueChange = { prefs.AUDIOBOOK_TTS_VOICE_ID.value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("TTS voice ID") },
            singleLine = true,
        )
        OutlinedTextField(
            value = engine,
            onValueChange = { prefs.AUDIOBOOK_TTS_VOICE_ENGINE.value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("TTS engine package") },
            singleLine = true,
        )

        Text("Speed: " + "%.2f".format(speed))
        Slider(
            value = speed,
            onValueChange = { prefs.AUDIOBOOK_TTS_VOICE_SPEED.value = it },
            valueRange = 0.25f..3f,
        )

        Text("Pitch: " + "%.2f".format(pitch))
        Slider(
            value = pitch,
            onValueChange = { prefs.AUDIOBOOK_TTS_VOICE_PITCH.value = it },
            valueRange = 0.5f..1.5f,
        )

        Text("Default output format")
        Row {
            RadioButton(
                selected = output != "MP4",
                onClick = { prefs.AUDIOBOOK_OUTPUT_FORMAT.value = "WAV" },
            )
            Text("WAV + JSON", modifier = Modifier.padding(top = 12.dp))
            RadioButton(
                selected = output == "MP4",
                onClick = { prefs.AUDIOBOOK_OUTPUT_FORMAT.value = "MP4" },
                modifier = Modifier.padding(start = 12.dp),
            )
            Text("MP4 + JSON", modifier = Modifier.padding(top = 12.dp))
        }

        if (visual.isNotBlank()) {
            Text("Default video visual: " + (visual.substringAfterLast('/').ifBlank { "selected" }))
            Text(
                "The file is stored by URI; the original image/video is not copied into settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "No default video visual selected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    visualPicker.launch(arrayOf("image/*", "video/*"))
                },
            ) {
                Text("Choose video visual")
            }
            if (visual.isNotBlank()) {
                FilledTonalButton(
                    onClick = { prefs.AUDIOBOOK_VISUAL_URI.value = "" },
                ) {
                    Text("Clear")
                }
            }
        }

        FilledTonalButton(
            onClick = {
                prefs.AUDIOBOOK_TTS_VOICE_ID.value = prefs.READER_TEXT_TO_SPEECH_VOICE_ID.value
                prefs.AUDIOBOOK_TTS_VOICE_ENGINE.value = prefs.READER_TEXT_TO_SPEECH_VOICE_ENGINE.value
                prefs.AUDIOBOOK_TTS_VOICE_SPEED.value = prefs.READER_TEXT_TO_SPEECH_VOICE_SPEED.value
                prefs.AUDIOBOOK_TTS_VOICE_PITCH.value = prefs.READER_TEXT_TO_SPEECH_VOICE_PITCH.value
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Use current reader TTS settings")
        }
    }
}
