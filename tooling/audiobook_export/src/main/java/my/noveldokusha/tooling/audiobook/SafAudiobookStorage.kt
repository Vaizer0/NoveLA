package my.noveldokusha.tooling.audiobook

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer

/** Ошибка доступа к SAF-дереву. */
class SafAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Созданный документ в SAF.
 *
 * [created] показывает, был документ создан этим вызовом или переиспользован
 * существующий. Это важно при откате: удалять можно только то, что создали
 * сами, иначе можно снести прошлый удачный экспорт пользователя.
 */
data class SafDocument(
    val uri: Uri,
    val displayName: String,
    val created: Boolean,
)

/**
 * Запись итоговых файлов аудиокниги через SAF.
 *
 * Структура папок фиксирована и обязательна:
 * `<выбранная папка>/Audiobooks/<Название книги>/`.
 * Корнем выбранной папки экспорт не пользуется — файлы всегда внутри
 * подпапки книги.
 *
 * Повторный экспорт той же книги (WAV, затем MP4, или наоборот) обязан
 * попадать в уже существующую папку книги. Провайдер нумерует коллизии
 * (`Name (1)`, `Name (2)`), поэтому имена папок сравниваются в
 * нормализованном виде, и существующая папка переиспользуется.
 *
 * Копирование потоковое: многогигабайтный MP4/WAV в память не читается.
 */
