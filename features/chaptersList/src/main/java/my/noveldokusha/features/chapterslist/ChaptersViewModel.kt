package my.noveldokusha.features.chapterslist

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import my.noveldokusha.core.Response
import androidx.lifecycle.ViewModel
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.DownloadManager
import my.noveldokusha.data.EnqueueResult
import my.noveldokusha.data.DownloaderRepository
import my.noveldokusha.data.LocalBookImporterRepository
import my.noveldokusha.data.VideoRepository
import my.noveldokusha.features.reader.video.DownloadState
import my.noveldokusha.features.reader.video.VideoDownloadManager
import my.noveldokusha.chapterslist.R
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.Toasty
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TranslationSettingsResolver
import my.noveldokusha.core.appPreferences.resolveExportDirectoryDisplayName
import my.noveldokusha.core.domain.ChapterPagination
import my.noveldokusha.core.isContentUri
import my.noveldokusha.core.isLocalUri
import my.noveldokusha.core.utils.GenreUtils
import my.noveldokusha.core.utils.StateExtra_String
import my.noveldokusha.core.utils.toState
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.DAOs.BookTranslationDao
import my.noveldokusha.feature.local_database.DAOs.BookTitleTranslation
import my.noveldokusha.feature.local_database.DAOs.ChapterBodyDao
import my.noveldokusha.feature.local_database.DAOs.ChapterDao
import my.noveldokusha.feature.local_database.DAOs.ChapterTranslationDao
import my.noveldokusha.feature.local_database.DAOs.LibraryDao
import my.noveldokusha.feature.local_database.DAOs.ReadingHistoryDao
import my.noveldokusha.feature.local_database.tables.Chapter
import my.noveldokusha.feature.local_database.tables.BookTranslation
import my.noveldokusha.feature.local_database.tables.ReadingHistory
import my.noveldokusha.interactor.LibraryUpdatesInteractions
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.core.utils.normalizeBookUrl
import my.noveldokusha.chapterslist.BuildConfig
import my.noveldokusha.debug.MemoryDiagnostics
import my.noveldokusha.text_translator.domain.TranslationManager
import my.noveldokusha.tooling.application_workers.AudiobookExportWorker
import my.noveldokusha.tooling.application_workers.BookExportWorker
import my.noveldokusha.tooling.application_workers.ExportMode
import my.noveldokusha.tooling.audiobook.AudiobookContentMode
import my.noveldokusha.tooling.audiobook.AudiobookExportProgressBus
import my.noveldokusha.tooling.audiobook.AudiobookExportRequest
import my.noveldokusha.tooling.audiobook.AudiobookFormat
import my.noveldokusha.tooling.audiobook.VisualSource
import timber.log.Timber
import javax.inject.Inject

