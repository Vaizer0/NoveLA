package my.noveldokusha.features.reader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import my.noveldokusha.coreui.composableActions.debouncedAction
import my.noveldokusha.reader.R
import kotlinx.coroutines.withTimeoutOrNull
import my.noveldokusha.features.reader.features.TextToSpeechSettingData

@Composable
internal fun TtsMiniPlayer(
    state: TextToSpeechSettingData,
    onClose: () -> Unit,
    onStartHere: () -> Unit,
    chapterCurrentNumber: Int = 0,
    chaptersCount: Int = 0,
    showParagraphText: Boolean = false,
    opacity: Float = 0.95f,
    onDrag: ((Float, Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    panelWidth: Float = 300f,
    onPanelWidthChange: ((Float) -> Unit)? = null,
    opacityValue: Float = 0.95f,
    onOpacityChange: ((Float) -> Unit)? = null,
    showTextToggle: Boolean = true,
    onShowTextToggle: (() -> Unit)? = null,
    paragraphMode: String = "tts",
    onParagraphModeChange: ((String) -> Unit)? = null,
    ttsHighlightEnabled: Boolean = false,
    ttsHighlightColor: String = "FFFF6D00",
    menuHidden: Boolean = false,
    onToggleMenuHidden: (() -> Unit)? = null,
    glowEnabled: Boolean = false,
    onToggleGlow: (() -> Unit)? = null,
) {
    FloatingTtsMiniPlayer(
        state = state,
        showParagraphText = showParagraphText,
        opacity = opacity,
        onClose = onClose,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        onStartHere = onStartHere,
        chapterCurrentNumber = chapterCurrentNumber,
        chaptersCount = chaptersCount,
        panelWidth = panelWidth,
        onPanelWidthChange = onPanelWidthChange,
        opacityValue = opacityValue,
        onOpacityChange = onOpacityChange,
        showTextToggle = showTextToggle,
        onShowTextToggle = onShowTextToggle,
        paragraphMode = paragraphMode,
        onParagraphModeChange = onParagraphModeChange,
        ttsHighlightEnabled = ttsHighlightEnabled,
        ttsHighlightColor = ttsHighlightColor,
        menuHidden = menuHidden,
        onToggleMenuHidden = onToggleMenuHidden,
        glowEnabled = glowEnabled,
        onToggleGlow = onToggleGlow,
    )
}

@Composable
private fun MiniPlayerControls(
    state: TextToSpeechSettingData,
    onClose: () -> Unit,
    onStartHere: () -> Unit,
    chapterCurrentNumber: Int,
    chaptersCount: Int,
    buttonSize: Dp = 32.dp,
    iconSize: Dp = 26.dp,
    iconCircleSize: Dp = 28.dp,
    playButtonSize: Dp = 40.dp,
    playIconSize: Dp = 36.dp,
    extraAction: @Composable () -> Unit = {},
    trailingAction: @Composable () -> Unit = {},
) {
    val badgeHorizPad = 6.dp
    val badgeVertPad = 2.dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier.padding(start = 2.dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
    ) {
        IconButton(
            onClick = debouncedAction(waitMillis = 100, action = onClose),
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(iconSize)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
        }
        if (chaptersCount > 0) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "Ch. $chapterCurrentNumber/$chaptersCount",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = badgeHorizPad, vertical = badgeVertPad)
                )
            }
        }

        IconButton(
            onClick = debouncedAction(waitMillis = 100) { state.playPreviousItem() },
            enabled = state.isThereActiveItem.value,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.NavigateBefore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(iconCircleSize)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
        }
        IconButton(
            onClick = { state.setPlaying(!state.isPlaying.value) },
            modifier = Modifier.size(playButtonSize)
        ) {
            AnimatedContent(
                targetState = state.isPlaying.value,
                modifier = Modifier
                    .size(playIconSize)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                label = ""
            ) { target ->
                when (target) {
                    true -> Icon(
                        Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    false -> Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        IconButton(
            onClick = debouncedAction(waitMillis = 100) { state.playNextItem() },
            enabled = state.isThereActiveItem.value,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.NavigateNext,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(iconCircleSize)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
        }

        extraAction()

        IconButton(
            onClick = debouncedAction(waitMillis = 100, action = onStartHere),
            enabled = state.isThereActiveItem.value,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                Icons.Filled.CenterFocusWeak,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(iconCircleSize)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
        }

        trailingAction()
    }
}

@Composable
private fun FloatingTtsMiniPlayer(
    state: TextToSpeechSettingData,
    showParagraphText: Boolean,
    opacity: Float,
    onClose: () -> Unit,
    onDrag: ((Float, Float) -> Unit)?,
    onDragEnd: (() -> Unit)?,
    onStartHere: () -> Unit,
    chapterCurrentNumber: Int,
    chaptersCount: Int,
    panelWidth: Float,
    onPanelWidthChange: ((Float) -> Unit)?,
    opacityValue: Float,
    onOpacityChange: ((Float) -> Unit)?,
    showTextToggle: Boolean,
    onShowTextToggle: (() -> Unit)?,
    paragraphMode: String = "tts",
    onParagraphModeChange: ((String) -> Unit)? = null,
    ttsHighlightEnabled: Boolean = false,
    ttsHighlightColor: String = "FFFF6D00",
    menuHidden: Boolean = false,
    onToggleMenuHidden: (() -> Unit)? = null,
    glowEnabled: Boolean = false,
    onToggleGlow: (() -> Unit)? = null,
) {
    val ttsParagraph = state.currentParagraphText.value
    val inverseParagraph = state.alternateParagraphText.value
    val hasInverse = inverseParagraph.isNotBlank()
    val displayText = when {
        paragraphMode == "inverse" && hasInverse -> inverseParagraph
        else -> ttsParagraph
    }
    val isBothMode = paragraphMode == "both" && hasInverse
    val hasParagraphText = showParagraphText && (displayText.isNotBlank() || isBothMode)

    val density = LocalDensity.current
    var showOpacitySlider by remember { mutableStateOf(false) }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val minWidth = 100f
    val maxWidth = screenWidthDp - 16f
    val ratio = ((panelWidth - minWidth) / (maxWidth - minWidth)).coerceIn(0f, 1f)

    fun lerpf(a: Float, b: Float, t: Float) = a + (b - a) * t

    val buttonSize = lerpf(20f, 28f, ratio).dp
    val iconSize = lerpf(14f, 22f, ratio).dp
    val iconCircleSize = lerpf(16f, 24f, ratio).dp
    val playButtonSize = lerpf(24f, 34f, ratio).dp
    val playIconSize = lerpf(20f, 30f, ratio).dp
    val progressHeight = lerpf(3f, 6f, ratio).dp
    val paragraphFontSize = lerpf(9f, 13f, ratio).sp

    val currentPanelWidth by rememberUpdatedState(panelWidth)
    val dragModifier = if (onDrag != null) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                },
                onDragEnd = { onDragEnd?.invoke() }
            )
        }
    } else {
        Modifier
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (!menuHidden) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = opacity) else Color.Transparent,
        shadowElevation = 0.dp,
        modifier = Modifier
            .then(dragModifier)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            AnimatedVisibility(
                visible = !menuHidden,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
            ) {
                Column {
                    MiniPlayerControls(
                        state = state,
                        onClose = onClose,
                        onStartHere = onStartHere,
                        chapterCurrentNumber = chapterCurrentNumber,
                        chaptersCount = chaptersCount,
                        buttonSize = buttonSize,
                        iconSize = iconSize,
                        iconCircleSize = iconCircleSize,
                        playButtonSize = playButtonSize,
                        playIconSize = playIconSize,
                        extraAction = {
                            if (onOpacityChange != null) {
                                IconButton(
                                    onClick = { showOpacitySlider = !showOpacitySlider },
                                    modifier = Modifier.size(buttonSize)
                                ) {
                                    Icon(
                                        Icons.Rounded.Tune,
                                        contentDescription = null,
                                        tint = if (showOpacitySlider) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(iconSize)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                    )
                                }
                            }
                        },
                        trailingAction = {
                            if (onShowTextToggle != null) {
                                IconButton(
                                    onClick = onShowTextToggle,
                                    modifier = Modifier.size(buttonSize)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.TextSnippet,
                                        contentDescription = null,
                                        tint = if (showTextToggle) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(iconSize)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                                    )
                                }
                            }
                        },
                    )

                    // YouTube-like progress bar with time labels
                    TtsSeekRow(
                        elapsedSeconds = state.elapsedSeconds.value,
                        totalDuration = state.totalDuration.value,
                        showRemaining = state.showRemaining.value,
                        onSeek = state.seekToTime,
                        onToggleShowRemaining = state.toggleShowRemaining,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )

                    if (onOpacityChange != null && showOpacitySlider) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.tts_floating_opacity),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Slider(
                                    value = opacityValue,
                                    onValueChange = { onOpacityChange(it) },
                                    valueRange = 0.3f..1f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                    )
                                )
                            }
                            if (onPanelWidthChange != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.tts_floating_width),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Slider(
                                        value = panelWidth,
                                        onValueChange = { onPanelWidthChange(it) },
                                        valueRange = minWidth..maxWidth,
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                        )
                                    )
                                }
                            }
                            if (onParagraphModeChange != null && state.parallelEnabled.value) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.tts_floating_paragraph),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.tts_voice),
                                        color = if (paragraphMode == "tts") MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (paragraphMode == "tts") FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .clickable { onParagraphModeChange("tts") }
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                    )
                                    Text(
                                        text = "/",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(R.string.tts_both),
                                        color = if (paragraphMode == "both") MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (paragraphMode == "both") FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .clickable { onParagraphModeChange("both") }
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                    )
                                    Text(
                                        text = "/",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(R.string.inverse),
                                        color = if (paragraphMode == "inverse") MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = if (paragraphMode == "inverse") FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .clickable { onParagraphModeChange("inverse") }
                                            .padding(horizontal = 6.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (hasParagraphText) {
                if (!menuHidden) Spacer(modifier = Modifier.height(4.dp))
                val glowColor = MaterialTheme.colorScheme.primary
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = opacity),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (glowEnabled) {
                                Modifier
                                    .border(1.5.dp, glowColor, RoundedCornerShape(8.dp))
                            } else {
                                Modifier
                            }
                        )
                        .pointerInput(Unit) {
                            var lastTapTime = 0L
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                var isPinch = false

                                val released = withTimeoutOrNull(1200L) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            isPinch = true
                                            break
                                        }
                                        if (pressed.isEmpty()) break
                                    }
                                    true
                                }

                                if (isPinch) {
                                    var prevSpan = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.size >= 2) {
                                            val p1 = pressed[0].position
                                            val p2 = pressed[1].position
                                            val span = (p1 - p2).getDistance()
                                            if (prevSpan > 0f) {
                                                val zoom = span / prevSpan
                                                val newWidth = (currentPanelWidth * zoom).coerceIn(minWidth, maxWidth)
                                                onPanelWidthChange?.invoke(newWidth)
                                            }
                                            prevSpan = span
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                } else if (released == null) {
                                    onToggleGlow?.invoke()
                                    do {
                                        val event = awaitPointerEvent()
                                    } while (event.changes.any { it.pressed })
                                } else {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapTime < 350) {
                                        onToggleMenuHidden?.invoke()
                                        lastTapTime = 0L
                                    } else {
                                        lastTapTime = now
                                    }
                                }
                            }
                        }
                ) {
                    if (isBothMode) {
                        val spokenRange = state.spokenWordRange.value
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            if (ttsHighlightEnabled && spokenRange != null) {
                                HighlightedText(
                                    text = ttsParagraph,
                                    range = spokenRange,
                                    highlightColorHex = ttsHighlightColor,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = paragraphFontSize),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    text = ttsParagraph,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = paragraphFontSize),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = inverseParagraph,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = paragraphFontSize * 0.85f),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            )
                        }
                    } else {
                        val spokenRange = state.spokenWordRange.value
                        val isInverse = paragraphMode == "inverse" && hasInverse
                        if (ttsHighlightEnabled && spokenRange != null && !isInverse) {
                            HighlightedText(
                                text = displayText,
                                range = spokenRange,
                                highlightColorHex = ttsHighlightColor,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = paragraphFontSize),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        } else {
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = paragraphFontSize),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * YouTube-like thin seekable progress bar for TTS playback.
 * Shows elapsed time (left) and a clickable total/remaining time (right).
 * Tapping or dragging the bar seeks to the corresponding time.
 */
@Composable
internal fun TtsSeekRow(
    elapsedSeconds: Int,
    totalDuration: Int,
    showRemaining: Boolean,
    onSeek: (Float) -> Unit,
    onToggleShowRemaining: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalDuration <= 0) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = formatDuration(elapsedSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Start,
        )
        TtsProgressBar(
            elapsedSeconds = elapsedSeconds,
            totalDuration = totalDuration,
            onSeek = onSeek,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        )
        val rightLabel = if (showRemaining) {
            "-${formatDuration((totalDuration - elapsedSeconds).coerceAtLeast(0))}"
        } else {
            formatDuration(totalDuration)
        }
        Text(
            text = rightLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(48.dp)
                .clickable { onToggleShowRemaining() },
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun TtsProgressBar(
    elapsedSeconds: Int,
    totalDuration: Int,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalDuration <= 0) return

    val fraction = (elapsedSeconds.toFloat() / totalDuration).coerceIn(0f, 1f)
    val trackHeight = 3.dp
    val thumbSize = 12.dp

    BoxWithConstraints(
        modifier = modifier
            .height(24.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(ratio * totalDuration)
                    },
                    onDrag = { change, _ ->
                        val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                        onSeek(ratio * totalDuration)
                    }
                )
            }
    ) {
        val trackWidth = maxWidth
        val thumbOffsetX = (fraction * (trackWidth - thumbSize)).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(trackWidth)
                .height(trackHeight)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                    RoundedCornerShape(2.dp)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width((fraction * trackWidth.value).dp)
                .height(trackHeight)
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(2.dp)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffsetX)
                .size(thumbSize)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "0:00"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${m}:${s.toString().padStart(2, '0')}"
    }
}

