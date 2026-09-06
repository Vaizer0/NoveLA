package my.noveldokusha.tooling.application_workers.video

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.TtsAudioSource
import org.json.JSONObject

/**
 * Sidecar identity for a generated chapter video.
 *
 * MP4 filenames alone are not sufficient to prove chapter identity: chapter titles can change,
 * chapter ordering can change, and legacy files can be left behind after interrupted exports.
 * The manifest binds the MP4 to the exact novel/chapter and the exact durable WAV + timeline URIs
 * used to generate it.
 */
object TtsVideoArtifactManifest {
    private const val VERSION = 1
    private const val SUFFIX = ".manifest.json"
    private const val STAGING_SUFFIX = ".manifest.json.part"

    fun finalName(videoName: String): String = "$videoName$SUFFIX"

    fun stagingName(videoName: String, jobId: String): String {
        val base = videoName.removeSuffix(".mp4")
        val safeJobId = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$base.$safeJobId$STAGING_SUFFIX"
    }

    fun buildJson(
        novelUrl: String,
        chapterUrl: String,
        source: TtsAudioSource,
        audioUri: String,
        timelineUri: String,
        displayName: String,
    ): String = JSONObject().apply {
        put("version", VERSION)
        put("novelUrl", novelUrl)
        put("chapterUrl", chapterUrl)
        put("source", source.name)
        put("audioUri", audioUri)
        put("timelineUri", timelineUri)
        put("displayName", displayName)
    }.toString(2)

    suspend fun findInDirectory(
        context: Context,
        parentUri: Uri,
        name: String,
    ): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val parentId = DocumentsContract.getDocumentId(parentUri)
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} = ?",
                arrayOf(name),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (cursor.moveToNext()) {
                    return@runCatching DocumentsContract.buildDocumentUriUsingTree(
                        parentUri,
                        cursor.getString(idCol),
                    )
                }
            }
            null
        }.getOrNull()
    }

    suspend fun matches(
        context: Context,
        manifestUri: Uri,
        novelUrl: String,
        chapterUrl: String,
        source: TtsAudioSource,
        audioUri: String,
        timelineUri: String,
        displayName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(manifestUri)?.use { input ->
                val json = JSONObject(input.bufferedReader(Charsets.UTF_8).use { it.readText() })
                json.optInt("version", -1) == VERSION &&
                    json.optString("novelUrl") == novelUrl &&
                    json.optString("chapterUrl") == chapterUrl &&
                    json.optString("source") == source.name &&
                    json.optString("audioUri") == audioUri &&
                    json.optString("timelineUri") == timelineUri &&
                    json.optString("displayName") == displayName
            } ?: false
        }.getOrDefault(false)
    }

    suspend fun isDocumentPresent(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }
}