class SafAudiobookStorage(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Проверяет, что tree URI всё ещё доступен и права не отозваны.
     * Возвращает `false`, вместо того чтобы падать внутри воркера.
     */
    fun isAccessible(treeUri: String): Boolean {
        val tree = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        if (tree.scheme != "content") return false
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /**
     * Создаёт (при необходимости) `Audiobooks/<bookName>/` внутри дерева
     * и возвращает URI этой папки. Существующая папка книги переиспользуется.
     */
    fun ensureNovelFolder(treeUri: String, novelName: String): Uri {
        val tree = runCatching { Uri.parse(treeUri) }.getOrNull()
            ?: throw SafAccessException("Invalid tree URI: $treeUri")
        val root = try {
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        } catch (e: Exception) {
            throw SafAccessException("Invalid tree URI: $treeUri", e)
        }

        val audiobooks = findOrCreateDirectory(root, AUDIOBOOKS_DIR)
        val folderName = sanitizeFileName(novelName).ifBlank { "Novel" }
        return findOrCreateDirectory(audiobooks, folderName)
    }

    /**
     * Создаёт документ в папке. Если документ с таким именем уже есть, он
     * переиспользуется (и будет перезаписан через `openOutputStream(..., "wt")`),
     * а не получает ` (1)` от провайдера. Так повторный экспорт осознанно
     * заменяет прошлый результат и не плодит дубликаты.
     */
    fun createDocument(folderUri: Uri, displayName: String, mimeType: String): SafDocument {
        findChildByName(folderUri, displayName, normalized = false)?.let { existing ->
            return SafDocument(uri = existing, displayName = displayName, created = false)
        }
        val created = try {
            DocumentsContract.createDocument(resolver, folderUri, mimeType, displayName)
        } catch (e: Exception) {
            throw SafAccessException("Failed to create document '$displayName'", e)
        } ?: throw SafAccessException("Provider returned no document for '$displayName'")
        return SafDocument(
            uri = toTreeDocumentUri(folderUri, created),
            displayName = displayName,
            created = true,
        )
    }

    /** Потоково копирует файл в SAF-документ. */
    fun copyToSaf(source: File, documentUri: Uri) {
        FileInputStream(source).use { input ->
            resolver.openOutputStream(documentUri, "wt")?.use { output ->
                copyStream(input, output, source.length())
            } ?: throw SafAccessException("Cannot open output stream for $documentUri")
        }
    }

    /** Копирует поток в SAF, ограничивая размер буфера. */
    fun copyStream(source: InputStream, target: Uri, totalBytes: Long) {
        val output: OutputStream = resolver.openOutputStream(target, "wt")
            ?: throw SafAccessException("Cannot open output stream for $target")
        output.use { copyStream(source, it, totalBytes) }
    }

    /** Потоковое копирование с фиксированным буфером. */
    fun copyStream(source: InputStream, target: OutputStream, totalBytes: Long) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var copied = 0L
        var lastReported = 0L
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            target.write(buffer, 0, read)
            copied += read
            if (totalBytes > 0 && copied - lastReported >= PROGRESS_STEP_BYTES) {
                lastReported = copied
            }
        }
        target.flush()
        if (totalBytes > 0 && copied != totalBytes) {
            Timber.w("SafAudiobookStorage: copied %d bytes, expected %d", copied, totalBytes)
        }
    }

    /** Удаляет документ, созданный этим экспортом (отмена, ошибка). */
    fun deleteDocument(uri: Uri?) {
        if (uri == null) return
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .onFailure { Timber.w(it, "SafAudiobookStorage: failed to delete %s", uri) }
    }

    /** URI каталога для «открыть папку» в файловом менеджере. */
    fun documentTreeUri(folderUri: Uri): String = try {
        val treeId = DocumentsContract.getTreeDocumentId(folderUri)
        "content://${folderUri.authority}/tree/${Uri.encode(treeId)}"
    } catch (e: Exception) {
        Timber.w(e, "SafAudiobookStorage: cannot build tree uri for %s", folderUri)
        folderUri.toString()
    }

    /** Реальное имя документа: провайдер мог переименовать его при коллизии. */
    fun resolveDisplayName(uri: Uri, fallback: String): String = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) it.getString(index) else fallback
            } else {
                fallback
            }
        } ?: fallback
    }.getOrDefault(fallback)

    private fun findOrCreateDirectory(parent: Uri, name: String): Uri {
        findChildByName(parent, name, normalized = true)?.let { return it }
        val created = try {
            DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            )
        } catch (e: Exception) {
            throw SafAccessException("Failed to create directory '$name'", e)
        } ?: throw SafAccessException("Provider returned no directory '$name'")
        return toTreeDocumentUri(parent, created)
    }

    /**
     * Ищет потомка [parent] по имени. [parent] обязан быть tree-документом
     * (`.../tree/<treeId>/document/<docId>`): только тогда его `documentId`
     * — это идентификатор самого родителя, а не корня дерева. Старый код
     * передавал `getTreeDocumentId(parent)` и потому всегда искал в корне —
     * именно из-за этого папка книги создавалась заново на каждом экспорте.
     */
    private fun findChildByName(parent: Uri, name: String, normalized: Boolean): Uri? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getDocumentId(parent),
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idIndex < 0 || nameIndex < 0) return@use null
            val target = if (normalized) normalizeName(name) else name
            while (cursor.moveToNext()) {
                val childName = cursor.getString(nameIndex) ?: continue
                val childKey = if (normalized) normalizeName(childName) else childName
                if (childKey == target) {
                    val childId = cursor.getString(idIndex) ?: return@use null
                    return@use DocumentsContract.buildDocumentUriUsingTree(parent, childId)
                }
            }
            null
        }
    }.getOrNull()

    /**
     * Приводит URI, полученный от провайдера, к tree-документу. Часть
     * провайдеров (например, Downloads) возвращает `.../document/<id>` без
     * ветки `tree`; на таком URI не работают дочерние запросы, поэтому без
     * нормализации повторный поиск снова не находил бы созданную папку.
     */
    private fun toTreeDocumentUri(treeDocument: Uri, rawDocument: Uri): Uri = runCatching {
        val documentId = DocumentsContract.getDocumentId(rawDocument)
        DocumentsContract.buildDocumentUriUsingTree(treeDocument, documentId)
    }.getOrDefault(rawDocument)

    /** Регистро- и пробело-независимый ключ имени папки. */
    private fun normalizeName(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKC)
            .trim()
            .replace(WHITESPACE_REGEX, " ")
            .lowercase()

    companion object {
        const val AUDIOBOOKS_DIR = "Audiobooks"
        const val WAV_MIME = "audio/wav"
        const val MP4_MIME = "video/mp4"
        const val JSON_MIME = "application/json"
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