interface ChapterStateBundle {
    val rawBookUrl: String
    val bookTitle: String
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class ChaptersViewModel @Inject constructor(
    private val appRepository: AppRepository,
    @ApplicationContext private val context: Context,
    private val appScope: AppCoroutineScope,
    scraper: Scraper,
    private val toasty: Toasty,
    private val appPreferences: AppPreferences,
    private val appFileResolver: AppFileResolver,
    private val downloaderRepository: DownloaderRepository,
    val downloadManager: DownloadManager,
    private val chaptersRepository: ChaptersRepository,
    private val localBookImporterRepository: LocalBookImporterRepository,
    private val libraryDao: LibraryDao,
    private val chapterDao: ChapterDao,
    private val chapterBodyDao: ChapterBodyDao,
    private val chapterTranslationDao: ChapterTranslationDao,
    private val bookTranslationDao: BookTranslationDao,
    private val translationSettingsResolver: TranslationSettingsResolver,
    private val translationManager: TranslationManager,
    private val readingHistoryDao: ReadingHistoryDao,
    private val libraryUpdatesInteractions: LibraryUpdatesInteractions,
    private val videoDownloadManager: VideoDownloadManager,
    private val videoRepository: VideoRepository,
    stateHandle: SavedStateHandle,
) : ViewModel(), ChapterStateBundle {

    override val rawBookUrl by StateExtra_String(stateHandle)
    override val bookTitle by StateExtra_String(stateHandle)

    private val bookUrlFlow = MutableStateFlow(
        normalizeBookUrl(
            appFileResolver.getLocalIfContentType(rawBookUrl, bookFolderName = bookTitle)
        )
    )
    private var bookUrl: String
        get() = bookUrlFlow.value
        set(value) {
            bookUrlFlow.value = value
        }

    @Volatile
    private var loadChaptersJob: Job? = null

    private var lastBookmarkClickMs = 0L

    @Volatile
    private var lastSelectedChapterUrl: String? = null
    private val source = scraper.getCompatibleSource(bookUrl)
    private val book = bookUrlFlow.flatMapLatest { url ->
        appRepository.libraryBooks.getFlow(url)
    }
        .filterNotNull()
        .map(ChaptersScreenState::BookState)
        .toState(
            viewModelScope,
            ChaptersScreenState.BookState(title = bookTitle, url = bookUrl, coverImageUrl = null)
        )

    val scraper: Scraper = scraper

    val state = ChaptersScreenState(
        book = book,
        error = mutableStateOf(""),
        chapters = mutableStateListOf(),
        selectedChaptersUrl = mutableStateMapOf(),
        isRefreshing = mutableStateOf(false),
        sourceCatalogNameStrRes = mutableStateOf(source?.nameStrId),
        settingChapterSort = appPreferences.CHAPTERS_SORT_ASCENDING.state(viewModelScope),
        isLocalSource = mutableStateOf(bookUrl.isLocalUri),
        isRefreshable = mutableStateOf(true),
        genres = mutableStateOf(emptyList()),
        rating = mutableStateOf(""),
        status = mutableStateOf(""),
        lastUpdateDate = mutableStateOf(""),
        translatedChapterTitles = mutableStateOf(emptyMap()),
        chapterSizes = mutableStateOf(emptyMap()),
        downloadTask = mutableStateOf(null),
        videoDownloadStates = mutableStateOf(emptyMap()),
    )

    // ─── Экспорт книги ───────────────────────────────────────────────────────

    val exportDialogState = mutableStateOf<ExportDialogState>(ExportDialogState.Hidden)
    val exportMessage = mutableStateOf<String?>(null)

    private var pendingExport: PendingExport? = null

    // Инжектируемая точка вызова воркера: тесты подменяют её лямбдой-шпионом.
    var enqueue: (Context, String, String, ExportMode, String, String, Int, String) -> Unit =
        BookExportWorker::enqueue

    // Инжектируемая точка разрешения имени папки экспорта: тесты подменяют её,
    // чтобы не зависеть от реального IO-хопа внутри resolveExportDirectoryDisplayName.
    var resolveExportDirectoryName: suspend (ContentResolver, String) -> String? =
        ::resolveExportDirectoryDisplayName

    fun onExportClicked(bookUrl: String, bookTitle: String) {
        viewModelScope.launch {
            // Счётчики — агрегатные запросы по bookUrl (JOIN), а не списки тел/переводов:
            // раньше диалог ждал десятки IN-запросов и перекачку всех JSON-текстов
            // переводов из БД — секунды на книгах с тысячами глав.
            val totalChapters = chapterDao.countByBookUrl(bookUrl)
            val downloadedChapters = chapterBodyDao.countDownloadedBodies(bookUrl)
            val availableTranslations = chapterTranslationDao
                .getTranslationGroups(bookUrl)
                .map { LangPair(it.sourceLang, it.targetLang, it.count) }
            val directoryUri = appPreferences.EXPORT_DIRECTORY_URI.value
            val exportDirectoryName = directoryUri.takeIf { it.isNotBlank() }
                ?.let { resolveExportDirectoryName(context.contentResolver, it) }

            if (downloadedChapters == 0) {
                exportMessage.value = context.getString(StringsR.string.export_no_chapters)
                return@launch
            }

            exportDialogState.value = ExportDialogState.ContentChoice(
                bookUrl = bookUrl,
                bookTitle = bookTitle,
                totalChapters = totalChapters,
                downloadedChapters = downloadedChapters,
                availableTranslations = availableTranslations,
                exportDirectoryName = exportDirectoryName,
            )
        }
    }

    fun onExportContentChosen(mode: String, sourceLang: String, targetLang: String) {
        val choice = exportDialogState.value as? ExportDialogState.ContentChoice ?: return
        // Для перевода счётчик — число переведённых глав выбранной пары, для
        // оригинала — число скачанных тел.
        val availableCount = availableCountFor(choice, mode, sourceLang, targetLang)
        if (availableCount == 0) {
            // Для перевода сообщение своё: «Нет скачанных глав» вводит в заблуждение.
            exportMessage.value = context.getString(
                if (mode == "translation") StringsR.string.export_no_translated_chapters
                else StringsR.string.export_no_chapters
            )
            return
        }
        onExportConfirmed(mode, sourceLang, targetLang)
    }

    fun onExportConfirmed(mode: String, sourceLang: String, targetLang: String) {
        val dialog = exportDialogState.value
        val (bookUrl, bookTitle) = when (dialog) {
            is ExportDialogState.ContentChoice -> dialog.bookUrl to dialog.bookTitle
            else -> return
        }
        val availableCount = availableCountFor(dialog, mode, sourceLang, targetLang)
        val directoryUri = appPreferences.EXPORT_DIRECTORY_URI.value
        if (directoryUri.isBlank()) {
            pendingExport = PendingExport(
                bookUrl = bookUrl,
                bookTitle = bookTitle,
                mode = mode,
                sourceLang = sourceLang,
                targetLang = targetLang,
                availableCount = availableCount,
            )
            exportDialogState.value = ExportDialogState.NeedDirectory
        } else {
            enqueue(
                context, bookUrl, bookTitle,
                if (mode == "original") ExportMode.ORIGINAL
                else ExportMode.TRANSLATION,
                sourceLang, targetLang, availableCount, directoryUri
            )
            exportMessage.value = context.getString(StringsR.string.export_started)
            exportDialogState.value = ExportDialogState.Hidden
        }
    }

    fun onExportDirectorySaved(uri: String) {
        appPreferences.EXPORT_DIRECTORY_URI.value = uri
        val pending = pendingExport
        if (pending != null) {
            pendingExport = null
            enqueue(
                context, pending.bookUrl, pending.bookTitle,
                if (pending.mode == "original") ExportMode.ORIGINAL
                else ExportMode.TRANSLATION,
                pending.sourceLang, pending.targetLang, pending.availableCount, uri
            )
            exportMessage.value = context.getString(StringsR.string.export_started)
            exportDialogState.value = ExportDialogState.Hidden
        } else {
            // Случай «Change» в ContentChoice: диалог остаётся открытым, но строку
            // с именем папки нужно обновить — иначе после выбора папки висит «not set».
            viewModelScope.launch {
                val name = resolveExportDirectoryName(context.contentResolver, uri)
                val state = exportDialogState.value
                if (state is ExportDialogState.ContentChoice) {
                    exportDialogState.value = state.copy(exportDirectoryName = name)
                }
            }
        }
    }

    fun onExportDialogDismiss() {
        // Сбрасываем отложенный экспорт: иначе после отмены в NeedDirectory
        // stale pendingExport останется и сработает для следующей книги.
        pendingExport = null
        exportDialogState.value = ExportDialogState.Hidden
    }

    // ─── Экспорт аудиокниги (WAV/MP4 + JSON) ─────────────────────────────────

    val audiobookDialogState =
        mutableStateOf<AudiobookExportDialogState>(AudiobookExportDialogState.Hidden)
    val audiobookMessage = mutableStateOf<String?>(null)

    /** Прогресс текущего аудиоэкспорта (0..100) или null, если он не идёт. */
    val audiobookProgress = mutableStateOf<Int?>(null)

    /** true — идёт экспорт MP4 (видео), false — WAV (аудио). */
    val audiobookProgressIsVideo = mutableStateOf(false)

    /** true — пользователь свернул прогресс («Оставить в фоне»). */
    val audiobookProgressDismissed = mutableStateOf(false)

    private var pendingAudiobook: PendingAudiobookExport? = null

    init {
        // Живой прогресс идёт напрямую из воркера (тот же процесс), поэтому
        // проценты обновляются сразу — без опроса WorkManager и «зависания на 0%».
        viewModelScope.launch {
            AudiobookExportProgressBus.progress.collect { live ->
                if (live == null) {
                    audiobookProgress.value = null
                    audiobookProgressDismissed.value = false
                } else {
                    if (audiobookProgress.value == null) {
                        // Новый экспорт: показываем диалог снова, даже если
                        // предыдущий был свёрнут в фон.
                        audiobookProgressDismissed.value = false
                    }
                    audiobookProgressIsVideo.value = live.format == AudiobookFormat.MP4
                    audiobookProgress.value = live.percent
                }
            }
        }

        // Ошибки воркера показываем снекбаром: иначе экспорт «молча» падает.
        viewModelScope.launch {
            AudiobookExportProgressBus.error.collect { message ->
                if (message != null) {
                    audiobookMessage.value = message
                    AudiobookExportProgressBus.clearError()
                }
            }
        }
    }

    fun onAudiobookExportCancel() {
        AudiobookExportWorker.cancelTask(context)
        AudiobookExportProgressBus.clear()
        audiobookProgress.value = null
        audiobookProgressDismissed.value = false
    }

    /** Свернуть диалог прогресса: экспорт продолжается в фоне (уведомление). */
    fun onAudiobookKeepInBackground() {
        audiobookProgressDismissed.value = true
    }

    /** Сохраняет выбранные настройки голоса для будущих экспортов. */
    fun onAudiobookTtsChanged(useReaderTts: Boolean, voiceId: String, speed: Float, pitch: Float) {
        appPreferences.AUDIOBOOK_EXPORT_USE_READER_TTS.value = useReaderTts
        appPreferences.AUDIOBOOK_EXPORT_TTS_VOICE_ID.value = voiceId
        appPreferences.AUDIOBOOK_EXPORT_TTS_SPEED.value = speed
        appPreferences.AUDIOBOOK_EXPORT_TTS_PITCH.value = pitch
    }

    fun onAudiobookTtsSaved() {
        audiobookMessage.value = context.getString(StringsR.string.audiobook_export_tts_saved)
    }

    // Инжектируемая точка постановки задачи: тесты подменяют её шпионом.
    var enqueueAudiobook: (Context, AudiobookExportRequest) -> Unit =
        AudiobookExportWorker::enqueue

    fun onAudiobookExportClicked(bookUrl: String, bookTitle: String) {
        viewModelScope.launch {
            val totalChapters = chapterDao.countByBookUrl(bookUrl)
            val downloadedChapters = chapterBodyDao.countDownloadedBodies(bookUrl)
            val availableTranslations = chapterTranslationDao
                .getTranslationGroups(bookUrl)
                .map { LangPair(it.sourceLang, it.targetLang, it.count) }
            val directoryUri = appPreferences.AUDIOBOOK_EXPORT_DIRECTORY_URI.value
            val directoryName = directoryUri.takeIf { it.isNotBlank() }
                ?.let { resolveExportDirectoryName(context.contentResolver, it) }

            if (downloadedChapters == 0) {
                audiobookMessage.value = context.getString(StringsR.string.audiobook_export_no_chapters)
                return@launch
            }

            audiobookDialogState.value = AudiobookExportDialogState.Configure(
                bookUrl = bookUrl,
                bookTitle = bookTitle,
                totalChapters = totalChapters,
                downloadedChapters = downloadedChapters,
                availableTranslations = availableTranslations,
                directoryName = directoryName,
                useReaderTts = appPreferences.AUDIOBOOK_EXPORT_USE_READER_TTS.value,
                ttsVoiceId = appPreferences.AUDIOBOOK_EXPORT_TTS_VOICE_ID.value,
                ttsSpeed = appPreferences.AUDIOBOOK_EXPORT_TTS_SPEED.value,
                ttsPitch = appPreferences.AUDIOBOOK_EXPORT_TTS_PITCH.value,
                readerEnginePackage = appPreferences.READER_TEXT_TO_SPEECH_VOICE_ENGINE.value,
                readerVoiceId = appPreferences.READER_TEXT_TO_SPEECH_VOICE_ID.value,
                readerSpeed = appPreferences.READER_TEXT_TO_SPEECH_VOICE_SPEED.value,
                readerPitch = appPreferences.READER_TEXT_TO_SPEECH_VOICE_PITCH.value,
            )
        }
    }

    fun onAudiobookExportConfirmed(config: AudiobookExportConfig) {
        val dialog = audiobookDialogState.value as? AudiobookExportDialogState.Configure ?: return
        val directoryUri = appPreferences.AUDIOBOOK_EXPORT_DIRECTORY_URI.value
        if (directoryUri.isBlank()) {
            // Папка не выбрана: сохраняем конфиг и просим UI открыть SAF-пикер.
            pendingAudiobook = PendingAudiobookExport(dialog = dialog, config = config)
            audiobookDialogState.value = dialog.copy(
                directoryRequestId = dialog.directoryRequestId + 1,
            )
        } else {
            enqueueAudiobookExport(dialog, config, directoryUri)
        }
    }

    fun onAudiobookDirectorySaved(uri: String) {
        appPreferences.AUDIOBOOK_EXPORT_DIRECTORY_URI.value = uri
        val pending = pendingAudiobook
        if (pending != null) {
            pendingAudiobook = null
            enqueueAudiobookExport(pending.dialog, pending.config, uri)
        } else {
            // Смена папки в открытом диалоге: обновляем имя, экспорт не стартуем.
            viewModelScope.launch {
                val name = resolveExportDirectoryName(context.contentResolver, uri)
                val state = audiobookDialogState.value
                if (state is AudiobookExportDialogState.Configure) {
                    audiobookDialogState.value = state.copy(directoryName = name)
                }
            }
        }
    }

    fun onAudiobookDialogDismiss() {
        pendingAudiobook = null
        audiobookDialogState.value = AudiobookExportDialogState.Hidden
    }

    private fun enqueueAudiobookExport(
        dialog: AudiobookExportDialogState.Configure,
        config: AudiobookExportConfig,
        directoryUri: String,
    ) {
        val useReader = config.useReaderTts
        val request = AudiobookExportRequest(
            bookUrl = dialog.bookUrl,
            bookTitle = dialog.bookTitle,
            format = config.format,
            contentMode = config.contentMode,
            sourceLang = if (config.contentMode == AudiobookContentMode.TRANSLATION) config.sourceLang else "",
            targetLang = if (config.contentMode == AudiobookContentMode.TRANSLATION) config.targetLang else "",
            startPosition = config.startPosition,
            endPosition = config.endPosition,
            enginePackage = if (useReader) {
                appPreferences.READER_TEXT_TO_SPEECH_VOICE_ENGINE.value
            } else {
                appPreferences.AUDIOBOOK_EXPORT_TTS_ENGINE.value
                    .ifBlank { appPreferences.READER_TEXT_TO_SPEECH_VOICE_ENGINE.value }
            },
            voiceId = if (useReader) {
                appPreferences.READER_TEXT_TO_SPEECH_VOICE_ID.value
            } else {
                config.ttsVoiceId
            },
            speed = if (useReader) {
                appPreferences.READER_TEXT_TO_SPEECH_VOICE_SPEED.value
            } else {
                config.ttsSpeed
            },
            pitch = if (useReader) {
                appPreferences.READER_TEXT_TO_SPEECH_VOICE_PITCH.value
            } else {
                config.ttsPitch
            },
            visualUri = config.visualUri,
            visualSource = config.visualSource,
            visualSourceName = config.visualSourceName,
            treeUri = directoryUri,
        )
        enqueueAudiobook(context, request)
        audiobookMessage.value = context.getString(
            if (config.format == AudiobookFormat.MP4) {
                StringsR.string.audiobook_export_started_video
            } else {
                StringsR.string.audiobook_export_started_audio
            },
        )
        audiobookDialogState.value = AudiobookExportDialogState.Hidden
    }

    // ─── Перевод названия и описания ──────────────────────────────────────────

    // Кнопка перевода метаданных: скрыта для видео- и манга-книг —
    // на их страницах переводчик описаний не используется. Тип берётся
    // как в isVideoBook(): сохранённый contentType книги, иначе у источника.
    val showTranslateButton = bookUrlFlow.flatMapLatest { url ->
        appRepository.libraryBooks.getFlow(url)
    }.map { book ->
        val contentType = book?.contentType?.takeIf { it.isNotEmpty() }
            ?: source?.contentType.orEmpty()
        contentType != "video" && contentType != "manga"
    }.toState(
        viewModelScope,
        source?.contentType.let { it != "video" && it != "manga" }
    )

    val translatedTitle = mutableStateOf<String?>(null)
    val translatedDescription = mutableStateOf<String?>(null)
    val isTranslatingInfo = mutableStateOf(false)

    // Флаг: результат ручного перевода метаданных книги.
    // Пока активен, подписка НЕ перезаписывает translatedTitle/translatedDescription
    // из БД (чтобы ручной результат не пропадал при scope != FULL).
    private var manualTranslationActive = false

    fun translateBookInfo() {
        if (isTranslatingInfo.value) return
        viewModelScope.launch {
            val pair = translationSettingsResolver.translationPairForBook(bookUrl)
            val sourceLang = pair.source
            val targetLang = pair.target
            if (targetLang.isBlank()) {
                toasty.show(R.string.translate_target_lang_not_set)
                return@launch
            }
            if (sourceLang.isBlank()) {
                toasty.show(R.string.translate_target_lang_not_set)
                return@launch
            }

            isTranslatingInfo.value = true
            try {
                val title = state.book.value.title
                val description = state.book.value.description

                // Заголовок/описание книги: PA→Free, не тратим LLM-токены и не требуем API-ключ.
                // translateTitle без override провайдера сам откатывается на PA→Free для LLM.
                val translatedTitleText = if (title.isNotBlank())
                    translationManager.translateTitle(title, sourceLang, targetLang)
                else null
                val translatedDescriptionText = if (description.isNotBlank())
                    try {
                        translationManager.translateBatch(
                            listOf(description), sourceLang, targetLang,
                            systemPromptOverride = null, provider = "GOOGLE_PA"
                        )[description]
                    } catch (e: Exception) {
                        // PA упал — фолбек на бесплатный Google (хуже качество, но бесплатно).
                        translationManager.translateBatch(
                            listOf(description), sourceLang, targetLang,
                            systemPromptOverride = null, provider = "GOOGLE_FREE"
                        )[description]
                    }
                else null

                bookTranslationDao.insertReplace(
                    BookTranslation(
                        bookUrl = bookUrl,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        titleTranslation = translatedTitleText ?: "",
                        descriptionTranslation = translatedDescriptionText ?: "",
                    )
                )
                // Ручной результат: пишем напрямую в state, чтобы показать
                // даже при scope != FULL (подписка на БД в этом случае не активна).
                manualTranslationActive = true
                translatedTitle.value = translatedTitleText?.takeIf { it.isNotBlank() }
                translatedDescription.value = translatedDescriptionText?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                toasty.show(R.string.translate_failed)
            } finally {
                isTranslatingInfo.value = false
            }
        }
    }

    fun clearBookInfoTranslation() {
        manualTranslationActive = false
        translatedTitle.value = null
        translatedDescription.value = null
        viewModelScope.launch {
            bookTranslationDao.deleteByBookUrls(listOf(bookUrl))
        }
    }

    // ─── Инициализация ────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            bookUrl = appRepository.libraryBooks.resolveStoredUrl(rawBookUrl)
            val canonical = normalizeBookUrl(bookUrl)
            if (canonical != bookUrl) {
                appRepository.libraryBooks.reparentBookUrl(bookUrl, canonical)
                bookUrl = canonical
            }

            if (rawBookUrl.isContentUri) {
                val localUrl = normalizeBookUrl(
                    appFileResolver.getLocalIfContentType(rawBookUrl, bookFolderName = bookTitle)
                )
                if (appRepository.libraryBooks.get(localUrl) == null) {
                    importUriContentSync()
                }
                bookUrl = localUrl
            }

            if (state.isLocalSource.value) {
                // ponytail: жанры загружаем здесь, после импорта — иначе race condition
                val cachedBook = libraryDao.get(bookUrl)
                if (cachedBook?.genres?.isNotBlank() == true) {
                    state.genres.value = GenreUtils.parse(cachedBook.genres)
                }
                if (!appRepository.bookChapters.hasChapters(bookUrl))
                    updateChaptersList()
                return@launch
            }

            if (!appRepository.bookChapters.hasChapters(bookUrl))
                updateChaptersList()

            // Строка книги могла быть создана гонкой в init (вставка глав
            // обгоняет downloadBookMetadata) либо в сессии, когда источник
            // был сломан — тогда cover/description остались пустыми навсегда,
            // повторные открытия их не восстанавливали. Дотягиваем пустые
            // поля; upsertCanonical не затирает непустые значения.
            val existingBook = appRepository.libraryBooks.getByUrl(bookUrl)
            if (existingBook == null ||
                existingBook.coverImageUrl.isBlank() ||
                existingBook.description.isBlank()
            ) {
                chaptersRepository.downloadBookMetadata(bookUrl = bookUrl, bookTitle = bookTitle)
            }
        }

