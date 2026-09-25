package my.noveldokusha.features.chapterslist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.components.ErrorView
import my.noveldokusha.chapterslist.R
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.scraper.Scraper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChaptersScreenBody(
    state: ChaptersScreenState,
    lazyListState: LazyListState,
    innerPadding: PaddingValues,
    translatedTitle: String?,
    translatedDescription: String?,
    isTranslating: Boolean,
    onTranslateClick: () -> Unit,
    onClearTranslationClick: () -> Unit,
    onChapterClick: (chapter: ChapterWithContext) -> Unit,
    onChapterLongClick: (chapter: ChapterWithContext) -> Unit,
    onChapterDownload: (chapter: ChapterWithContext) -> Unit,
    onPullRefresh: () -> Unit,
    onCoverLongClick: () -> Unit,
    onGlobalSearchClick: (input: String) -> Unit,
    bookCategory: String,
    categories: () -> List<String>,
    onCategoryClick: () -> Unit,
    scraper: Scraper,
    modifier: Modifier = Modifier,
) {
    var isRefreshingDelayed by remember { mutableStateOf(state.isRefreshing.value) }
    LaunchedEffect(Unit) {
        snapshotFlow { state.isRefreshing.value }
            .distinctUntilChanged()
            .collectLatest {
                if (it) delay(200)
                isRefreshingDelayed = it
            }
    }

    val pullToRefreshState = rememberPullToRefreshState()
    val coroutineScope = rememberCoroutineScope()

    var highlightedChapterUrl by remember { mutableStateOf<String?>(null) }

    val scrollOffset = -350

    suspend fun smoothScrollToIndex(index: Int) {
        val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
        val firstVisible = lazyListState.firstVisibleItemIndex
        val isNearby = index in (firstVisible - 5)..(firstVisible + visibleItems.size + 5)
        if (!isNearby) {
            val jumpTo = if (index > firstVisible) index - 3 else index + 3
            lazyListState.scrollToItem(jumpTo.coerceIn(0, lazyListState.layoutInfo.totalItemsCount - 1))
        }
        lazyListState.animateScrollToItem(index, scrollOffset)
    }

    val lastReadChapterIndex = remember(state.book.value.lastReadChapter, state.chapters.size) {
        val url = state.book.value.lastReadChapter ?: return@remember null
        val idx = state.chapters.indexOfFirst { it.chapter.url == url }
        if (idx == -1) null else idx + 1
    }

    val readChapters by remember { derivedStateOf { state.chapters.count { it.chapter.read } } }

    val onScrollToLastRead: (() -> Unit)? = lastReadChapterIndex?.let { index ->
        {
            coroutineScope.launch {
                val url = state.book.value.lastReadChapter
                smoothScrollToIndex(index)
                highlightedChapterUrl = url
                delay(1500)
                highlightedChapterUrl = null
            }
        }
    }

    var showGoToChapterDialog by rememberSaveable { mutableStateOf(false) }

    if (showGoToChapterDialog) {
        GoToChapterDialog(
            chapters = state.chapters,
            onChapterSelected = { index, url ->
                coroutineScope.launch {
                    smoothScrollToIndex(index)
                    highlightedChapterUrl = url
                    delay(1500)
                    highlightedChapterUrl = null
                }
            },
            onDismiss = { showGoToChapterDialog = false }
        )
    }

    PullToRefreshBox(
        modifier = modifier,
        isRefreshing = isRefreshingDelayed,
        onRefresh = onPullRefresh,
        state = pullToRefreshState,
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 300.dp),
        ) {
            item(
                key = "header",
                contentType = { 0 },
            ) {
                ChaptersScreenHeader(
                    bookState = state.book.value,
                    genres = state.genres.value,
                    rating = state.rating.value,
                    status = state.status.value,
                    lastUpdateDate = state.lastUpdateDate.value,
                    sourceCatalogName = if (state.sourceCatalogNameStrRes.value == 0) {
                        val source = scraper.getCompatibleSource(state.book.value.url)
                        source?.name ?: stringResource(R.string.invalid_source)
                    } else {
                        stringResource(id = state.sourceCatalogNameStrRes.value ?: R.string.invalid_source)
                    },
                    numberOfChapters = state.chapters.size,
                    readChapters = readChapters,
                    paddingValues = innerPadding,
                    modifier = Modifier,
                    translatedTitle = translatedTitle,
                    translatedDescription = translatedDescription,
                    isTranslating = isTranslating,
                    onTranslateClick = onTranslateClick,
                    onClearTranslationClick = onClearTranslationClick,
                    onCoverLongClick = onCoverLongClick,
                    onGlobalSearchClick = onGlobalSearchClick,
                    onScrollToLastRead = onScrollToLastRead,
                    onScrollToChapter = { showGoToChapterDialog = true },
                    bookCategory = bookCategory,
                    categories = categories,
                    onCategoryClick = onCategoryClick,
                )
            }

            state.audiobookExportStatus.value?.let { exportStatus ->
                item(
                    key = "audiobook_export_status",
                    contentType = { 3 },
                ) {
                    AudiobookExportStatusBar(
                        status = exportStatus,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            items(
                items = state.chapters,
                key = { "_" + it.chapter.url },
                contentType = { 1 }
            ) {
                ChaptersScreenChapterItem(
                    chapterWithContext = it,
                    translatedTitle = state.translatedChapterTitles.value[it.chapter.url],
                    chapterSize = state.chapterSizes.value[it.chapter.url],
                    selected = state.selectedChaptersUrl.containsKey(it.chapter.url),
                    isLocalSource = state.isLocalSource.value,
                    highlighted = it.chapter.url == highlightedChapterUrl,
                    onClick = { onChapterClick(it) },
                    onLongClick = { onChapterLongClick(it) },
                    onDownload = { onChapterDownload(it) }
                )
            }

            if (state.error.value.isNotBlank()) item(
                key = "error",
                contentType = { 2 }
            ) {
                ErrorView(error = state.error.value)
            }
        }
    }
}

@Composable
private fun AudiobookExportStatusBar(
    status: AudiobookExportUiState,
    modifier: Modifier = Modifier,
) {
    val active = status.state == AudiobookExportWorkState.ENQUEUED ||
        status.state == AudiobookExportWorkState.RUNNING ||
        status.state == AudiobookExportWorkState.BLOCKED
    val failed = status.state == AudiobookExportWorkState.FAILED ||
        status.state == AudiobookExportWorkState.CANCELLED
    val percent = status.percent.coerceIn(0, 100)

    Column(
        modifier = modifier,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    active -> "Audiobook export"
                    status.state == AudiobookExportWorkState.SUCCEEDED -> "Audiobook export complete"
                    failed -> "Audiobook export stopped"
                    else -> "Audiobook export"
                },
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${percent}%",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )

        Text(
            text = when {
                active -> {
                    val range = if (status.startChapter > 0 && status.endChapter > 0) {
                        "Chapters ${status.startChapter}–${status.endChapter}"
                    } else {
                        "Selected chapters"
                    }
                    val stage = status.stage
                        .replace('_', ' ')
                        .lowercase()
                        .replaceFirstChar { it.uppercase() }
                    "${status.format} • $range${if (stage.isNotBlank()) " • $stage" else ""}"
                }
                failed -> status.error?.takeIf(String::isNotBlank)
                    ?: "Export stopped before completion"
                status.state == AudiobookExportWorkState.SUCCEEDED ->
                    "${status.format} • Chapters ${status.startChapter}–${status.endChapter}"
                else -> "Audiobook export"
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
