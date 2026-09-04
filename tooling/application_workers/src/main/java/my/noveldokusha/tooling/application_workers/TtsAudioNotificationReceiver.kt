package my.noveldokusha.tooling.application_workers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.AndroidEntryPoint
import my.noveldokusha.core.appPreferences.AppPreferences
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class TtsAudioNotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL -> {
                val workRequestId = intent.getStringExtra(EXTRA_WORK_REQUEST_ID)
                if (workRequestId.isNullOrBlank()) {
                    Timber.w("TtsAudio: cancel notification missing WorkRequest id; ignoring")
                } else {
                    TtsAudioQueue.cancel(context, appPreferences, workRequestId)
                }
            }
            ACTION_DELETE -> {
                intent.getStringExtra(EXTRA_URI)?.let { uriString ->
                    runCatching {
                        context.contentResolver.delete(Uri.parse(uriString), null, null)
                    }.onFailure { Timber.e(it, "TtsAudio: failed to delete audio file") }
                }
                cancelNotification(context, intent)
            }
            ACTION_OPEN -> {
                intent.getStringExtra(EXTRA_URI)?.let { uriString ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(uriString), TtsAudioExportNotification.MIME_TYPE)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }.onFailure { Timber.e(it, "TtsAudio: failed to open audio file") }
                }
                cancelNotification(context, intent)
            }
        }
    }

    private fun cancelNotification(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (id != -1) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
        }
    }

    companion object {
        const val ACTION_CANCEL = "my.noveldokusha.action.CANCEL_TTS_AUDIO"
        const val ACTION_OPEN = "my.noveldokusha.action.OPEN_TTS_AUDIO"
        const val ACTION_DELETE = "my.noveldokusha.action.DELETE_TTS_AUDIO"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_WORK_REQUEST_ID = "extra_work_request_id"
    }
}
