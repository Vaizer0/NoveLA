package my.noveldokusha.features.chapterslist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TtsVideoJobState
import my.noveldokusha.core.appPreferences.TtsVideoJobStatus
import my.noveldokusha.coreui.components.ErrorView
import my.noveldokusha.chapterslist.R
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.text_to_speech.TtsVideoPreferences
import my.noveldokusha.tooling.application_workers.TtsVideoExportLauncher
import my.noveldokusha.tooling.application_workers.TtsVideoQueue

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
    onChapterAudio: (chapter: ChapterWithContext, source: TtsAudioSource) -> Unit,
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val appPreferences = remember { AppPreferences(context.applicationContext) }
    val videoPreferences = remember { TtsVideoPreferences(context.applicationContext) }
    var pendingVideo by remember { mutableStateOf<PendingVideoExport?>(null) }

    val videoDirectoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            pendingVideo = null
        } else {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            videoPreferences.outputDirectoryUri = uri.toString()
            val pending = pendingVideo
            pendingVideo = null
            if (pending != null) {
                enqueueVideoChapter(
                    context = context,
                    appPreferences = appPreferences,
                    videoPreferences = videoPreferences,
                    novelUrl = state.book.value.url,
                    novelTitle = state.book.value.title,
                    chapter = pending.chapter,
                    source = pending.source,
                )
            }
        }
    }

    // TtsVideoPreferences is intentionally a simple persisted store, so the chapter
    // screen polls it at a low cadence and mirrors only changes into Compose state.
    // This keeps queue/work-manager progress visible without touching the large
    // ChaptersViewModel or coupling the chapter UI to the worker implementation.
    LaunchedEffect(state.book.value.url) {
        while (true) {
            val byChapter = videoPreferences.jobs().values
                .asSequence()
                .filter { it.novelUrl == state.book.value.url }
                .associateBy { VideoJobKey(it.chapterUrl, it.source) }
            if (state.videoJobs != byChapter) {
                state.videoJobs.clear()
                state.videoJobs.putAll(byChapter)
            }
            delay(750)
        }
    }

    fun handleVideo(chapter: ChapterWithContext, source: TtsAudioSource) {
        val key = VideoJobKey(chapter.chapter.url, source)
        val job = state.videoJobs[key]
        when {
            job?.status == TtsVideoJobStatus.SUCCESS && job.outputUri.isNotBlank() -> {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(android.net.Uri.parse(job.outputUri), "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
            job?.status == TtsVideoJobStatus.QUEUED || job?.status == TtsVideoJobStatus.RUNNING -> {
                TtsVideoQueue.cancel(context, job.jobId)
            }
            else -> {
                if (videoPreferences.outputDirectoryUri.isBlank()) {
                    pendingVideo = PendingVideoExport(chapter, source)
                    videoDirectoryPicker.launch(null)
                } else {
                    enqueueVideoChapter(
                        context = context,
                        appPreferences = appPreferences,
                        videoPreferences = videoPreferences,
                        novelUrl = state.book.value.url,
                        novelTitle = state.book.value.title,
                        chapter = chapter,
                        source = source,
                    )
                }
            }
        }
    }

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

            items(
                items = state.chapters,
                key = { "_" + it.chapter.url },
                contentType = { 1 }
            ) {
                Column {
                    ChaptersScreenChapterItem(
                        chapterWithContext = it,
                        translatedTitle = state.translatedChapterTitles.value[it.chapter.url],
                        chapterSize = state.chapterSizes.value[it.chapter.url],
                        audioOriginalJob = state.audioJobs[
                            AudioJobKey(it.chapter.url, TtsAudioSource.ORIGINAL)
                        ],
                        audioOriginalFileExists = state.audioFilesExist[
                            AudioJobKey(it.chapter.url, TtsAudioSource.ORIGINAL)
                        ] ?: false,
                        audioTranslatedJob = state.audioJobs[
                            AudioJobKey(it.chapter.url, TtsAudioSource.TRANSLATED)
                        ],
                        audioTranslatedFileExists = state.audioFilesExist[
                            AudioJobKey(it.chapter.url, TtsAudioSource.TRANSLATED)
                        ] ?: false,
                        selected = state.selectedChaptersUrl.containsKey(it.chapter.url),
                        isLocalSource = state.isLocalSource.value,
                        highlighted = it.chapter.url == highlightedChapterUrl,
                        onClick = { onChapterClick(it) },
                        onLongClick = { onChapterLongClick(it) },
                        onDownload = { onChapterDownload(it) },
                        onAudioOriginal = { onChapterAudio(it, TtsAudioSource.ORIGINAL) },
                        onAudioTranslated = { onChapterAudio(it, TtsAudioSource.TRANSLATED) },
                        translatedAudioAvailable =
                            state.translatedAudioAvailable.value[it.chapter.url] ?: false
                    )
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VideoChapterAction(
                            label = "Video",
                            job = state.videoJobs[VideoJobKey(it.chapter.url, TtsAudioSource.ORIGINAL)],
                            enabled = true,
                            onClick = { handleVideo(it, TtsAudioSource.ORIGINAL) },
                        )
                        if (state.translatedAudioAvailable.value[it.chapter.url] == true ||
                            state.videoJobs.containsKey(VideoJobKey(it.chapter.url, TtsAudioSource.TRANSLATED))
                        ) {
                            VideoChapterAction(
                                label = "Translated video",
                                job = state.videoJobs[VideoJobKey(it.chapter.url, TtsAudioSource.TRANSLATED)],
                                enabled = true,
                                onClick = { handleVideo(it, TtsAudioSource.TRANSLATED) },
                            )
                        }
                    }
                }
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
private fun VideoChapterAction(
    label: String,
    job: TtsVideoJobState?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val status = job?.status
    val active = status == TtsVideoJobStatus.QUEUED || status == TtsVideoJobStatus.RUNNING
    val text = when (status) {
        TtsVideoJobStatus.QUEUED -> "$label · queued"
        TtsVideoJobStatus.RUNNING -> "$label · ${job?.progress?.coerceIn(0, 100) ?: 0}%"
        TtsVideoJobStatus.SUCCESS -> "$label · ready"
        TtsVideoJobStatus.FAILED -> "$label · failed"
        TtsVideoJobStatus.CANCELLED -> "$label · cancelled"
        null -> label
    }
    TextButton(onClick = onClick, enabled = enabled || job != null) {
        when {
            active -> {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        progress = { ((job?.progress ?: 0).coerceIn(0, 100)) / 100f },
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "${(job?.progress ?: 0).coerceIn(0, 100)}",
                        fontSize = 7.sp,
                    )
                }
            }
            status == TtsVideoJobStatus.SUCCESS -> Icon(Icons.Filled.CheckCircle, contentDescription = null)
            status == TtsVideoJobStatus.FAILED -> Icon(Icons.Filled.Refresh, contentDescription = null)
            else -> Icon(Icons.Filled.PlayArrow, contentDescription = null)
        }
        Text(text = text, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun enqueueVideoChapter(
    context: android.content.Context,
    appPreferences: AppPreferences,
    videoPreferences: TtsVideoPreferences,
    novelUrl: String,
    novelTitle: String,
    chapter: ChapterWithContext,
    source: TtsAudioSource,
) {
    // The launcher snapshots the dedicated video settings and TTS profile at enqueue time.
    // Keep all user-visible failures explicit instead of silently pretending a job started.
    runCatching {
        require(videoPreferences.outputDirectoryUri.isNotBlank()) { "Select a video output folder first" }
        TtsVideoExportLauncher.enqueue(
            context = context,
            appPreferences = appPreferences,
            novelUrl = novelUrl,
            novelTitle = novelTitle,
            chapterUrl = chapter.chapter.url,
            chapterIndex = chapter.chapter.position,
            chapterTitle = chapter.chapter.title,
            source = source,
        )
    }.onFailure {
        android.widget.Toast.makeText(context, it.message ?: "Unable to start video export", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private data class PendingVideoExport(
    val chapter: ChapterWithContext,
    val source: TtsAudioSource,
)
