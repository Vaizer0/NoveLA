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

/** Ошибка доступа к SAF-дереву. */
class SafAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Созданный документ в SAF. */
data class SafDocument(
    val uri: Uri,
    val displayName: String,
)

/**
 * Запись итоговых файлов аудиокниги через SAF.
 *
 * Структура папок фиксирована и обязательна:
 * `<выбранная папка>/Audiobooks/<Название книги>/`.
 * Корнем выбранной папки экспорт не пользуется — файлы всегда внутри
 * подпапки книги.
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
     * и возвращает URI этой папки. Существующая папка переиспользуется.
     */
    fun ensureNovelFolder(treeUri: String, novelName: String): Uri {
        val tree = Uri.parse(treeUri)
        val root = try {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } catch (e: Exception) {
            throw SafAccessException("Invalid tree URI: $treeUri", e)
        }

        val audiobooks = findOrCreateDirectory(root, AUDIOBOOKS_DIR)
        val novelFolder = findOrCreateDirectory(audiobooks, sanitizeFileName(novelName).ifBlank { "Novel" })
        return novelFolder
    }

    /**
     * Создаёт документ в папке. При коллизии имён возвращается уникальное
     * имя, чтобы не затереть чужой файл.
     */
    fun createDocument(folderUri: Uri, displayName: String, mimeType: String): SafDocument {
        val uniqueName = uniqueName(folderUri, displayName)
        val uri = try {
            DocumentsContract.createDocument(resolver, folderUri, mimeType, uniqueName)
        } catch (e: Exception) {
            throw SafAccessException("Failed to create document '$uniqueName'", e)
        } ?: throw SafAccessException("Provider returned no document for '$uniqueName'")
        return SafDocument(uri = uri, displayName = displayName)
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

    /** Удаляет частично созданный документ (отмена, ошибка). */
    fun deleteDocument(uri: Uri?) {
        if (uri == null) return
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            .onFailure { Timber.w(it, "SafAudiobookStorage: failed to delete %s", uri) }
    }

    /** URI каталога для «открыть папку» в файловом менеджере. */
    fun documentTreeUri(folderUri: Uri): String = try {
        val docId = DocumentsContract.getDocumentId(folderUri)
        val treeId = docId.substringAfterLast(':').ifBlank { docId }
        "content://${folderUri.authority}/tree/${android.net.Uri.encode(treeId)}"
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
        findChild(parent, name)?.let { return it }
        val created = try {
            DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
        } catch (e: Exception) {
            throw SafAccessException("Failed to create directory '$name'", e)
        } ?: throw SafAccessException("Provider returned no directory '$name'")
        return created
    }

    private fun findChild(parent: Uri, name: String): Uri? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent,
            DocumentsContract.getTreeDocumentId(parent),
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
            while (cursor.moveToNext()) {
                val childName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                if (childName == name && idIndex >= 0) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idIndex))
                }
            }
            null
        }
    }.getOrNull()

    /** Добавляет суффикс к имени, если документ с таким именем уже есть. */
    private fun uniqueName(folderUri: Uri, displayName: String): String {
        if (findChild(folderUri, displayName) == null) return displayName
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val extension = if (dot > 0) displayName.substring(dot) else ""
        var index = 2
        while (index < MAX_UNIQUE_ATTEMPTS) {
            val candidate = "$base ($index)$extension"
            if (findChild(folderUri, candidate) == null) return candidate
            index++
        }
        return "$base (${System.currentTimeMillis()})$extension"
    }

    companion object {
        const val AUDIOBOOKS_DIR = "Audiobooks"
        const val WAV_MIME = "audio/wav"
        const val MP4_MIME = "video/mp4"
        const val JSON_MIME = "application/json"
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val PROGRESS_STEP_BYTES = 4L * 1024 * 1024
        private const val MAX_UNIQUE_ATTEMPTS = 100
    }
}