        // Берём жанры из БД. Если их нет — загружаем с сети.
        viewModelScope.launch {
            if (state.isLocalSource.value) return@launch
            val cachedBook = libraryDao.get(bookUrl)
            if (cachedBook?.genres?.isNotBlank() == true) {
                state.genres.value = GenreUtils.parse(cachedBook.genres)
                return@launch
            }
            updateGenres()
        }

        // Берём рейтинг из БД. Если его нет — загружаем с сети.
        viewModelScope.launch {
            if (state.isLocalSource.value) return@launch
            val cachedBook = libraryDao.get(bookUrl)
            if (cachedBook?.rating?.isNotBlank() == true) {
                state.rating.value = cachedBook.rating
                return@launch
            }
            updateRating()
        }

        // Берём статус из БД. Если его нет — загружаем с сети.
        viewModelScope.launch {
            if (state.isLocalSource.value) return@launch
            val cachedBook = libraryDao.get(bookUrl)
            if (cachedBook?.status?.isNotBlank() == true) {
                state.status.value = cachedBook.status
                return@launch
            }
            updateStatus()
        }

        // Берём дату последнего обновления из БД. Если её нет — загружаем с сети.
        viewModelScope.launch {
            if (state.isLocalSource.value) return@launch
            val cachedBook = libraryDao.get(bookUrl)
            if (cachedBook?.lastUpdateDate?.isNotBlank() == true) {
                state.lastUpdateDate.value = cachedBook.lastUpdateDate
                return@launch
            }
            updateLastUpdateDate()
        }

