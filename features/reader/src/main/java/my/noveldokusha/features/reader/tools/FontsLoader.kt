package my.noveldokusha.features.reader.tools

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontFamily
import my.noveldokusha.reader_visuals.ReaderFontResolver

/**
 * Тонкая обёртка над общим [ReaderFontResolver] (tooling/reader_visuals),
 * сохраняющая реактивный список шрифтов для UI и тот же публичный API,
 * что у старого локального резолвера. Вся логика (резолв/systemFonts/импорт/
 * удаление/кэши) переехала в общий модуль и переиспользуется видеорендером.
 */
internal class FontsLoader(
    private val context: Context
) {
    init {
        if (ReaderFontResolver.init(context)) refreshAvailableFonts()
    }

    fun getTypeFaceNORMAL(name: String): Typeface = ReaderFontResolver.getTypeFaceNORMAL(name)

    fun getTypeFaceBOLD(name: String): Typeface = ReaderFontResolver.getTypeFaceBOLD(name)

    fun getFontFamily(name: String): FontFamily = FontFamily(getTypeFaceNORMAL(name))

    /** Импортирует шрифт из content-URI; при успехе обновляет список. */
    suspend fun importFont(uri: Uri): Result<Unit> {
        val result = ReaderFontResolver.importFont(uri)
        if (result.isSuccess) refreshAvailableFonts()
        return result
    }

    /** Удаляет импортированный шрифт; при успехе обновляет список. */
    fun deleteFont(value: String): Boolean {
        val deleted = ReaderFontResolver.deleteFont(value)
        if (deleted) refreshAvailableFonts()
        return deleted
    }

    companion object {
        /** Единый реактивный источник списка шрифтов для дропдауна и читалки. */
        val availableFonts: MutableState<List<String>> = mutableStateOf(ReaderFontResolver.systemFonts)

        val systemFonts: List<String> = ReaderFontResolver.systemFonts

        /** Инвалидирует кэши и пересобирает список (вызывается после импорта/удаления). */
        fun refreshAvailableFonts() {
            ReaderFontResolver.resetCaches()
            availableFonts.value = ReaderFontResolver.systemFonts + ReaderFontResolver.scanImported()
        }

        fun isImported(value: String): Boolean = ReaderFontResolver.isImported(value)

        fun parsePrefValue(value: String): String? = ReaderFontResolver.parsePrefValue(value)

        fun sanitizeFileName(raw: String): String = ReaderFontResolver.sanitizeFileName(raw)

        fun displayName(value: String, systemFonts: Set<String>): String =
            ReaderFontResolver.displayName(value, systemFonts)

        fun isValidFontExtension(name: String): Boolean =
            ReaderFontResolver.isValidFontExtension(name)
    }
}