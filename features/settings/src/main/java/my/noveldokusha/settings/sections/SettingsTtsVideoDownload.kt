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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import my.noveldokusha.text_to_speech.VideoTextAlignment

private enum class PreviewSample(val label: String) {
    SHORT("Short"), NORMAL("Normal"), LONG("Long"), DIALOGUE("Dialogue"), VERY_LONG("Very long")
}

@Composable
fun SettingsTtsVideoDownload() {
    val context = LocalContext.current
    val preferences = remember { TtsVideoPreferences(context) }
    var draft by remember { mutableStateOf(preferences.visualSettings().coerceForVideo()) }
    var draftOutputUri by remember { mutableStateOf(preferences.outputDirectoryUri) }
    var saved by remember { mutableStateOf(false) }
    var previewSample by remember { mutableStateOf(PreviewSample.NORMAL) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewTimeUs by remember { mutableLongStateOf(2_000_000L) }

    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                draftOutputUri = uri.toString()
                saved = false
            }
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            changeVisual(draft.copy(backgroundMode = BackgroundMode.IMAGE, backgroundUri = uri.toString())) { draft = it; saved = false }
        }
    }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            changeVisual(draft.copy(artworkUris = uris.map(Uri::toString).distinct())) { draft = it; saved = false }
        }
    }

    LaunchedEffect(previewPlaying) {
        while (previewPlaying) {
            kotlinx.coroutines.delay(100L)
            previewTimeUs = (previewTimeUs + 100_000L).coerceAtMost(previewDurationUs(previewSample))
            if (previewTimeUs >= previewDurationUs(previewSample)) previewPlaying = false
        }
    }

    val timeline = remember(previewSample) { sampleTimeline(previewSample) }
    val previewBitmap = remember(draft, previewSample, (previewTimeUs / 100_000L)) {
        val settings = draft.copy(
            width = 480,
            height = 270,
            fps = 30,
            fontSizePx = (draft.fontSizePx / 4f).coerceIn(14f, 30f),
            minFontSizePx = (draft.minFontSizePx / 4f).coerceIn(10f, 18f),
            cardPaddingPx = draft.cardPaddingPx / 4f,
            cardCornerRadiusPx = draft.cardCornerRadiusPx / 4f,
            paragraphSpacingPx = draft.paragraphSpacingPx / 4f,
            safeMarginPx = (draft.safeMarginPx / 4f).coerceIn(10f, 38f),
            artworkWidthPx = draft.artworkWidthPx / 4f,
        ).coerceForVideo()
        Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888).also { bitmap ->
            TtsVideoCompositionRenderer(context).render(
                Canvas(bitmap), timeline, settings, my.noveldokusha.text_to_speech.TtsVideoVisualSnapshot(), previewTimeUs.coerceAtMost(timeline.durationUs - 1L).coerceAtLeast(0L)
            )
        }
    }
    DisposableEffect(previewBitmap) { onDispose { previewBitmap.recycle() } }

    fun change(next: TtsVideoVisualSettings) {
        draft = next.coerceForVideo()
        saved = false
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live sample preview", style = MaterialTheme.typography.titleLarge)
                Image(bitmap = previewBitmap.asImageBitmap(), contentDescription = "Video preview", modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { previewPlaying = !previewPlaying }) { Text(if (previewPlaying) "Pause" else "Play") }
                    Text("${(previewTimeUs / 1_000_000f).formatOneDecimal()} / ${(timeline.durationUs / 1_000_000f).formatOneDecimal()} s", style = MaterialTheme.typography.bodySmall)
                }
                Slider(value = previewTimeUs.toFloat(), onValueChange = { previewTimeUs = it.toLong(); previewPlaying = false }, valueRange = 0f..timeline.durationUs.toFloat())
                chipRow(PreviewSample.entries.toList(), previewSample, { previewSample = it; previewTimeUs = 0L; previewPlaying = false }) { it.label }
                Text("Deterministic preview timeline; no repeated export-TTS synthesis on slider changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        VideoSection("Text") {
            Text("Paragraph display", style = MaterialTheme.typography.titleSmall)
            chipRow(ParagraphDisplayMode.entries.toList(), draft.paragraphMode, { change(draft.copy(paragraphMode = it)) }) { prettyName(it.name) }
            ToggleRow("Bold", draft.bold) { change(draft.copy(bold = it)) }
            ToggleRow("Italic", draft.italic) { change(draft.copy(italic = it)) }
            Text("Font size: ${draft.fontSizePx.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
            Slider(value = draft.fontSizePx, onValueChange = { change(draft.copy(fontSizePx = it)) }, valueRange = 30f..86f)
            Text("Minimum size: ${draft.minFontSizePx.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
            Slider(value = draft.minFontSizePx, onValueChange = { change(draft.copy(minFontSizePx = it)) }, valueRange = 18f..54f)
            Text("Letter spacing: %.2f em".format(draft.letterSpacingEm))
            Slider(value = draft.letterSpacingEm, onValueChange = { change(draft.copy(letterSpacingEm = it)) }, valueRange = -0.02f..0.08f)
            chipRow(VideoTextAlignment.entries.toList(), draft.horizontalAlignment, { change(draft.copy(horizontalAlignment = it)) }) { prettyName(it.name) }
            Text("Vertical position: ${(draft.verticalPositionFraction * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(value = draft.verticalPositionFraction, onValueChange = { change(draft.copy(verticalPositionFraction = it)) }, valueRange = .20f..0.80f)
        }

        VideoSection("Layout") {
            chipRow(LongParagraphMode.entries.toList(), draft.longParagraphMode, { change(draft.copy(longParagraphMode = it)) }) { prettyName(it.name) }
            Text("Line height: %.2f×".format(draft.lineSpacingMultiplier))
            Slider(value = draft.lineSpacingMultiplier, onValueChange = { change(draft.copy(lineSpacingMultiplier = it)) }, valueRange = 1f..1.8f)
            Text("Paragraph spacing: ${draft.paragraphSpacingPx.toInt()} dp")
            Slider(value = draft.paragraphSpacingPx, onValueChange = { change(draft.copy(paragraphSpacingPx = it)) }, valueRange = 8f..72f)
            Text("Horizontal margin: ${(draft.horizontalMarginFraction * 100).toInt()}%")
            Slider(value = draft.horizontalMarginFraction, onValueChange = { change(draft.copy(horizontalMarginFraction = it)) }, valueRange = .03f..0.25f)
            Text("Vertical safe area: ${(draft.verticalMarginFraction * 100).toInt()}%")
            Slider(value = draft.verticalMarginFraction, onValueChange = { change(draft.copy(verticalMarginFraction = it)) }, valueRange = .03f..0.18f)
            Text("Maximum text width: ${(draft.maxTextWidthFraction * 100).toInt()}%")
            Slider(value = draft.maxTextWidthFraction, onValueChange = { change(draft.copy(maxTextWidthFraction = it)) }, valueRange = .55f..0.95f)
            ToggleRow("Card background", draft.cardEnabled) { change(draft.copy(cardEnabled = it)) }
            Text("Card opacity: ${(draft.cardAlpha * 100).toInt()}%")
            Slider(value = draft.cardAlpha, onValueChange = { change(draft.copy(cardAlpha = it)) }, valueRange = .2f..1f)
        }

        VideoSection("Word Highlight") {
            Text("Always enabled for video exports", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Opacity: ${(draft.highlightAlpha * 100).toInt()}%")
            Slider(value = draft.highlightAlpha, onValueChange = { change(draft.copy(highlightAlpha = it)) }, valueRange = .35f..1f)
            chipRow(listOf("Gold", "Yellow", "Cyan", "White"), highlightName(draft.highlightColor), { name -> change(draft.copy(highlightColor = highlightColor(name))) }) { it }
            Text("Radius: ${draft.highlightCornerRadiusPx.toInt()} dp")
            Slider(value = draft.highlightCornerRadiusPx, onValueChange = { change(draft.copy(highlightCornerRadiusPx = it)) }, valueRange = 0f..18f)
            Text("Padding: ${draft.highlightPaddingPx.toInt()} dp")
            Slider(value = draft.highlightPaddingPx, onValueChange = { change(draft.copy(highlightPaddingPx = it)) }, valueRange = 0f..12f)
        }

        VideoSection("Background") {
            chipRow(BackgroundMode.entries.toList(), draft.backgroundMode, { change(draft.copy(backgroundMode = it)) }) { prettyName(it.name) }
            Text("Color", style = MaterialTheme.typography.titleSmall)
            chipRow(listOf(Color.rgb(18,18,22), Color.rgb(22,42,64), Color.rgb(45,30,20), Color.rgb(30,55,40)), draft.backgroundColor, { change(draft.copy(backgroundColor = it)) }) { "Preset ${listOf(Color.rgb(18,18,22), Color.rgb(22,42,64), Color.rgb(45,30,20), Color.rgb(30,55,40)).indexOf(it) + 1}" }
            if (draft.backgroundMode == BackgroundMode.IMAGE) Button(onClick = { backgroundPicker.launch(arrayOf("image/*")) }) { Text(if (draft.backgroundUri.isBlank()) "Choose image" else "Replace background image") }
        }

        VideoSection("Side Artwork") {
            chipRow(ArtworkMode.entries.toList(), draft.artworkMode, { change(draft.copy(artworkMode = it)) }) { prettyName(it.name) }
            ToggleRow("Overlay safe region", draft.artworkOverlay) { change(draft.copy(artworkOverlay = it)) }
            Text("Width: ${draft.artworkWidthPx.toInt()} dp")
            Slider(value = draft.artworkWidthPx, onValueChange = { change(draft.copy(artworkWidthPx = it)) }, valueRange = 120f..420f)
            Text("Opacity: ${(draft.artworkOpacity * 100).toInt()}%")
            Slider(value = draft.artworkOpacity, onValueChange = { change(draft.copy(artworkOpacity = it)) }, valueRange = .25f..1f)
            Button(onClick = { artworkPicker.launch(arrayOf("image/*")) }) { Text("Add / replace artwork images") }
            draft.artworkUris.forEachIndexed { index, uri ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Image ${index + 1}: ${Uri.parse(uri).lastPathSegment?.takeLast(28) ?: "selected"}", Modifier.weight(1f), maxLines = 1)
                    IconButton(enabled = index > 0, onClick = {
                        val list = draft.artworkUris.toMutableList(); list.add(index - 1, list.removeAt(index)); change(draft.copy(artworkUris = list))
                    }) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
                    IconButton(enabled = index < draft.artworkUris.lastIndex, onClick = {
                        val list = draft.artworkUris.toMutableList(); list.add(index + 1, list.removeAt(index)); change(draft.copy(artworkUris = list))
                    }) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { change(draft.copy(artworkUris = draft.artworkUris.filterIndexed { i, _ -> i != index })) }) { Icon(Icons.Outlined.Delete, "Remove") }
                }
            }
        }

        VideoSection("Image Slideshow") {
            ToggleRow("Enabled", draft.slideshowEnabled) { change(draft.copy(slideshowEnabled = it)) }
            if (draft.slideshowEnabled) {
                chipRow(SlideshowIntervalMode.entries.toList(), draft.slideshowIntervalMode, { change(draft.copy(slideshowIntervalMode = it)) }) { prettyName(it.name) }
                when (draft.slideshowIntervalMode) {
                    SlideshowIntervalMode.FIXED_INTERVAL, SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION -> {
                        Text("Base interval: ${(draft.slideshowIntervalMs / 1000f).formatOneDecimal()} s")
                        Slider(value = draft.slideshowIntervalMs.toFloat(), onValueChange = { change(draft.copy(slideshowIntervalMs = it.toLong())) }, valueRange = 2_000f..30_000f)
                    }
                    SlideshowIntervalMode.RANDOM_INTERVAL -> {
                        Text("Random minimum: ${(draft.randomIntervalMinMs / 1000f).formatOneDecimal()} s")
                        Slider(value = draft.randomIntervalMinMs.toFloat(), onValueChange = { change(draft.copy(randomIntervalMinMs = minOf(it.toLong(), draft.randomIntervalMaxMs - 500L))) }, valueRange = 2_000f..30_000f)
                        Text("Random maximum: ${(draft.randomIntervalMaxMs / 1000f).formatOneDecimal()} s")
                        Slider(value = draft.randomIntervalMaxMs.toFloat(), onValueChange = { change(draft.copy(randomIntervalMaxMs = maxOf(it.toLong(), draft.randomIntervalMinMs + 500L))) }, valueRange = 2_500f..45_000f)
                    }
                }
                Text("${when (draft.slideshowIntervalMode) { SlideshowIntervalMode.FIXED_INTERVAL -> "Uses the same interval for every slide."; SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION -> "Divides the chapter duration evenly across images."; SlideshowIntervalMode.RANDOM_INTERVAL -> "Chooses deterministic intervals between the configured minimum and maximum." }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                chipRow(SlideshowTransition.entries.toList(), draft.slideshowTransition, { change(draft.copy(slideshowTransition = it)) }) { prettyName(it.name) }
                Text("Transition duration: ${(draft.transitionDurationMs / 1000f).formatOneDecimal()} s")
                Slider(value = draft.transitionDurationMs.toFloat(), onValueChange = { change(draft.copy(transitionDurationMs = it.toLong())) }, valueRange = 0f..2_000f)
            }
        }

        VideoSection("Output Location") {
            Text(if (draftOutputUri.isBlank()) "No dedicated folder selected. The video exporter can initially use the Audio Downloads folder." else "Dedicated video folder selected.")
            Button(onClick = { outputPicker.launch(null) }) { Text("Choose video folder") }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = draftOutputUri.isNotBlank(),
            onClick = {
                preferences.putVisual(draft)
                preferences.outputDirectoryUri = draftOutputUri
                saved = true
            },
        ) { Text(if (saved) "Saved" else "Save video settings") }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun VideoSection(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> chipRow(values: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label(value)) })
        }
    }
}