        // Дозаполняем метку контента из источника, если книга в библиотеке
        // была добавлена до поддержки content_type (иначе манга откроется
        // NOVEL-ридером и покажет бейдж N).
        viewModelScope.launch {
            val sourceType = source?.contentType ?: ""
            if (sourceType.isBlank()) return@launch
            val cachedBook = libraryDao.get(bookUrl)
            if (cachedBook?.inLibrary == true && cachedBook.contentType.isBlank()) {
                appRepository.libraryBooks.updateContentType(bookUrl, sourceType)
            }
        }

        viewModelScope.launch {
            bookUrlFlow.collect { url ->
                chaptersRepository.getChaptersSortedFlow(bookUrl = url).collect {
                    state.chapters.clear()
                    state.chapters.addAll(it)
                }
            }
        }

        // Размеры глав: быстрый путь из БД, затем дебаунс-скан диска.
        viewModelScope.launch {
            bookUrlFlow.collect { url ->
                chaptersRepository.getChapterSizesFlow(bookUrl = url).collect {
                    state.chapterSizes.value = it
                }
            }
        }

        // Статусы media3-загрузок видео-эпизодов (Task 14) → бейджи глав.
        // Статусы резолвятся заново при каждой перезагрузке списка глав;
        // для не-видео книг подписок не создаётся.
        viewModelScope.launch {
            snapshotFlow { state.chapters.map { it.chapter.url } }
                .flatMapLatest { urls ->
                    if (urls.isEmpty() || !isVideoBook()) flowOf(emptyMap<String, DownloadState>())
                    else combine(urls.map { videoDownloadManager.observe(it) }) { statuses ->
                        urls.zip(statuses).toMap()
                    }
                }
                .distinctUntilChanged()
                .collect { state.videoDownloadStates.value = it }
        }

        // Подписываемся на статус загрузки текущей книги
        viewModelScope.launch {
            downloadManager.tasks.collect { tasks ->
                state.downloadTask.value = tasks.find { it.bookUrl == bookUrlFlow.value }
            }
        }

        // Подписываемся на переведённые названия глав из БД.
        // Каскад (включая плагинный уровень) резолвится через settingsChangeSignal
        // — тот же путь, что для метаданных книги, чтобы книга плагина с включённым
        // переводчиком переводила и названия глав независимо от глобального режима.
        viewModelScope.launch {
            translationSettingsResolver.settingsChangeSignal(bookUrlFlow.value)
                .flatMapLatest { settings ->
                    Timber.d(
                        "chapterTitles: url=%s enabled=%s target=%s scope=%s provider=%s",
                        bookUrlFlow.value, settings.enabled, settings.target, settings.scope, settings.provider
                    )
                    // Названия глав переводятся всегда, когда перевод включён — независимо
                    // от scope. Scope (STANDARD/FULL) влияет только на название/описание
                    // самой книги и каталог; главы и содержимое читалки переводятся всегда.
                    if (settings.enabled) {
                        chapterTranslationDao.getTranslatedTitlesFlow(bookUrlFlow.value, settings.target)
                    } else {
                        flowOf(emptyList())
                    }
                }
                .collectLatest { list ->
                    state.translatedChapterTitles.value = list.associate {
                        it.chapterUrl to it.translatedText
                    }
                }
        }

