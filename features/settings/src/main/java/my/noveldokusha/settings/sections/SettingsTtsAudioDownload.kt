package my.noveldokusha.settings.sections

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.coreui.components.PillSlider
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.coreui.theme.colorAccent
import my.noveldokusha.coreui.theme.textPadding
import my.noveldokusha.strings.R as StringsR
import kotlin.coroutines.resume

/**
 * Настройки «Загрузка аудио»: голос (выделенный TTS-инстанс, независимый от
 * живой озвучки), скорость/высота, источник текста и папка назначения (SAF).
 *
 * Голоса перечисляются ВЫДЕЛЕННЫМ probe-инстансом TextToSpeech (не общим
 * AppTtsEngine), поэтому настройка никогда не трогает озвучку читалки.
 */
@Composable
internal fun SettingsTtsAudioDownload(
    voiceId: String,
    voiceEngine: String,
    speed: Float,
    pitch: Float,
    source: TtsAudioSource,
    speedRange: ClosedFloatingPointRange<Float>,
    pitchRange: ClosedFloatingPointRange<Float>,
    onVoiceChange: (enginePackage: String, voiceId: String) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onSourceChange: (TtsAudioSource) -> Unit,
    onSelectDirectory: () -> Unit,
    directoryDisplayName: String,
) {
    var openSourceDialog by remember { mutableStateOf(false) }
    var openVoiceDialog by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(StringsR.string.settings_audio_download_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.textPadding(),
            color = colorAccent()
        )
        HorizontalDivider()

        // Voice + engine
        SlimListItem(
            headlineContent = {
                Text(text = stringResource(StringsR.string.settings_audio_download_voice))
            },
            supportingContent = {
                Text(
                    text = voiceId.ifBlank { stringResource(StringsR.string.tts_audio_voice_not_set) },
                    maxLines = 1,
                )
            },
            leadingContent = {
                Icon(Icons.Outlined.RecordVoiceOver, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.clickable { openVoiceDialog = true }
        )

        // Speed
        var localSpeed by remember { mutableStateOf(speed) }
        LaunchedEffect(speed) { localSpeed = speed }
        PillSlider(
            label = stringResource(StringsR.string.settings_audio_download_speed),
            value = localSpeed,
            valueRange = speedRange,
            onValueChange = { localSpeed = it },
            onValueChangeFinished = { onSpeedChange(localSpeed) },
            valueText = "%.2f".format(localSpeed),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        // Pitch
        var localPitch by remember { mutableStateOf(pitch) }
        LaunchedEffect(pitch) { localPitch = pitch }
        PillSlider(
            label = stringResource(StringsR.string.settings_audio_download_pitch),
            value = localPitch,
            valueRange = pitchRange,
            onValueChange = { localPitch = it },
            onValueChangeFinished = { onPitchChange(localPitch) },
            valueText = "%.2f".format(localPitch),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        // Text source
        SlimListItem(
            headlineContent = {
                Text(text = stringResource(StringsR.string.settings_audio_download_source))
            },
            supportingContent = {
                Text(text = sourceLabel(source))
            },
            leadingContent = {
                Icon(Icons.Outlined.LibraryMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.clickable { openSourceDialog = true }
        )

        // Destination folder (SAF)
        SlimListItem(
            headlineContent = {
                Text(text = stringResource(StringsR.string.settings_audio_download_folder))
            },
            supportingContent = {
                Text(
                    text = directoryDisplayName.ifBlank {
                        stringResource(StringsR.string.tts_audio_download_folder_is_not_within_library)
                    }
                )
            },
            leadingContent = {
                Icon(Icons.Outlined.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.clickable { onSelectDirectory() }
        )
    }

    if (openSourceDialog) {
        AlertDialog(
            onDismissRequest = { openSourceDialog = false },
            title = { Text(text = stringResource(StringsR.string.settings_audio_download_source)) },
            text = {
                Column {
                    TtsAudioSource.entries.forEach { entry ->
                        val selected = entry == source
                        SlimListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = sourceLabel(entry),
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSourceChange(entry)
                                    openSourceDialog = false
                                }
                        )
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { openSourceDialog = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (openVoiceDialog) {
        VoicePickerDialog(
            initialVoiceId = voiceId,
            onDismiss = { openVoiceDialog = false },
            onPick = { engine, voice ->
                onVoiceChange(engine, voice)
                openVoiceDialog = false
            }
        )
    }
}

@Composable
private fun sourceLabel(source: TtsAudioSource): String = stringResource(
    when (source) {
        TtsAudioSource.ORIGINAL -> StringsR.string.tts_audio_source_original
        TtsAudioSource.TRANSLATED -> StringsR.string.tts_audio_source_translated
        TtsAudioSource.ASK_EVERY_TIME -> StringsR.string.tts_audio_source_ask_every_time
    }
)

/** Диалог выбора голоса для загрузки аудио (отдельный TTS-инстанс-пробник). */
@Composable
private fun VoicePickerDialog(
    initialVoiceId: String,
    onDismiss: () -> Unit,
    onPick: (enginePackage: String, voiceId: String) -> Unit,
) {
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<VoiceEntry>>(emptyList()) }
    var selected by remember { mutableStateOf(initialVoiceId) }

    LaunchedEffect(Unit) {
        voices = withContext(Dispatchers.Default) { probeVoices(context) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(StringsR.string.settings_audio_download_voice)) },
        text = {
            Column(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (voices.isEmpty()) {
                    Text(
                        text = stringResource(StringsR.string.settings_audio_download_queue_empty),
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    voices.sortedBy { it.language }.forEach { voice ->
                        val isSelected = voice.id == selected
                        SlimListItem(
                            headlineContent = {
                                Text(text = voice.id, maxLines = 1)
                            },
                            supportingContent = {
                                Text(text = voice.language, maxLines = 1)
                            },
                            trailingContent = {
                                if (isSelected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = voice.id
                                    onPick(voice.enginePackage, voice.id)
                                }
                        )
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}

/** Неизменяемая запись голоса, доступного для загрузки аудио. */
private data class VoiceEntry(
    val enginePackage: String,
    val id: String,
    val language: String,
)

/** Перечисляет голоса выделенным TextToSpeech-инстансом (не трогая AppTtsEngine). */
private suspend fun probeVoices(context: Context): List<VoiceEntry> {
    var engine: TextToSpeech? = null
    suspendCancellableCoroutine<Unit> { cont ->
        // Инстанс присваивается до init-колбэка (он асинхронный), поэтому
        // self-reference внутри own-initializer не требуется.
        engine = TextToSpeech(context.applicationContext) { status ->
            if (cont.isActive) {
                cont.resume(Unit)
            }
        }
        cont.invokeOnCancellation {
            runCatching { engine?.stop() }
            runCatching { engine?.shutdown() }
        }
    }
    val tts = engine ?: return emptyList()
    val enginePackage = tts.defaultEngine ?: ""
    val result = runCatching {
        (tts.voices ?: emptyList()).map { voice ->
            VoiceEntry(
                enginePackage = enginePackage,
                id = voice.name,
                language = voice.locale?.displayLanguage ?: "",
            )
        }
    }.getOrDefault(emptyList())
    runCatching { tts.shutdown() }
    return result
}
