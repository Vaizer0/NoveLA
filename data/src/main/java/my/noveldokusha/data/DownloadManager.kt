package my.noveldokusha.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.feature.local_database.DAOs.DownloadTaskDao
import my.noveldokusha.feature.local_database.tables.DownloadTaskEntity
import my.noveldokusha.text_translator.domain.TranslationManager
import org.json.JSONArray
import java.net.URI
import java.net.UnknownHostException
import java.net.ConnectException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Состояние одной задачи на скачивание.
 */
data class DownloadTaskState(
    val bookTitle: String,
    val bookUrl: String,
    val chapterUrls: List<String>,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false,
    val isCompleted: Boolean = false,
    val isWaitingForNetwork: Boolean = false,
    val errorCount: Int = 0,
    val successCount: Int = 0,
    val consecutiveErrors: Int = 0,
    val skippedCount: Int = 0,
    val translationErrorCount: Int = 0,
) {
    val progressText: String
        get() = "$currentIndex / $totalCount"
}

/**
 * Per-domain очередь загрузок.
 *
 * Один домен = один worker-корутин, обрабатывающий книги последовательно.
 * Разные домены работают параллельно и независимо.
 *
 * Пауза/отмена освобождают домен для следующей книги в очереди.
 * Это предотвращает бан источника из-за параллельных запросов.
 */
private class DomainQueue {
    /** FIFO-очередь bookUrl для этого домена. */
    val queue: ArrayDeque<String> = ArrayDeque()

    /** Job активного worker'а. null или завершённый = worker не запущен. */
    var workerJob: Job? = null
}

/**
 * Менеджер очереди загрузок глав.
 *
 * Архитектура:
 * - Каждый домен имеет свою [DomainQueue] с FIFO-очередью книг.
 * - Worker домена последовательно берёт книги из очереди и скачивает их.
 * - Пауза задачи немедленно освобождает домен — следующая книга начинает качаться.
 * - Resume ставит книгу в начало очереди домена (prepend=true).
 * - Разные домены работают полностью независимо и параллельно.
 *
 * Синхронизация:
 * - [tasksLock] защищает [_tasks], [domainQueues], [activeJobs] — единый lock, без deadlock.
 * - [notifications] — ConcurrentHashMap, не требует lock (read/write атомарны).
 *
 * QA-сценарии покрытые архитектурой:
 * 1. Две книги с одного домена → вторая ждёт в DomainQueue, не начинает качаться параллельно.
 * 2. Пауза книги А не останавливает книгу Б с другого домена.
 * 3. Пауза освобождает домен → следующая книга на этом домене сразу начинает качаться.
 * 4. Resume вставляет книгу в начало очереди домена (не в конец).
 * 5. Cancel во время backoff delay → проверка isPaused/isCancelled после delay.
 * 6. Enqueue уже качающейся книги → добавляет только новые главы, не сбрасывает счётчики.
 * 7. Enqueue паузнутой книги → resume.
 * 8. Enqueue завершённой/отменённой книги → создаёт новую задачу.
 * 9. Перезапуск приложения → задачи восстанавливаются из БД; паузнутые показаны, активные в очереди.
 * 10. Race condition pause() до регистрации activeJob → CoroutineStart.LAZY фиксит.
 * 11. Уведомление: прогресс обновляется без мигания (updateProgress, не showDownloading).
 * 12. Уведомление: завершение → только Dismiss (смахнуть), без Pause/Cancel.
 * 13. Уведомление: cancel → уведомление исчезает через 2s.
 * 14. Уведомление: PendingIntent requestCode уникален, не коллидирует между книгами.
 */
/**
 * Результат вызова [DownloadManager.enqueue].
 *
 * [Added]       — новая задача создана, [count] глав добавлено в очередь.
 * [ChaptersAdded] — задача уже существует, [count] новых глав добавлено.
 * [Resumed]     — задача была паузнута, возобновлена.
 * [AlreadyQueued] — все главы уже в очереди или скачаны, ничего не изменилось.
 * [AllCached]   — все главы уже скачаны, нечего качать.
 */