        // Подписка на перевод метаданных книги (title + description):
        // ручной translateBookInfo() и FULL-конвейер пишут строку в BookTranslation,
        // а реактивность обеспечивает settingsChangeSignal: смена пары/режима при
        // открытом экране переизлучает комбинацию → flatMapLatest переподписывает
        // DAO-flow → UI обновляется без ручного действия.
        viewModelScope.launch {
            translationSettingsResolver.settingsChangeSignal(bookUrlFlow.value)
                .flatMapLatest { settings ->
                    // Диагностика резолва метаданных книги.
                    Timber.d(
                        "bookInfo: url=%s enabled=%s source=%s target=%s scope=%s",
                        bookUrlFlow.value, settings.enabled, settings.source, settings.target, settings.scope
                    )
                    // Авто-показ перевода метаданных книги ТОЛЬКО при scope == FULL.
                    // При scope != FULL — не загружаем из БД (ручной результат
                    // управляется отдельно через manualTranslationActive флаг).
                    if (settings.target.isBlank() || settings.scope != AppPreferences.TRANSLATION_SCOPE_FULL) {
                        flowOf(null)
                    } else {
                        bookTranslationDao.getTranslatedBookFlow(bookUrlFlow.value, settings.target)
                            .map { rows -> selectBookInfoRow(rows, settings.source) }
                    }
                }
                .collectLatest { row ->
                    // Не перезаписываем результат ручного перевода, пока он активен.
                    if (manualTranslationActive) return@collectLatest
                    translatedTitle.value = row?.titleTranslation?.takeIf { it.isNotBlank() }
                    translatedDescription.value = row?.descriptionTranslation?.takeIf { it.isNotBlank() }
                }
        }

        // FULL-scope: автоматически переводим заголовок и описание новеллы при её
        // открытии. Запускается после загрузки метаданных книги, только если перевод
        // включён, пара языков полная, scope == FULL и перевода ещё нет в БД.
        viewModelScope.launch {
            snapshotFlow { state.book.value }
                .filter { it.title.isNotBlank() || it.description.isNotBlank() }
                .collect { bookState ->
                    val enabled = translationSettingsResolver.translationEnabledForBook(bookUrl)
                    val pair = translationSettingsResolver.translationPairForBook(bookUrl)
                    val sourceLang = pair.source
                    val targetLang = pair.target
                    val scope = translationSettingsResolver.translationScopeForBook(bookUrl)
                    val provider = translationSettingsResolver.translationProviderForBook(bookUrl)
                    // Диагностика FULL-конвейера: почему метаданные переводятся/пропускаются.
                    Timber.d(
                        "bookInfoFull: url=%s enabled=%s source=%s target=%s scope=%s provider=%s hasBody=%s",
                        bookUrl, enabled, sourceLang, targetLang, scope, provider,
                        bookState.description.isNotBlank() || bookState.title.isNotBlank()
                    )
                    if (!enabled) return@collect
                    if (sourceLang.isBlank() || targetLang.isBlank()) return@collect
                    if (scope != AppPreferences.TRANSLATION_SCOPE_FULL) return@collect
                    val existingRow = bookTranslationDao.get(bookUrl, sourceLang, targetLang)
                    if (existingRow?.descriptionTranslation?.isNotBlank() == true) return@collect
                    if (isTranslatingInfo.value) return@collect

                    isTranslatingInfo.value = true
                    try {
                        val translatedTitleText =
                            existingRow?.titleTranslation?.takeIf { it.isNotBlank() }
                                ?: if (bookState.title.isNotBlank())
                                    translationManager.translateTitle(
                                        bookState.title, sourceLang, targetLang, provider
                                    ) ?: ""
                                else ""
                        val translatedDescriptionText =
                            if (bookState.description.isNotBlank())
                                translationManager.translateBatch(
                                    listOf(bookState.description), sourceLang, targetLang,
                                    systemPromptOverride = null, provider = provider
                                )[bookState.description] ?: ""
                            else ""

                        if (translatedTitleText.isNotBlank() || translatedDescriptionText.isNotBlank()) {
                            bookTranslationDao.insertReplace(
                                BookTranslation(
                                    bookUrl = bookUrl,
                                    sourceLang = sourceLang,
                                    targetLang = targetLang,
                                    titleTranslation = translatedTitleText,
                                    descriptionTranslation = translatedDescriptionText,
                                )
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "auto book info translation failed")
                    } finally {
                        isTranslatingInfo.value = false
                    }
                }
        }
    }

    fun toggleBookmark() {
        val now = System.currentTimeMillis()
        if (now - lastBookmarkClickMs < 300L) return
        lastBookmarkClickMs = now
        viewModelScope.launch {
            val isBookmarked =
                appRepository.toggleBookmark(
                    bookTitle = bookTitle,
                    bookUrl = bookUrl,
                    rating = state.rating.value,
                    contentType = source?.contentType ?: ""
                )
            if (isBookmarked) {
                // Дозаполняем статус и дату обновления при добавлении в библиотеку
                // (аналогично rating/contentType в LibraryBooksRepository.toggleBookmark).
                if (state.status.value.isNotBlank()) {
                    libraryDao.updateStatus(bookUrl, state.status.value)
                }
                if (state.lastUpdateDate.value.isNotBlank()) {
                    libraryDao.updateLastUpdateDate(bookUrl, state.lastUpdateDate.value)
                }
            }
            val msg = if (isBookmarked) R.string.added_to_library else R.string.removed_from_library
            toasty.show(msg)
        }
    }

    fun getCategories(): List<String> =
        listOf("", "Completed") + appPreferences.LIBRARY_CUSTOM_CATEGORIES.value

    fun updateBookCategory(category: String) {
        viewModelScope.launch {
            val isCompleted = category == "Completed"
            libraryDao.updateCategoryAndCompleted(bookUrl, category, isCompleted)
        }
    }

    /**
     * «Починить книгу»: принудительно сбрасывает кэш-признаки списка глав
     * (chaptersListHash + chaptersLastPage) и запускает ПОЛНЫЙ репарс глав.
     * Прогресс (read/позиция) сохраняется — merge берёт его из старых записей.
     */
    fun fixBook() {
        val url = bookUrl
        viewModelScope.launch {
            appRepository.libraryBooks.updateChaptersListHash(url, null)
            appRepository.libraryBooks.updateChaptersLastPage(url, null)
            updateChaptersList()
        }
    }

    fun onPullRefresh() {
        if (!state.isRefreshable.value) {
            toasty.show(R.string.local_book_nothing_to_update)
            state.isRefreshing.value = false
            return
        }
        toasty.show(R.string.updating_book_info)
        if (rawBookUrl.isContentUri) {
            importUriContent()
        } else if (!state.isLocalSource.value) {
            updateCover()
            updateTitle()
            updateDescription()
            updateChaptersList()
            viewModelScope.launch { updateRating() }
            viewModelScope.launch { updateStatus() }
            viewModelScope.launch { updateLastUpdateDate() }
        } else {
            updateChaptersList()
        }
    }

    private suspend fun updateGenres() {
        downloaderRepository.bookGenres(bookUrl = bookUrl).onSuccess { genres ->
            if (genres.isEmpty()) return@onSuccess
            val normalized = GenreUtils.normalize(genres)
            libraryDao.updateGenres(bookUrl, normalized)
            state.genres.value = GenreUtils.parse(normalized)
        }
    }

    private suspend fun updateRating() {
        downloaderRepository.bookRating(bookUrl = bookUrl).onSuccess { rating ->
            if (rating.isNullOrBlank()) return@onSuccess
            libraryDao.updateRating(bookUrl, rating)
            state.rating.value = rating
        }
    }

    private suspend fun updateStatus() {
        downloaderRepository.bookStatus(bookUrl = bookUrl).onSuccess { status ->
            if (status.isNullOrBlank()) return@onSuccess
            libraryDao.updateStatus(bookUrl, status)
            state.status.value = status
        }
    }

