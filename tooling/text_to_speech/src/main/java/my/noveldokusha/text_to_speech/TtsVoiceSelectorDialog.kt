package my.noveldokusha.text_to_speech

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.noveldokusha.strings.R as StringsR

@Composable
fun TtsVoiceSelectorDialog(
    isOpen: Boolean,
    voices: List<VoiceData>,
    currentVoice: VoiceData?,
    favoriteKeys: Set<String>,
    onSelect: (VoiceData) -> Unit,
    onToggleFavorite: (VoiceData) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isOpen) return
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val filtered = remember(voices, query, favoriteKeys) {
        val q = query.trim()
        voices.filter { voice ->
            q.isBlank() || voice.language.contains(q, true) || voice.id.contains(q, true) || voice.enginePackage.contains(q, true)
        }.sortedWith(
            compareByDescending<VoiceData> { it.key() in favoriteKeys }
                .thenBy { it.language.lowercase() }
                .thenByDescending { it.quality }
                .thenBy { it.id.lowercase() }
                .thenBy { it.enginePackage.lowercase() },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(StringsR.string.audiobook_select_voice)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(StringsR.string.audiobook_search_voices)) }, singleLine = true,
                )
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                    if (filtered.isEmpty()) {
                        item { Text(stringResource(StringsR.string.audiobook_no_voices), Modifier.fillMaxWidth().padding(16.dp)) }
                    } else {
                        items(filtered, key = { it.key() }) { voice ->
                            val selected = currentVoice?.key() == voice.key()
                            val pinned = voice.key() in favoriteKeys
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
                                    .clickable { onSelect(voice) }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(voice.language, fontWeight = FontWeight.Bold)
                                    Text(voice.id, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        voice.enginePackage + if (voice.needsInternet) " • Internet" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        repeat(5) { index ->
                                            Icon(
                                                if (voice.quality > index * 100) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                contentDescription = null, modifier = Modifier.size(11.dp),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.size(4.dp))
                                IconButton(onClick = { onToggleFavorite(voice) }) {
                                    Icon(
                                        if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = if (pinned) stringResource(StringsR.string.audiobook_unpin_voice) else stringResource(StringsR.string.audiobook_pin_voice),
                                    )
                                }
                            }
                            HorizontalDivider(Modifier.alpha(0.25f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
