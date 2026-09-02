package my.noveldokusha.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.components.PillSlider
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.coreui.theme.colorAccent
import my.noveldokusha.video_export.SlideshowConfig
import my.noveldokusha.video_export.SlideshowTimingMode
import my.noveldokusha.video_export.SlideshowTransition
import my.noveldokusha.video_export.TextAlignment
import my.noveldokusha.video_export.ParagraphPresentation
import my.noveldokusha.video_export.VideoStyleSettings

/** Settings → Video Appearance (Video Studio export). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsVideoAppearance(
    style: VideoStyleSettings,
    onStyleChange: (VideoStyleSettings) -> Unit,
) {
    var expandedGroup by remember { mutableStateOf<String?>("Typography") }

    Column {
        Text(
            text = "Video Appearance",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = colorAccent(),
        )

        CollapsibleGroup(
            title = "Typography",
            expanded = expandedGroup == "Typography",
            onToggle = { expandedGroup = if (expandedGroup == "Typography") null else "Typography" },
        ) {
            var localFontSize by remember(style.fontSizeSp) { mutableStateOf(style.fontSizeSp ?: 22f) }
            PillSlider(
                label = "Font size (sp)",
                value = localFontSize,
                valueRange = 14f..40f,
                onValueChange = { localFontSize = it },
                onValueChangeFinished = { onStyleChange(style.copy(fontSizeSp = localFontSize)) },
                valueText = "%.1f".format(localFontSize),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            var localLineHeight by remember(style.lineHeight) { mutableStateOf(style.lineHeight ?: 1.4f) }
            PillSlider(
                label = "Line height",
                value = localLineHeight,
                valueRange = 1.1f..2.2f,
                onValueChange = { localLineHeight = it },
                onValueChangeFinished = { onStyleChange(style.copy(lineHeight = localLineHeight)) },
                valueText = "%.2f".format(localLineHeight),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            var localSpacing by remember(style.letterSpacing) { mutableStateOf(style.letterSpacing ?: 0.02f) }
            PillSlider(
                label = "Letter spacing",
                value = localSpacing,
                valueRange = 0f..0.2f,
                onValueChange = { localSpacing = it },
                onValueChangeFinished = { onStyleChange(style.copy(letterSpacing = localSpacing)) },
                valueText = "%.2f".format(localSpacing),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            var localParaSpacing by remember(style.paragraphSpacing) { mutableStateOf(style.paragraphSpacing ?: 0f) }
            PillSlider(
                label = "Paragraph spacing (px)",
                value = localParaSpacing,
                valueRange = 0f..80f,
                onValueChange = { localParaSpacing = it },
                onValueChangeFinished = { onStyleChange(style.copy(paragraphSpacing = localParaSpacing)) },
                valueText = "%.0f".format(localParaSpacing),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            SlimListItem(
                headlineContent = { Text("Bold") },
                trailingContent = {
                    Switch(checked = style.bold ?: false, onCheckedChange = { onStyleChange(style.copy(bold = it)) })
                },
            )
            SlimListItem(
                headlineContent = { Text("Italic") },
                trailingContent = {
                    Switch(checked = style.italic ?: false, onCheckedChange = { onStyleChange(style.copy(italic = it)) })
                },
            )
            SlimListItem(
                headlineContent = { Text("Text alignment") },
                supportingContent = { Text(style.textAlignment?.name ?: "inherit") },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextAlignment.entries.forEach { a ->
                            FilterChip(
                                selected = style.textAlignment == a,
                                onClick = { onStyleChange(style.copy(textAlignment = a)) },
                                label = { Text(a.name) },
                            )
                        }
                    }
                },
            )
        }

        CollapsibleGroup(
            title = "Colors",
            expanded = expandedGroup == "Colors",
            onToggle = { expandedGroup = if (expandedGroup == "Colors") null else "Colors" },
        ) {
            var localHighlightAlpha by remember(style.highlightAlpha) { mutableStateOf(style.highlightAlpha ?: 0.5f) }
            PillSlider(
                label = "Highlight opacity",
                value = localHighlightAlpha,
                valueRange = 0.05f..1f,
                onValueChange = { localHighlightAlpha = it },
                onValueChangeFinished = { onStyleChange(style.copy(highlightAlpha = localHighlightAlpha)) },
                valueText = "%.2f".format(localHighlightAlpha),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            var localCardAlpha by remember(style.currentCardAlpha) { mutableStateOf(style.currentCardAlpha ?: 1f) }
            PillSlider(
                label = "Card opacity",
                value = localCardAlpha,
                valueRange = 0.2f..1f,
                onValueChange = { localCardAlpha = it },
                onValueChangeFinished = { onStyleChange(style.copy(currentCardAlpha = localCardAlpha)) },
                valueText = "%.2f".format(localCardAlpha),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            var localContextOpacity by remember(style.contextParagraphOpacity) { mutableStateOf(style.contextParagraphOpacity ?: 0.45f) }
            PillSlider(
                label = "Context opacity",
                value = localContextOpacity,
                valueRange = 0.05f..1f,
                onValueChange = { localContextOpacity = it },
                onValueChangeFinished = { onStyleChange(style.copy(contextParagraphOpacity = localContextOpacity)) },
                valueText = "%.2f".format(localContextOpacity),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            ColorPresetRow(
                label = "Text color",
                current = style.textColorArgb,
                presets = PRESET_TEXT_COLORS,
                onSelect = { onStyleChange(style.copy(textColorArgb = it)) },
                onInherit = { onStyleChange(style.copy(textColorArgb = null)) },
            )
            ColorPresetRow(
                label = "Card fill",
                current = style.cardFillArgb,
                presets = PRESET_CARD_FILLS,
                onSelect = { onStyleChange(style.copy(cardFillArgb = it)) },
                onInherit = { onStyleChange(style.copy(cardFillArgb = null)) },
            )
            ColorPresetRow(
                label = "Highlight color",
                current = style.highlightColorArgb,
                presets = PRESET_TEXT_COLORS,
                onSelect = { onStyleChange(style.copy(highlightColorArgb = it)) },
                onInherit = { onStyleChange(style.copy(highlightColorArgb = null)) },
            )
        }

        CollapsibleGroup(
            title = "Card & Layout",
            expanded = expandedGroup == "Card & Layout",
            onToggle = { expandedGroup = if (expandedGroup == "Card & Layout") null else "Card & Layout" },
        ) {
            var localMargin by remember(style.marginX) { mutableStateOf(style.marginX ?: 256f) }
            PillSlider(
                label = "Side margin (px)",
                value = localMargin,
                valueRange = 64f..700f,
                onValueChange = { localMargin = it },
                onValueChangeFinished = { onStyleChange(style.copy(marginX = localMargin)) },
                valueText = "%.0f".format(localMargin),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            var localRadius by remember(style.cardCornerRadius) { mutableStateOf(style.cardCornerRadius ?: 20f) }
            PillSlider(
                label = "Card corner radius (px)",
                value = localRadius,
                valueRange = 0f..60f,
                onValueChange = { localRadius = it },
                onValueChangeFinished = { onStyleChange(style.copy(cardCornerRadius = localRadius)) },
                valueText = "%.0f".format(localRadius),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ParagraphPresentation.entries.forEach { p ->
                    FilterChip(
                        selected = style.presentation == p,
                        onClick = { onStyleChange(style.copy(presentation = p)) },
                        label = { Text(p.name) },
                    )
                }
            }
        }

        CollapsibleGroup(
            title = "Slideshow",
            expanded = expandedGroup == "Slideshow",
            onToggle = { expandedGroup = if (expandedGroup == "Slideshow") null else "Slideshow" },
        ) {
            val config = style.slideshowConfig
            SlimListItem(
                headlineContent = { Text("Enable slideshow") },
                supportingContent = { Text("Shows images behind the text") },
                trailingContent = {
                    Switch(
                        checked = config?.enabled ?: false,
                        onCheckedChange = { enabled ->
                            val base = config ?: SlideshowConfig(
                                enabled = false,
                                timingMode = SlideshowTimingMode.FIXED_INTERVAL,
                                fixedIntervalMs = 8000,
                                percentageSections = 0.5f,
                                randomMinMs = 4000,
                                randomMaxMs = 12000,
                                randomSeed = 0L,
                                transitionType = SlideshowTransition.FADE,
                                transitionDurationMs = 700,
                            )
                            onStyleChange(style.copy(slideshowConfig = base.copy(enabled = enabled)))
                        },
                    )
                },
            )
            if (config?.enabled == true) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SlideshowTimingMode.entries.forEach { m ->
                        FilterChip(
                            selected = config.timingMode == m,
                            onClick = { onStyleChange(style.copy(slideshowConfig = config.copy(timingMode = m))) },
                            label = { Text(m.name) },
                        )
                    }
                }
                when (config.timingMode) {
                    SlideshowTimingMode.FIXED_INTERVAL -> {
                        var local by remember(config.fixedIntervalMs) { mutableStateOf(config.fixedIntervalMs.toFloat()) }
                        PillSlider(
                            label = "Interval (s)",
                            value = local / 1000f,
                            valueRange = 3f..30f,
                            onValueChange = { local = it * 1000f },
                            onValueChangeFinished = { onStyleChange(style.copy(slideshowConfig = config.copy(fixedIntervalMs = local.toLong()))) },
                            valueText = "%.0fs".format(local / 1000f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                    SlideshowTimingMode.RANDOM_INTERVAL -> {
                        var localMin by remember(config.randomMinMs) { mutableStateOf(config.randomMinMs.toFloat()) }
                        var localMax by remember(config.randomMaxMs) { mutableStateOf(config.randomMaxMs.toFloat()) }
                        PillSlider(
                            label = "Min interval (s)",
                            value = localMin / 1000f,
                            valueRange = 1f..20f,
                            onValueChange = { localMin = it * 1000f },
                            onValueChangeFinished = { onStyleChange(style.copy(slideshowConfig = config.copy(randomMinMs = localMin.toLong().coerceAtMost(config.randomMaxMs)))) },
                            valueText = "%.0fs".format(localMin / 1000f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                        PillSlider(
                            label = "Max interval (s)",
                            value = localMax / 1000f,
                            valueRange = 1f..40f,
                            onValueChange = { localMax = it * 1000f },
                            onValueChangeFinished = { onStyleChange(style.copy(slideshowConfig = config.copy(randomMaxMs = localMax.toLong().coerceAtLeast(config.randomMinMs)))) },
                            valueText = "%.0fs".format(localMax / 1000f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                    SlideshowTimingMode.PERCENT_OF_TOTAL_DURATION -> {
                        var local by remember(config.percentageSections) { mutableStateOf(config.percentageSections) }
                        PillSlider(
                            label = "Section share",
                            value = local,
                            valueRange = 0.1f..0.9f,
                            onValueChange = { local = it },
                            onValueChangeFinished = { onStyleChange(style.copy(slideshowConfig = config.copy(percentageSections = local))) },
                            valueText = "%.0f%%".format(local * 100f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SlideshowTransition.entries.forEach { t ->
                        FilterChip(
                            selected = config.transitionType == t,
                            onClick = { onStyleChange(style.copy(slideshowConfig = config.copy(transitionType = t))) },
                            label = { Text(t.name) },
                        )
                    }
                }
                var localTransMs by remember(config.transitionDurationMs) { mutableStateOf(config.transitionDurationMs.toFloat()) }
                PillSlider(
                    label = "Transition (ms)",
                    value = localTransMs,
                    valueRange = 200f..2000f,
                    onValueChange = { localTransMs = it },
                    onValueChangeFinished = { onStyleChange(style.copy(slideshowConfig = config.copy(transitionDurationMs = localTransMs.toLong()))) },
                    valueText = "%.0f".format(localTransMs),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                Text(
                    text = "${style.slideshowItems.size} slide(s) added",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CollapsibleGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPresetRow(
    label: String,
    current: Int?,
    presets: List<Int>,
    onSelect: (Int) -> Unit,
    onInherit: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            FilterChip(selected = current == null, onClick = onInherit, label = { Text("inherit") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            presets.forEach { argb ->
                val selected = current == argb
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(argb), CircleShape)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = CircleShape,
                        )
                        .clickable { onSelect(argb) },
                )
            }
        }
    }
}

private val PRESET_TEXT_COLORS = listOf(
    0xFF1A1A1A.toInt(), 0xFFE53935.toInt(), 0xFF000000.toInt(),
    0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF8E24AA.toInt(),
    0xFFF4511E.toInt(),
)

private val PRESET_CARD_FILLS = listOf(
    0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF263238.toInt(),
    0xFFF5F5F5.toInt(), 0x1AFFFFFF.toInt(),
)