    private suspend fun updateLastUpdateDate() {
        downloaderRepository.bookLastUpdate(bookUrl = bookUrl).onSuccess { lastUpdateDate ->
            if (lastUpdateDate.isNullOrBlank()) return@onSuccess
            libraryDao.updateLastUpdateDate(bookUrl, lastUpdateDate)
            state.lastUpdateDate.value = lastUpdateDate
        }
    }

    private fun updateCover() = viewModelScope.launch {
        if (state.isLocalSource.value || book.value.coverImageUrl?.isLocalUri == true) return@launch
        downloaderRepository.bookCoverImageUrl(bookUrl = bookUrl).onSuccess {
            if (it == null) return@onSuccess
            appRepository.libraryBooks.updateCover(bookUrl, it)
            libraryUpdatesInteractions.syncCover(bookUrl, it)
        }
    }

    private fun updateTitle() = viewModelScope.launch {
        if (state.isLocalSource.value) return@launch
        downloaderRepository.bookTitle(bookUrl = bookUrl).onSuccess {
            if (it == null) return@onSuccess
            val currentBook = appRepository.libraryBooks.get(bookUrl)
            if (currentBook?.title == "Unknown Novel" || currentBook?.title.isNullOrBlank()) {
                appRepository.libraryBooks.updateTitle(bookUrl, it)
            }
        }
    }

    private fun updateDescription() = viewModelScope.launch {
        if (state.isLocalSource.value) return@launch
        downloaderRepository.bookDescription(bookUrl = bookUrl).onSuccess {
            if (it == null) return@onSuccess
            appRepository.libraryBooks.updateDescription(bookUrl, it)
        }
    }

    private fun importUriContent() {
        if (loadChaptersJob?.isActive == true) return
        loadChaptersJob = appScope.launch {
            state.error.value = ""
            state.isRefreshing.value = true
            val localUrl = normalizeBookUrl(
                appFileResolver.getLocalIfContentType(rawBookUrl, bookFolderName = bookTitle)
            )
            val isInLibrary = appRepository.libraryBooks.existInLibrary(localUrl)
            localBookImporterRepository.importFromContentUri(
                contentUri = rawBookUrl,
                bookTitle = bookTitle,
                addToLibrary = isInLibrary
            ).onSuccess {
                bookUrl = localUrl
            }.onError {
                state.error.value = it.message
            }
            state.isRefreshing.value = false
        }
    }

    private suspend fun importUriContentSync() {
        importUriContent()
        loadChaptersJob?.join()
    }

    private fun updateChaptersList() {
        if (loadChaptersJob?.isActive == true) return
        loadChaptersJob = appScope.launch {
            state.error.value = ""
            state.isRefreshing.value = true
            val url = bookUrl
            val book = appRepository.libraryBooks.get(url)

            // Try incremental parsePage if book already has chaptersLastPage.
            // This only re-checks the last known page + loads new pages,
            // instead of re-parsing all pages from scratch.
            val lastPage = book?.chaptersLastPage
            val chapterCount = appRepository.bookChapters.countByBookUrl(url)
            if (lastPage != null &&
                ChapterPagination.isPageCounterConsistent(lastPage, chapterCount)
            ) {
                updateChaptersIncremental(url, lastPage)
            } else {
                // Счётчик страниц рассинхронизирован с БД (или первый парс/legacy) —
                // сбрасываем и делаем полный репарс, позиции пересобираются с нуля.
                if (lastPage != null) {
                    Timber.w("updateChaptersList: lastPage=$lastPage несогласован с $chapterCount главами, сброс и полный репарс")
                    appRepository.libraryBooks.updateChaptersLastPage(url, null)
                }
                // First time or legacy: try full parsePage, fallback to getChapterList
                updateChaptersFull(url)
            }

            appRepository.libraryBooks.updateLastUpdateEpochTimeMilli(bookUrl = url)
            if (BuildConfig.DEBUG) MemoryDiagnostics.logMemoryStats("ChaptersList:updateChaptersList")
            state.isRefreshing.value = false
        }
    }

    /**
     * Incremental update: re-read the last known page to detect new chapters,
     * then load any new pages beyond the last known total.
     */
    private suspend fun updateChaptersIncremental(bookUrl: String, lastKnownPage: Int) {
        val lastPageResult = downloaderRepository.bookChaptersPage(bookUrl, lastKnownPage)
        val lastPageData = (lastPageResult as? Response.Success)?.data
        if (lastPageData == null) {
            Timber.w("updateChaptersIncremental: failed to load lastPage=$lastKnownPage, falling back to full update")
            updateChaptersFull(bookUrl)
            return
        }

        val existingUrls = appRepository.bookChapters.getChapterUrls(bookUrl).toSet()
        var positionOffset = existingUrls.size
        val chaptersToAdd = mutableListOf<Chapter>()

        // From the last page, only take chapters that don't exist yet
        val newFromLastPage = lastPageData.chapters.filter { it.url !in existingUrls }
        newFromLastPage.forEachIndexed { idx, ch ->
            chaptersToAdd.add(
                Chapter(
                    title = ch.title, url = ch.url, bookUrl = bookUrl, position = positionOffset + idx, volume = ch.volume
                )
            )
        }
        positionOffset += chaptersToAdd.size

        // Load any new pages beyond the last known total
        val newTotalPages = lastPageData.totalPages
        for (page in (lastKnownPage + 1)..newTotalPages) {
            val pageData = (downloaderRepository.bookChaptersPage(bookUrl, page) as? Response.Success)?.data
                ?: break
            val offset = positionOffset
            pageData.chapters.forEachIndexed { idx, ch ->
                chaptersToAdd.add(
                    Chapter(
                        title = ch.title, url = ch.url, bookUrl = bookUrl, position = offset + idx, volume = ch.volume
                    )
                )
            }
            positionOffset += pageData.chapters.size
        }

        if (chaptersToAdd.isNotEmpty()) {
            appRepository.bookChapters.merge(newChapters = chaptersToAdd, bookUrl = bookUrl)
        }

        if (newTotalPages != lastKnownPage) {
            appRepository.libraryBooks.updateChaptersLastPage(bookUrl, newTotalPages)
        }

        if (BuildConfig.DEBUG) MemoryDiagnostics.logMemoryStats("ChaptersList:updateChaptersIncremental")
    }

    /**
     * Full update: load all pages via parsePage or fallback to getChapterList.
     */
    private suspend fun updateChaptersFull(bookUrl: String) {
        downloaderRepository.bookChaptersList(bookUrl = bookUrl)
            .onSuccess {
                if (it.isEmpty()) toasty.show(R.string.no_chapters_found)
                appRepository.bookChapters.merge(newChapters = it, bookUrl = bookUrl)
                // Save chaptersLastPage for future incremental updates
                val firstPage = downloaderRepository.bookChaptersPage(bookUrl, 1)
                val totalPages = (firstPage as? Response.Success)?.data?.totalPages
                if (totalPages != null) {
                    appRepository.libraryBooks.updateChaptersLastPage(bookUrl, totalPages)
                }
                if (BuildConfig.DEBUG) MemoryDiagnostics.logMemoryStats("ChaptersList:updateChaptersFull")
            }.onError {
                state.error.value = it.message
            }
    }

    suspend fun getLastReadChapter(): String? =
        chaptersRepository.getLastReadChapter(bookUrl = bookUrl)

