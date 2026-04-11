package my.noveldokusha.features.reader.features

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.noveldokusha.core.Response
import my.noveldokusha.core.isValidChapterContent
import my.noveldokusha.features.reader.ReaderRepository
import my.noveldokusha.features.reader.domain.ChapterLoaded
import my.noveldokusha.features.reader.domain.ChapterState
import my.noveldokusha.features.reader.domain.ChapterStats
import my.noveldokusha.features.reader.domain.ChapterUrl
import my.noveldokusha.features.reader.domain.InitialPositionChapter
import my.noveldokusha.features.reader.domain.ReaderItem
import my.noveldokusha.features.reader.domain.ReaderState
import my.noveldokusha.features.reader.domain.ReadingChapterPosStats
import my.noveldokusha.features.reader.domain.indexOfReaderItem
import my.noveldokusha.features.reader.tools.textToItemsConverter
import my.noveldokusha.features.reader.ui.ReaderViewHandlersActions
import my.noveldokusha.feature.local_database.DAOs.ChapterTranslationDao
import my.noveldokusha.feature.local_database.tables.Chapter
import my.noveldokusha.feature.local_database.tables.ChapterTranslation
import kotlin.coroutines.CoroutineContext

internal class ReaderChaptersLoader(
    private val readerRepository: ReaderRepository,
    private val translatorTranslateOrNull: suspend (text: String) -> String?,
    private val translatorIsActive: () -> Boolean,
    private val translatorSourceLanguageOrNull: () -> String?,
    private val translatorTargetLanguageOrNull: () -> String?,
    private val translatorProvider: () -> String,
    private val translatorBatchTranslateOrNull: () -> (suspend (List<String>) -> Map<String, String>)?,
    private val bookUrl: String,
    val orderedChapters: List<Chapter>,
    @Volatile var readerState: ReaderState,
    private val readerViewHandlersActions: ReaderViewHandlersActions,
    private val chapterTranslationDao: ChapterTranslationDao,
    private val regexRulesProvider: () -> List<my.noveldokusha.core.models.RegexRule> = { emptyList() },
) : CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Main.immediate

    private sealed interface LoadChapter {
        enum class Type { RestartInitial, Initial, Previous, Next }
        data class RestartInitialChapter(val state: ChapterState) : LoadChapter
        data class Initial(val chapterIndex: Int) : LoadChapter
        data object Previous : LoadChapter
        data object Next : LoadChapter
    }

    val chaptersStats = mutableMapOf<ChapterUrl, ChapterStats>()
    val loadedChapters = mutableSetOf<ChapterUrl>()
    val chapterLoadedFlow = MutableSharedFlow<ChapterLoaded>()
    private val items: MutableList<ReaderItem> = ArrayList()
    private val loaderQueue = mutableSetOf<LoadChapter.Type>()
    private val chapterLoaderFlow = MutableSharedFlow<LoadChapter>(extraBufferCapacity = 1)

    private @Volatile var _hasLoadingError = false
    private var autoResetJob: kotlinx.coroutines.Job? = null

    var hasLoadingError: Boolean
        get() = _hasLoadingError
        set(value) {
            _hasLoadingError = value
            autoResetJob?.cancel()
            autoResetJob = null
            if (value) {
                autoResetJob = launch {
                    delay(30_000L)
                    _hasLoadingError = false
                    autoResetJob = null
                    android.util.Log.d(TAG, "Auto-reset hasLoadingError after 30s timeout")
                }
            }
        }


    init {
        startChapterLoaderWatcher()
    }

    fun getItems(): List<ReaderItem> = items

    fun getItemContext(itemIndex: Int, chapterUrl: String): ReadingChapterPosStats? {
        val item = items.getOrNull(itemIndex) ?: return null
        if (item !is ReaderItem.Position) return null
        val chapterStats = chaptersStats[chapterUrl] ?: return null
        return ReadingChapterPosStats(
            chapterIndex = item.chapterIndex,
            chapterCount = orderedChapters.size,
            chapterItemPosition = item.chapterItemPosition,
            chapterItemsCount = chapterStats.itemsCount,
            chapterTitle = chapterStats.chapter.title,
            chapterUrl = chapterStats.chapter.url,
        )
    }

    fun getItemContext(chapterIndex: Int, chapterItemPosition: Int): ReadingChapterPosStats? {
        val itemIndex = indexOfReaderItem(
            list = items,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition
        )
        val item = items.getOrNull(itemIndex) ?: return null
        if (item !is ReaderItem.Position) return null
        val chapterStats = chaptersStats[item.chapterUrl] ?: return null
        return ReadingChapterPosStats(
            chapterIndex = chapterIndex,
            chapterCount = orderedChapters.size,
            chapterItemPosition = item.chapterItemPosition,
            chapterItemsCount = chapterStats.itemsCount,
            chapterTitle = chapterStats.chapter.title,
            chapterUrl = chapterStats.chapter.url,
        )
    }

    fun isLastChapter(chapterIndex: Int): Boolean = chapterIndex == orderedChapters.lastIndex
    fun isChapterIndexLoaded(chapterIndex: Int): Boolean {
        return orderedChapters.getOrNull(chapterIndex)?.url
            ?.let { loadedChapters.contains(it) }
            ?: false
    }
    fun isChapterIndexValid(chapterIndex: Int) = chapterIndex in 0 until orderedChapters.size

    @Synchronized fun tryLoadInitial(chapterIndex: Int) {
        if (LoadChapter.Type.Initial in loaderQueue) return
        loaderQueue.add(LoadChapter.Type.Initial)
        launch { chapterLoaderFlow.emit(LoadChapter.Initial(chapterIndex = chapterIndex)) }
    }

    @Synchronized fun tryLoadRestartedInitial(chapterLastState: ChapterState) {
        if (LoadChapter.Type.RestartInitial in loaderQueue) return
        loaderQueue.add(LoadChapter.Type.RestartInitial)
        launch { chapterLoaderFlow.emit(LoadChapter.RestartInitialChapter(state = chapterLastState)) }
    }

    @Synchronized fun tryLoadPrevious() {
        if (LoadChapter.Type.Previous in loaderQueue) return
        loaderQueue.add(LoadChapter.Type.Previous)
        launch { chapterLoaderFlow.emit(LoadChapter.Previous) }
    }

    @Synchronized fun tryLoadNext() {
        if (hasLoadingError) {
            android.util.Log.d(TAG, "tryLoadNext: blocked due to previous loading error")
            return
        }
        if (LoadChapter.Type.Next in loaderQueue) return
        loaderQueue.add(LoadChapter.Type.Next)
        launch { chapterLoaderFlow.emit(LoadChapter.Next) }
    }

    fun clearErrorAndLoadNext() {
        hasLoadingError = false
        tryLoadNext()
    }

    /**
     * Удаляет все items главы с ошибкой, чистит кэш тела в БД и перезагружает её заново.
     * Без удаления из БД fetchBody вернёт кэшированную пустую строку и до сети не дойдёт.
     */
    fun retryChapter(chapterIndex: Int) {
        launch(Dispatchers.Main.immediate) {
            val chapterUrl = orderedChapters.getOrNull(chapterIndex)?.url

            // Сначала сбрасываем флаги — до любых IO-операций
            hasLoadingError = false
            if (chapterUrl != null) {
                chaptersStats.remove(chapterUrl)
                loadedChapters.remove(chapterUrl)
            }

            // Удаляем ВСЕ items этой главы (Error, Title, Divider, Body и т.д.)
            items.removeAll { it.chapterIndex == chapterIndex }
            readerViewHandlersActions.doForceUpdateListViewState()

            // Чистим кэш тела в БД (IO после очистки UI-стейта)
            if (chapterUrl != null) {
                withContext(Dispatchers.IO) {
                    readerRepository.deleteChapterBody(chapterUrl)
                }
            }

            // Перезагружаем главу
            readerState = ReaderState.LOADING

            // Вставляем на правильное место: перед первым итемом следующей главы.
            // Без этого items.add() кладёт главу в конец списка, и она оказывается после уже загруженных следующих глав.
            var insertIndex = items.indexOfFirst { it.chapterIndex > chapterIndex }
            if (insertIndex == -1) insertIndex = items.size

            val insert: suspend (ReaderItem) -> Unit = {
                withContext(Dispatchers.Main.immediate) {
                    items.add(insertIndex, it)
                    insertIndex += 1
                    readerViewHandlersActions.doForceUpdateListViewState()
                }
            }
            val insertAll: suspend (Collection<ReaderItem>) -> Unit = {
                withContext(Dispatchers.Main.immediate) {
                    items.addAll(insertIndex, it)
                    insertIndex += it.size
                    readerViewHandlersActions.doForceUpdateListViewState()
                }
            }
            val remove: suspend (ReaderItem) -> Unit = {
                withContext(Dispatchers.Main.immediate) {
                    val idx = items.indexOf(it)
                    if (idx != -1 && idx < insertIndex) insertIndex -= 1
                    items.remove(it)
                    readerViewHandlersActions.doForceUpdateListViewState()
                }
            }
            addChapter(chapterIndex = chapterIndex, insert = insert, insertAll = insertAll, remove = remove)
            readerState = ReaderState.IDLE
        }
    }

    fun reload() {
        coroutineContext.cancelChildren()
        loaderQueue.clear()
        launch(Dispatchers.Main.immediate) {
            items.clear()
            readerViewHandlersActions.doForceUpdateListViewState()
            loadedChapters.clear()
            hasLoadingError = false
            readerState = ReaderState.INITIAL_LOAD
            startChapterLoaderWatcher()
        }
    }

    private fun startChapterLoaderWatcher() {
        launch {
            chapterLoaderFlow.collect {
                when (it) {
                    is LoadChapter.Initial -> {
                        loadInitialChapter(chapterIndex = it.chapterIndex)
                        removeQueueItem(LoadChapter.Type.Initial)
                    }
                    is LoadChapter.Next -> {
                        loadNextChapter()
                        removeQueueItem(LoadChapter.Type.Next)
                    }
                    is LoadChapter.Previous -> {
                        loadPreviousChapter()
                        removeQueueItem(LoadChapter.Type.Previous)
                    }
                    is LoadChapter.RestartInitialChapter -> {
                        loadRestartedInitialChapter(chapterLastState = it.state)
                        removeQueueItem(LoadChapter.Type.RestartInitial)
                    }
                }
            }
        }
    }

    @Synchronized private fun removeQueueItem(type: LoadChapter.Type) {
        loaderQueue.remove(type)
    }

    private suspend fun loadRestartedInitialChapter(
        chapterLastState: ChapterState
    ) = withContext(Dispatchers.Main.immediate) {
        readerState = ReaderState.INITIAL_LOAD
        items.clear()
        readerViewHandlersActions.doForceUpdateListViewState()

        val insert: suspend (ReaderItem) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.add(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }
        val insertAll: suspend (Collection<ReaderItem>) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.addAll(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }
        val remove: suspend (ReaderItem) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.remove(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }

        val index = orderedChapters.indexOfFirst { it.url == chapterLastState.chapterUrl }
        if (index == -1) {
            readerViewHandlersActions.doShowInvalidChapterDialog()
            return@withContext
        }

        addChapter(chapterIndex = index, insert = insert, insertAll = insertAll, remove = remove,
            maintainPosition = readerViewHandlersActions::doMaintainStartPosition)

        readerViewHandlersActions.doForceUpdateListViewState()
        readerViewHandlersActions.doSetInitialPosition(
            InitialPositionChapter(
                chapterIndex = index,
                chapterItemPosition = chapterLastState.chapterItemPosition,
                chapterItemOffset = chapterLastState.offset
            )
        )
        readerState = ReaderState.IDLE
        chapterLoadedFlow.emit(ChapterLoaded(chapterIndex = index, type = ChapterLoaded.Type.Initial))
    }

    private suspend fun loadInitialChapter(
        chapterIndex: Int
    ) = withContext(Dispatchers.Main.immediate) {
        readerState = ReaderState.INITIAL_LOAD
        items.clear()
        readerViewHandlersActions.doForceUpdateListViewState()

        if (chapterIndex < 0 || chapterIndex >= orderedChapters.size) {
            readerViewHandlersActions.doShowInvalidChapterDialog()
            return@withContext
        }

        val insert: suspend (ReaderItem) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.add(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }
        val insertAll: suspend (Collection<ReaderItem>) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.addAll(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }
        val remove: suspend (ReaderItem) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.remove(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }

        addChapter(chapterIndex = chapterIndex, insert = insert, insertAll = insertAll, remove = remove,
            maintainPosition = readerViewHandlersActions::doMaintainStartPosition)

        val chapter = orderedChapters[chapterIndex]
        val initialPosition = readerRepository.getInitialChapterItemPosition(
            bookUrl = bookUrl,
            chapterIndex = chapter.position,
            chapter = chapter,
        )
        readerViewHandlersActions.doForceUpdateListViewState()
        readerViewHandlersActions.doSetInitialPosition(initialPosition)
        chapterLoadedFlow.emit(ChapterLoaded(chapterIndex = chapterIndex, type = ChapterLoaded.Type.Initial))
        readerState = ReaderState.IDLE
    }

    private suspend fun loadPreviousChapter() = withContext(Dispatchers.Main.immediate) {
        readerState = ReaderState.LOADING

        val firstItem = items.firstOrNull()
        if (firstItem == null) {
            readerState = ReaderState.IDLE
            return@withContext
        }
        if (firstItem is ReaderItem.BookStart) {
            readerState = ReaderState.IDLE
            return@withContext
        }

        var listIndex = 0
        val insert: suspend (ReaderItem) -> Unit = {
            withContext(Dispatchers.Main.immediate) {
                items.add(listIndex, it); listIndex += 1
                readerViewHandlersActions.doForceUpdateListViewState()
            }
        }
        val insertAll: suspend (Collection<ReaderItem>) -> Unit = {
            withContext(Dispatchers.Main.immediate) {
                items.addAll(listIndex, it); listIndex += it.size
                readerViewHandlersActions.doForceUpdateListViewState()
            }
        }
        val remove: suspend (ReaderItem) -> Unit = {
            withContext(Dispatchers.Main.immediate) {
                if (items.remove(it)) listIndex -= 1
                readerViewHandlersActions.doForceUpdateListViewState()
            }
        }

        val previousIndex = firstItem.chapterIndex - 1
        if (previousIndex < 0) {
            readerViewHandlersActions.doMaintainLastVisiblePosition {
                insert(ReaderItem.BookStart(chapterIndex = previousIndex))
                readerViewHandlersActions.doForceUpdateListViewState()
            }
            readerState = ReaderState.IDLE
            return@withContext
        }

        addChapter(chapterIndex = previousIndex, insert = insert, insertAll = insertAll, remove = remove,
            maintainPosition = readerViewHandlersActions::doMaintainLastVisiblePosition)

        chapterLoadedFlow.emit(ChapterLoaded(chapterIndex = previousIndex, type = ChapterLoaded.Type.Previous))
        readerState = ReaderState.IDLE
    }

    private suspend fun loadNextChapter() = withContext(Dispatchers.Main.immediate) {
        readerState = ReaderState.LOADING

        // ✅ Правильный способ определить последнюю загруженную главу
        val lastChapterIndex = items.maxOfOrNull { it.chapterIndex }

        if (lastChapterIndex == null) {
            readerState = ReaderState.IDLE
            return@withContext
        }

        val insert: suspend (ReaderItem) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.add(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }
        val insertAll: suspend (Collection<ReaderItem>) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.addAll(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }
        val remove: suspend (ReaderItem) -> Unit = {
            withContext(Dispatchers.Main.immediate) { items.remove(it); readerViewHandlersActions.doForceUpdateListViewState() }
        }

        if (lastChapterIndex >= orderedChapters.lastIndex) {
            if (items.none { it is ReaderItem.BookEnd }) {
                insert(ReaderItem.BookEnd(chapterIndex = lastChapterIndex + 1))
                readerViewHandlersActions.doForceUpdateListViewState()
            }
            readerState = ReaderState.IDLE
            return@withContext
        }

        // ✅ Всегда грузим только следующую по порядку главу
        val nextIndex = lastChapterIndex + 1

        addChapter(chapterIndex = nextIndex, insert = insert, insertAll = insertAll, remove = remove)
        chapterLoadedFlow.emit(ChapterLoaded(chapterIndex = nextIndex, type = ChapterLoaded.Type.Next))
        readerState = ReaderState.IDLE
    }

    private suspend fun addChapter(
        chapterIndex: Int,
        insert: suspend (ReaderItem) -> Unit,
        insertAll: suspend (Collection<ReaderItem>) -> Unit,
        remove: suspend (ReaderItem) -> Unit,
        maintainPosition: suspend (suspend () -> Unit) -> Unit = { it() },
    ) = withContext(Dispatchers.Default) {
        val chapter = orderedChapters[chapterIndex]

        // Защита от двойной загрузки: блокируем сразу при входе в функцию
        synchronized(loadedChapters) {
            if (loadedChapters.contains(chapter.url)) {
                android.util.Log.d(TAG, "addChapter: chapter ${chapter.url} already loaded or loading, skipping")
                return@withContext
            }
            loadedChapters.add(chapter.url)
        }

        val itemProgressBar = ReaderItem.Progressbar(chapterIndex = chapterIndex)
        var chapterItemPosition = 0
        val titleOriginal = chapter.title
        val titleTranslated = if (translatorIsActive()) {
            val sourceLang = translatorSourceLanguageOrNull()
            val targetLang = translatorTargetLanguageOrNull()
            // Сначала проверяем кэш — избегаем лишнего сетевого запроса при повторном открытии
            val cachedTitle = if (sourceLang != null && targetLang != null) {
                withContext(Dispatchers.IO) {
                    chapterTranslationDao.getTranslations(
                        chapterUrl = chapter.url,
                        sourceLang = sourceLang,
                        targetLang = targetLang
                    ).find { it.originalText == titleOriginal }?.translatedText
                }
            } else null

            if (cachedTitle != null) {
                cachedTitle
            } else {
                val translated = translatorTranslateOrNull(titleOriginal) ?: titleOriginal
                // Сохраняем перевод названия в БД чтобы список глав мог его отобразить
                if (sourceLang != null && targetLang != null && translated != titleOriginal) {
                    withContext(Dispatchers.IO) {
                        chapterTranslationDao.insertReplace(
                            listOf(
                                ChapterTranslation(
                                    chapterUrl = chapter.url,
                                    sourceLang = sourceLang,
                                    targetLang = targetLang,
                                    originalText = titleOriginal,
                                    translatedText = translated,
                                )
                            )
                        )
                    }
                }
                translated
            }
        } else titleOriginal

        val itemTitle = ReaderItem.Title(
            chapterUrl = chapter.url,
            chapterIndex = chapterIndex,
            text = titleOriginal,
            chapterItemPosition = chapterItemPosition,
        ).copy(textTranslated = titleTranslated)
        chapterItemPosition += 1

        maintainPosition {
            insert(ReaderItem.Divider(chapterIndex = chapterIndex))
            insert(itemTitle)
            insert(itemProgressBar)
            readerViewHandlersActions.doForceUpdateListViewState()
        }

        when (val res = readerRepository.downloadChapter(chapter.url)) {
            is Response.Success -> {
                if (!isValidChapterContent(res.data)) {
                    withContext(Dispatchers.Main.immediate) {
                        hasLoadingError = true
                        android.util.Log.w(TAG, "Chapter content invalid, stopping auto-loading. Preview: ${res.data.take(200)}")
                    }
                    maintainPosition {
                        remove(itemProgressBar)
                        // Откатываем Divider и Title, вставленные до загрузки контента.
                        // Без этого скролл до Error-item'а обновляет currentChapter на следующую главу,
                        // что приводит к сохранению неверной позиции и пропуску главы при retry.
                        remove(itemTitle)
                        items.removeAll { it is ReaderItem.Divider && it.chapterIndex == chapterIndex }
                        val preview = res.data.take(300).ifBlank { "<null>" }
                        val userMessage = if (java.util.Locale.getDefault().language == "ru")
                            "Ошибка контента: защита Cloudflare, пустая глава или требуется авторизация. Попробуйте открыть в браузере, чтобы пройти проверку.\n\nПолучено: $preview"
                        else
                            "Invalid content: Cloudflare protection, empty chapter, or login required. Try opening in browser to pass the check.\n\nReceived: $preview"
                        insert(ReaderItem.Error(chapterIndex = chapterIndex, chapterUrl = chapter.url, text = userMessage))
                        readerViewHandlersActions.doForceUpdateListViewState()
                    }
                    return@withContext
                }

                val itemsOriginal = textToItemsConverter(
                    chapterUrl = chapter.url,
                    chapterIndex = chapterIndex,
                    chapterItemPositionDisplacement = chapterItemPosition,
                    text = res.data,
                    userRegexRules = regexRulesProvider(),
                )
                chapterItemPosition += itemsOriginal.size

                val itemTranslationAttribution = if (translatorIsActive()) {
                    ReaderItem.TranslateAttribution(chapterIndex = chapterIndex, provider = translatorProvider())
                } else null

                val itemTranslating = if (translatorIsActive()) {
                    ReaderItem.Translating(
                        chapterIndex = chapterIndex,
                        sourceLang = translatorSourceLanguageOrNull() ?: "",
                        targetLang = translatorTargetLanguageOrNull() ?: "",
                    )
                } else null

                if (itemTranslating != null) {
                    maintainPosition {
                        insert(itemTranslating)
                        readerViewHandlersActions.doForceUpdateListViewState()
                    }
                }

                val items = when {
                    translatorIsActive() -> {
                        val sourceLang = translatorSourceLanguageOrNull() ?: "en"
                        val targetLang = translatorTargetLanguageOrNull() ?: "zh"
                        val batchTranslator = translatorBatchTranslateOrNull()

                        if (batchTranslator != null) {
                            val dbTranslations = withContext(Dispatchers.IO) {
                                chapterTranslationDao.getTranslations(
                                    chapterUrl = chapter.url,
                                    sourceLang = sourceLang,
                                    targetLang = targetLang
                                ).associate { it.originalText to it.translatedText }
                            }

                            val textsToTranslate = itemsOriginal.filterIsInstance<ReaderItem.Body>().map { it.text }

                            val result = if (dbTranslations.isNotEmpty()) {
                                // Кэш есть — проверяем полноту
                                val missingFromDb = textsToTranslate.filter { !dbTranslations.containsKey(it) }

                                // ✅ Если всё уже переведено — пропускаем запрос полностью
                                if (missingFromDb.isEmpty()) {
                                    android.util.Log.d(TAG, "Using full DB cache for chapter ${chapter.title} (${dbTranslations.size} translations)")
                                    itemsOriginal.map {
                                        if (it is ReaderItem.Body) it.copy(textTranslated = dbTranslations[it.text] ?: it.text)
                                        else it
                                    }
                                } else {
                                    android.util.Log.d(TAG, "DB cache partial: ${dbTranslations.size}/${textsToTranslate.size}, translating ${missingFromDb.size} missing")
                                    val extraTranslations = withContext(Dispatchers.IO) {
                                        batchTranslator.invoke(missingFromDb)
                                    }
                                    withContext(Dispatchers.IO) {
                                        val entities = missingFromDb.map { original ->
                                            ChapterTranslation(
                                                chapterUrl = chapter.url,
                                                sourceLang = sourceLang,
                                                targetLang = targetLang,
                                                originalText = original,
                                                translatedText = extraTranslations[original] ?: original
                                            )
                                        }
                                        chapterTranslationDao.insertReplace(entities)
                                    }
                                    val fullTranslations = dbTranslations + extraTranslations
                                    itemsOriginal.map {
                                        if (it is ReaderItem.Body) it.copy(textTranslated = fullTranslations[it.text] ?: it.text)
                                        else it
                                    }
                                }
                            } else {
                                // Кэша нет — переводим и сохраняем
                                translateAndCache(itemsOriginal, textsToTranslate, batchTranslator, chapter.url, sourceLang, targetLang)
                            }
                            result
                        } else {
                            android.util.Log.d(TAG, "Using paragraph-by-paragraph translation (batch not available)")
                            itemsOriginal.map {
                                if (it is ReaderItem.Body) it.copy(textTranslated = translatorTranslateOrNull(it.text))
                                else it
                            }
                        }
                    }
                    else -> itemsOriginal
                }

                withContext(Dispatchers.Main.immediate) {
                    chaptersStats[chapter.url] = ChapterStats(
                        chapter = chapter,
                        itemsCount = items.size,
                        orderedChaptersIndex = chapterIndex
                    )
                }

                maintainPosition {
                    remove(itemProgressBar)
                    itemTranslating?.let { remove(it) }
                    itemTranslationAttribution?.let { insert(it) }
                    insertAll(items)
                    insert(ReaderItem.Divider(chapterIndex = chapterIndex))
                    readerViewHandlersActions.doForceUpdateListViewState()
                }
            }
            is Response.Error -> {
                withContext(Dispatchers.Main.immediate) {
                    chaptersStats[chapter.url] = ChapterStats(
                        chapter = chapter,
                        itemsCount = 1,
                        orderedChaptersIndex = chapterIndex
                    )
                    hasLoadingError = true
                    // ✅ Освобождаем блокировку чтобы можно было повторить попытку
                    synchronized(loadedChapters) {
                        loadedChapters.remove(chapter.url)
                    }
                    android.util.Log.w(TAG, "Chapter load error: ${res.message}, stopping further auto-loading")
                }
                maintainPosition {
                    remove(itemProgressBar)
                    // Откатываем Divider и Title, вставленные до загрузки контента.
                    // Без этого скролл до Error-item'а обновляет currentChapter на следующую главу,
                    // что приводит к сохранению неверной позиции и пропуску главы при retry.
                    remove(itemTitle)
                    items.removeAll { it is ReaderItem.Divider && it.chapterIndex == chapterIndex }
                    val rawDetail = res.exception.message?.takeIf { it.isNotBlank() } ?: res.message
                    // LuaError dumps the entire plugin source into the message after ":N " —
                    // extract only the short error part that follows the last colon+space pattern
                    // e.g. "...script...:301 [1401] You are not logged in!" → "[1401] You are not logged in!"
                    val detail = Regex(""":\d+\s+(\[?\w.*?)$""", RegexOption.DOT_MATCHES_ALL)
                        .find(rawDetail)?.groupValues?.get(1)?.trim()
                        ?: rawDetail.lines().lastOrNull { it.isNotBlank() }?.trim()
                        ?: rawDetail
                    val userMessage = if (java.util.Locale.getDefault().language == "ru")
                        "Ошибка загрузки: $detail\n\nВозможные причины: защита Cloudflare, требуется авторизация или проблема с источником. Попробуйте открыть в браузере."
                    else
                        "Load error: $detail\n\nPossible causes: Cloudflare protection, login required, or source issue. Try opening in browser."
                    insert(ReaderItem.Error(chapterIndex = chapterIndex, chapterUrl = chapter.url, text = userMessage))
                    readerViewHandlersActions.doForceUpdateListViewState()
                }
            }
        }
    }

    private suspend fun translateAndCache(
        itemsOriginal: List<ReaderItem>,
        textsToTranslate: List<String>,
        batchTranslator: suspend (List<String>) -> Map<String, String>,
        chapterUrl: String,
        sourceLang: String,
        targetLang: String,
    ): List<ReaderItem> {
        if (textsToTranslate.isEmpty()) return itemsOriginal

        android.util.Log.d(TAG, "translateAndCache: translating ${textsToTranslate.size} paragraphs")
        val translations = batchTranslator.invoke(textsToTranslate)

        val missing = textsToTranslate.size - translations.size
        if (missing > 0) android.util.Log.w(TAG, "translateAndCache: $missing paragraphs missing, saving original as fallback")

        withContext(Dispatchers.IO) {
            val entities = textsToTranslate.map { original ->
                ChapterTranslation(
                    chapterUrl = chapterUrl,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    originalText = original,
                    translatedText = translations[original] ?: original
                )
            }
            chapterTranslationDao.insertReplace(entities)
            android.util.Log.d(TAG, "translateAndCache: saved ${entities.size} translations to DB")
        }

        return itemsOriginal.map {
            if (it is ReaderItem.Body) it.copy(textTranslated = translations[it.text] ?: it.text)
            else it
        }
    }

    companion object {
        private const val TAG = "ReaderChaptersLoader"
    }
}