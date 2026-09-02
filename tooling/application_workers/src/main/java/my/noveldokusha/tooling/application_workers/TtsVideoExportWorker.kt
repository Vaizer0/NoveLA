package my.noveldokusha.tooling.application_workers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TtsVideoJobStatus
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.text_to_speech.TtsAudioSource as SpeechSource
import my.noveldokusha.text_to_speech.TtsVideoAudioSynthesizer
import my.noveldokusha.text_to_speech.TtsVideoCompositionRenderer
import my.noveldokusha.text_to_speech.TtsVideoMp4Encoder
import my.noveldokusha.text_to_speech.TtsVideoPreferences
import my.noveldokusha.text_to_speech.TtsVideoRequest
import my.noveldokusha.text_to_speech.TtsVideoTimelineBuilder
import my.noveldokusha.text_to_speech.toTtsVideoRequest
import org.json.JSONArray
import java.io.File

class TtsVideoExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val request = inputData.getString(KEY_REQUEST_JSON)?.toTtsVideoRequest() ?: return Result.failure()
        val prefs = TtsVideoPreferences(applicationContext)
        update(prefs, request, TtsVideoJobStatus.RUNNING, 1)
        try {
            setForeground(ForegroundInfo(NOTIFICATION_ID, notification(request.chapterTitle, 1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            val entry = androidx.work.WorkManager.getInstance(applicationContext)
            val db = resolveDatabase() ?: return fail(prefs, request, "Database unavailable")
            val textBlocks = withContext(Dispatchers.IO) { loadText(db, request) }
                ?: return fail(prefs, request, if (request.source == SpeechSource.TRANSLATED) "Cached translation is missing" else "Chapter text is not downloaded")
            if (request.source == SpeechSource.TRANSLATED && textBlocks.isEmpty()) return fail(prefs, request, "Cached translation is empty")

            val temp = File(applicationContext.cacheDir, "tts_video/${request.jobId}").apply { mkdirs() }
            val wav = File(temp, "audio.wav"); val mp4 = File(temp, "video.mp4")
            val renderer = TtsVideoCompositionRenderer(applicationContext)
            val snapshot = my.noveldokusha.text_to_speech.TtsVideoVisualSnapshot(
                backgroundBitmap = renderer.loadBitmap(request.visual.backgroundUri),
                artworkBitmaps = request.visual.artworkUris.mapNotNull(renderer::loadBitmap)
            )
            val synthesis = TtsVideoAudioSynthesizer(applicationContext).synthesize(request, textBlocks, wav) { f ->
                val p = (f * 45f).toInt().coerceIn(1, 45); update(prefs, request, TtsVideoJobStatus.RUNNING, p); setProgressAsync(androidx.work.Data.Builder().putInt("progress", p).build())
            }
            val timeline = TtsVideoTimelineBuilder.build(synthesis.chunks, synthesis.sampleRate)
            update(prefs, request, TtsVideoJobStatus.RUNNING, 50)
            TtsVideoMp4Encoder().encode(wav, mp4, timeline, request.visual, renderer, snapshot) { f ->
                val p = (50 + f * 45f).toInt().coerceIn(50, 95); update(prefs, request, TtsVideoJobStatus.RUNNING, p); setProgressAsync(androidx.work.Data.Builder().putInt("progress", p).build())
            }
            val outUri = withContext(Dispatchers.IO) { publish(applicationContext, Uri.parse(request.outputDirectoryUri), request, mp4) }
                ?: return fail(prefs, request, "Unable to create video file")
            wav.delete(); mp4.delete(); temp.deleteRecursively()
            update(prefs, request, TtsVideoJobStatus.SUCCESS, 100, outUri.toString())
            return Result.success()
        } catch (e: CancellationException) {
            update(prefs, request, TtsVideoJobStatus.CANCELLED, 0, message = "cancelled"); return Result.failure()
        } catch (e: Throwable) {
            update(prefs, request, TtsVideoJobStatus.FAILED, 0, message = e.message ?: "Video export failed"); return Result.failure()
        }
    }

    private fun resolveDatabase(): AppDatabase? = runCatching {
        val ep = applicationContext as? dagger.hilt.android.internal.modules.ApplicationContextModule
        null
    }.getOrNull()

    private suspend fun loadText(db: AppDatabase, request: TtsVideoRequest): List<String>? {
        return if (request.source == SpeechSource.ORIGINAL) {
            db.chapterBodyDao().get(request.chapterUrl)?.body?.let { TtsVideoTextJson.paragraphsFromBody(it) }
        } else {
            val row = db.chapterTranslationDao().getTranslations(request.chapterUrl, request.translationSourceLang, request.translationTargetLang) ?: return null
            TtsVideoTextJson.readArray(row.translatedParagraphs)
        }
    }

    private fun publish(context: Context, root: Uri, request: TtsVideoRequest, file: File): Uri? {
        val rootName = "NoveLA Video"; val novel = sanitize(request.novelTitle).ifBlank { "Novel" }
        val rootChild = findOrCreateDirectory(context, root, rootName) ?: return null
        val novelChild = findOrCreateDirectory(context, rootChild, novel) ?: return null
        val suffix = if (request.source == SpeechSource.TRANSLATED) "Translated" else "Original"
        val name = "Chapter ${request.chapterIndex + 1} - ${sanitize(request.chapterTitle)} [$suffix].mp4"
        val uri = DocumentsContract.createDocument(context.contentResolver, novelChild, "video/mp4", name) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return null
        return uri
    }

    private fun findOrCreateDirectory(context: Context, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getTreeDocumentId(parent))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null).use { c ->
            if (c != null) while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
                }
            }
        }
        return DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
    }

    private fun sanitize(s: String): String = s.replace(Regex("[\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim().take(180)
    private fun update(p: TtsVideoPreferences, r: TtsVideoRequest, status: TtsVideoJobStatus, progress: Int, output: String = "", message: String = "") {
        val jobs=p.jobs().toMutableMap(); val old=jobs[r.jobId] ?: my.noveldokusha.core.appPreferences.TtsVideoJobState(r.chapterUrl,r.novelUrl,r.chapterTitle,r.source,status)
        jobs[r.jobId]=old.copy(status=status,progress=progress,outputUri=output.ifBlank{old.outputUri},message=message);p.saveJobs(jobs)
    }
    private fun fail(p: TtsVideoPreferences, r: TtsVideoRequest, msg: String): Result { update(p,r,TtsVideoJobStatus.FAILED,0,message=msg); return Result.failure() }

    private fun notification(title: String, progress: Int): Notification {
        val manager=applicationContext.getSystemService(NotificationManager::class.java); if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(NotificationChannel(CHANNEL,"TTS Video",NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Creating video").setContentText(title).setProgress(100,progress,false).setOngoing(true).build()
    }
    companion object { const val KEY_REQUEST_JSON="tts_video_request_json"; private const val CHANNEL="tts_video_export"; private const val NOTIFICATION_ID=49017 }
}

private object TtsVideoTextJson {
    fun readArray(json: String): List<String> = runCatching { val a=JSONArray(json); (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.getOrDefault(emptyList())
    fun paragraphsFromBody(body: String): List<String> = my.noveldokusha.text_to_speech.TtsTextPreparer.paragraphsFromBody(body)
}