private fun prettyName(value: String): String = value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
private fun Float.formatOneDecimal(): String = "%.1f".format(this)
private fun highlightName(color: Int): String = when (color) {
    0xFFFFD24B.toInt() -> "Gold"
    Color.YELLOW -> "Yellow"
    Color.CYAN -> "Cyan"
    else -> "White"
}
private fun highlightColor(name: String): Int = when (name) {
    "Gold" -> 0xFFFFD24B.toInt()
    "Yellow" -> Color.YELLOW
    "Cyan" -> Color.CYAN
    else -> Color.WHITE
}

private fun changeVisual(next: TtsVideoVisualSettings, apply: (TtsVideoVisualSettings) -> Unit) = apply(next.coerceForVideo())

private fun TtsVideoVisualSettings.coerceForVideo(): TtsVideoVisualSettings {
    val min = minOf(minFontSizePx.coerceIn(18f, 80f), fontSizePx.coerceIn(24f, 120f))
    return copy(
        width = 1920, height = 1080, fps = 30,
        fontSizePx = fontSizePx.coerceIn(24f, 120f), minFontSizePx = min,
        lineSpacingMultiplier = lineSpacingMultiplier.coerceIn(1f, 2f), paragraphSpacingPx = paragraphSpacingPx.coerceIn(0f, 120f),
        horizontalMarginFraction = horizontalMarginFraction.coerceIn(.03f, .25f), verticalMarginFraction = verticalMarginFraction.coerceIn(.03f, .18f),
        verticalPositionFraction = verticalPositionFraction.coerceIn(.20f, .80f), maxTextWidthFraction = maxTextWidthFraction.coerceIn(.45f, .95f),
        cardAlpha = cardAlpha.coerceIn(0f, 1f), highlightAlpha = highlightAlpha.coerceIn(0f, 1f),
        highlightCornerRadiusPx = highlightCornerRadiusPx.coerceIn(0f, 24f), highlightPaddingPx = highlightPaddingPx.coerceIn(0f, 16f),
        safeMarginPx = safeMarginPx.coerceIn(20f, 240f), artworkWidthPx = artworkWidthPx.coerceIn(80f, 600f), artworkOpacity = artworkOpacity.coerceIn(0f, 1f),
        slideshowIntervalMs = slideshowIntervalMs.coerceIn(1_000L, 60_000L), randomIntervalMinMs = randomIntervalMinMs.coerceIn(1_000L, 60_000L),
        randomIntervalMaxMs = randomIntervalMaxMs.coerceIn(randomIntervalMinMs.coerceIn(1_000L, 59_500L) + 500L, 120_000L), transitionDurationMs = transitionDurationMs.coerceIn(0L, 3_000L),
    )
}

