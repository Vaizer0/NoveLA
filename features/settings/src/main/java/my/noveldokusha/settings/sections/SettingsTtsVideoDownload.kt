package my.noveldokusha.settings.sections

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import my.noveldokusha.text_to_speech.BackgroundMode
import my.noveldokusha.text_to_speech.LongParagraphMode
import my.noveldokusha.text_to_speech.ParagraphDisplayMode
import my.noveldokusha.text_to_speech.TtsVideoCompositionRenderer
import my.noveldokusha.text_to_speech.TtsVideoPreferences
import my.noveldokusha.text_to_speech.TtsVideoTimeline
import my.noveldokusha.text_to_speech.TtsVideoVisualSettings
import my.noveldokusha.text_to_speech.VideoParagraph
import my.noveldokusha.text_to_speech.VideoSpokenRange
import my.noveldokusha.text_to_speech.TimelineTimingMode
import java.util.UUID

@Composable
fun SettingsTtsVideoDownload() {
    val context = LocalContext.current
    val preferences = remember { TtsVideoPreferences(context) }
    var draft by remember { mutableStateOf(preferences.visualSettings()) }
    var draftOutputUri by remember { mutableStateOf(preferences.outputDirectoryUri) }
    var saved by remember { mutableStateOf(false) }

    val directoryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                draftOutputUri = uri.toString()
                saved = false
            }
        }
    }

    val previewBitmap = remember(draft) {
        val previewSettings = draft.copy(
            width = 480,
            height = 270,
            fps = 30,
            safeMarginPx = 20f,
            fontSizePx = (draft.fontSizePx / 4f).coerceAtLeast(16f),
            minFontSizePx = (draft.minFontSizePx / 4f).coerceAtLeast(12f),
            cardPaddingPx = draft.cardPaddingPx / 4f,
            cardCornerRadiusPx = draft.cardCornerRadiusPx / 4f,
            paragraphSpacingPx = draft.paragraphSpacingPx / 4f,
            artworkWidthPx = draft.artworkWidthPx / 4f,
        )
        val text = "A live preview uses the exact video composition renderer. The chapter export uses the full 1920x1080 snapshot."
        val paragraph = VideoParagraph(
            id = "preview:0",
            displayText = text,
            preparedText = text,
            startUs = 0L,
            endUs = 8_000_000L,
            blockIndex = 0,
            spokenRanges = listOf(
                VideoSpokenRange(0L, 1_000_000L, 0, 4, 0, 4, TimelineTimingMode.EXACT),
                VideoSpokenRange(1_000_000L, 2_000_000L, 5, 9, 5, 9, TimelineTimingMode.EXACT),
                VideoSpokenRange(2_000_000L, 3_000_000L, 10, 15, 10, 15, TimelineTimingMode.EXACT),
                VideoSpokenRange(3_000_000L, 8_000_000L, 16, text.length, 16, text.length, TimelineTimingMode.EXACT),
            ),
        )
        val bitmap = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        TtsVideoCompositionRenderer(context).render(
            Canvas(bitmap),
            TtsVideoTimeline(listOf(paragraph), paragraph.endUs, TimelineTimingMode.EXACT),
            previewSettings,
            my.noveldokusha.text_to_speech.TtsVideoVisualSnapshot(),
            1_500_000L,
        )
        bitmap
    }

    DisposableEffect(previewBitmap) {
        onDispose { previewBitmap.recycle() }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Video Downloads", style = MaterialTheme.typography.titleLarge)
            Text(
                "YouTube-ready 1920×1080 · 30 FPS · H.264 + AAC. Settings are draft-only until Save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "Video preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )

            Text("Paragraph display", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ParagraphDisplayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.paragraphMode == mode,
                        onClick = { draft = draft.copy(paragraphMode = mode); saved = false },
                        label = { Text(mode.name.replace('_', ' ')) },
                    )
                }
            }

            Text("Long paragraph behavior: ${draft.longParagraphMode.name.replace('_', ' ')}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LongParagraphMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.longParagraphMode == mode,
                        onClick = { draft = draft.copy(longParagraphMode = mode); saved = false },
                        label = { Text(mode.name.replace('_', ' ')) },
                    )
                }
            }

            Text("Font size: ${draft.fontSizePx.toInt()} px")
            Slider(
                value = draft.fontSizePx,
                onValueChange = { draft = draft.copy(fontSizePx = it); saved = false },
                valueRange = 34f..86f,
            )

            Text("Word highlight opacity: ${(draft.highlightAlpha * 100).toInt()}%")
            Slider(
                value = draft.highlightAlpha,
                onValueChange = { draft = draft.copy(highlightAlpha = it); saved = false },
                valueRange = .35f..1f,
            )

            Text("Background", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BackgroundMode.entries.forEach { mode ->
                    FilterChip(
                        selected = draft.backgroundMode == mode,
                        onClick = { draft = draft.copy(backgroundMode = mode); saved = false },
                        label = { Text(mode.name) },
                    )
                }
            }

            Text("Slideshow: ${if (draft.slideshowEnabled) "ON" else "OFF"}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = draft.slideshowEnabled,
                    onClick = { draft = draft.copy(slideshowEnabled = !draft.slideshowEnabled); saved = false },
                    label = { Text("Enabled") },
                )
                FilterChip(
                    selected = !draft.slideshowEnabled,
                    onClick = { draft = draft.copy(slideshowEnabled = false); saved = false },
                    label = { Text("Disabled") },
                )
            }

            Text(
                text = if (draftOutputUri.isBlank()) "Output folder: not selected (choose a dedicated video folder)"
                else "Output folder selected",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { directoryPicker.launch(null) }) { Text("Choose folder") }
                Button(onClick = {
                    preferences.putVisual(draft)
                    preferences.outputDirectoryUri = draftOutputUri
                    saved = true
                }) { Text(if (saved) "Saved" else "Save") }
            }
            Spacer(Modifier.size(2.dp))
        }
    }
}
