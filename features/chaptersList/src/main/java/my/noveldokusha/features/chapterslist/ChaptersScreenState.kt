package my.noveldokusha.features.chapterslist

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import my.noveldokusha.core.appPreferences.TernaryState
import my.noveldokusha.data.DownloadTaskState
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.features.reader.video.DownloadState

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
    val chapterSizes: MutableState<Map<String, ChapterSize>>,
    val downloadTask: MutableState<DownloadTaskState?>,
    // Статусы media3-загрузок видео-эпизодов: chapterUrl → бейдж (Task 14).
    val videoDownloadStates: MutableState<Map<String, DownloadState>>,
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

/** Состояние диалога экспорта книги в аудиокнигу (WAV/MP4 + JSON). */
sealed interface AudiobookExportDialogState {
    data object Hidden : AudiobookExportDialogState

    data class Configure(
        val bookUrl: String,
        val bookTitle: String,
        val totalChapters: Int,
        val downloadedChapters: Int,
        val availableTranslations: List<LangPair>,
        val directoryName: String?,
        /** Растёт при каждом запросе папки — UI открывает SAF-пикер по изменению. */
        val directoryRequestId: Int = 0,
        /** Начальные настройки голоса для экспорта (из AppPreferences). */
        val useReaderTts: Boolean = true,
        val ttsVoiceId: String = "",
        val ttsSpeed: Float = 1f,
        val ttsPitch: Float = 1f,
        /** Текущие настройки читалки — для режима «как в читалке» и списка голосов. */
        val readerEnginePackage: String = "",
        val readerVoiceId: String = "",
        val readerSpeed: Float = 1f,
        val readerPitch: Float = 1f,
    ) : AudiobookExportDialogState
}