private fun previewDurationUs(sample: PreviewSample): Long = sampleTimeline(sample).durationUs

private fun sampleTimeline(sample: PreviewSample): TtsVideoTimeline {
    val texts = when (sample) {
        PreviewSample.SHORT -> listOf("A new chapter begins.", "Every choice has a consequence.")
        PreviewSample.NORMAL -> listOf("The city was quiet beneath the silver rain.", "He tightened his coat and stepped into the empty street.", "Somewhere beyond the old gate, a strange light appeared.")
        PreviewSample.LONG -> listOf(
            "The road stretched beyond the hills, and every step revealed another broken sign, another abandoned house, and another piece of a journey that nobody remembered.",
            "He kept reading the ancient inscription because the smallest missing word could change the meaning of the entire prophecy."
        )
        PreviewSample.DIALOGUE -> listOf("\"You should not be here,\" she whispered.", "\"Neither should you,\" he replied, looking toward the dark corridor.", "For a moment neither of them moved.")
        PreviewSample.VERY_LONG -> listOf(("The archive contained forgotten records, contradictory histories, names without faces, and fragments of conversations that had survived for centuries. ").repeat(5))
    }
    val per = 3_000_000L
    val paragraphs = texts.mapIndexed { index, text ->
        val start = index * per
        val end = start + per
        VideoParagraph(
            id = "preview:$index", displayText = text, preparedText = text, startUs = start, endUs = end, blockIndex = index,
            spokenRanges = buildPreviewRanges(text, start, per),
        )
    }
    return TtsVideoTimeline(paragraphs, paragraphs.last().endUs, TimelineTimingMode.EXACT)
}

private fun buildPreviewRanges(text: String, baseUs: Long, durationUs: Long): List<VideoSpokenRange> {
    val words = Regex("\\S+").findAll(text).toList()
    if (words.isEmpty()) return emptyList()
    val slice = durationUs.toDouble() / words.size
    return words.mapIndexed { index, match ->
        val start = baseUs + (index * slice).toLong()
        val end = baseUs + ((index + 1) * slice).toLong().coerceAtMost(durationUs + baseUs)
        VideoSpokenRange(start, end, match.range.first, match.range.last + 1, match.range.first, match.range.last + 1, TimelineTimingMode.EXACT)
    }
}
