package my.noveldokusha.features.reader.video

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class CinematicVideoExportActivity : ComponentActivity() {
    private var wavUri by mutableStateOf<Uri?>(null)
    private var timelineUri by mutableStateOf<Uri?>(null)
    private var outputUri by mutableStateOf<Uri?>(null)
    private var progress by mutableStateOf(0)
    private var running by mutableStateOf(false)
    private var errorText by mutableStateOf<String?>(null)

    private val workManager by lazy { WorkManager.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            my.noveldokusha.coreui.theme.InternalTheme {
                Surface {
                    ExportScreen(
                        wavUri = wavUri,
                        timelineUri = timelineUri,
                        outputUri = outputUri,
                        progress = progress,
                        running = running,
                        errorText = errorText,
                        onPickWav = { pickWav.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) },
                        onPickTimeline = { pickTimeline.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onPickOutput = { createOutput.launch("NoveLA_cinematic_${System.currentTimeMillis()}.mp4") },
                        onStart = ::startExport,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    private val pickWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            wavUri = uri
            errorText = null
        }
    }

    private val pickTimeline = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            takePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            timelineUri = uri
            errorText = null
        }
    }

    private val createOutput = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) {
            takePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            outputUri = uri
            errorText = null
        }
    }

    private fun takePermission(uri: Uri, mode: Int) {
        runCatching { contentResolver.takePersistableUriPermission(uri, mode) }
    }

    private fun startExport() {
        val wav = wavUri ?: return
        val timeline = timelineUri ?: return
        val output = outputUri ?: return
        running = true
        progress = 0
        errorText = null

        val request = OneTimeWorkRequestBuilder<CinematicVideoWorker>()
            .setInputData(
                workDataOf(
                    CinematicVideoWorker.KEY_WAV_URI to wav.toString(),
                    CinematicVideoWorker.KEY_TIMELINE_URI to timeline.toString(),
                    CinematicVideoWorker.KEY_OUTPUT_URI to output.toString(),
                )
            )
            .build()

        workManager.enqueueUniqueWork(
            "cinematic_video_${UUID.randomUUID()}",
            ExistingWorkPolicy.REPLACE,
            request,
        )

        lifecycleScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collectLatest { info ->
                if (info == null) return@collectLatest
                progress = info.progress.getInt(CinematicVideoWorker.KEY_PROGRESS, progress)
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> running = true
                    WorkInfo.State.SUCCEEDED -> {
                        running = false
                        progress = 100
                        Toast.makeText(this@CinematicVideoExportActivity, "Video created successfully", Toast.LENGTH_LONG).show()
                    }
                    WorkInfo.State.FAILED -> {
                        running = false
                        errorText = info.outputData.getString(CinematicVideoWorker.KEY_ERROR) ?: "Video generation failed"
                    }
                    WorkInfo.State.CANCELLED -> {
                        running = false
                        errorText = "Video generation cancelled"
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ExportScreen(
    wavUri: Uri?,
    timelineUri: Uri?,
    outputUri: Uri?,
    progress: Int,
    running: Boolean,
    errorText: String?,
    onPickWav: () -> Unit,
    onPickTimeline: () -> Unit,
    onPickOutput: () -> Unit,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Cinematic video", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Create a 1920×1080 / 24 FPS novel video from a NoveLA WAV file and its matching timeline JSON.",
            style = MaterialTheme.typography.bodyMedium,
        )
        FileRow("Audio WAV", wavUri?.toString(), onPickWav)
        FileRow("Timeline JSON", timelineUri?.toString(), onPickTimeline)
        FileRow("Output MP4", outputUri?.toString(), onPickOutput)
        if (running) {
            Text("Rendering… $progress%")
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        errorText?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClose, enabled = !running) { Text("Close") }
            Button(onClick = onStart, enabled = !running && wavUri != null && timelineUri != null && outputUri != null) {
                Text("Create video")
            }
        }
    }
}

@Composable
private fun FileRow(label: String, uri: String?, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(uri ?: "Not selected", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        OutlinedButton(onClick = onPick) { Text(if (uri == null) "Choose" else "Change") }
    }
}
