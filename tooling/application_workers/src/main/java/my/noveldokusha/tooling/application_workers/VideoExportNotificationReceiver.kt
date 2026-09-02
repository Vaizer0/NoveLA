package my.noveldokusha.tooling.application_workers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import timber.log.Timber

class VideoExportNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL -> VideoExportQueue.cancelAllReactive(context)
            ACTION_DELETE -> {
                intent.getStringExtra(EXTRA_URI)?.let { uriString ->
                    runCatching {
                        context.contentResolver.delete(Uri.parse(uriString), null, null)
                    }.onFailure { Timber.e(it, "VideoExport: failed to delete video file") }
                }
                cancelNotification(context, intent)
            }
            ACTION_OPEN -> {
                intent.getStringExtra(EXTRA_URI)?.let { uriString ->
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(uriString), VideoExportNotification.MIME_TYPE)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }.onFailure { Timber.e(it, "VideoExport: failed to open video file") }
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
        const val ACTION_CANCEL = "my.noveldokusha.action.CANCEL_VIDEO_EXPORT"
        const val ACTION_OPEN = "my.noveldokusha.action.OPEN_VIDEO_EXPORT"
        const val ACTION_DELETE = "my.noveldokusha.action.DELETE_VIDEO_EXPORT"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}