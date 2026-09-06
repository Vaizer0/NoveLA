package my.noveldokusha.tooling.application_workers.video

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/** SAF helpers used to keep an MP4 durable while it is being generated. */
@RequiresApi(Build.VERSION_CODES.O)
object SafMp4Stager {
    private const val MIME_MP4 = "video/mp4"
    private const val COPY_BUFFER_BYTES = 1_048_576

    suspend fun remuxLocalMp4ToSaf(context: Context, sourceFile: File, targetUri: Uri) = withContext(Dispatchers.IO) {
        require(sourceFile.isFile && sourceFile.length() > 0L) { "Local MP4 is missing or empty" }
        val extractor = android.media.MediaExtractor().apply { setDataSource(sourceFile.absolutePath) }
        try {
            require(extractor.trackCount > 0) { "Local MP4 has no tracks" }
            context.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
                val muxer = android.media.MediaMuxer(
                    pfd.fileDescriptor,
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
                var started = false
                try {
                    val outTracks = IntArray(extractor.trackCount) { -1 }
                    for (track in 0 until extractor.trackCount) {
                        outTracks[track] = muxer.addTrack(extractor.getTrackFormat(track))
                    }
                    muxer.start()
                    started = true
                    val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
                    val info = android.media.MediaCodec.BufferInfo()
                    for (track in 0 until extractor.trackCount) {
                        extractor.selectTrack(track)
                        while (true) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                            muxer.writeSampleData(outTracks[track], buffer, info)
                            extractor.advance()
                        }
                        extractor.unselectTrack(track)
                    }
                } finally {
                    if (started) runCatching { muxer.stop() }
                    runCatching { muxer.release() }
                }
            } ?: throw IllegalStateException("Cannot open SAF staging document $targetUri")
        } finally {
            extractor.release()
        }
        require(isValidMp4(context, targetUri)) { "Generated SAF staging document is not a valid MP4" }
    }

    suspend fun isValidMp4(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            querySize(context, uri) > 0L && context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(pfd.fileDescriptor)
                    var hasVideo = false
                    var hasAudio = false
                    for (track in 0 until extractor.trackCount) {
                        when (extractor.getTrackFormat(track).getString(android.media.MediaFormat.KEY_MIME)?.substringBefore('/')) {
                            "video" -> hasVideo = true
                            "audio" -> hasAudio = true
                        }
                    }
                    hasVideo && hasAudio
                } finally {
                    extractor.release()
                }
            } ?: false
        }.getOrDefault(false)
    }

    suspend fun querySize(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (sizeCol >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    suspend fun delete(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
}
