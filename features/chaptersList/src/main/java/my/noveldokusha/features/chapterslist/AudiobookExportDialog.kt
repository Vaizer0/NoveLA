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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import my.noveldokusha.text_to_speech.OutputFormat

@Composable
internal fun AudiobookExportDialog(
    state: AudiobookDialogState.ContentChoice,
    onConfirm: (
        start: Int,
        end: Int,
        mode: String,
        source: String,
        target: String,
        voiceId: String,
        enginePackage: String,
        speed: Float,
        pitch: Float,
        format: OutputFormat,
        visualUri: Uri?,
    ) -> Unit,
    onDismiss: () -> Unit,
    onChangeDirectory: () -> Unit,
) {
    val context = LocalContext.current
    val chapterPositions = remember(state.chapters) { state.chapters.map { it.position } }
    var mode by remember(state.bookUrl) { mutableStateOf("original") }
    var startText by remember(state.bookUrl) { mutableStateOf(chapterPositions.firstOrNull()?.toString().orEmpty()) }
    var endText by remember(state.bookUrl) { mutableStateOf(chapterPositions.lastOrNull()?.toString().orEmpty()) }
    var source by remember(state.bookUrl) { mutableStateOf(state.availableTranslations.firstOrNull()?.sourceLang.orEmpty()) }
    var target by remember(state.bookUrl) { mutableStateOf(state.availableTranslations.firstOrNull()?.targetLang.orEmpty()) }
    var voiceId by remember(state.bookUrl) { mutableStateOf(state.defaultVoiceId) }
    var engine by remember(state.bookUrl) { mutableStateOf(state.defaultEnginePackage) }
    var speed by remember(state.bookUrl) { mutableFloatStateOf(state.defaultSpeed) }
    var pitch by remember(state.bookUrl) { mutableFloatStateOf(state.defaultPitch) }
    var formatName by remember(state.bookUrl) { mutableStateOf(state.defaultOutputFormat.ifBlank { "WAV" }) }
    var visualUri by remember(state.bookUrl) { mutableStateOf(state.defaultVisualUri.takeIf(String::isNotBlank)) }
    var pairExpanded by remember { mutableStateOf(false) }
    var visualMenu by remember { mutableStateOf(false) }

    val visualPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            visualUri = uri.toString()
        }
    }

    val parsedStart = startText.toIntOrNull()
    val parsedEnd = endText.toIntOrNull()
    val startChapter = state.chapters.firstOrNull { it.position == parsedStart }
    val endChapter = state.chapters.firstOrNull { it.position == parsedEnd }
    val validRange = parsedStart != null && parsedEnd != null && parsedStart <= parsedEnd &&
        startChapter != null && endChapter != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download audiobook") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Content", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = mode == "original", onClick = { mode = "original" })
                    Text("Original", modifier = Modifier.padding(top = 12.dp))
                    RadioButton(
                        selected = mode == "translation",
                        onClick = { mode = "translation" },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    Text("Translation", modifier = Modifier.padding(top = 12.dp))
                }

                if (mode == "translation") {
                    val pairLabel = if (source.isBlank() || target.isBlank()) {
                        "Select language pair"
                    } else {
                        source + " → " + target
                    }
                    FilledTonalButton(
                        onClick = { pairExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(pairLabel)
                    }
                    DropdownMenu(
                        expanded = pairExpanded,
                        onDismissRequest = { pairExpanded = false },
                    ) {
                        state.availableTranslations.forEach { pair ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        pair.sourceLang + " → " + pair.targetLang +
                                            " (" + pair.translatedChapters + ")"
                                    )
                                },
                                onClick = {
                                    source = pair.sourceLang
                                    target = pair.targetLang
                                    pairExpanded = false
                                },
                            )
                        }
                    }
                }

                Text("Chapter range", style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Start") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it.filter(Char::isDigit) },
                        modifier = Modifier.weight(1f),
                        label = { Text("End") },
                        singleLine = true,
                    )
                }
                Text(
                    text = if (!validRange) {
                        "Enter valid chapter positions."
                    } else {
                        startChapter!!.title + " → " + endChapter!!.title
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("TTS voice", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = voiceId,
                    onValueChange = { voiceId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Voice ID") },
                    supportingText = {
                        Text("Use the same Android TTS voice ID used by the reader.")
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = engine,
                    onValueChange = { engine = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TTS engine package (optional)") },
                    singleLine = true,
                )

                Text(
                    "Speed: " + "%.2f".format(speed),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 0.25f..3f,
                )
                Text(
                    "Pitch: " + "%.2f".format(pitch),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    valueRange = 0.5f..1.5f,
                )

                Text("Output", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth()) {
                    RadioButton(selected = formatName == "WAV", onClick = { formatName = "WAV" })
                    Text("WAV + JSON", modifier = Modifier.padding(top = 12.dp))
                    RadioButton(
                        selected = formatName == "MP4",
                        onClick = { formatName = "MP4" },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Text("MP4 + JSON", modifier = Modifier.padding(top = 12.dp))
                }

                if (formatName == "MP4") {
                    Text("Video visual", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = visualUri?.let(Uri::parse)?.lastPathSegment
                            ?: "No visual selected; a black background will be used.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { visualMenu = true }) { Text("Choose") }
                        if (visualUri != null) {
                            TextButton(onClick = { visualUri = null }) { Text("Clear") }
                        }
                    }
                    DropdownMenu(
                        expanded = visualMenu,
                        onDismissRequest = { visualMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Image / GIF / Video") },
                            onClick = {
                                visualMenu = false
                                visualPicker.launch(arrayOf("image/*", "video/*"))
                            },
                        )
                    }
                }

                Text(
                    text = "Export folder: " + (state.exportDirectoryName ?: "Not set"),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onChangeDirectory) { Text("Change folder") }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val selectedFormat =
                        if (formatName == "MP4") OutputFormat.MP4 else OutputFormat.WAV
                    val start = parsedStart ?: return@FilledTonalButton
                    val end = parsedEnd ?: return@FilledTonalButton
                    onConfirm(
                        start,
                        end,
                        mode,
                        source,
                        target,
                        voiceId,
                        engine,
                        speed,
                        pitch,
                        selectedFormat,
                        visualUri?.let(Uri::parse),
                    )
                },
                enabled = validRange && (mode == "original" || (source.isNotBlank() && target.isNotBlank())),
            ) {
                Text("Generate")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
