package my.noveldokusha.tooling.application_workers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TranslationLangPair
import my.noveldokusha.text_to_speech.TtsVideoPreferences
import my.noveldokusha.text_to_speech.TtsVideoRequest
import my.noveldokusha.text_to_speech.toJson
import java.security.MessageDigest

/** Builds an immutable request from the current audio TTS profile and video settings snapshot. */
object TtsVideoExportLauncher {
    fun enqueue(
        context: Context,
        appPreferences: AppPreferences,
        novelUrl: String,
        novelTitle: String,
        chapterUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        source: TtsAudioSource,
    ): String {
        val videoPrefs = TtsVideoPreferences(context)
        val visual = videoPrefs.visualSettings().let { settings ->
            if (settings.slideshowSeed != 0L) settings
            else settings.copy(slideshowSeed = stableSeed(novelUrl, chapterUrl))
        }
        // Video exports intentionally use their own SAF tree. Never fall back to the
        // audio-download directory: the two output locations are independent settings.
        val outputUri = videoPrefs.outputDirectoryUri
        require(outputUri.isNotBlank()) { "Select a video output folder first" }
        requireAccessibleTree(context, outputUri)

        if (appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ENGINE.value.isBlank() ||
            appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ID.value.isBlank()
        ) {
            throw IllegalStateException("Select a TTS voice for video export first")
        }

        val pair = if (source == TtsAudioSource.TRANSLATED) {
            appPreferences.translationPairForBook(novelUrl)
        } else TranslationLangPair()
        if (source == TtsAudioSource.TRANSLATED && (pair.source.isBlank() || pair.target.isBlank())) {
            throw IllegalStateException("Translation language pair is incomplete")
        }

        val requestIdentity = buildString {
            append(novelUrl); append('\u0000')
            append(chapterUrl); append('\u0000')
            append(source.name); append('\u0000')
            append(pair.source); append('\u0000')
            append(pair.target); append('\u0000')
            append(appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ENGINE.value); append('\u0000')
            append(appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ID.value); append('\u0000')
            append(appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_SPEED.value); append('\u0000')
            append(appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_PITCH.value); append('\u0000')
            append(outputUri); append('\u0000')
            append(visual.toJson().toString())
        }
        val jobId = sha256(requestIdentity)
        val request = TtsVideoRequest(
            jobId = jobId,
            novelUrl = novelUrl,
            novelTitle = novelTitle,
            chapterUrl = chapterUrl,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            source = source,
            translationSourceLang = pair.source,
            translationTargetLang = pair.target,
            enginePackage = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ENGINE.value,
            voiceId = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_ID.value,
            speed = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_SPEED.value,
            pitch = appPreferences.TTS_AUDIO_DOWNLOAD_VOICE_PITCH.value,
            visual = visual,
            outputDirectoryUri = outputUri,
        )
        TtsVideoQueue.enqueue(context, request)
        return jobId
    }

    /** Background export requires a persisted write grant and a queryable tree root. */
    private fun requireAccessibleTree(context: Context, uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrElse {
            throw IllegalStateException("Video output folder URI is invalid")
        }
        require(DocumentsContract.isTreeUri(uri)) { "Video output folder is not a SAF tree" }
        val resolver = context.contentResolver
        val persisted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        require(persisted) { "Video output folder permission was revoked; choose the folder again" }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrElse {
            throw IllegalStateException("Video output folder URI is inaccessible")
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        try {
            resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null).use { cursor ->
                require(cursor?.moveToFirst() == true) { "Video output folder is inaccessible; choose the folder again" }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (_: Throwable) {
            throw IllegalStateException("Video output folder is inaccessible; choose the folder again")
        }
    }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun stableSeed(a: String, b: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest("$a\u0000$b".toByteArray())
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (d[i].toLong() and 255)
        return v
    }
}
