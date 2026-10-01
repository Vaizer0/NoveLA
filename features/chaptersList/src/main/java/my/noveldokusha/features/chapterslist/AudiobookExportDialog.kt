package my.noveldokusha.features.chapterslist

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import my.noveldokusha.chapterslist.R
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.tooling.audiobook.AudiobookContentMode
import my.noveldokusha.tooling.audiobook.AudiobookFormat
import my.noveldokusha.tooling.audiobook.VisualSource
import timber.log.Timber

/**
 * Диалог экспорта аудиокниги: формат, контент, диапазон глав, визуал (MP4)
 * и папка назначения. По нажатию «Начать» собирается [AudiobookExportConfig]
 * и передаётся наверх — ViewModel ставит задачу в WorkManager.
 */
@Composable
internal fun AudiobookExportDialog(
    state: AudiobookExportDialogState.Configure,
    onConfirm: (AudiobookExportConfig) -> Unit,
    onDirectorySaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var format by remember { mutableStateOf(AudiobookFormat.WAV) }
    var contentMode by remember { mutableStateOf(AudiobookContentMode.ORIGINAL) }
    var sourceLang by remember { mutableStateOf("") }
    var targetLang by remember { mutableStateOf("") }
    var startText by remember { mutableStateOf("1") }
    var endText by remember {
        mutableStateOf(state.totalChapters.coerceAtLeast(1).toString())
    }
    var visualUri by remember { mutableStateOf<String?>(null) }
    var visualSource by remember { mutableStateOf<VisualSource?>(null) }
    var visualName by remember { mutableStateOf<String?>(null) }

    val directoryPicker = rememberLauncherForActivityResult(
        contract = AudiobookOpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Timber.w(e, "Audiobook export: persistable tree permission denied")
            }
            onDirectorySaved(uri.toString())
        }
    }

    val visualPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mime = context.contentResolver.getType(uri)
            visualUri = uri.toString()
            visualSource = when {
                mime?.startsWith("video/") == true -> VisualSource.VIDEO
                mime == "image/gif" -> VisualSource.GIF
                else -> VisualSource.IMAGE
            }
            visualName = uri.lastPathSegment?.substringAfterLast('/') ?: mime
        }
    }

    // Папка не выбрана: ViewModel запросил выбор — открываем SAF-пикер.
    // Ключ растёт при каждом запросе, поэтому повторное нажатие «Начать»
    // после отмены пикера снова откроет выбор папки.
    LaunchedEffect(state.directoryRequestId) {
        if (state.directoryRequestId > 0) directoryPicker.launch(null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(StringsR.string.audiobook_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(StringsR.string.audiobook_export_format))
                ChoiceButton(
                    text = stringResource(StringsR.string.audiobook_export_format_wav),
                    selected = format == AudiobookFormat.WAV,
                    onClick = { format = AudiobookFormat.WAV },
                )
                ChoiceButton(
                    text = stringResource(StringsR.string.audiobook_export_format_mp4),
                    selected = format == AudiobookFormat.MP4,
                    onClick = { format = AudiobookFormat.MP4 },
                )

                SectionLabel(stringResource(StringsR.string.audiobook_export_content))
                ChoiceButton(
                    text = stringResource(StringsR.string.audiobook_export_content_original),
                    selected = contentMode == AudiobookContentMode.ORIGINAL,
                    onClick = {
                        contentMode = AudiobookContentMode.ORIGINAL
                        sourceLang = ""
                        targetLang = ""
                    },
                )
                state.availableTranslations.forEach { pair ->
                    val selected = contentMode == AudiobookContentMode.TRANSLATION &&
                        sourceLang == pair.sourceLang && targetLang == pair.targetLang
                    ChoiceButton(
                        text = stringResource(
                            StringsR.string.audiobook_export_content_translation,
                            pair.sourceLang,
                            pair.targetLang,
                        ),
                        selected = selected,
                        onClick = {
                            contentMode = AudiobookContentMode.TRANSLATION
                            sourceLang = pair.sourceLang
                            targetLang = pair.targetLang
                        },
                    )
                }

                SectionLabel(stringResource(StringsR.string.audiobook_export_range))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(StringsR.string.audiobook_export_range_start)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(StringsR.string.audiobook_export_range_end)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (format == AudiobookFormat.MP4) {
                    SectionLabel(stringResource(StringsR.string.audiobook_export_visual))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = visualName
                                ?: stringResource(StringsR.string.audiobook_export_visual_none),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { visualPicker.launch(arrayOf("image/*", "video/*")) }) {
                            Text(text = stringResource(StringsR.string.audiobook_export_choose_visual))
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = stringResource(
                            StringsR.string.export_folder,
                            state.directoryName ?: stringResource(StringsR.string.not_set),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { directoryPicker.launch(null) }) {
                        Text(text = stringResource(StringsR.string.export_change_folder))
                    }
                }
                Text(
                    text = stringResource(StringsR.string.audiobook_export_folder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.chapter_x_over_n,
                        state.downloadedChapters,
                        state.totalChapters,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(android.R.string.cancel),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                FilledTonalButton(
                    onClick = {
                        if (format == AudiobookFormat.MP4 && visualUri == null) return@FilledTonalButton
                        val lastPosition = state.totalChapters.coerceAtLeast(1) - 1
                        val start = (startText.toIntOrNull() ?: 1).coerceIn(1, state.totalChapters.coerceAtLeast(1)) - 1
                        val end = (endText.toIntOrNull() ?: (lastPosition + 1))
                            .coerceIn(1, state.totalChapters.coerceAtLeast(1)) - 1
                        onConfirm(
                            AudiobookExportConfig(
                                format = format,
                                contentMode = contentMode,
                                sourceLang = sourceLang,
                                targetLang = targetLang,
                                startPosition = start,
                                endPosition = end.coerceAtLeast(start),
                                visualUri = visualUri,
                                visualSource = visualSource,
                                visualSourceName = visualName,
                            ),
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(StringsR.string.audiobook_export_start),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        dismissButton = {},
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledTonalButton(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = text, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Check, contentDescription = null)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = text)
        }
    }
}

/** SAF-пикер каталога с persistable read/write доступом. */
private class AudiobookOpenDocumentTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        return super.createIntent(context, input)
            .addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
    }
}
