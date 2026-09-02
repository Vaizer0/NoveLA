package my.noveldokusha.features.chapterslist

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import my.noveldokusha.core.appPreferences.TernaryState
import my.noveldokusha.core.appPreferences.TtsAudioJobState
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TtsVideoJobState
import my.noveldokusha.data.DownloadTaskState
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.tables.Book

internal data class ChaptersScreenState(
    val book: State<BookState>, val error: MutableState<String>, val selectedChaptersUrl: SnapshotStateMap<String, Unit>, val chapters: SnapshotStateList<ChapterWithContext>,
    val isRefreshing: MutableState<Boolean>, val sourceCatalogNameStrRes: State<Int?>, val settingChapterSort: MutableState<TernaryState>, val isLocalSource: State<Boolean>,
    val isRefreshable: State<Boolean>, val genres: MutableState<List<String>>, val rating: MutableState<String>, val status: MutableState<String>, val lastUpdateDate: MutableState<String>,
    val translatedChapterTitles: MutableState<Map<String, String>>, val translatedAudioAvailable: MutableState<Map<String, Boolean>>, val chapterSizes: MutableState<Map<String, ChapterSize>>,
    val downloadTask: MutableState<DownloadTaskState?>, val audioJobs: SnapshotStateMap<AudioJobKey, TtsAudioJobState>, val audioFilesExist: SnapshotStateMap<AudioJobKey, Boolean>,
    val audioNeedDirectory: MutableState<Boolean>, val videoJobs: SnapshotStateMap<VideoJobKey, TtsVideoJobState> = mutableStateMapOf(),
) {
    val isInSelectionMode = derivedStateOf { selectedChaptersUrl.size != 0 }
    data class BookState(
        val title: String, val url: String, val completed: Boolean = false, val lastReadChapter: String? = null, val inLibrary: Boolean = false,
        val coverImageUrl: String? = null, val description: String = "", val category: String = "",
    ) {
        constructor(book: Book) : this(book.title, book.url, book.completed, book.lastReadChapter, book.inLibrary, book.coverImageUrl, book.description, book.category)
    }
}

internal data class AudioJobKey(val chapterUrl: String, val source: TtsAudioSource)
internal data class VideoJobKey(val chapterUrl: String, val source: TtsAudioSource)

data class LangPair(val sourceLang: String, val targetLang: String, val translatedChapters: Int)

sealed interface ExportDialogState {
    data object Hidden : ExportDialogState
    data class ContentChoice(val bookUrl: String, val bookTitle: String, val totalChapters: Int, val downloadedChapters: Int, val availableTranslations: List<LangPair>, val exportDirectoryName: String?) : ExportDialogState
    data object NeedDirectory : ExportDialogState
}
