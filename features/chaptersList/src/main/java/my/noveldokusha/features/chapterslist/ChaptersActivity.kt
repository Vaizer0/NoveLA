package my.noveldokusha.features.chapterslist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.BaseActivity
import my.noveldokusha.coreui.composableActions.SetSystemBarTransparent
import my.noveldokusha.coreui.composableActions.onDoAskForImage
import my.noveldokusha.coreui.theme.Theme
import my.noveldokusha.core.utils.Extra_String
import my.noveldokusha.navigation.NavigationRoutes
import my.noveldokusha.feature.local_database.BookMetadata
import my.noveldokusha.feature.local_database.tables.Chapter
import javax.inject.Inject

/**
 * Цель кнопки «Продолжить»: последняя глава, только пока она НЕ дочитана
 * (read == false) — иначе её повторное открытие навсегда осталось бы у конца
 * (saveVideoLastReadState пишет lastReadChapter при каждом сохранении);
 * далее первый непросмотренный эпизод, иначе первая глава по позиции.
 * Устаревший lastReadUrl (главы нет в списке, например после ре-синка)
 * считается отсутствующим. Порядок — по position независимо от порядка списка.
 */
internal fun pickContinueChapter(lastReadUrl: String?, chapters: List<Chapter>): Chapter? {
    val sorted = chapters.sortedBy { it.position }
    val lastRead = sorted.firstOrNull { it.url == lastReadUrl }
    if (lastRead != null && !lastRead.read) return lastRead
    return sorted.firstOrNull { !it.read } ?: sorted.firstOrNull()
}

@AndroidEntryPoint
class ChaptersActivity : BaseActivity() {
    class IntentData : Intent, ChapterStateBundle {
        override var rawBookUrl by Extra_String()
        override var bookTitle by Extra_String()

        constructor(intent: Intent) : super(intent)
        constructor(ctx: Context, bookMetadata: BookMetadata) : super(
            ctx,
            ChaptersActivity::class.java
        ) {
            this.rawBookUrl = bookMetadata.url
            this.bookTitle = bookMetadata.title
        }
    }

    @Inject
    internal lateinit var navigationRoutes: NavigationRoutes