    private fun refreshReadingHistory() {
        appScope.launch {
            val total = appRepository.bookChapters.countByBookUrl(bookUrl)
            val read = appRepository.bookChapters.countReadByBookUrl(bookUrl)
            val book = appRepository.libraryBooks.get(bookUrl)
            readingHistoryDao.upsert(
                ReadingHistory(
                    bookUrl = bookUrl,
                    bookTitle = book?.title ?: "",
                    bookCoverUrl = book?.coverImageUrl ?: "",
                    lastReadChapterUrl = book?.lastReadChapter,
                    lastReadChapterTitle = null,
                    lastReadEpochTimeMilli = System.currentTimeMillis(),
                    totalChapters = total,
                    readChapters = read,
                )
            )
        }
    }

    fun setAsUnreadSelected() {
        val list = state.selectedChaptersUrl.toList()
        appScope.launch {
            appRepository.bookChapters.setAsUnread(list.map { it.first })
            refreshReadingHistory()
        }
    }

    fun setAsReadSelected() {
        val list = state.selectedChaptersUrl.toList()
        appScope.launch {
            appRepository.bookChapters.setAsRead(list.map { it.first })
            refreshReadingHistory()
        }
    }

    fun setAsReadUpToSelected() {
        if (state.selectedChaptersUrl.size > 1) return
        val selectedIndex = state.selectedChaptersUrl.keys.firstOrNull()?.let { selectedUrl ->
            state.chapters.indexOfFirst { it.chapter.url == selectedUrl }
        } ?: return

        if (selectedIndex != -1) {
            val chaptersToMarkAsRead = state.chapters.take(selectedIndex + 1).map { it.chapter.url }
            appScope.launch {
                appRepository.bookChapters.setAsRead(chaptersToMarkAsRead)
                refreshReadingHistory()
            }
        }
    }

    fun setAsReadUpToUnSelected() {
        if (state.selectedChaptersUrl.size > 1) return
        val selectedIndex = state.selectedChaptersUrl.keys.firstOrNull()?.let { selectedUrl ->
            state.chapters.indexOfFirst { it.chapter.url == selectedUrl }
        } ?: return

        if (selectedIndex != -1) {
            val chaptersToMarkAsUnread = state.chapters.take(selectedIndex + 1).map { it.chapter.url }
            appScope.launch {
                appRepository.bookChapters.setAsUnread(chaptersToMarkAsUnread)
                refreshReadingHistory()
            }
        }
    }

    // Видео-книги: HTML-путь скачивания не применим — качаем через media3
    // (Task 14). Как в resolveGateType читалера: метка из Book.contentType,
    // при пустой — из contentType источника.
    private suspend fun isVideoBook(): Boolean {
        val stored = runCatching { appRepository.libraryBooks.get(bookUrl)?.contentType }
            .getOrNull()
        if (!stored.isNullOrEmpty()) return stored == "video"
        return source?.contentType == "video"
    }

    // Видео: резолв потока через плагин → media3-очередь. Последовательно,
    // чтобы не забивать источник сотнями параллельных запросов.
    private suspend fun enqueueVideoDownloads(chapterUrls: List<String>) {
        var added = 0
        var failed = 0
        var skipped = 0
        for (url in chapterUrls) {
            // Уже скачанные пропускаем ДО резолва — без этого каждая глава
            // гонит лишний сетевой запрос через плагин.
            if (videoDownloadManager.completedVideo(url) != null) {
                skipped++
                continue
            }
            when (val result = videoRepository.resolve(url)) {
                is Response.Success -> {
                    val video = result.data.firstOrNull()
                    if (video == null) {
                        failed++
                        Timber.e("enqueueVideoDownloads: пустой поток для $url")
                    } else {
                        videoDownloadManager.enqueue(url, video)
                        added++
                    }
                }
                is Response.Error -> {
                    failed++
                    Timber.e(
                        result.exception,
                        "enqueueVideoDownloads: резолв не удался для $url: ${result.message}"
                    )
                }
            }
        }
        Timber.d("enqueueVideoDownloads: added=$added failed=$failed skipped=$skipped")
        when {
            // Всё уже скачано (added=0, failed=0) — это не ошибка, молчим
            added > 0 -> toasty.show(R.string.download_added_to_queue)
            failed > 0 -> toasty.show(R.string.download_error)
        }
    }

    fun downloadNext100Chapters() {
        if (state.isLocalSource.value) return
        val allChapters = state.chapters.toList().sortedBy { it.chapter.position }
        val lastIndex = allChapters.indexOfLast { it.downloaded }
        val nextChapters = allChapters.drop(lastIndex + 1).take(100)
        if (nextChapters.isEmpty()) {
            toasty.show(R.string.download_all_cached)
            return
        }
        val chapterUrls = nextChapters.map { it.chapter.url }
        viewModelScope.launch {
            if (isVideoBook()) {
                enqueueVideoDownloads(chapterUrls)
                return@launch
            }
            when (val result = downloadManager.enqueue(
                bookTitle = bookTitle,
                bookUrl = bookUrl,
                chapterUrls = chapterUrls,
            )) {
                is EnqueueResult.Added -> toasty.show(R.string.download_added_to_queue)
                is EnqueueResult.ChaptersAdded -> toasty.show(R.string.download_chapters_added)
                is EnqueueResult.Resumed -> toasty.show(R.string.download_resumed)
                is EnqueueResult.AlreadyQueued -> toasty.show(R.string.download_already_queued)
                is EnqueueResult.AllCached -> toasty.show(R.string.download_all_cached)
            }
        }
    }

    fun downloadAllChapters() {
        if (state.isLocalSource.value) return
        val allChapters = state.chapters.toList().sortedBy { it.chapter.position }
        val chapterUrls = allChapters.map { it.chapter.url }
        viewModelScope.launch {
            if (isVideoBook()) {
                enqueueVideoDownloads(chapterUrls)
                return@launch
            }
            when (val result = downloadManager.enqueue(
                bookTitle = bookTitle,
                bookUrl = bookUrl,
                chapterUrls = chapterUrls,
            )) {
                is EnqueueResult.Added -> toasty.show(R.string.download_added_to_queue)
                is EnqueueResult.ChaptersAdded -> toasty.show(R.string.download_chapters_added)
                is EnqueueResult.Resumed -> toasty.show(R.string.download_resumed)
                is EnqueueResult.AlreadyQueued -> toasty.show(R.string.download_already_queued)
                is EnqueueResult.AllCached -> toasty.show(R.string.download_all_cached)
            }
        }
    }

    fun downloadSelected() {
        if (state.isLocalSource.value) return

        val selectedUrls = state.selectedChaptersUrl.keys.toSet()
        val sortedChapters = state.chapters
            .filter { selectedUrls.contains(it.chapter.url) }
            .sortedBy { it.chapter.position }

        val chapterUrls = sortedChapters.map { it.chapter.url }
        viewModelScope.launch {
            if (isVideoBook()) {
                enqueueVideoDownloads(chapterUrls)
                return@launch
            }
            when (val result = downloadManager.enqueue(
                bookTitle = bookTitle,
                bookUrl = bookUrl,
                chapterUrls = chapterUrls,
            )) {
                is EnqueueResult.Added -> toasty.show(R.string.download_added_to_queue)
                is EnqueueResult.ChaptersAdded -> toasty.show(R.string.download_chapters_added)
                is EnqueueResult.Resumed -> toasty.show(R.string.download_resumed)
                is EnqueueResult.AlreadyQueued -> toasty.show(R.string.download_already_queued)
                is EnqueueResult.AllCached -> toasty.show(R.string.download_all_cached)
            }
        }
    }