sealed class EnqueueResult {
    data class Added(val count: Int) : EnqueueResult()
    data class ChaptersAdded(val count: Int) : EnqueueResult()
    object Resumed : EnqueueResult()
    object AlreadyQueued : EnqueueResult()
    object AllCached : EnqueueResult()
}

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val appRepository: my.noveldokusha.data.AppRepository,
    private val chapterBodyRepository: ChapterBodyRepository,
    private val translationManager: TranslationManager,
    private val chapterTranslationDao: my.noveldokusha.feature.local_database.DAOs.ChapterTranslationDao,
    private val notificationsCenter: NotificationsCenter,
    private val downloadTaskDao: DownloadTaskDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Единый lock для _tasks, domainQueues, activeJobs
    private val tasksLock = Any()
    private val _tasks = MutableStateFlow<List<DownloadTaskState>>(emptyList())
    val tasks: StateFlow<List<DownloadTaskState>> = _tasks.asStateFlow()

    // activeJobs: bookUrl -> Job скачивания. Доступ только под tasksLock.
    private val activeJobs = HashMap<String, Job>()

    // Per-domain очереди. Доступ только под tasksLock.
    private val domainQueues = HashMap<String, DomainQueue>()

    // Уведомления: bookUrl -> BookDownloadNotification.
    // ConcurrentHashMap — не требует tasksLock, операции атомарны.
    private val notifications = ConcurrentHashMap<String, BookDownloadNotification>()

    companion object {
        private const val TAG = "DownloadManager"
    }

    init {
        scope.launch { restoreTasksFromDatabase() }
    }

    // ── Восстановление из БД ─────────────────────────────────────────────────

    private suspend fun restoreTasksFromDatabase() {
        val savedTasks = try {
            withContext(Dispatchers.IO) { downloadTaskDao.getAll() }
        } catch (e: Exception) {
            // Таблица может не существовать если миграция БД не была применена.
            // Логируем и выходим — не крашим приложение.
            android.util.Log.e(TAG, "restoreTasksFromDatabase: failed to read DB", e)
            return
        }

        android.util.Log.d(TAG, "restoreTasksFromDatabase: found ${savedTasks.size} tasks")

        for (entity in savedTasks) {
            if (entity.isCompleted || entity.isCancelled) {
                withContext(Dispatchers.IO) { downloadTaskDao.delete(entity.bookUrl) }
                continue
            }
            val task = entity.toState()
            android.util.Log.d(TAG, "restoreTasksFromDatabase: restoring ${entity.bookUrl} isPaused=${entity.isPaused} isWaitingForNetwork=${entity.isWaitingForNetwork} index=${entity.currentIndex}/${entity.totalCount}")
            val notif = createNotification(task)

            synchronized(tasksLock) {
                _tasks.value = _tasks.value + task
                if (!task.isPaused) enqueueToWorker(task.bookUrl)
            }

            if (task.isPaused) notif.showPaused(task) else notif.showQueued(task)
        }
    }

    // ── Сериализация ─────────────────────────────────────────────────────────

    private fun toChapterUrlsJson(urls: List<String>): String = JSONArray(urls).toString()

    private fun parseChapterUrlsJson(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun DownloadTaskEntity.toState() = DownloadTaskState(
        bookTitle = bookTitle,
        bookUrl = bookUrl,
        chapterUrls = parseChapterUrlsJson(chapterUrlsJson),
        currentIndex = currentIndex,
        totalCount = totalCount,
        isPaused = isPaused,
        isCancelled = isCancelled,
        isCompleted = isCompleted,
        isWaitingForNetwork = isWaitingForNetwork,
        errorCount = errorCount,
        successCount = successCount,
        consecutiveErrors = consecutiveErrors,
        skippedCount = skippedCount,
        translationErrorCount = translationErrorCount,
    )

    private suspend fun saveTaskToDatabase(task: DownloadTaskState) {
        withContext(Dispatchers.IO) {
            downloadTaskDao.insert(
                DownloadTaskEntity(
                    bookUrl = task.bookUrl,
                    bookTitle = task.bookTitle,
                    chapterUrlsJson = toChapterUrlsJson(task.chapterUrls),
                    currentIndex = task.currentIndex,
                    totalCount = task.totalCount,
                    isPaused = task.isPaused,
                    isCancelled = task.isCancelled,
                    isCompleted = task.isCompleted,
                    isWaitingForNetwork = task.isWaitingForNetwork,
                    errorCount = task.errorCount,
                    successCount = task.successCount,
                    consecutiveErrors = task.consecutiveErrors,
                    skippedCount = task.skippedCount,
                    translationErrorCount = task.translationErrorCount,
                )
            )
        }
    }

    // ── Домены ───────────────────────────────────────────────────────────────

    private fun extractDomain(url: String): String = try {
        URI(url).host ?: "local"
    } catch (_: Exception) {
        "local"
    }

    /**
     * Ставит bookUrl в очередь домена и запускает worker если он не активен.
     * [prepend] = true — вставить в начало (Resume).
     * Вызывать только под [tasksLock].
     */
    private fun enqueueToWorker(bookUrl: String, prepend: Boolean = false) {
        val domain = extractDomain(bookUrl)
        val dq = domainQueues.getOrPut(domain) { DomainQueue() }

        if (bookUrl !in dq.queue) {
            if (prepend) dq.queue.addFirst(bookUrl) else dq.queue.addLast(bookUrl)
        }

        if (dq.workerJob?.isActive != true) {
            dq.workerJob = scope.launch { runDomainWorker(domain) }
        }
    }

    /**
     * Worker домена: последовательно обрабатывает книги из очереди.
     * Между книгами одного домена нет параллелизма.
     */
    private suspend fun runDomainWorker(domain: String) {
        android.util.Log.d(TAG, "worker started: domain=$domain")
        while (true) {
            val bookUrl = synchronized(tasksLock) {
                domainQueues[domain]?.queue?.removeFirstOrNull()
            } ?: break

            val task = synchronized(tasksLock) {
                _tasks.value.find { it.bookUrl == bookUrl }
            }

            if (task == null || task.isCancelled || task.isCompleted || task.isPaused) {
                android.util.Log.d(TAG, "worker skip $bookUrl: null/cancelled/completed/paused")
                continue
            }

            android.util.Log.d(TAG, "worker processing $bookUrl")
            downloadBook(task)
            android.util.Log.d(TAG, "worker finished $bookUrl")
        }
        android.util.Log.d(TAG, "worker stopped: domain=$domain (queue empty)")
    }

    // ── Публичный API ────────────────────────────────────────────────────────

    /**
     * Добавляет главы книги в очередь загрузки.
     *
     * Suspend-функция — возвращает [EnqueueResult] который ViewModel использует
     * для показа правильного тоста. Вызывать из viewModelScope.
     *
     * Логика:
     * - Если задача завершена/отменена — создаём новую.
     * - Если задача активна — добавляем только главы которых ещё нет в очереди.
     * - Если задача паузнута — возобновляем.
     * - Если все главы уже в кеше — возвращаем AllCached.
     * - Если все главы уже в очереди — возвращаем AlreadyQueued.
     */
    suspend fun enqueue(
        bookTitle: String,
        bookUrl: String,
        chapterUrls: List<String>,
    ): EnqueueResult {
        val uniqueUrls = chapterUrls.distinct()
        if (uniqueUrls.isEmpty()) return EnqueueResult.AllCached

        // Фильтруем уже скачанные до lock — IO-операция
        val notDownloadedUrls = withContext(Dispatchers.IO) {
            uniqueUrls.filter { url -> chapterBodyRepository.getCachedBody(url) == null }
        }

        if (notDownloadedUrls.isEmpty()) return EnqueueResult.AllCached

        data class SyncResult(
            val shouldCreateNew: Boolean,
            val result: EnqueueResult?,  // не null = уже знаем ответ, выходим
        )

        val syncResult = synchronized(tasksLock) {
            val existingIdx = _tasks.value.indexOfFirst { it.bookUrl == bookUrl }

            if (existingIdx >= 0) {
                val existing = _tasks.value[existingIdx]

                if (existing.isCancelled || existing.isCompleted) {
                    // Старая завершена/отменена — убираем, создадим новую
                    _tasks.value = _tasks.value.toMutableList().also { it.removeAt(existingIdx) }
                    notifications.remove(bookUrl)?.close()
                    SyncResult(shouldCreateNew = true, result = null)
                } else if (existing.isPaused) {
                    // Паузнутая задача — добавляем новые главы если есть, потом resume
                    val newUrls = notDownloadedUrls.filter { it !in existing.chapterUrls }
                    val base = if (newUrls.isNotEmpty()) {
                        val updated = existing.copy(
                            chapterUrls = existing.chapterUrls + newUrls,
                            totalCount = existing.totalCount + newUrls.size,
                            isCompleted = false,
                            isCancelled = false,
                        )
                        _tasks.value = _tasks.value.toMutableList().also { it[existingIdx] = updated }
                        scope.launch { saveTaskToDatabase(updated) }
                        updated
                    } else existing

                    val resumed = base.copy(isPaused = false, isWaitingForNetwork = false)
                    val idx = _tasks.value.indexOfFirst { it.bookUrl == bookUrl }
                    if (idx >= 0) {
                        _tasks.value = _tasks.value.toMutableList().also { it[idx] = resumed }
                    }
                    scope.launch { saveTaskToDatabase(resumed) }
                    notifications[bookUrl]?.showDownloading(resumed)
                    enqueueToWorker(bookUrl, prepend = true)
                    SyncResult(shouldCreateNew = false, result = EnqueueResult.Resumed)
                } else {
                    // Задача активно качается — добавляем только новые главы
                    val newUrls = notDownloadedUrls.filter { it !in existing.chapterUrls }
                    if (newUrls.isNotEmpty()) {
                        val updated = existing.copy(
                            chapterUrls = existing.chapterUrls + newUrls,
                            totalCount = existing.totalCount + newUrls.size,
                            isCompleted = false,
                            isCancelled = false,
                        )
                        _tasks.value = _tasks.value.toMutableList().also { it[existingIdx] = updated }
                        scope.launch { saveTaskToDatabase(updated) }
                        notifications[bookUrl]?.updateProgress(updated)
                        SyncResult(shouldCreateNew = false, result = EnqueueResult.ChaptersAdded(newUrls.size))
                    } else {
                        // Все главы уже в очереди
                        SyncResult(shouldCreateNew = false, result = EnqueueResult.AlreadyQueued)
                    }
                }
            } else {
                SyncResult(shouldCreateNew = true, result = null)
            }
        }

        // Результат уже известен — выходим без создания новой задачи
        syncResult.result?.let { return it }
        if (!syncResult.shouldCreateNew) return EnqueueResult.AlreadyQueued

        val task = DownloadTaskState(
            bookTitle = bookTitle,
            bookUrl = bookUrl,
            chapterUrls = notDownloadedUrls,
            totalCount = notDownloadedUrls.size,
        )
        val notif = createNotification(task)
        synchronized(tasksLock) {
            _tasks.value = _tasks.value + task
            enqueueToWorker(bookUrl)
        }
        scope.launch { saveTaskToDatabase(task) }
        notif.showQueued(task)
        return EnqueueResult.Added(notDownloadedUrls.size)
    }

    fun pause(bookUrl: String) {
        val paused: DownloadTaskState?
        synchronized(tasksLock) {
            val idx = _tasks.value.indexOfFirst { it.bookUrl == bookUrl }
            if (idx < 0) return
            val task = _tasks.value[idx]
            if (task.isPaused || task.isCompleted || task.isCancelled) return

            paused = task.copy(isPaused = true, isWaitingForNetwork = false)
            _tasks.value = _tasks.value.toMutableList().also { it[idx] = paused!! }

            activeJobs.remove(bookUrl)?.cancel()
            domainQueues[extractDomain(bookUrl)]?.queue?.remove(bookUrl)
        }
        paused ?: return
        scope.launch { saveTaskToDatabase(paused) }
        notifications[bookUrl]?.showPaused(paused)
    }

    fun resume(bookUrl: String) {
        val resumed: DownloadTaskState?
        synchronized(tasksLock) {
            val idx = _tasks.value.indexOfFirst { it.bookUrl == bookUrl }
            if (idx < 0) return
            val task = _tasks.value[idx]
            if (!task.isPaused) return

            resumed = task.copy(isPaused = false, isWaitingForNetwork = false)
            _tasks.value = _tasks.value.toMutableList().also { it[idx] = resumed!! }
            enqueueToWorker(bookUrl, prepend = true)
        }
        resumed ?: return
        scope.launch { saveTaskToDatabase(resumed) }
        notifications[bookUrl]?.showDownloading(resumed)
    }

    fun cancel(bookUrl: String) {
        val task: DownloadTaskState?
        synchronized(tasksLock) {
            activeJobs.remove(bookUrl)?.cancel()
            domainQueues[extractDomain(bookUrl)]?.queue?.remove(bookUrl)
            val idx = _tasks.value.indexOfFirst { it.bookUrl == bookUrl }
            task = if (idx >= 0) {
                _tasks.value[idx].also {
                    _tasks.value = _tasks.value.toMutableList().also { l -> l.removeAt(idx) }
                }
            } else null
        }
        task ?: return
        // showCancelled и auto-close через 2s
        notifications.remove(bookUrl)?.let { notif ->
            notif.showCancelled()
            scope.launch {
                delay(2_000)
                notif.close()
            }
        }
        scope.launch { withContext(Dispatchers.IO) { downloadTaskDao.delete(bookUrl) } }
    }

    /**
     * Убирает завершённую/паузнутую задачу из UI и закрывает уведомление.
     * Вызывается пользователем из шторки или по смахиванию уведомления.
     */
    fun dismiss(bookUrl: String) {
        synchronized(tasksLock) {
            _tasks.value = _tasks.value.filter { it.bookUrl != bookUrl }
        }
        notifications.remove(bookUrl)?.close()
        scope.launch { withContext(Dispatchers.IO) { downloadTaskDao.delete(bookUrl) } }
    }

    // ── Скачивание книги ─────────────────────────────────────────────────────

    private suspend fun downloadBook(initialTask: DownloadTaskState) {
        val bookUrl = initialTask.bookUrl

        // CoroutineStart.LAZY: регистрируем в activeJobs ДО старта корутины.
        // Устраняет race condition когда pause() приходит до регистрации job.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Переключаем уведомление с Queued (только Cancel)
                // на Downloading (Pause + Cancel) в момент реального старта.
                val startTask = synchronized(tasksLock) {
                    _tasks.value.find { it.bookUrl == bookUrl }
                }
                if (startTask != null) notifications[bookUrl]?.showDownloading(startTask)

                val delayMs = appPreferences.DOWNLOAD_DELAY_MS.value
                var i = synchronized(tasksLock) {
                    _tasks.value.find { it.bookUrl == bookUrl }?.currentIndex ?: 0
                }

                while (true) {
                    val current = synchronized(tasksLock) {
                        _tasks.value.find { it.bookUrl == bookUrl }
                    } ?: return@launch

                    if (current.isCancelled) return@launch
                    if (current.isPaused) return@launch
                    if (i >= current.chapterUrls.size) break

                    val chapterUrl = current.chapterUrls[i]

                    // Обновляем индекс, сохраняем в БД и обновляем уведомление
                    val withIndex = updateTask(bookUrl) { it.copy(currentIndex = i + 1) }
                    if (withIndex != null) {
                        // Сохраняем currentIndex в БД чтобы после перезапуска
                        // приложения продолжить с нужного места, а не с нуля
                        scope.launch { saveTaskToDatabase(withIndex) }
                        notifications[bookUrl]?.updateProgress(withIndex)
                    }

                    // Пропускаем уже скачанные — без delay, нет сетевого запроса
                    if (chapterBodyRepository.getCachedBody(chapterUrl) != null) {
                        android.util.Log.d(TAG, "skip cached: $chapterUrl")
                        updateTask(bookUrl) { it.copy(skippedCount = it.skippedCount + 1, consecutiveErrors = 0) }
                        i++
                        continue
                    }

                    // Загрузка с retry + exponential backoff и ожиданием сети
                    when (val fetchResult = fetchWithRetry(bookUrl, chapterUrl)) {
                        is FetchResult.Interrupted -> {
                            // Пользователь нажал паузу/отмену во время backoff.
                            // errorCount НЕ инкрементируем — это не ошибка загрузки.
                            android.util.Log.d(TAG, "fetch interrupted by user: $chapterUrl")
                            return@launch
                        }
                        is FetchResult.WaitingForNetwork -> {
                            // Сеть недоступна — ждём и пробуем снова.
                            // Задача остаётся активной, домен не освобождается.
                            android.util.Log.w(TAG, "network unavailable, waiting for connection: $chapterUrl")
                            continue
                        }
                        is FetchResult.Failed -> {
                            // Все 4 попытки исчерпаны — паузим задачу.
                            // Домен освобождается для следующей книги.
                            android.util.Log.w(TAG, "all retries failed, pausing: $bookUrl")
                            val paused = updateTask(bookUrl) {
                                it.copy(
                                    isPaused = true,
                                    isWaitingForNetwork = false,
                                    errorCount = it.errorCount + 1,
                                    consecutiveErrors = it.consecutiveErrors + 1,
                                )
                            }
                            if (paused != null) {
                                scope.launch { saveTaskToDatabase(paused) }
                                notifications[bookUrl]?.showPaused(paused)
                            }
                            return@launch
                        }
                        is FetchResult.Success -> {
                            // Проверяем паузу перед переводом — он может быть долгим
                            val taskBeforeTranslate = synchronized(tasksLock) {
                                _tasks.value.find { it.bookUrl == bookUrl }
                            }
                            if (taskBeforeTranslate == null || taskBeforeTranslate.isCancelled || taskBeforeTranslate.isPaused) {
                                android.util.Log.d(TAG, "interrupted before translate: $chapterUrl")
                                return@launch
                            }

                            val translationSuccess = translateAndSave(chapterUrl, fetchResult.body)
                            updateTask(bookUrl) {
                                it.copy(
                                    successCount = it.successCount + 1,
                                    consecutiveErrors = 0,
                                    translationErrorCount = if (translationSuccess) it.translationErrorCount
                                    else it.translationErrorCount + 1,
                                )
                            }
                            i++

                            // Читаем актуальный размер списка глав — он мог вырасти
                            // пока мы качали (пользователь добавил новые главы через enqueue)
                            val actualSize = synchronized(tasksLock) {
                                _tasks.value.find { it.bookUrl == bookUrl }?.chapterUrls?.size ?: i
                            }
                            if (i < actualSize) delay(delayMs)
                        }
                    }
                }

                // Все главы обработаны
                val completed = updateTask(bookUrl) { it.copy(isCompleted = true, isWaitingForNetwork = false) }
                if (completed != null) notifications[bookUrl]?.showCompleted(completed)
                withContext(Dispatchers.IO) { downloadTaskDao.delete(bookUrl) }

            } finally {
                synchronized(tasksLock) { activeJobs.remove(bookUrl) }
            }
        }

        synchronized(tasksLock) { activeJobs[bookUrl] = job }
        job.start()
        job.join()
    }

    /**
     * Результат попытки загрузки главы.
     *
     * [Success]           — глава загружена, body непустой.
     * [Interrupted]       — пауза или отмена пользователем во время backoff.
     *                       NOT ошибка: errorCount не инкрементируется.
     * [WaitingForNetwork] — все 4 попытки исчерпаны из-за сетевой/DNS ошибки.
     *                       Задача не паузится, retry каждые 30 секунд.
     * [Failed]            — все 4 попытки исчерпаны (не сетевая ошибка).
     *                       errorCount инкрементируется, задача паузится.
     */
    private sealed class FetchResult {
        data class Success(val body: String) : FetchResult()
        object Interrupted : FetchResult()
        object WaitingForNetwork : FetchResult()
        object Failed : FetchResult()
    }

    /**
     * Определяет, является ли ошибка сетевой (DNS/соединение).
     */
    private fun isNetworkError(error: my.noveldokusha.core.Response.Error): Boolean {
        val exception = error.exception
        if (exception is UnknownHostException) return true
        if (exception is ConnectException) return true
        val msg = error.message
        return msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("Network is unreachable", ignoreCase = true) ||
                msg.contains("hostname", ignoreCase = true)
    }

    /**
     * Загружает главу с 4 попытками и exponential backoff.
     *
     * Backoff: 0s → 60s → 300s → 600s между попытками.
     * Длинные паузы намеренны — защита от бана источника.
     *
     * Если на любой попытке обнаружена сетевая ошибка (DNS/соединение),
     * то сразу переходит в бесконечный цикл с retry каждые 30с без паузы задачи.
     *
     * Возвращает [FetchResult.Interrupted] если задача была паузнута/отменена
     * во время ожидания — это не считается ошибкой загрузки.
     */
    private suspend fun fetchWithRetry(
        bookUrl: String,
        chapterUrl: String,
    ): FetchResult {
        for (attempt in 0 until 4) {
            if (attempt > 0) {
                val backoffMs = when (attempt) {
                    1 -> 60_000L
                    2 -> 300_000L
                    else -> 600_000L
                }
                android.util.Log.d(TAG, "retry $attempt for $chapterUrl, wait ${backoffMs}ms")
                delay(backoffMs)

                // Проверяем статус после ожидания backoff.
                val taskAfterWait = synchronized(tasksLock) {
                    _tasks.value.find { it.bookUrl == bookUrl }
                }
                if (taskAfterWait == null || taskAfterWait.isCancelled || taskAfterWait.isPaused) {
                    android.util.Log.d(TAG, "interrupted during backoff: $chapterUrl")
                    return FetchResult.Interrupted
                }
            }

            val result = chapterBodyRepository.fetchBody(chapterUrl)
            when (result) {
                is my.noveldokusha.core.Response.Success -> {
                    val body = result.data
                    if (body.isNullOrBlank()) {
                        android.util.Log.w(TAG, "empty body attempt=$attempt: $chapterUrl")
                        continue
                    }
                    return FetchResult.Success(body)
                }
                is my.noveldokusha.core.Response.Error -> {
                    android.util.Log.w(TAG, "fetch error attempt=$attempt: $chapterUrl — ${result.message}")
                    // Если сетевая ошибка — сразу переходим в ожидание сети, без лишних попыток
                    if (isNetworkError(result)) {
                        android.util.Log.w(TAG, "network error, entering network wait: $chapterUrl")
                        return waitForNetworkThenRetry(bookUrl, chapterUrl)
                    }
                    // Иначе продолжаем попытки с backoff
                }
            }
        }

        // Все 4 попытки исчерпаны (не сетевые ошибки — пустой body, 500 и т.д.)
        android.util.Log.w(TAG, "all retries exhausted (non-network): $chapterUrl")
        return FetchResult.Failed
    }

    /**
     * Бесконечный цикл ожидания сети.
     * Пробует повторно каждые 30 секунд.
     * При успехе возвращает [FetchResult.Success].
     * При паузе/отмене пользователя возвращает [FetchResult.Interrupted].
     */
    private suspend fun waitForNetworkThenRetry(
        bookUrl: String,
        chapterUrl: String,
    ): FetchResult {
        // Переводим задачу в статус "ожидание сети"
        updateTask(bookUrl) { it.copy(isWaitingForNetwork = true) }
        notifications[bookUrl]?.showWaitingForNetwork(
            synchronized(tasksLock) { _tasks.value.find { it.bookUrl == bookUrl } }
                ?: return FetchResult.Interrupted
        )

        while (true) {
            delay(30_000L)

            // Проверяем статус — могла прийти пауза/отмена
            val task = synchronized(tasksLock) {
                _tasks.value.find { it.bookUrl == bookUrl }
            }
            if (task == null || task.isCancelled) {
                android.util.Log.d(TAG, "network wait cancelled: $chapterUrl")
                return FetchResult.Interrupted
            }
            if (task.isPaused) {
                android.util.Log.d(TAG, "network wait paused: $chapterUrl")
                return FetchResult.Interrupted
            }

            // Пробуем загрузить
            android.util.Log.d(TAG, "network retry for $chapterUrl")
            val result = chapterBodyRepository.fetchBody(chapterUrl)
            when (result) {
                is my.noveldokusha.core.Response.Success -> {
                    val body = result.data
                    if (body.isNullOrBlank()) {
                        android.util.Log.w(TAG, "network retry empty body: $chapterUrl")
                        continue
                    }
                    // Сеть восстановилась — сбрасываем флаг ожидания
                    updateTask(bookUrl) { it.copy(isWaitingForNetwork = false) }
                    notifications[bookUrl]?.showDownloading(
                        synchronized(tasksLock) { _tasks.value.find { it.bookUrl == bookUrl } }
                            ?: return FetchResult.Interrupted
                    )
                    return FetchResult.Success(body)
                }
                is my.noveldokusha.core.Response.Error -> {
                    if (isNetworkError(result)) {
                        android.util.Log.d(TAG, "network still down, retrying in 30s: $chapterUrl")
                        continue
                    }
                    // Не сетевая ошибка — паузим задачу
                    android.util.Log.w(TAG, "non-network error during network wait: $chapterUrl")
                    return FetchResult.Failed
                }
            }
        }
    }

    // ── Вспомогательные методы ───────────────────────────────────────────────

    /**
     * Атомарно обновляет задачу в [_tasks] и возвращает новое состояние.
     * Не сохраняет в БД — вызывающий сохраняет сам если нужно.
     */
    private fun updateTask(
        bookUrl: String,
        transform: (DownloadTaskState) -> DownloadTaskState,
    ): DownloadTaskState? {
        var result: DownloadTaskState? = null
        synchronized(tasksLock) {
            _tasks.value = _tasks.value.map {
                if (it.bookUrl == bookUrl) transform(it).also { t -> result = t } else it
            }
        }
        return result
    }

    /**
     * Создаёт [BookDownloadNotification] для книги и регистрирует в [notifications].
     * Если для этой книги уже есть уведомление — закрывает старое и создаёт новое.
     */
    private fun createNotification(task: DownloadTaskState): BookDownloadNotification {
        notifications.remove(task.bookUrl)?.close()
        val notif = BookDownloadNotification(
            bookUrl = task.bookUrl,
            bookTitle = task.bookTitle,
            context = context,
            notificationsCenter = notificationsCenter,
        )
        notifications[task.bookUrl] = notif
        return notif
    }

    // ── Перевод ──────────────────────────────────────────────────────────────

    private suspend fun translateAndSave(chapterUrl: String, body: String): Boolean {
        val sourceLang = appPreferences.GLOBAL_TRANSLATION_PREFERRED_SOURCE.value
        val targetLang = appPreferences.GLOBAL_TRANSLATION_PREFERRED_TARGET.value
        val isEnabled = appPreferences.GLOBAL_TRANSLATION_ENABLED.value
        if (!isEnabled || sourceLang.isBlank() || targetLang.isBlank()) {
            android.util.Log.d(TAG, "translation skipped (enabled=$isEnabled)")
            return true
        }

        return try {
            val paragraphs = body
                .replace(Regex("<(?!(imgEntry|/imgEntry))[^>]*>"), "")
                .replace("\r\n", "\n")
                .replace("\u00A0", " ")
                .replace(Regex("[ ]+"), " ")
                .let { clean ->
                    var parts = clean.split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
                    if (parts.size <= 1 && clean.contains("\n"))
                        parts = clean.split("\n").filter { it.isNotBlank() }
                    parts.map { it.trim() }.filter { it.isNotBlank() }
                }

            if (paragraphs.isEmpty()) return true

            val translations = translationManager.translateBatch(paragraphs, sourceLang, targetLang)
            val entities = paragraphs.mapIndexed { index, original ->
                my.noveldokusha.feature.local_database.tables.ChapterTranslation(
                    chapterUrl = chapterUrl,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    paragraphIndex = index,
                    originalText = original,
                    translatedText = translations[original] ?: original
                )
            }.toMutableList()

            try {
                val chapter = appRepository.bookChapters.get(chapterUrl)
                if (chapter != null && chapter.title.isNotBlank()) {
                    val titleTranslated = translationManager.translateTitle(
                        chapter.title, sourceLang, targetLang
                    )
                    if (titleTranslated != null) {
                        entities.add(
                            my.noveldokusha.feature.local_database.tables.ChapterTranslation(
                                chapterUrl = chapterUrl,
                                sourceLang = sourceLang,
                                targetLang = targetLang,
                                paragraphIndex = -1,
                                originalText = chapter.title,
                                translatedText = titleTranslated
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "title translation failed", e)
            }

            chapterTranslationDao.insertReplace(entities)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "translateAndSave failed", e)
            false
        }
    }
}