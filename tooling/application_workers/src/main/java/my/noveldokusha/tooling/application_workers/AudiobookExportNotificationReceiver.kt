package my.noveldokusha.tooling.application_workers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Обрабатывает нажатие «Отмена» в уведомлении экспорта аудиокниги.
 */
class AudiobookExportNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CANCEL_EXPORT) {
            AudiobookExportWorker.cancelTask(context)
        }
    }

    companion object {
        const val ACTION_CANCEL_EXPORT = "my.noveldokusha.action.CANCEL_AUDIOBOOK_EXPORT"
    }
}
