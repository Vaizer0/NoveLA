package my.noveldokusha.settings.sections

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.text_to_speech.TtsVoiceCatalog
import my.noveldokusha.text_to_speech.TtsVoiceSelectorDialog
import my.noveldokusha.text_to_speech.VoiceData
import my.noveldokusha.text_to_speech.key

@Composable
internal fun SettingsAudiobook(prefs: AppPreferences) {
    val useReaderTts by prefs.AUDIOBOOK_USE_READER_TTS.flow().collectAsState(initial = prefs.AUDIOBOOK_USE_READER_TTS.value)
    val readerVoiceId by prefs.READER_TEXT_TO_SPEECH_VOICE_ID.flow().collectAsState(initial = prefs.READER_TEXT_TO_SPEECH_VOICE_ID.value)
    val readerEngine by prefs.READER_TEXT_TO_SPEECH_VOICE_ENGINE.flow().collectAsState(initial = prefs.READER_TEXT_TO_SPEECH_VOICE_ENGINE.value)
    val readerSpeed by prefs.READER_TEXT_TO_SPEECH_VOICE_SPEED.flow().collectAsState(initial = prefs.READER_TEXT_TO_SPEECH_VOICE_SPEED.value)
    val readerPitch by prefs.READER_TEXT_TO_SPEECH_VOICE_PITCH.flow().collectAsState(initial = prefs.READER_TEXT_TO_SPEECH_VOICE_PITCH.value)
    val audioVoiceId by prefs.AUDIOBOOK_TTS_VOICE_ID.flow().collectAsState(initial = prefs.AUDIOBOOK_TTS_VOICE_ID.value)
    val audioEngine by prefs.AUDIOBOOK_TTS_VOICE_ENGINE.flow().collectAsState(initial = prefs.AUDIOBOOK_TTS_VOICE_ENGINE.value)
    val audioSpeed by prefs.AUDIOBOOK_TTS_VOICE_SPEED.flow().collectAsState(initial = prefs.AUDIOBOOK_TTS_VOICE_SPEED.value)
    val audioPitch by prefs.AUDIOBOOK_TTS_VOICE_PITCH.flow().collectAsState(initial = prefs.AUDIOBOOK_TTS_VOICE_PITCH.value)
    val output by prefs.AUDIOBOOK_OUTPUT_FORMAT.flow().collectAsState(initial = prefs.AUDIOBOOK_OUTPUT_FORMAT.value)
    val visual by prefs.AUDIOBOOK_VISUAL_URI.flow().collectAsState(initial = prefs.AUDIOBOOK_VISUAL_URI.value)
    val folder by prefs.AUDIOBOOK_EXPORT_DIRECTORY_URI.flow().collectAsState(initial = prefs.AUDIOBOOK_EXPORT_DIRECTORY_URI.value)
    val favorites by prefs.AUDIOBOOK_FAVORITE_VOICE_KEYS.flow().collectAsState(initial = prefs.AUDIOBOOK_FAVORITE_VOICE_KEYS.value)

    var voiceDialogOpen by remember { mutableStateOf(false) }
    val voices = remember { mutableStateListOf<VoiceData>() }
    LaunchedEffect(Unit) { voices.addAll(TtsVoiceCatalog.load(prefs.context)) }

    val activeVoiceId = if (useReaderTts) readerVoiceId else audioVoiceId
    val activeEngine = if (useReaderTts) readerEngine else audioEngine
    val activeSpeed = if (useReaderTts) readerSpeed else audioSpeed
    val activePitch = if (useReaderTts) readerPitch else audioPitch
    val activeVoice = voices.firstOrNull { it.id == activeVoiceId && it.enginePackage == activeEngine }

    val folderName = remember(folder) {
        if (folder.isBlank()) null else runCatching {
            val tree = Uri.parse(folder)
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            prefs.context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0).takeIf(String::isNotBlank) else null
            }
        }.getOrNull()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                prefs.context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.AUDIOBOOK_EXPORT_DIRECTORY_URI.value = uri.toString()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Audiobook / audio download", style = MaterialTheme.typography.titleMedium)
        Text(
            "Choose the audiobook TTS defaults and output folder here. Chapter range is selected when you start a download.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Use Reader TTS settings", style = MaterialTheme.typography.titleSmall)
                Text(
                    "ON by default — current Reader voice, engine, speed and pitch are used when exporting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = useReaderTts,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        prefs.AUDIOBOOK_TTS_VOICE_ID.value = readerVoiceId
                        prefs.AUDIOBOOK_TTS_VOICE_ENGINE.value = readerEngine
                        prefs.AUDIOBOOK_TTS_VOICE_SPEED.value = readerSpeed
                        prefs.AUDIOBOOK_TTS_VOICE_PITCH.value = readerPitch
                    }
                    prefs.AUDIOBOOK_USE_READER_TTS.value = enabled
                },
            )
        }

        OutlinedButton(
            onClick = { voiceDialogOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(activeVoice?.let { it.language + " • " + it.id } ?: if (activeVoiceId.isBlank()) "Reader/system default" else activeVoiceId)
        }
        activeVoice?.let {
            Text(
                "Engine: " + it.enginePackage + if (it.needsInternet) " • Internet required" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("Speed: " + "%.2f".format(activeSpeed))
        Slider(
            value = activeSpeed,
            onValueChange = { prefs.AUDIOBOOK_TTS_VOICE_SPEED.value = it },
            valueRange = 0.1f..5f,
            enabled = !useReaderTts,
        )
        Text("Pitch: " + "%.2f".format(activePitch))
        Slider(
            value = activePitch,
            onValueChange = { prefs.AUDIOBOOK_TTS_VOICE_PITCH.value = it },
            valueRange = 0.1f..5f,
            enabled = !useReaderTts,
        )

        Text("Default output format", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = output != "MP4", onClick = { prefs.AUDIOBOOK_OUTPUT_FORMAT.value = "WAV" })
            Text("WAV + JSON")
            RadioButton(selected = output == "MP4", onClick = { prefs.AUDIOBOOK_OUTPUT_FORMAT.value = "MP4" }, modifier = Modifier.padding(start = 12.dp))
            Text("MP4 + JSON")
        }

        Text("Audiobook save folder", style = MaterialTheme.typography.titleSmall)
        Text(
            folderName ?: "No folder selected — choose a folder before the first download.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose / change folder") }
            if (folder.isNotBlank()) {
                TextButton(onClick = { prefs.AUDIOBOOK_EXPORT_DIRECTORY_URI.value = "" }) { Text("Clear") }
            }
        }

        if (output == "MP4") {
            Text("Default video visual", style = MaterialTheme.typography.titleSmall)
            Text(
                visual.substringAfterLast('/').ifBlank { "None — black background will be used." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(
            onClick = {
                prefs.AUDIOBOOK_USE_READER_TTS.value = true
            },
        ) {
            Text("Reset audiobook TTS to Reader")
        }
    }

    TtsVoiceSelectorDialog(
        isOpen = voiceDialogOpen,
        voices = voices,
        currentVoice = activeVoice,
        favoriteKeys = favorites.toSet(),
        onSelect = { selected ->
            prefs.AUDIOBOOK_USE_READER_TTS.value = false
            prefs.AUDIOBOOK_TTS_VOICE_ID.value = selected.id
            prefs.AUDIOBOOK_TTS_VOICE_ENGINE.value = selected.enginePackage
            voiceDialogOpen = false
        },
        onToggleFavorite = { selected ->
            val set = favorites.toMutableSet()
            val key = selected.key()
            if (!set.add(key)) set.remove(key)
            prefs.AUDIOBOOK_FAVORITE_VOICE_KEYS.value = set.toList()
        },
        onDismiss = { voiceDialogOpen = false },
    )
}
