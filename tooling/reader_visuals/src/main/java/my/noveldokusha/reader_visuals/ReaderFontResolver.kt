package my.noveldokusha.reader_visuals

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.coreui.R
import java.io.File

/**
 * Shared reader font resolver (system fonts, bundled fonts, imported files).
 *
 * Mirrors the behaviour that used to live in features/reader `FontsLoader` so that
 * both the live reader and the video exporter resolve fonts identically:
 *   - system names are resolved via Typeface.create(name, style)
 *   - bundled fonts (inter/lora/merriweather/source-sans-pro) via :coreui R.font
 *   - `font_file:` pref values via filesDir/fonts/<file>
 *
 * Statics are intentionally shared across all consumer instances (same process):
 * ReaderActivity and StyleSettingDialog must see the same caches and the same
 * imported-font list.
 */
object ReaderFontResolver {

    private lateinit var appContext: Context

    val systemFonts = listOf(
        "inter",
        "lora",
        "merriweather",
        "source-sans-pro",
        "casual",
        "cursive",
        "monospace",
        "sans-serif",
        "sans-serif-black",
        "sans-serif-condensed",
        "sans-serif-condensed-light",
        "sans-serif-light",
        "sans-serif-medium",
        "sans-serif-smallcaps",
        "sans-serif-thin",
        "serif",
        "serif-monospace"
    )

    private val typeFaceNORMALCache = mutableMapOf<String, Typeface>()
    private val typeFaceBOLDCache = mutableMapOf<String, Typeface>()

    /** Мягкий лимит ~100MB на импортируемый файл шрифта. */
    private const val MAX_IMPORT_BYTES = 100 * 1024 * 1024

    /**
     * Инициализирует статический appContext, если ещё не сделан.
     * @return true, если инициализация произошла впервые (вызов инициировал скан).
     */
    fun init(context: Context): Boolean {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            return true
        }
        return false
    }

    /** Инвалидирует кэши для всех потребителей. */
    fun resetCaches() {
        typeFaceNORMALCache.clear()
        typeFaceBOLDCache.clear()
    }

    /** true, если значение — импортированный файл шрифта. */
    fun isImported(value: String): Boolean = value.startsWith("font_file:")

    /** Имя файла на диске для импортированного значения, иначе null. */
    fun parsePrefValue(value: String): String? =
        if (isImported(value)) value.removePrefix("font_file:") else null

    /** Оставляет только [A-Za-z0-9._-], схлопывает ".."; пусто -> fallback-имя. */
    fun sanitizeFileName(raw: String): String {
        val safe = raw.filter {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
                it == '.' || it == '_' || it == '-'
        }.replace(Regex("\\.{2,}"), ".")
        return safe.ifEmpty { "font_${System.currentTimeMillis()}" }
    }

    /** Отображаемое имя: без префикса и расширения, с суффиксом при коллизии. */
    fun displayName(value: String, systemFonts: Set<String>): String {
        val fileName = parsePrefValue(value) ?: return value
        val base = fileName.substringBeforeLast('.')
        return if (base in systemFonts) "$base (imported)" else base
    }

    /** true, если имя файла оканчивается на распознаваемое расширение шрифта (.ttf/.otf). */
    fun isValidFontExtension(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".ttf") || lower.endsWith(".otf")
    }

    /** Единый резолвер файла импортированного шрифта (используется NORMAL и BOLD). */
    fun resolveFile(name: String): File? {
        val fileName = parsePrefValue(name) ?: return null
        return File(fontsDir(), fileName).takeIf { it.exists() }
    }

    /** Директория импортированных шрифтов (зеркалит паттерн AppFileResolver `File(filesDir,"books")`). */
    fun fontsDir(): File = File(appContext.filesDir, "fonts")

    /** Список импортированных шрифтов в виде `font_file:`-значений, отсортированный по имени. */
    fun scanImported(): List<String> =
        fontsDir().listFiles()?.toList().orEmpty()
            .filter { it.isFile }
            .map { "font_file:" + it.name }
            .sorted()

    private fun customTypeface(name: String): Typeface? = when (name) {
        "inter" -> ResourcesCompat.getFont(appContext, R.font.inter_regular)
        "lora" -> ResourcesCompat.getFont(appContext, R.font.lora_regular)
        "merriweather" -> ResourcesCompat.getFont(appContext, R.font.merriweather_regular)
        "source-sans-pro" -> ResourcesCompat.getFont(appContext, R.font.source_sans_pro_regular)
        else -> null
    }

    fun getTypeFaceNORMAL(name: String): Typeface = typeFaceNORMALCache.getOrPut(name) {
        if (isImported(name)) {
            resolveFile(name)?.let { file ->
                runCatching { Typeface.createFromFile(file) }.getOrNull()
            } ?: Typeface.create("serif", Typeface.NORMAL)
        } else {
            customTypeface(name) ?: Typeface.create(name, Typeface.NORMAL)
        }
    }

    fun getTypeFaceBOLD(name: String): Typeface = typeFaceBOLDCache.getOrPut(name) {
        if (isImported(name)) {
            resolveFile(name)?.let { file ->
                runCatching { Typeface.createFromFile(file) }.getOrNull()
                    ?.let { Typeface.create(it, Typeface.BOLD) }
            } ?: Typeface.create("serif", Typeface.BOLD)
        } else {
            customTypeface(name)?.let { Typeface.create(it, Typeface.BOLD) }
                ?: Typeface.create(name, Typeface.BOLD)
        }
    }

    /** Импортирует шрифт из content-URI: копирует файл, валидирует. */
    suspend fun importFont(uri: Uri): Result<Unit> {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val rawName = appContext.contentResolver.query(uri, null, null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) cursor.getString(idx) else null
                        } else null
                    }
                    ?: uri.lastPathSegment
                    ?: "font"

                var fileName = sanitizeFileName(rawName)
                // Расширение — рекомендательное: реальный гейт — createFromFile ниже.
                if (!isValidFontExtension(fileName)) fileName += ".ttf"

                val dir = fontsDir().apply { mkdirs() }
                val file = File(dir, fileName)

                val input = appContext.contentResolver.openInputStream(uri)
                    ?: return@runCatching error("Не удалось открыть поток для $uri")
                input.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size > MAX_IMPORT_BYTES) {
                        file.delete()
                        return@runCatching error("Файл шрифта превышает лимит ${MAX_IMPORT_BYTES} байт")
                    }
                    file.outputStream().use { it.write(bytes) }
                }

                // Валидация: повреждённый/не-шрифт файл удаляем и сообщаем об ошибке.
                runCatching { Typeface.createFromFile(file) }.getOrElse {
                    file.delete()
                    return@runCatching error("Файл не является валидным шрифтом: $fileName")
                }

                Unit
            }
        }
        return result
    }

    /** Удаляет импортированный шрифт; системные/встроенные значения не трогает. */
    fun deleteFont(value: String): Boolean {
        if (!isImported(value)) return false
        val fileName = parsePrefValue(value) ?: return false
        val file = File(fontsDir(), fileName)
        val deleted = file.delete() || !file.exists()
        return deleted
    }
}