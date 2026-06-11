package my.noveldokusha.features.chapterslist

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GTranslate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import my.noveldokusha.coreui.components.BookImageButtonView
import my.noveldokusha.coreui.components.BookTitlePosition
import my.noveldokusha.coreui.components.ExpandableText
import my.noveldokusha.coreui.components.ImageView
import my.noveldokusha.coreui.theme.clickableNoIndicator
import my.noveldokusha.chapterslist.R
import my.noveldokusha.core.rememberResolvedBookImagePath

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChaptersScreenHeader(
    bookState: ChaptersScreenState.BookState,
    genres: List<String>,
    sourceCatalogName: String,
    numberOfChapters: Int,
    paddingValues: PaddingValues,
    translatedTitle: String?,
    translatedDescription: String?,
    isTranslating: Boolean,
    onTranslateClick: () -> Unit,
    onClearTranslationClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCoverLongClick: () -> Unit,
    onGlobalSearchClick: (input: String) -> Unit,
    onScrollToLastRead: (() -> Unit)?,
    onScrollToChapter: () -> Unit,
) {
    val coverImageModel = bookState.coverImageUrl?.let {
        rememberResolvedBookImagePath(
            bookUrl = bookState.url,
            imagePath = it
        )
    } ?: R.drawable.ic_baseline_empty_24

    Box(modifier = modifier) {
        Box {
            ImageView(
                imageModel = coverImageModel,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .alpha(0.2f)
                    .fillMaxWidth()
                    .height(200.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            1f to MaterialTheme.colorScheme.background,
                        )
                    )
            )
        }
        Column(
            modifier = Modifier
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                var showImageFullScreen by rememberSaveable { mutableStateOf(false) }
                BookImageButtonView(
                    title = "",
                    coverImageModel = coverImageModel,
                    onClick = { showImageFullScreen = true },
                    onLongClick = onCoverLongClick,
                    bookTitlePosition = BookTitlePosition.Hidden,
                    modifier = Modifier.weight(1f)
                )
                if (showImageFullScreen) Dialog(
                    onDismissRequest = { showImageFullScreen = false },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = true
                    )
                ) {
                    ImageView(
                        imageModel = coverImageModel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableNoIndicator { showImageFullScreen = false },
                        contentScale = ContentScale.Fit
                    )
                }

                Column(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxHeight()
                        .weight(1f),
                ) {
                    SelectionContainer {
                        Text(
                            text = translatedTitle ?: bookState.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 5,
                            modifier = Modifier.clickableNoIndicator {
                                onGlobalSearchClick(bookState.title)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SelectionContainer {
                        Text(
                            text = sourceCatalogName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                    SelectionContainer {
                        Text(
                            text = stringResource(id = R.string.chapters) + " " + numberOfChapters.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                }
            }

            // Описание
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                val displayDesc = (translatedDescription ?: bookState.description).trim()
                val text by remember(displayDesc) { derivedStateOf { displayDesc } }
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    ExpandableText(
                        text = text,
                        linesForExpand = 4,
                    )
                }
            }

            // Жанры — показываем только если есть, с возможностью свернуть/развернуть
            if (genres.isNotEmpty()) {
                val genresCollapsedCount = 8
                var genresExpanded by rememberSaveable { mutableStateOf(false) }
                val visibleGenres = if (genresExpanded || genres.size <= genresCollapsedCount)
                    genres
                else
                    genres.take(genresCollapsedCount)

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { if (genres.size > genresCollapsedCount) genresExpanded = !genresExpanded }
                        )
                ) {
                    visibleGenres.forEach { genre ->
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    text = genre,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                disabledContainerColor = MaterialTheme.colorScheme.surface,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = false,
                                borderColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                    // Чип "ещё N" когда свёрнуто
                    if (!genresExpanded && genres.size > genresCollapsedCount) {
                        AssistChip(
                            onClick = { genresExpanded = true },
                            label = {
                                Text(
                                    text = "+${genres.size - genresCollapsedCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                }
            }

            // Кнопки навигации по главам
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                // Кнопка "К последней читаемой"
                if (onScrollToLastRead != null) {
                    Button(
                        onClick = onScrollToLastRead,
                        shape = my.noveldokusha.coreui.theme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BookmarkAdded,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(id = R.string.scroll_to_last_read_chapter),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // Кнопка "Перевод"
                Button(
                    onClick = {
                        if (translatedTitle != null || translatedDescription != null) {
                            onClearTranslationClick()
                        } else {
                            onTranslateClick()
                        }
                    },
                    shape = my.noveldokusha.coreui.theme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Icon(
                            imageVector = if (translatedTitle != null || translatedDescription != null) Icons.Outlined.Close else Icons.Outlined.GTranslate,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (translatedTitle != null || translatedDescription != null) stringResource(R.string.clear_translation) else stringResource(R.string.translate),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                // Кнопка "Перейти к главе"
                Button(
                    onClick = onScrollToChapter,
                    shape = my.noveldokusha.coreui.theme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(id = R.string.go_to_chapter),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            HorizontalDivider(
                Modifier
                    .padding(horizontal = 40.dp)
                    .alpha(0.5f), thickness = Dp.Hairline
            )
        }
    }
}