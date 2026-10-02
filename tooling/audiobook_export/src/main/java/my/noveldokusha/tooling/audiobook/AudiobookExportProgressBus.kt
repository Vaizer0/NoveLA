package my.noveldokusha.tooling.audiobook

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Живой прогресс аудиоэкспорта для UI.
 *
 * Воркер и экран живут в одном процессе, поэтому вместо опроса WorkManager
 * (чья доставка прогресса не мгновенна) прогресс публикуется сюда напрямую.
 * Так экран получает проценты сразу, без задержек и «зависания на 0%».
 */
data class AudiobookExportLiveProgress(
    val percent: Int,
    val format: AudiobookFormat,
    val stage: AudiobookStage,
)

object AudiobookExportProgressBus {

    private val _progress = MutableStateFlow<AudiobookExportLiveProgress?>(null)

    val progress: StateFlow<AudiobookExportLiveProgress?> = _progress.asStateFlow()

    fun publish(percent: Int, format: AudiobookFormat, stage: AudiobookStage) {
        _progress.value = AudiobookExportLiveProgress(
            percent = percent.coerceIn(0, 100),
            format = format,
            stage = stage,
        )
    }

    fun clear() {
        _progress.value = null
    }
}
