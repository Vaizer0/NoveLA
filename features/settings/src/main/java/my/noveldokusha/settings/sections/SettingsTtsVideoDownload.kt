package my.noveldokusha.settings.sections

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import my.noveldokusha.text_to_speech.ArtworkMode
import my.noveldokusha.text_to_speech.BackgroundMode
import my.noveldokusha.text_to_speech.LongParagraphMode
import my.noveldokusha.text_to_speech.ParagraphDisplayMode
import my.noveldokusha.text_to_speech.SlideshowIntervalMode
import my.noveldokusha.text_to_speech.SlideshowTransition
import my.noveldokusha.text_to_speech.TimelineTimingMode
import my.noveldokusha.text_to_speech.TtsVideoCompositionRenderer
import my.noveldokusha.text_to_speech.TtsVideoPreferences
import my.noveldokusha.text_to_speech.TtsVideoTimeline
import my.noveldokusha.text_to_speech.TtsVideoVisualSettings
import my.noveldokusha.text_to_speech.VideoParagraph
import my.noveldokusha.text_to_speech.VideoSpokenRange

@Composable
fun SettingsTtsVideoDownload() {
    val context = LocalContext.current
    val preferences = remember { TtsVideoPreferences(context) }
    var draft by remember { mutableStateOf(preferences.visualSettings()) }
    var draftOutputUri by remember { mutableStateOf(preferences.outputDirectoryUri) }
    var saved by remember { mutableStateOf(false) }

    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                draftOutputUri = uri.toString()
                saved = false
            }
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                draft = draft.copy(backgroundMode = BackgroundMode.IMAGE, backgroundUri = uri.toString())
                saved = false
            }
        }
    }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            draft = draft.copy(artworkUris = uris.map(Uri::toString).distinct())
            saved = false
        }
    }

    val previewBitmap = remember(draft) {
        val previewSettings = draft.copy(
            width = 480,
            height = 270,
            fps = 30,
            safeMarginPx = (draft.safeMarginPx / 4f).coerceAtLeast(12f),
            fontSizePx = (draft.fontSizePx / 4f).coerceAtLeast(16f),
            minFontSizePx = (draft.minFontSizePx / 4f).coerceAtLeast(10f),
            cardPaddingPx = draft.cardPaddingPx / 4f,
            cardCornerRadiusPx = draft.cardCornerRadiusPx / 4f,
            paragraphSpacingPx = draft.paragraphSpacingPx / 4f,
            artworkWidthPx = draft.artworkWidthPx / 4f,
        )
        val text = "The live preview uses the same composition renderer as the exported 1920×1080 video."
        val paragraph = VideoParagraph(
            id = "preview:0",
            displayText = text,
            preparedText = text,
            startUs = 0L,
            endUs = 8_000_000L,
            blockIndex = 0,
            spokenRanges = listOf(
                VideoSpokenRange(0L, 1_000_000L, 0, 3, 0, 3, TimelineTimingMode.EXACT),
                VideoSpokenRange(1_000_000L, 2_000_000L, 4, 9, 4, 9, TimelineTimingMode.EXACT),
                VideoSpokenRange(2_000_000L, 3_000_000L, 10, 14, 10, 14, TimelineTimingMode.EXACT),
            ),
        )
        Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).also { bitmap ->
            TtsVideoCompositionRenderer(context).render(
                Canvas(bitmap),
                TtsVideoTimeline(listOf(paragraph), paragraph.endUs, TimelineTimingMode.EXACT),
                previewSettings,
                my.noveldokusha.text_to_speech.TtsVideoVisualSnapshot(),
                1_500_000L,
            )
        }
    }
    DisposableEffect(previewBitmap) { onDispose { previewBitmap.recycle() } }

    fun change(next: TtsVideoVisualSettings) {
        draft = next.coerceForVideo()
        saved = false
    }

    Surface(modifier = Modifier.fillMaxWidth().padding(12.dp), tonalElevation = 1.dp, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Video Downloads", style = MaterialTheme.typography.titleLarge)
            Text("YouTube-ready 1920×1080 · 30 FPS · H.264 + AAC. Settings stay in this draft until Save.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Preview", style = MaterialTheme.typography.titleMedium)
            Image(bitmap = previewBitmap.asImageBitmap(), contentDescription = "Video preview", modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))

            Text("Text", style = MaterialTheme.typography.titleMedium)
            chipRow(ParagraphDisplayMode.entries, draft.paragraphMode, { change(draft.copy(paragraphMode = it)) }) { it.name.replace('_', ' ') }
            Text("Font size: ${draft.fontSizePx.toInt()} px")
            Slider(value = draft.fontSizePx, onValueChange = { change(draft.copy(fontSizePx = it)) }, valueRange = 34f..86f)
            Text("Minimum font size: ${draft.minFontSizePx.toInt()} px")
            Slider(value = draft.minFontSizePx, onValueChange = { change(draft.copy(minFontSizePx = it)) }, valueRange = 20f..54f)
            Text("Line spacing: %.2f×".format(draft.lineSpacingMultiplier))
            Slider(value = draft.lineSpacingMultiplier, onValueChange = { change(draft.copy(lineSpacingMultiplier = it)) }, valueRange = 1f..1.8f)
            Text("Letter spacing: %.2f em".format(draft.letterSpacingEm))
            Slider(value = draft.letterSpacingEm, onValueChange = { change(draft.copy(letterSpacingEm = it)) }, valueRange = -0.02f..0.08f)

            Text("Layout", style = MaterialTheme.typography.titleMedium)
            chipRow(LongParagraphMode.entries, draft.longParagraphMode, { change(draft.copy(longParagraphMode = it)) }) { it.name.replace('_', ' ') }
            Text("Safe margin: ${draft.safeMarginPx.toInt()} px")
            Slider(value = draft.safeMarginPx, onValueChange = { change(draft.copy(safeMarginPx = it)) }, valueRange = 40f..180f)
            Text("Maximum text width: ${(draft.maxTextWidthFraction * 100).toInt()}%")
            Slider(value = draft.maxTextWidthFraction, onValueChange = { change(draft.copy(maxTextWidthFraction = it)) }, valueRange = .55f..1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Card background", modifier = Modifier.weight(1f))
                Switch(checked = draft.cardEnabled, onCheckedChange = { change(draft.copy(cardEnabled = it)) })
            }
            Text("Card opacity: ${(draft.cardAlpha * 100).toInt()}%")
            Slider(value = draft.cardAlpha, onValueChange = { change(draft.copy(cardAlpha = it)) }, valueRange = .2f..1f)
            Text("Card padding: ${draft.cardPaddingPx.toInt()} px")
            Slider(value = draft.cardPaddingPx, onValueChange = { change(draft.copy(cardPaddingPx = it)) }, valueRange = 8f..60f)
            Text("Paragraph spacing: ${draft.paragraphSpacingPx.toInt()} px")
            Slider(value = draft.paragraphSpacingPx, onValueChange = { change(draft.copy(paragraphSpacingPx = it)) }, valueRange = 8f..72f)

            Text("Word Highlight", style = MaterialTheme.typography.titleMedium)
            Text("Always enabled in video · opacity ${(draft.highlightAlpha * 100).toInt()}%")
            Slider(value = draft.highlightAlpha, onValueChange = { change(draft.copy(highlightAlpha = it)) }, valueRange = .35f..1f)
            chipRow(listOf("Gold", "Yellow", "Cyan", "White"), when (draft.highlightColor) { 0xFFFFD24B.toInt() -> "Gold"; Color.YELLOW -> "Yellow"; Color.CYAN -> "Cyan"; else -> "White" }, { name ->
                change(draft.copy(highlightColor = when (name) { "Gold" -> 0xFFFFD24B.toInt(); "Yellow" -> Color.YELLOW; "Cyan" -> Color.CYAN; else -> Color.WHITE }))
            }) { it }

            Text("Background", style = MaterialTheme.typography.titleMedium)
            chipRow(BackgroundMode.entries, draft.backgroundMode, { change(draft.copy(backgroundMode = it)) }) { it.name }
            if (draft.backgroundMode == BackgroundMode.IMAGE) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { backgroundPicker.launch(arrayOf("image/*")) }) { Text("Choose image") }
                    Text(if (draft.backgroundUri.isBlank()) "No image" else "Image selected")
                }
            }

            Text("Side Artwork", style = MaterialTheme.typography.titleMedium)
            chipRow(ArtworkMode.entries, draft.artworkMode, { change(draft.copy(artworkMode = it)) }) { it.name }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Overlay", modifier = Modifier.weight(1f))
                Switch(checked = draft.artworkOverlay, onCheckedChange = { change(draft.copy(artworkOverlay = it)) })
            }
            Text("Artwork width: ${draft.artworkWidthPx.toInt()} px")
            Slider(value = draft.artworkWidthPx, onValueChange = { change(draft.copy(artworkWidthPx = it)) }, valueRange = 120f..420f)
            Text("Artwork opacity: ${(draft.artworkOpacity * 100).toInt()}%")
            Slider(value = draft.artworkOpacity, onValueChange = { change(draft.copy(artworkOpacity = it)) }, valueRange = .25f..1f)
            Button(onClick = { artworkPicker.launch(arrayOf("image/*")) }) { Text("Choose artwork images") }
            Text("${draft.artworkUris.size} artwork image(s) selected", style = MaterialTheme.typography.bodySmall)

            Text("Image Slideshow", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = draft.slideshowEnabled, onCheckedChange = { change(draft.copy(slideshowEnabled = it)) })
            }
            if (draft.slideshowEnabled) {
                chipRow(SlideshowIntervalMode.entries, draft.slideshowIntervalMode, { change(draft.copy(slideshowIntervalMode = it)) }) { it.name.replace('_', ' ') }
                Text("Interval: ${draft.slideshowIntervalMs / 1000f}s")
                Slider(value = draft.slideshowIntervalMs.toFloat(), onValueChange = { change(draft.copy(slideshowIntervalMs = it.toLong())) }, valueRange = 2000f..30000f)
                Text("Transition", style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SlideshowTransition.entries.forEach { transition ->
                        FilterChip(selected = draft.slideshowTransition == transition, onClick = { change(draft.copy(slideshowTransition = transition)) }, label = { Text(transition.name.replace('_', ' ')) })
                    }
                }
            }

            Text("Output Location", style = MaterialTheme.typography.titleMedium)
            Text(if (draftOutputUri.isBlank()) "No video output folder selected" else "Dedicated video output folder selected", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { outputPicker.launch(null) }) { Text("Choose video folder") }

            Text("Save", style = MaterialTheme.typography.titleMedium)
            Button(enabled = draftOutputUri.isNotBlank(), onClick = {
                preferences.putVisual(draft)
                preferences.outputDirectoryUri = draftOutputUri
                saved = true
            }) { Text(if (saved) "Saved" else "Save") }
            Spacer(Modifier.size(2.dp))
        }
    }
}

private fun TtsVideoVisualSettings.coerceForVideo(): TtsVideoVisualSettings = copy(
    width = 1920,
    height = 1080,
    fps = 30,
    fontSizePx = fontSizePx.coerceIn(24f, 120f),
    minFontSizePx = minFontSizePx.coerceIn(18f, fontSizePx.coerceIn(18f, 120f)),
    lineSpacingMultiplier = lineSpacingMultiplier.coerceIn(1f, 2f),
    paragraphSpacingPx = paragraphSpacingPx.coerceIn(0f, 120f),
    cardAlpha = cardAlpha.coerceIn(0f, 1f),
    highlightAlpha = highlightAlpha.coerceIn(0f, 1f),
    safeMarginPx = safeMarginPx.coerceIn(20f, 240f),
    maxTextWidthFraction = maxTextWidthFraction.coerceIn(.45f, 1f),
    artworkWidthPx = artworkWidthPx.coerceIn(80f, 600f),
    artworkOpacity = artworkOpacity.coerceIn(0f, 1f),
    slideshowIntervalMs = slideshowIntervalMs.coerceAtLeast(1000L),
)

@Composable
private fun <T> chipRow(values: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label(value)) })
        }
    }
}
