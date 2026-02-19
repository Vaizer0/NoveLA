package my.noveldokusha.features.reader.features

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
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

    // Главы, которые сейчас переводятся в фоне (pre-translate)
    // Используется как мьютекс чтобы не запускать дублирующий перевод
    private val preTranslatingChapters = mutableSetOf<String>()

    fun clearTranslationCache() {
        android.util.Log.d(TAG, "clearTranslationCache: clearing pre-translating chapters tracking")
        preTranslatingChapters.clear()
    }

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
    private val chapterLoaderFlow = MutableSharedFlow<LoadChapter>()

    @Volatile var hasLoadingError = false

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

    fun reload() {
        coroutineContext.cancelChildren()
        loaderQueue.clear()
        launch(Dispatchers.Main.immediate) {
            items.clear()
            readerViewHandlersActions.doForceUpdateListViewState()
            loadedChapters.clear()
            preTranslatingChapters.clear()
            hasLoadingError = false
            readerState = ReaderState.INITIAL_LOAD
            startChapterLoaderWatcher()
        }
    }

    /**
     * Pre-translate следующей главы в фоне.
     * Сохраняет ВСЕ параграфы в БД (включая fallback на оригинал).
     * Если перевод уже идёт или уже есть в БД — пропускает.
     */
    fun preTranslateNextChapter(currentChapterIndex: Int) {
        if (hasLoadingError) return

        val batchTranslator = translatorBatchTranslateOrNull()
        if (!translatorIsActive() || batchTranslator == null) return

        val nextIndex = currentChapterIndex + 1
        if (nextIndex >= orderedChapters.size) return

        val nextChapter = orderedChapters[nextIndex]

        // Уже переводится — пропускаем (мьютекс)
        if (!preTranslatingChapters.add(nextChapter.url)) {
            android.util.Log.d(TAG, "Pre-translation: Chapter ${nextChapter.title} already in progress, skipping")
            return
        }

        android.util.Log.d(TAG, "Pre-translation: Starting for chapter ${nextChapter.title}")

        launch(Dispatchers.IO) {
            try {
                val sourceLang = translatorSourceLanguageOrNull() ?: "en"
                val targetLang = translatorTargetLanguageOrNull() ?: "zh"

                // Проверяем БД — если переводы уже есть, не переводим повторно
                val existing = chapterTranslationDao.getTranslations(
                    chapterUrl = nextChapter.url,
                    sourceLang = sourceLang,
                    targetLang = targetLang
                )
                if (existing.isNotEmpty()) {
                    android.util.Log.d(TAG, "Pre-translation: Chapter ${nextChapter.title} already in DB (${existing.size}), skipping")
                    return@launch
                }

                val res = readerRepository.downloadChapter(nextChapter.url)
                if (res !is Response.Success) return@launch

                if (!isValidChapterContent(res.data)) {
                    android.util.Log.w(TAG, "Pre-translation: Invalid content, skipping")
                    hasLoadingError = true
                    return@launch
                }

                val itemsOriginal = textToItemsConverter(
                    chapterUrl = nextChapter.url,
                    chapterIndex = nextIndex,
                    chapterItemPositionDisplacement = 0,
                    text = res.data,
                    userRegexRules = regexRulesProvider(),
                )

                val textsToTranslate = itemsOriginal.filterIsInstance<ReaderItem.Body>().map { it.text }
                if (textsToTranslate.isEmpty()) return@launch

                android.util.Log.d(TAG, "Pre-translation: Translating ${textsToTranslate.size} paragraphs for ${nextChapter.title}")
                val translations = batchTranslator.invoke(textsToTranslate)

                // Сохраняем ВСЕ параграфы — если перевод не пришёл, пишем оригинал
                // Это гарантирует что при загрузке главы кэш будет полным
                val entities = textsToTranslate.map { original ->
                    ChapterTranslation(
                        chapterUrl = nextChapter.url,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        originalText = original,
                        translatedText = translations[original] ?: original
                    )
                }
                chapterTranslationDao.insertReplace(entities)
                android.util.Log.d(TAG, "Pre-translation: Saved ${entities.size} translations (${textsToTranslate.size - translations.size} fallback to original)")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Pre-translation: Failed - ${e.message}")
            } finally {
                preTranslatingChapters.remove(nextChapter.url)
            }
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

        val firstItem = items.firstOrNull()!!
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

        val lastItem = items.lastOrNull()!!
        if (lastItem is ReaderItem.BookEnd) {
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

        val nextIndex = lastItem.chapterIndex + 1
        if (nextIndex >= orderedChapters.size) {
            insert(ReaderItem.BookEnd(chapterIndex = nextIndex))
            readerViewHandlersActions.doForceUpdateListViewState()
            readerState = ReaderState.IDLE
            return@withContext
        }

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
        val itemProgressBar = ReaderItem.Progressbar(chapterIndex = chapterIndex)
        var chapterItemPosition = 0
        val itemTitle = ReaderItem.Title(
            chapterUrl = chapter.url,
            chapterIndex = chapterIndex,
            text = chapter.title,
            chapterItemPosition = chapterItemPosition,
        ).copy(textTranslated = translatorTranslateOrNull(chapter.title) ?: chapter.title)
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
                        android.util.Log.w(TAG, "Chapter content invalid, stopping auto-loading")
                    }
                    maintainPosition {
                        remove(itemProgressBar)
                        insert(ReaderItem.Error(chapterIndex = chapterIndex, text = "Invalid content - Cloudflare protection or empty chapter"))
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
                            // Проверяем БД
                            val dbTranslations = withContext(Dispatchers.IO) {
                                chapterTranslationDao.getTranslations(
                                    chapterUrl = chapter.url,
                                    sourceLang = sourceLang,
                                    targetLang = targetLang
                                ).associate { it.originalText to it.translatedText }
                            }

                            val textsToTranslate = itemsOriginal.filterIsInstance<ReaderItem.Body>().map { it.text }

                            if (dbTranslations.isNotEmpty()) {
                                // Кэш есть — проверяем полноту
                                val missingFromDb = textsToTranslate.filter { !dbTranslations.containsKey(it) }

                                if (missingFromDb.isNotEmpty()) {
                                    // В кэше дыры (старый неполный кэш) — доперевводим недостающее
                                    android.util.Log.d(TAG, "DB cache partial: ${dbTranslations.size}/${textsToTranslate.size}, translating ${missingFromDb.size} missing")
                                    val extraTranslations = withContext(Dispatchers.IO) {
                                        batchTranslator.invoke(missingFromDb)
                                    }
                                    // Сохраняем недостающие в БД
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
                                } else {
                                    android.util.Log.d(TAG, "Using full DB cache for chapter ${chapter.title} (${dbTranslations.size} translations)")
                                    itemsOriginal.map {
                                        if (it is ReaderItem.Body) it.copy(textTranslated = dbTranslations[it.text] ?: it.text)
                                        else it
                                    }
                                }
                            } else {
                                // Кэша нет — ждём если pre-translate сейчас идёт, иначе переводим сами
                                if (preTranslatingChapters.contains(chapter.url)) {
                                    // Pre-translate уже работает — ждём его завершения
                                    android.util.Log.d(TAG, "Pre-translation in progress for ${chapter.title}, waiting...")
                                    var waited = 0
                                    while (preTranslatingChapters.contains(chapter.url) && waited < 10_000) {
                                        kotlinx.coroutines.delay(200)
                                        waited += 200
                                    }
                                    // Перечитываем БД после ожидания
                                    val freshTranslations = withContext(Dispatchers.IO) {
                                        chapterTranslationDao.getTranslations(
                                            chapterUrl = chapter.url,
                                            sourceLang = sourceLang,
                                            targetLang = targetLang
                                        ).associate { it.originalText to it.translatedText }
                                    }
                                    if (freshTranslations.isNotEmpty()) {
                                        android.util.Log.d(TAG, "Pre-translation finished, using DB cache (${freshTranslations.size} translations)")
                                        itemsOriginal.map {
                                            if (it is ReaderItem.Body) it.copy(textTranslated = freshTranslations[it.text] ?: it.text)
                                            else it
                                        }
                                    } else {
                                        // Pre-translate не успел или упал — переводим сами
                                        translateAndCache(itemsOriginal, textsToTranslate, batchTranslator, chapter.url, sourceLang, targetLang)
                                    }
                                } else {
                                    // Нет кэша и нет pre-translate — переводим сами
                                    translateAndCache(itemsOriginal, textsToTranslate, batchTranslator, chapter.url, sourceLang, targetLang)
                                }
                            }
                        } else {
                            // Fallback: перевод параграф за параграфом (MLKit)
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
                withContext(Dispatchers.Main.immediate) {
                    loadedChapters.add(chapter.url)
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
                    android.util.Log.w(TAG, "Chapter load error: ${res.message}, stopping further auto-loading")
                }
                maintainPosition {
                    remove(itemProgressBar)
                    insert(ReaderItem.Error(chapterIndex = chapterIndex, text = res.message))
                    readerViewHandlersActions.doForceUpdateListViewState()
                }
            }
        }
    }

    /**
     * Переводит параграфы и сохраняет ВСЕ в БД (включая fallback на оригинал для пропущенных).
     */
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

        // Сохраняем ВСЕ параграфы — пропущенные сохраняем как оригинал
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