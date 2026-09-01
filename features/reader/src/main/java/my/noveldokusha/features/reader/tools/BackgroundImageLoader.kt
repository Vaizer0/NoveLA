package my.noveldokusha.features.reader.tools

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import my.noveldokusha.reader_visuals.ReaderBackgroundResolver
import java.io.File

/**
 * Тонкая обёртка над общим [ReaderBackgroundResolver] (tooling/reader_visuals),
 * сохраняющая реактивный список фоновых картинок для UI и тот же публичный API,
 * что у старого локального резолвера. Логика (импорт/удаление/sanitize) общая с
 * видеорендером.
 */
internal class BackgroundImageLoader(
    private val context: Context
) {
    init {
        if (ReaderBackgroundResolver.init(context)) refreshAvailableBackgrounds()
    }

    /** Импортирует фоновую картинку из content-URI; при успехе обновляет список. */
    suspend fun importBackgroundImage(uri: Uri): Result<Unit> {
        val result = ReaderBackgroundResolver.importBackgroundImage(uri)
        if (result.isSuccess) refreshAvailableBackgrounds()
        return result
    }

    /** Удаляет импортированную фоновую картинку; при успехе обновляет список. */
    fun deleteBackground(value: String): Boolean {
        val deleted = ReaderBackgroundResolver.deleteBackground(value)
        if (deleted) refreshAvailableBackgrounds()
        return deleted
    }

    companion object {
        /** Единый реактивный список импортированных фоновых картинок для UI и рендера. */
        val availableBackgrounds: MutableState<List<File>> = mutableStateOf(emptyList())

        /** Пересобирает список (вызывается после импорта/удаления). */
        fun refreshAvailableBackgrounds() {
            availableBackgrounds.value = ReaderBackgroundResolver.scanImported()
        }

        fun isImported(value: String): Boolean = ReaderBackgroundResolver.isImported(value)

        fun parsePrefValue(value: String): String? = ReaderBackgroundResolver.parsePrefValue(value)

        fun sanitizeFileName(raw: String): String = ReaderBackgroundResolver.sanitizeFileName(raw)

        fun isValidImageExtension(name: String): Boolean =
            ReaderBackgroundResolver.isValidImageExtension(name)

        fun resolveFile(name: String): File? = ReaderBackgroundResolver.resolveFile(name)
    }
}