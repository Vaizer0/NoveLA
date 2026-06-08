package my.noveldokusha.interactor

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.DownloaderRepository
import my.noveldokusha.core.isLocalUri
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.feature.local_database.tables.Chapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryUpdatesInteractions @Inject constructor(
    private val appRepository: AppRepository,
    private val downloaderRepository: DownloaderRepository,
) {
    data class NewUpdate(
        val newChapters: List<Chapter>,
        val book: Book
    )

    data class CountingUpdating(
        val updated: Int,
        val total: Int
    )

    suspend fun updateLibraryBooks(
        completedOnes: Boolean,
        countingUpdating: MutableStateFlow<CountingUpdating?>,
        currentUpdating: MutableStateFlow<Set<Book>>,
        newUpdates: MutableStateFlow<Set<NewUpdate>>,
        failedUpdates: MutableStateFlow<Set<Book>>,
    ): Unit = withContext(Dispatchers.Default) {
        appRepository.libraryBooks.getAllInLibrary()
            .filter { it.completed == completedOnes }
            .filter { !it.url.isLocalUri }
            .also { list ->
                countingUpdating.update {
                    CountingUpdating(
                        updated = 0,
                        total = list.size
                    )
                }
            }
            .groupBy { it.url.toHttpUrlOrNull()?.host }
            .map { (_, books) ->
                async {
                    for (book in books) {
                        updateBook(
                            book = book,
                            currentUpdating = currentUpdating,
                            newUpdates = newUpdates,
                            failedUpdates = failedUpdates,
                            countingUpdating = countingUpdating
                        )
                    }
                }
            }
            .awaitAll()
    }

    private suspend fun updateBook(
        book: Book,
        countingUpdating: MutableStateFlow<CountingUpdating?>,
        currentUpdating: MutableStateFlow<Set<Book>>,
        newUpdates: MutableStateFlow<Set<NewUpdate>>,
        failedUpdates: MutableStateFlow<Set<Book>>,
    ): Unit = withContext(Dispatchers.Default) {
        currentUpdating.update { it + book }

        // Быстрая проверка хэша — только для книг без chaptersLastPage (старый путь).
        // parsePage-плагины не используют хэш.
        if (book.chaptersLastPage == null && book.chaptersListHash != null) {
            val hashUnchanged = downloaderRepository.bookChaptersListHash(bookUrl = book.url).let { result ->
                if (result is my.noveldokusha.core.Response.Success) {
                    result.data == book.chaptersListHash
                } else false
            }
            if (hashUnchanged) {
                currentUpdating.update { it - book }
                countingUpdating.update { it?.copy(updated = it.updated + 1) }
                return@withContext
            }
        }

        // Загружаем текущий список URL глав один раз — используется во всех стратегиях.
        val oldChaptersList = async(Dispatchers.IO) {
            appRepository.bookChapters.chapters(book.url).map { it.url }.toSet()
        }

        // Обновляем метаданные книги если нужно
        if (book.title == "Unknown Novel" || book.title.isBlank()) {
            downloaderRepository.bookTitle(bookUrl = book.url).onSuccess { newTitle ->
                if (!newTitle.isNullOrBlank() && newTitle != "Unknown Novel") {
                    appRepository.libraryBooks.updateTitle(book.url, newTitle)
                }
            }
        }
        if (book.coverImageUrl.isBlank()) {
            downloaderRepository.bookCoverImageUrl(bookUrl = book.url).onSuccess { newCoverUrl ->
                if (!newCoverUrl.isNullOrBlank()) {
                    appRepository.libraryBooks.updateCover(book.url, newCoverUrl)
                }
            }
        }
        if (book.description.isBlank()) {
            downloaderRepository.bookDescription(bookUrl = book.url).onSuccess { newDescription ->
                if (!newDescription.isNullOrBlank()) {
                    appRepository.libraryBooks.updateDescription(book.url, newDescription)
                }
            }
        }

        // ── Выбор стратегии обновления ────────────────────────────────────────
        if (book.chaptersLastPage != null) {
            // parsePage-режим: книга уже была спарсена через parsePage.
            // Перечитываем последнюю известную страницу + догружаем новые.
            updateBookWithParsePage(book, oldChaptersList, newUpdates, failedUpdates)
        } else {
            // Проверяем, поддерживает ли плагин parsePage (первый раз для этой книги).
            val firstPageResult = downloaderRepository.bookChaptersPage(book.url, page = 1)
            if (firstPageResult != null) {
                // Плагин поддерживает parsePage — полный первоначальный парс всех страниц
                updateBookFirstTimeParsePage(book, firstPageResult, oldChaptersList, newUpdates, failedUpdates)
            } else {
                // Плагин не поддерживает parsePage — старый путь через getChapterList
                updateBookLegacy(book, oldChaptersList, newUpdates, failedUpdates)
            }
        }

        currentUpdating.update { it - book }
        countingUpdating.update { it?.copy(updated = it.updated + 1) }
    }

    /**
     * Первый парс книги через parsePage: загружаем все страницы 1..totalPages,
     * сохраняем chaptersLastPage = totalPages.
     */
    private suspend fun updateBookFirstTimeParsePage(
        book: Book,
        firstPageResult: my.noveldokusha.core.Response<my.noveldokusha.scraper.SourceInterface.Catalog.PagedChapterResult>,
        oldChaptersList: Deferred<Set<String>>,
        newUpdates: MutableStateFlow<Set<NewUpdate>>,
        failedUpdates: MutableStateFlow<Set<Book>>,
    ) {
        val firstPage = (firstPageResult as? my.noveldokusha.core.Response.Success)?.data
            ?: run { failedUpdates.update { it + book }; return }

        val totalPages = firstPage.totalPages
        val allChapters = mutableListOf<Chapter>()

        // Добавляем главы первой страницы
        firstPage.chapters.forEachIndexed { idx, ch ->
            allChapters.add(Chapter(title = ch.title, url = ch.url, bookUrl = book.url, position = idx))
        }

        // Загружаем оставшиеся страницы 2..totalPages
        for (page in 2..totalPages) {
            val pageData = (downloaderRepository.bookChaptersPage(book.url, page) as? my.noveldokusha.core.Response.Success)?.data
                ?: break
            // Захватываем offset ДО начала итерации — allChapters.size меняется внутри forEachIndexed
            val offset = allChapters.size
            pageData.chapters.forEachIndexed { idx, ch ->
                allChapters.add(
                    Chapter(title = ch.title, url = ch.url, bookUrl = book.url, position = offset + idx)
                )
            }
        }

        mergeAndNotify(book, allChapters, oldChaptersList, newUpdates)
        appRepository.libraryBooks.updateChaptersLastPage(book.url, totalPages)
    }

    /**
     * Инкрементальное обновление для parsePage-книги:
     * перечитываем последнюю известную страницу и берём из неё только НОВЫЕ главы
     * (не меняем позиции уже сохранённых), затем догружаем страницы lastPage+1..newTotalPages.
     *
     * Важно: в merge() позиция перезаписывается для любого переданного URL,
     * поэтому существующие главы нельзя включать в список с пересчитанными позициями.
     */
    private suspend fun updateBookWithParsePage(
        book: Book,
        oldChaptersList: Deferred<Set<String>>,
        newUpdates: MutableStateFlow<Set<NewUpdate>>,
        failedUpdates: MutableStateFlow<Set<Book>>,
    ) {
        val lastKnownPage = book.chaptersLastPage ?: return

        val lastPageResult = downloaderRepository.bookChaptersPage(book.url, lastKnownPage)
        val lastPageData = (lastPageResult as? my.noveldokusha.core.Response.Success)?.data
            ?: run { failedUpdates.update { it + book }; return }

        val newTotalPages = lastPageData.totalPages

        // Единственный источник истины о существующих главах.
        // Переиспользуем уже запущенный deferred — никакого дополнительного DB-запроса.
        val existingUrls = oldChaptersList.await()
        var positionOffset = existingUrls.size

        val chaptersToAdd = mutableListOf<Chapter>()

        // Из последней страницы берём ТОЛЬКО новые главы.
        // Существующие не передаём в merge() — иначе их позиции будут перезаписаны неверными значениями.
        lastPageData.chapters
            .filter { it.url !in existingUrls }
            .forEachIndexed { idx, ch ->
                chaptersToAdd.add(
                    Chapter(title = ch.title, url = ch.url, bookUrl = book.url, position = positionOffset + idx)
                )
            }
        positionOffset += chaptersToAdd.size

        // Если появились новые страницы — загружаем их
        for (page in (lastKnownPage + 1)..newTotalPages) {
            val pageData = (downloaderRepository.bookChaptersPage(book.url, page) as? my.noveldokusha.core.Response.Success)?.data
                ?: break
            val offset = positionOffset
            pageData.chapters.forEachIndexed { idx, ch ->
                chaptersToAdd.add(
                    Chapter(title = ch.title, url = ch.url, bookUrl = book.url, position = offset + idx)
                )
            }
            positionOffset += pageData.chapters.size
        }

        mergeAndNotify(book, chaptersToAdd, oldChaptersList, newUpdates)

        if (newTotalPages != lastKnownPage) {
            appRepository.libraryBooks.updateChaptersLastPage(book.url, newTotalPages)
        }
    }

    /**
     * Старый путь — полный getChapterList без пагинации.
     */
    private suspend fun updateBookLegacy(
        book: Book,
        oldChaptersList: Deferred<Set<String>>,
        newUpdates: MutableStateFlow<Set<NewUpdate>>,
        failedUpdates: MutableStateFlow<Set<Book>>,
    ) {
        downloaderRepository.bookChaptersList(bookUrl = book.url).onSuccess { chapters ->
            mergeAndNotify(book, chapters, oldChaptersList, newUpdates)
            // Обновляем хэш для быстрого скипа в следующий раз
            downloaderRepository.bookChaptersListHash(bookUrl = book.url).onSuccess { hash ->
                if (hash != null) {
                    appRepository.libraryBooks.updateChaptersListHash(book.url, hash)
                }
            }
        }.onError {
            failedUpdates.update { it + book }
        }
    }

    private suspend fun mergeAndNotify(
        book: Book,
        chapters: List<Chapter>,
        oldChaptersList: Deferred<Set<String>>,
        newUpdates: MutableStateFlow<Set<NewUpdate>>,
    ) {
        oldChaptersList.join()
        appRepository.bookChapters.merge(chapters, book.url)
        val newChapters = chapters.filter { it.url !in oldChaptersList.await() }
        if (newChapters.isNotEmpty()) {
            appRepository.libraryBooks.updateLastUpdateEpochTimeMilli(bookUrl = book.url)
            newUpdates.update { it + NewUpdate(book = book, newChapters = newChapters) }
        }
    }
}