    private val viewModel by viewModels<ChaptersViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }
        addOnBackPressedCallback(backPressedCallback)

        setContent {
            Theme(themeProvider = themeProvider) {
                SetSystemBarTransparent()
                ChaptersScreen(
                    state = viewModel.state,
                    onLibraryToggle = viewModel::toggleBookmark,
                    onSearchBookInDatabase = ::searchBookInDatabase,
                    onResumeReading = ::onOpenLastActiveChapter,
                    onPressBack = ::finish,
                    onSelectedDeleteDownloads = viewModel::deleteDownloadsSelected,
                    onSelectedDownload = viewModel::downloadSelected,
                    onSelectedTranslate = viewModel::translateSelected,
                    onSelectedDeleteTranslations = viewModel::deleteSelectedTranslations,
                    onSelectedSetRead = viewModel::setAsReadSelected,
                    onSelectedSetUnread = viewModel::setAsUnreadSelected,
                    onSelectedSetReadUpToChapterRead = viewModel::setAsReadUpToSelected,
                    onSelectedSetReadUpToChapterUnread = viewModel::setAsReadUpToUnSelected,
                    onSelectedInvertSelection = viewModel::invertSelection,
                    onSelectAllChapters = viewModel::selectAll,
                    onCloseSelectionBar = viewModel::unselectAll,
                    onChapterClick = { openBookAtChapter(chapterUrl = it.chapter.url) },
                    onChapterLongClick = viewModel::onChapterLongClick,
                    onSelectionModeChapterClick = viewModel::onSelectionModeChapterClick,
                    onSelectionModeChapterLongClick = viewModel::onSelectionModeChapterLongClick,
                    onChapterDownload = viewModel::onChapterDownload,
                    onStopDownload = viewModel::onStopVideoDownload,
                    onPullRefresh = viewModel::onPullRefresh,
                    onCoverLongClick = { searchBookInDatabase(input = viewModel.bookTitle) },
                    onChangeCover = onDoAskForImage { viewModel.saveImageAsCover(it) },
                    onOpenInBrowser = { navigationRoutes.webView(this, url = it, bookUrl = it).let(::startActivity) },
                    onGlobalSearchClick = { navigationRoutes.globalSearch(this, text = it).let(::startActivity) },
                    onDownloadNext100Chapters = viewModel::downloadNext100Chapters,
                    onDownloadAllChapters = viewModel::downloadAllChapters,
                    onExport = viewModel::onExportClicked,
                    onExportContentChosen = viewModel::onExportContentChosen,
                    onExportDirectorySaved = viewModel::onExportDirectorySaved,
                    onExportDialogDismiss = viewModel::onExportDialogDismiss,
                    exportDialogState = viewModel.exportDialogState.value,
                    exportMessage = viewModel.exportMessage.value,
                    onExportMessageShown = { viewModel.exportMessage.value = null },
                    onAudiobookExport = viewModel::onAudiobookExportClicked,
                    onAudiobookExportConfirmed = viewModel::onAudiobookExportConfirmed,
                    onAudiobookDirectorySaved = viewModel::onAudiobookDirectorySaved,
                    onAudiobookDialogDismiss = viewModel::onAudiobookDialogDismiss,
                    audiobookDialogState = viewModel.audiobookDialogState.value,
                    audiobookMessage = viewModel.audiobookMessage.value,
                    onAudiobookMessageShown = { viewModel.audiobookMessage.value = null },
                    audiobookProgress = viewModel.audiobookProgress.value,
                    onAudiobookCancel = viewModel::onAudiobookExportCancel,
                    onAudiobookKeepInBackground = viewModel::onAudiobookKeepInBackground,
                    audiobookProgressIsVideo = viewModel.audiobookProgressIsVideo.value,
                    audiobookProgressDismissed = viewModel.audiobookProgressDismissed.value,
                    onAudiobookTtsChanged = viewModel::onAudiobookTtsChanged,
                    onAudiobookTtsSaved = viewModel::onAudiobookTtsSaved,
                    onMigrateBook = {
                        navigationRoutes.novelMigration(
                            this,
                            bookUrl = viewModel.state.book.value.url,
                            bookTitle = viewModel.state.book.value.title
                        ).let(::startActivity)
                    },
                    onDeleteTranslations = viewModel::deleteTranslationsForBook,
                    onFixBook = viewModel::fixBook,
                    categories = viewModel::getCategories,
                    onUpdateCategory = viewModel::updateBookCategory,
                    translatedTitle = viewModel.translatedTitle.value,
                    translatedDescription = viewModel.translatedDescription.value,
                    isTranslatingInfo = viewModel.isTranslatingInfo.value,
                    showTranslateButton = viewModel.showTranslateButton.value,
                    onTranslateBookInfo = viewModel::translateBookInfo,
                    onClearBookInfoTranslation = viewModel::clearBookInfoTranslation,
                    scraper = viewModel.scraper,
                )
            }
        }
    }

    private fun onOpenLastActiveChapter() {
        lifecycleScope.launch {
            // Цель: последняя глава, пока не дочитана (resume позиции), иначе
            // первый непросмотренный эпизод (для видео — основной сценарий),
            // иначе первая глава; если цели нет — Bug1c: выходим молча.
            val target = pickContinueChapter(
                lastReadUrl = viewModel.getLastReadChapter(),
                chapters = viewModel.state.chapters.map { it.chapter },
            )?.url ?: return@launch

            // Дальше существующий путь: ReaderActivity → resolveGateType →
            // для video-книг VideoPlayerActivity (позиция восстанавливается Task 9).
            openBookAtChapter(chapterUrl = target)
        }
    }

    private fun searchBookInDatabase(
        input: String = viewModel.state.book.value.title
    ) = navigationRoutes.databaseSearch(
        this,
        input = input
    ).let(::startActivity)

    private fun openBookAtChapter(chapterUrl: String) = navigationRoutes.reader(
        this, bookUrl = viewModel.state.book.value.url, chapterUrl = chapterUrl
    ).let(::startActivity)
}