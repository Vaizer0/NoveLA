package my.noveldokusha.features.reader.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.features.reader.features.TextToSpeechSettingData
import my.noveldokusha.reader.R

@Composable
internal fun FloatingTtsOverlayContent(
    state: TextToSpeechSettingData,
    showText: Boolean,
    opacity: Float,
    onClose: () -> Unit,
    onToggleExpand: () -> Unit,
    isExpanded: Boolean,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    panelWidth: Float = 300f,
    onPanelWidthChange: ((Float) -> Unit)? = null,
    opacityValue: Float = 0.95f,
    onOpacityChange: ((Float) -> Unit)? = null,
    showTextToggle: Boolean = true,
    onShowTextToggle: (() -> Unit)? = null,
    paragraphMode: String = "tts",
    onParagraphModeChange: ((String) -> Unit)? = null,
) {
    if (isExpanded) {
        TtsMiniPlayer(
            state = state,
            onClose = onClose,
            onStartHere = { state.playFirstVisibleItem() },
            showParagraphText = showText,
            opacity = opacity,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            panelWidth = panelWidth,
            onPanelWidthChange = onPanelWidthChange,
            opacityValue = opacityValue,
            onOpacityChange = onOpacityChange,
            showTextToggle = showTextToggle,
            onShowTextToggle = onShowTextToggle,
            paragraphMode = paragraphMode,
            onParagraphModeChange = onParagraphModeChange,
        )
    } else {
        FloatingBubble(
            isPlaying = state.isPlaying.value,
            onClick = onToggleExpand,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            opacity = opacity,
        )
    }
}

@Composable
private fun FloatingBubble(
    isPlaying: Boolean,
    onClick: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    opacity: Float = 1f,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = opacity),
        shadowElevation = 8.dp,
        modifier = Modifier
            .size(56.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    },
                    onDragEnd = onDragEnd
                )
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = isPlaying,
                label = ""
            ) { playing ->
                when (playing) {
                    true -> Icon(
                        Icons.Rounded.Pause,
                        contentDescription = stringResource(R.string.media_control_pause),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    false -> Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.media_control_play),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
