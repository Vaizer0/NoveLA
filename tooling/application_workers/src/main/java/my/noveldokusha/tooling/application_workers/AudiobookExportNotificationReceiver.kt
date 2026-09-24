package my.noveldokusha.tooling.application_workers

import android.content.BroadcastReceiver
import android.content.Context

class AudiobookExportNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action == ACTION_CANCEL) {
            AudiobookExportWorker.cancelTask(context)
        }
    }

    companion object {
        const val ACTION_CANCEL = "my.noveldokusha.action.CANCEL_AUDIOBOOK_EXPORT"
    }
}
