package my.noveldokusha.features.chapterslist

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import my.noveldokusha.core.appPreferences.TernaryState
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.VideoExportJobState
import my.noveldokusha.data.DownloadTaskState
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.tables.Book

internal data class ChaptersScreenState(
    val book: State<BookState>,
    val error: MutableState<String>,
    val selectedChaptersUrl: SnapshotStateMap<String, Unit>,
    val chapters: SnapshotStateList<ChapterWithContext>,
    val isRefreshing: MutableState<Boolean>,
    val sourceCatalogNameStrRes: State<Int?>,
    val settingChapterSort: MutableState<TernaryState>,
    val isLocalSource: State<Boolean>,
    val isRefreshable: State<Boolean>,
    val genres: MutableState<List<String>>,
    val rating: MutableState<String>,
    val status: MutableState<String>,
    val lastUpdateDate: MutableState<String>,
    val translatedChapterTitles: MutableState<Map<String, String>>,
    // chapterUrl → есть ли закэшированный перевод ТЕЛА главы для активной пары.
    // Управляет доступностью кнопки «Translated» аудиозагрузки: она активна только
    // когда воркеру реально есть что синтезировать (исходник тот же, что у воркера).
    val translatedAudioAvailable: MutableState<Map<String, Boolean>>,
    val chapterSizes: MutableState<Map<String, ChapterSize>>,
    val downloadTask: MutableState<DownloadTaskState?>,
    // Аудиозагрузка глав (TTS): AudioJobKey → состояние для иконки у главы.
    // Ключ составной (chapterUrl + source): Original и Translated одной главы —
    // независимые задачи, ни одна не перетирает другую.
    val audioJobs: SnapshotStateMap<AudioJobKey, TtsAudioJobState>,
    // AudioJobKey → существует ли готовый аудиофайл на диске (SUCCESS + SAF-документ жив).
    val audioFilesExist: SnapshotStateMap<AudioJobKey, Boolean>,
    // Нужно выбрать папку аудио (SAF): UI открывает пикер.
    val audioNeedDirectory: MutableState<Boolean>,
    // Видео-экспорт глав: chapterUrl → состояние для иконки у главы (одна задача на главу).
    val videoJobs: SnapshotStateMap<String, VideoExportJobState>,
    // Нужно выбрать папку видео (SAF): UI открывает пикер.
    val videoNeedDirectory: MutableState<Boolean>,
) {

    val isInSelectionMode = derivedStateOf { selectedChaptersUrl.size != 0 }

    data class BookState(
        val title: String,
        val url: String,
        val completed: Boolean = false,
        val lastReadChapter: String? = null,
        val inLibrary: Boolean = false,
        val coverImageUrl: String? = null,
        val description: String = "",
        val category: String = "",
    ) {
        constructor(book: Book) : this(
            title = book.title,
            url = book.url,
            completed = book.completed,
            lastReadChapter = book.lastReadChapter,
            inLibrary = book.inLibrary,
            coverImageUrl = book.coverImageUrl,
            description = book.description,
            category = book.category,
        )
    }
}

/** Пара языков перевода, доступная для экспорта, с числом переведённых глав. */
data class LangPair(
    val sourceLang: String,
    val targetLang: String,
    val translatedChapters: Int,
)

/**
 * Детерминированный ключ задачи аудиозагрузки главы в UI-состоянии: глава +
 * источник. Original и Translated одной главы — РАЗНЫЕ ключи (независимые
 * задачи), поэтому прогресс/завершение одного источника не маскирует другой.
 */
internal data class AudioJobKey(
    val chapterUrl: String,
    val source: TtsAudioSource,
)

/** Состояние диалога экспорта книги в EPUB. */
sealed interface ExportDialogState {
    data object Hidden : ExportDialogState

    /** Выбор контента для экспорта: оригинал или один из переводов. */
    data class ContentChoice(
        val bookUrl: String,
        val bookTitle: String,
        val totalChapters: Int,
        val downloadedChapters: Int,
        val availableTranslations: List<LangPair>,
        val exportDirectoryName: String?,
    ) : ExportDialogState

    /** Папка экспорта не выбрана — UI открывает SAF-пикер. */
    data object NeedDirectory : ExportDialogState
}