    fun translateSelected() {
        if (!translationSettingsResolver.translationEnabledForBook(bookUrl)) {
            toasty.show(R.string.translation_not_configured)
            return
        }
        val pair = translationSettingsResolver.translationPairForBook(bookUrl)
        val sourceLang = pair.source
        val targetLang = pair.target
        if (sourceLang.isBlank() || targetLang.isBlank()) {
            toasty.show(R.string.translation_not_configured)
            return
        }

        val selectedUrls = state.selectedChaptersUrl.keys.toSet()
        val sortedChapters = state.chapters
            .filter { selectedUrls.contains(it.chapter.url) }
            .sortedBy { it.chapter.position }

        val chapterUrls = sortedChapters.map { it.chapter.url }
        viewModelScope.launch {
            // Перевод видео не применяется — guard из Task 12 остаётся,
            // но с корректным текстом после появления media3-скачивания.
            if (isVideoBook()) {
                toasty.show(R.string.video_translate_unavailable)
                return@launch
            }
            when (val result = downloadManager.enqueue(
                bookTitle = bookTitle,
                bookUrl = bookUrl,
                chapterUrls = chapterUrls,
                translateMode = true,
            )) {
                is EnqueueResult.Added,
                is EnqueueResult.ChaptersAdded,
                EnqueueResult.Resumed,
                EnqueueResult.AlreadyQueued -> toasty.show(R.string.translation_queued)
                EnqueueResult.AllCached -> toasty.show(R.string.translation_nothing_to_translate)
            }
        }
    }

    fun deleteDownloadsSelected() {
        if (state.isLocalSource.value) return
        val list = state.selectedChaptersUrl.toList()
        appScope.launch {
            // Видео: снимаем media3-загрузку (индекс + файлы в кэше).
            if (isVideoBook()) {
                list.forEach { videoDownloadManager.remove(it.first) }
                return@launch
            }
            appRepository.chapterBody.removeRows(list.map { it.first })
        }
    }

    fun deleteTranslationsForBook() {
        appScope.launch {
            chapterTranslationDao.deleteTranslationsByBookUrls(listOf(bookUrl))
        }
    }

    fun deleteSelectedTranslations() {
        val urls = state.selectedChaptersUrl.keys.toList()
        appScope.launch {
            urls.chunked(500).forEach { chapterTranslationDao.deleteChapterTranslationsByUrls(it) }
        }
    }

    fun onSelectionModeChapterClick(chapter: ChapterWithContext) {
        val url = chapter.chapter.url
        if (state.selectedChaptersUrl.containsKey(url)) {
            state.selectedChaptersUrl.remove(url)
        } else {
            state.selectedChaptersUrl[url] = Unit
        }
        lastSelectedChapterUrl = url
    }

    fun saveImageAsCover(uri: Uri) {
        appRepository.libraryBooks.saveImageAsCover(imageUri = uri, bookUrl = bookUrl)
    }

    fun onSelectionModeChapterLongClick(chapter: ChapterWithContext) {
        val url = chapter.chapter.url
        if (url != lastSelectedChapterUrl) {
            val indexOld = state.chapters.indexOfFirst { it.chapter.url == lastSelectedChapterUrl }
            val indexNew = state.chapters.indexOfFirst { it.chapter.url == url }
            val min = minOf(indexOld, indexNew)
            val max = maxOf(indexOld, indexNew)
            if (min >= 0 && max >= 0) {
                for (index in min..max) {
                    state.selectedChaptersUrl[state.chapters[index].chapter.url] = Unit
                }
                lastSelectedChapterUrl = state.chapters[indexNew].chapter.url
                return
            }
        }

        if (state.selectedChaptersUrl.containsKey(url)) {
            state.selectedChaptersUrl.remove(url)
        } else {
            state.selectedChaptersUrl[url] = Unit
        }
        lastSelectedChapterUrl = url
    }

    fun onChapterLongClick(chapter: ChapterWithContext) {
        val url = chapter.chapter.url
        state.selectedChaptersUrl[url] = Unit
        lastSelectedChapterUrl = url
    }

    fun onChapterDownload(chapter: ChapterWithContext) {
        if (state.isLocalSource.value) return
        viewModelScope.launch {
            if (isVideoBook()) {
                enqueueVideoDownloads(listOf(chapter.chapter.url))
                return@launch
            }
            when (downloadManager.enqueue(
                bookTitle = bookTitle,
                bookUrl = bookUrl,
                chapterUrls = listOf(chapter.chapter.url),
            )) {
                is EnqueueResult.Added -> toasty.show(R.string.download_added_to_queue)
                is EnqueueResult.ChaptersAdded -> toasty.show(R.string.download_chapters_added)
                is EnqueueResult.Resumed -> toasty.show(R.string.download_resumed)
                is EnqueueResult.AlreadyQueued -> toasty.show(R.string.download_already_queued)
                is EnqueueResult.AllCached -> toasty.show(R.string.download_all_cached)
            }
        }
    }

    // Остановка идущей/очередной видео-загрузки: отмена и удаление media3-загрузки.
    fun onStopVideoDownload(chapter: ChapterWithContext) {
        videoDownloadManager.remove(chapter.chapter.url)
    }

    fun unselectAll() {
        state.selectedChaptersUrl.clear()
    }

    fun selectAll() {
        state.chapters
            .toList()
            .map { it.chapter.url to Unit }
            .let { state.selectedChaptersUrl.putAll(it) }
    }

    fun invertSelection() {
        val allChaptersUrl = state.chapters.asSequence().map { it.chapter.url }.toSet()
        val selectedUrl = state.selectedChaptersUrl.asSequence().map { it.key }.toSet()
        val inverse = (allChaptersUrl - selectedUrl).asSequence().associateWith { }
        state.selectedChaptersUrl.clear()
        state.selectedChaptersUrl.putAll(inverse)
    }
}

/** Конфигурация экспорта аудиокниги, выбранная в диалоге. */
data class AudiobookExportConfig(
    val format: AudiobookFormat = AudiobookFormat.WAV,
    val contentMode: AudiobookContentMode = AudiobookContentMode.ORIGINAL,
    val sourceLang: String = "",
    val targetLang: String = "",
    /** Позиция первой главы (0-based). */
    val startPosition: Int = 0,
    /** Позиция последней главы включительно (0-based). */
    val endPosition: Int = 0,
    val visualUri: String? = null,
    val visualSource: VisualSource? = null,
    val visualSourceName: String? = null,
    /** true — голос/скорость/тон брать из настроек читалки. */
    val useReaderTts: Boolean = true,
    /** Голос для ручного режима (пусто — голос движка по умолчанию). */
    val ttsVoiceId: String = "",
    val ttsSpeed: Float = 1f,
    val ttsPitch: Float = 1f,
)

/** Аудиоэкспорт, ожидающий выбора папки через SAF. */
private data class PendingAudiobookExport(
    val dialog: AudiobookExportDialogState.Configure,
    val config: AudiobookExportConfig,
)

/** Экспорт, ожидающий выбора папки через SAF (ветка NeedDirectory). */
private data class PendingExport(
    val bookUrl: String,
    val bookTitle: String,
    val mode: String,
    val sourceLang: String,
    val targetLang: String,
    val availableCount: Int,
)

/**
 * Число глав, доступных для экспорта: скачанные тела для оригинала,
 * переведённые главы выбранной пары для перевода.
 */
private fun availableCountFor(
    dialog: ExportDialogState,
    mode: String,
    sourceLang: String,
    targetLang: String,
): Int = when (dialog) {
    is ExportDialogState.ContentChoice -> if (mode == "translation") {
        dialog.availableTranslations.firstOrNull {
            it.sourceLang == sourceLang && it.targetLang == targetLang
        }?.translatedChapters ?: 0
    } else {
        dialog.downloadedChapters
    }
    else -> 0
}

internal fun selectBookInfoRow(
    rows: List<BookTitleTranslation>,
    effectiveSourceLang: String,
): BookTitleTranslation? {
    if (rows.isEmpty()) return null
    return rows.firstOrNull { it.sourceLang == effectiveSourceLang }
}