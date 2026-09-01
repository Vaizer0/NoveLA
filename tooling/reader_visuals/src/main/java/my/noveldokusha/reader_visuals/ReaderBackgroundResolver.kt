package my.noveldokusha.reader_visuals

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.noveldokusha.core.isImage
import java.io.File

/**
 * Shared reader background-image resolver (imported wallpaper images only).
 *
 * Mirrors the behaviour that used to live in features/reader `BackgroundImageLoader`.
 * Imported images live in filesDir/backgrounds and are referenced by prefs values
 * with the `background_file:` prefix. Shared with the video exporter so the video
 * background uses the exact same files the reader shows.
 */
object ReaderBackgroundResolver {

    private lateinit var appContext: Context

    /** Мягкий лимит ~20MB на импортируемую фоновую картинку. */
    private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

    // Сериализует запись+валидацию импорта: два быстрых импорта с коллизией
    // санитизированных имён не должны писать в один файл одновременно.
    private val importMutex = Mutex()

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

    /** true, если значение — импортированная фоновая картинка. */
    fun isImported(value: String): Boolean = value.startsWith("background_file:")

    /** Имя файла на диске для импортированного значения, иначе null. */
    fun parsePrefValue(value: String): String? =
        if (isImported(value)) value.removePrefix("background_file:") else null

    /** Оставляет только [A-Za-z0-9._-], схлопывает ".."; пусто -> fallback-имя. */
    fun sanitizeFileName(raw: String): String {
        val safe = raw.filter {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
                it == '.' || it == '_' || it == '-'
        }.replace(Regex("\\.{2,}"), ".")
        return safe.ifEmpty { "background_${System.currentTimeMillis()}" }
    }

    /** true, если имя файла оканчивается на распознаваемое расширение картинки. */
    fun isValidImageExtension(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    /** Единый резолвер файла импортированной фоновой картинки. */
    fun resolveFile(name: String): File? {
        val fileName = parsePrefValue(name) ?: return null
        return File(backgroundsDir(), fileName).takeIf { it.exists() }
    }

    /** Директория импортированных фоновых картинок. */
    fun backgroundsDir(): File = File(appContext.filesDir, "backgrounds")

    /** Список импортированных фоновых картинок, отсортированный по имени. */
    fun scanImported(): List<File> =
        backgroundsDir().listFiles()?.toList().orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }

    /** Импортирует фоновую картинку из content-URI: копирует файл, валидирует. */
    suspend fun importBackgroundImage(uri: Uri): Result<Unit> {
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
                    ?: "image"

                var fileName = sanitizeFileName(rawName)
                // Расширение — рекомендательное: реальный гейт — isImage ниже.
                if (!isValidImageExtension(fileName)) fileName += ".png"

                // Запись+валидация под общим мьютексом: коллизия санитизированных имён
                // не должна приводить к перемежению/порче файла или молчаливой перезаписи.
                importMutex.withLock {
                    val dir = backgroundsDir().apply { mkdirs() }
                    val file = File(dir, fileName)

                    val input = appContext.contentResolver.openInputStream(uri)
                        ?: return@runCatching error("Не удалось открыть поток для $uri")
                    input.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size > MAX_IMPORT_BYTES) {
                            file.delete()
                            return@runCatching error("Файл превышает лимит ${MAX_IMPORT_BYTES} байт")
                        }
                        file.outputStream().use { it.write(bytes) }
                    }

                    // Валидация магическими байтами: повреждённый/не-картинка файл удаляем.
                    val imageBytes = file.readBytes()
                    if (!isImage(imageBytes)) {
                        file.delete()
                        return@runCatching error("Файл не является картинкой: $fileName")
                    }

                    Unit
                }
            }
        }
        return result
    }

    /** Удаляет импортированную фоновую картинку; префы-заглушки не трогает. */
    fun deleteBackground(value: String): Boolean {
        if (!isImported(value)) return false
        val fileName = parsePrefValue(value) ?: return false
        val file = File(backgroundsDir(), fileName)
        val deleted = file.delete() || !file.exists()
        return deleted
    }
}