internal fun formatDurationCompact(seconds: Int): String {
    if (seconds <= 0) return "0s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return buildString {
        if (h > 0) append("${h}h ")
        if (m > 0 || h > 0) append("${m}m ")
        if (s > 0 && h == 0) append("${s}s")
    }.trimEnd()
}

@Composable
private fun HighlightedText(
    text: String,
    range: IntRange,
    highlightColorHex: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val bgColor = remember(highlightColorHex) {
        try {
            Color(android.graphics.Color.parseColor("#$highlightColorHex"))
        } catch (_: Exception) {
            Color(android.graphics.Color.parseColor("#FFFF6D00"))
        }
    }
    val annotated = remember(text, range) {
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(0, text.length)
        buildAnnotatedString {
            if (start > 0) append(text.substring(0, start))
            if (start < end) {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
                    append(text.substring(start, end))
                }
            }
            if (end < text.length) append(text.substring(end))
        }
    }
    Text(
        text = annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier.drawBehind {
            val l = layout ?: return@drawBehind
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(0, text.length)
            if (start >= end) return@drawBehind

            val sLine = l.getLineForOffset(start)
            val eLine = l.getLineForOffset(end - 1).coerceAtLeast(sLine)
            for (line in sLine..eLine) {
                val lineStartChar = l.getLineStart(line)
                val lineEndChar = (l.getLineEnd(line) - 1).coerceAtLeast(lineStartChar)
                val left = if (line == sLine) l.getBoundingBox(start).left else l.getBoundingBox(lineStartChar).left
                val right = if (line == eLine) l.getBoundingBox(end - 1).right else l.getBoundingBox(lineEndChar).right
                drawRoundRect(
                    color = bgColor.copy(alpha = 0.5f),
                    topLeft = Offset(left, l.getLineTop(line)),
                    size = Size(right - left, l.getLineBottom(line) - l.getLineTop(line)),
                    cornerRadius = CornerRadius(6f)
                )
            }
        }
    )
}
