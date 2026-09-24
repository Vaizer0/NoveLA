package my.noveldokusha.features.chapterslist

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import my.noveldokusha.text_to_speech.OutputFormat
import my.noveldokusha.text_to_speech.TtsVoiceCatalog
import my.noveldokusha.text_to_speech.TtsVoiceSelectorDialog
import my.noveldokusha.text_to_speech.VoiceData
import my.noveldokusha.text_to_speech.key

@Composable
internal fun AudiobookExportDialog(
    state: AudiobookDialogState.ContentChoice,
    onConfirm: (
        start: Int, end: Int, mode: String, source: String, target: String, useReaderTts: Boolean,
        voiceId: String, enginePackage: String, speed: Float, pitch: Float,
        format: OutputFormat, visualUri: Uri?,
    ) -> Unit,
    onFavoriteKeysChanged: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onChangeDirectory: () -> Unit,
) {
    val context = LocalContext.current
    val positions = remember(state.chapters) { state.chapters.map { it.position } }
    var useReaderTts by remember(state.bookUrl) { mutableStateOf(state.defaultUseReaderTts) }
    var voiceId by remember(state.bookUrl) { mutableStateOf(if (state.defaultUseReaderTts) state.readerVoiceId else state.defaultVoiceId) }
    var enginePackage by remember(state.bookUrl) { mutableStateOf(if (state.defaultUseReaderTts) state.readerEnginePackage else state.defaultEnginePackage) }
    var speed by remember(state.bookUrl) { mutableFloatStateOf(if (state.defaultUseReaderTts) state.readerSpeed else state.defaultSpeed) }
    var pitch by remember(state.bookUrl) { mutableFloatStateOf(if (state.defaultUseReaderTts) state.readerPitch else state.defaultPitch) }
    var mode by remember(state.bookUrl) { mutableStateOf("original") }
    var source by remember(state.bookUrl) { mutableStateOf(state.availableTranslations.firstOrNull()?.sourceLang.orEmpty()) }
    var target by remember(state.bookUrl) { mutableStateOf(state.availableTranslations.firstOrNull()?.targetLang.orEmpty()) }
    var startText by remember(state.bookUrl) { mutableStateOf(positions.firstOrNull()?.toString().orEmpty()) }
    var endText by remember(state.bookUrl) { mutableStateOf(positions.lastOrNull()?.toString().orEmpty()) }
    var format by remember(state.bookUrl) { mutableStateOf(if (state.defaultOutputFormat == "MP4") OutputFormat.MP4 else OutputFormat.WAV) }
    var visualUri by remember(state.bookUrl) { mutableStateOf(state.defaultVisualUri.takeIf(String::isNotBlank)) }
    var voiceDialogOpen by remember { mutableStateOf(false) }
    var favorites by remember(state.bookUrl, state.favoriteVoiceKeys) { mutableStateOf(state.favoriteVoiceKeys.toSet()) }

    val voices = remember { mutableStateListOf<VoiceData>() }
    var voicesLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        voicesLoading = true
        voices.clear()
        voices.addAll(TtsVoiceCatalog.load(context))
        voicesLoading = false
    }

    val currentVoice = voices.firstOrNull { it.id == voiceId && it.enginePackage == enginePackage }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            visualUri = uri.toString()
        }
    }

    val start = startText.toIntOrNull()
    val end = endText.toIntOrNull()
    val startChapter = state.chapters.firstOrNull { it.position == start }
    val endChapter = state.chapters.firstOrNull { it.position == end }
    val contiguous = if (start != null && end != null) (start..end).all { positions.contains(it) } else false
    val validRange = start != null && end != null && start <= end && contiguous
    val translationValid = mode == "original" || state.availableTranslations.any {
        it.sourceLang == source && it.targetLang == target && it.translatedChapters >= (end?.minus(start ?: end)?.plus(1) ?: 0)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download audiobook") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Use Reader TTS settings", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Default ON. Voice, engine, speed and pitch are taken from Reader when Generate is pressed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useReaderTts, onCheckedChange = { enabled ->
                        useReaderTts = enabled
                        if (enabled) {
                            voiceId = state.readerVoiceId
                            enginePackage = state.readerEnginePackage
                            speed = state.readerSpeed
                            pitch = state.readerPitch
                        } else {
                            voiceId = state.defaultVoiceId.ifBlank { state.readerVoiceId }
                            enginePackage = state.defaultEnginePackage.ifBlank { state.readerEnginePackage }
                            speed = state.defaultSpeed
                            pitch = state.defaultPitch
                        }
                    })
                }

                Text("Voice", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { voiceDialogOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    if (voicesLoading) Text("Loading voices…")
                    else Text(currentVoice?.let { it.language + " • " + it.id } ?: if (voiceId.isBlank()) "Reader/system default" else voiceId)
                }
                currentVoice?.let { voice ->
                    Text(
                        "Engine: " + voice.enginePackage + if (voice.needsInternet) " • Internet required" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("Chapter range", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.OutlinedTextField(
                        value = startText, onValueChange = { startText = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f), label = { Text("From chapter") }, singleLine = true,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = endText, onValueChange = { endText = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f), label = { Text("To chapter") }, singleLine = true,
                    )
                }
                Text(
                    if (!validRange) "Select existing contiguous chapter positions."
                    else "From ${startChapter!!.position}: ${startChapter.title} → ${endChapter!!.position}: ${endChapter.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (validRange) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )

                Text("Content", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = mode == "original", onClick = { mode = "original" })
                    Text("Original", Modifier.padding(top = 12.dp))
                    RadioButton(selected = mode == "translation", onClick = { mode = "translation" }, modifier = Modifier.padding(start = 12.dp))
                    Text("Translation", Modifier.padding(top = 12.dp))
                }
                if (mode == "translation") {
                    if (state.availableTranslations.isEmpty()) {
                        Text("No saved translation pairs available.", color = MaterialTheme.colorScheme.error)
                    } else {
                        state.availableTranslations.forEach { pair ->
                            val selected = pair.sourceLang == source && pair.targetLang == target
                            TextButton(onClick = { source = pair.sourceLang; target = pair.targetLang }) {
                                Text((if (selected) "✓ " else "") + pair.sourceLang + " → " + pair.targetLang + " • " + pair.translatedChapters + " chapters")
                            }
                        }
                    }
                    if (!translationValid && validRange) Text("The selected translation does not cover every chapter in this range.", color = MaterialTheme.colorScheme.error)
                }

                Text("Speed: " + "%.2f".format(speed))
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.1f..5f, enabled = !useReaderTts)
                Text("Pitch: " + "%.2f".format(pitch))
                Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.1f..5f, enabled = !useReaderTts)

                Text("Output format", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = format == OutputFormat.WAV, onClick = { format = OutputFormat.WAV })
                    Text("WAV + JSON", Modifier.padding(top = 12.dp))
                    RadioButton(selected = format == OutputFormat.MP4, onClick = { format = OutputFormat.MP4 }, modifier = Modifier.padding(start = 12.dp))
                    Text("MP4 + JSON", Modifier.padding(top = 12.dp))
                }
                if (format == OutputFormat.MP4) {
                    Text("Video visual", style = MaterialTheme.typography.titleSmall)
                    Text(visualUri?.let(Uri::parse)?.lastPathSegment ?: "No visual selected — black background.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { picker.launch(arrayOf("image/*", "video/*")) }) { Text("Choose image / GIF / video") }
                        if (visualUri != null) TextButton(onClick = { visualUri = null }) { Text("Clear") }
                    }
                }

                Text("Save folder", style = MaterialTheme.typography.titleSmall)
                Text(state.exportDirectoryName ?: "No folder selected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onChangeDirectory) { Text("Choose / change folder") }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    onConfirm(start ?: return@FilledTonalButton, end ?: return@FilledTonalButton, mode, source, target, useReaderTts, voiceId, enginePackage, speed, pitch, format, visualUri?.let(Uri::parse))
                },
                enabled = validRange && translationValid,
            ) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    TtsVoiceSelectorDialog(
        isOpen = voiceDialogOpen,
        voices = voices,
        currentVoice = currentVoice,
        favoriteKeys = favorites,
        onSelect = { selected ->
            if (useReaderTts) {
                useReaderTts = false
                speed = state.readerSpeed
                pitch = state.readerPitch
            }
            voiceId = selected.id
            enginePackage = selected.enginePackage
            voiceDialogOpen = false
        },
        onToggleFavorite = { selected ->
            val updated = favorites.toMutableSet()
            val k = selected.key()
            if (!updated.add(k)) updated.remove(k)
            favorites = updated
            onFavoriteKeysChanged(updated)
        },
        onDismiss = { voiceDialogOpen = false },
    )
}
