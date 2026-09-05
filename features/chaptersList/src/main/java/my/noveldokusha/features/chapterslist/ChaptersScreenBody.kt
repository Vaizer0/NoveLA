package my.noveldokusha.features.chapterslist

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TranslationLangPair
import my.noveldokusha.coreui.components.ErrorView
import my.noveldokusha.chapterslist.R
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.text_to_speech.TtsAudioExportRequest
import my.noveldokusha.text_to_speech.TtsAudioFormat
import my.noveldokusha.text_to_speech.TtsExportMode
import my.noveldokusha.tooling.application_workers.TtsCinematicVideoQueue

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface ChaptersCinematicVideoEntryPoint {
    fun appPreferences(): AppPreferences
}

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
    onChapterVideo: ((chapter: ChapterWithContext, source: TtsAudioSource) -> Unit)? = null,
    onPullRefresh: () -> Unit,
    onCoverLongClick: () -> Unit,
    onGlobalSearchClick: (input: String) -> Unit,
    bookCategory: String,
    categories: () -> List<String>,
    onCategoryClick: () -> Unit,
    scraper: Scraper,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appPreferences = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ChaptersCinematicVideoEntryPoint::class.java,
        ).appPreferences()
    }

    fun enqueueDefaultVideo(chapter: ChapterWithContext, source: TtsAudioSource) {
        val outputDirectoryUri = appPreferences.TTS_AUDIO_DOWNLOAD_LOCATION_URI.value
        if (outputDirectoryUri.isBlank()) {
            Toast.makeText(context, "Choose the audio export folder first", Toast.LENGTH_SHORT).show()
            return
        }

        val pair = if (source == TtsAudioSource.TRANSLATED) {
            appPreferences.translationPairForBook(state.book.value.url)
        } else {
            TranslationLangPair()
        }

        val chapterIndex = state.chapters.indexOfFirst { it.chapter.url == chapter.chapter.url }
            .takeIf { it >= 0 }
            ?: chapter.chapter.position

        val request = TtsAudioExportRequest(
            jobId = TtsAudioExportRequest.makeJobId(
                state.book.value.url,
                chapter.chapter.url,
                source,
                pair.source,
                pair.target,
                TtsExportMode.CINEMATIC_VIDEO,
            ),
            novelTitle = state.book.value.title,
            novelUrl = state.book.value.url,
            chapterUrl = chapter.chapter.url,
            chapterTitle = chapter.chapter.title,
            chapterIndex = chapterIndex,
            source = source,
            enginePackage = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ENGINE.value,
            voiceId = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ID.value,
            speed = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_SPEED.value,
            pitch = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_PITCH.value,
            outputDirectoryUri = outputDirectoryUri,
            format = TtsAudioFormat.WAV,
            translationSourceLang = pair.source,
            translationTargetLang = pair.target,
            exportMode = TtsExportMode.CINEMATIC_VIDEO,
        )

        runCatching {
            TtsCinematicVideoQueue.enqueue(
                context = context.applicationContext,
                appPreferences = appPreferences,
                request = request,
            )
        }.onFailure { error ->
            Toast.makeText(
                context,
                error.message ?: "Could not start cinematic video export",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val videoAction = onChapterVideo ?: ::enqueueDefaultVideo

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
            onDismiss = { showGoToChapterDialog = false },
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
            item(key = "header", contentType = { 0 }) {
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
                contentType = { 1 },
            ) {
                val originalKey = AudioJobKey(it.chapter.url, TtsAudioSource.ORIGINAL)
                val translatedKey = AudioJobKey(it.chapter.url, TtsAudioSource.TRANSLATED)
                ChaptersScreenChapterItem(
                    chapterWithContext = it,
                    translatedTitle = state.translatedChapterTitles.value[it.chapter.url],
                    chapterSize = state.chapterSizes.value[it.chapter.url],
                    audioOriginalJob = state.audioJobs[originalKey],
                    audioOriginalFileExists = state.audioFilesExist[originalKey] ?: false,
                    audioTranslatedJob = state.audioJobs[translatedKey],
                    audioTranslatedFileExists = state.audioFilesExist[translatedKey] ?: false,
                    selected = state.selectedChaptersUrl.containsKey(it.chapter.url),
                    isLocalSource = state.isLocalSource.value,
                    highlighted = it.chapter.url == highlightedChapterUrl,
                    onClick = { onChapterClick(it) },
                    onLongClick = { onChapterLongClick(it) },
                    onDownload = { onChapterDownload(it) },
                    onAudioOriginal = { onChapterAudio(it, TtsAudioSource.ORIGINAL) },
                    onAudioTranslated = { onChapterAudio(it, TtsAudioSource.TRANSLATED) },
                    onVideoOriginal = { videoAction(it, TtsAudioSource.ORIGINAL) },
                    onVideoTranslated = { videoAction(it, TtsAudioSource.TRANSLATED) },
                    translatedAudioAvailable = state.translatedAudioAvailable.value[it.chapter.url] ?: false,
                )
            }

            if (state.error.value.isNotBlank()) item(key = "error", contentType = { 2 }) {
                ErrorView(error = state.error.value)
            }
        }
    }
}
