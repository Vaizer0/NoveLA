package my.noveldokusha.tooling.application_workers

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.strings.R as StringsR
import my.noveldokusha.tooling.audiobook.AudiobookFormat
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Уведомления прогресса экспорта аудиокниги.
 *
 * Прогресс приходит из [my.noveldokusha.tooling.audiobook.AudiobookExporter]
 * реальными значениями (процент синтеза), а длительность — из фактически
 * записанного аудио. Показывается не чаще раза в секунду (см. воркер).
 */
class AudiobookExportNotification(
    private val bookTitle: String,
    private val context: Context,
    private val notificationsCenter: NotificationsCenter,
    private val format: AudiobookFormat = AudiobookFormat.WAV,
) {
    val notificationId: Int = idCounter.getAndIncrement()

    private var builder: NotificationCompat.Builder? = null

    private val channelName = context.getString(StringsR.string.audiobook_export_channel_name)

    /** «Экспорт видео» для MP4 и «экспорт аудио» для WAV — всегда явно. */
    private fun progressText(percent: Int): String = context.getString(
        if (format == AudiobookFormat.MP4) {
            StringsR.string.audiobook_export_progress_video
        } else {
            StringsR.string.audiobook_export_progress_audio
        },
        percent,
    )

    private fun preparingText(): String = context.getString(
        if (format == AudiobookFormat.MP4) {
            StringsR.string.audiobook_export_preparing_video
        } else {
            StringsR.string.audiobook_export_preparing_audio
        },
    )

    fun showProgress(percent: Int) {
        if (!hasNotificationPermission()) return
        val currentBuilder = builder
        if (currentBuilder == null) {
            builder = notificationsCenter.showNotification(
                channelId = CHANNEL_ID,
                channelName = channelName,
                notificationId = notificationId,
                importance = NotificationManager.IMPORTANCE_LOW,
            ) {
                setContentTitle(bookTitle)
                setContentText(progressText(percent))
                setProgress(100, percent, false)
                setOngoing(true)
                addCancelAction()
            }
            return
        }
        notificationsCenter.modifyNotification(currentBuilder, notificationId) {
            setContentText(progressText(percent))
            setProgress(100, percent, false)
        }
    }

    fun showComplete(displayName: String, uri: Uri?) {
        if (!hasNotificationPermission()) return
        builder = notificationsCenter.showNotification(
            channelId = CHANNEL_ID,
            channelName = channelName,
            notificationId = notificationId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(bookTitle)
            setContentText(context.getString(StringsR.string.audiobook_export_complete, displayName))
            setOngoing(false)
            setAutoCancel(true)
            if (uri != null) {
                buildOpenContentIntent(uri)?.let { setContentIntent(it) }
            }
        }
    }

    fun foregroundNotification(): Notification =
        notificationsCenter.showNotification(
            channelId = CHANNEL_ID,
            channelName = channelName,
            notificationId = notificationId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(bookTitle)
            setContentText(preparingText())
            setOngoing(true)
        }.build()

    fun showError(message: String) {
        if (!hasNotificationPermission()) return
        builder = notificationsCenter.showNotification(
            channelId = CHANNEL_ID,
            channelName = channelName,
            notificationId = notificationId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(bookTitle)
            setContentText(message)
            setOngoing(false)
            setAutoCancel(true)
        }
    }

    fun close() {
        notificationsCenter.close(notificationId)
        builder = null
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("POST_NOTIFICATIONS denied, skipping audiobook export notification")
        }
        return granted
    }

    private fun NotificationCompat.Builder.addCancelAction() {
        addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(StringsR.string.audiobook_export_cancel),
            PendingIntent.getBroadcast(
                context,
                notificationId,
                Intent(context, AudiobookExportNotificationReceiver::class.java).apply {
                    action = AudiobookExportNotificationReceiver.ACTION_CANCEL_EXPORT
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun buildOpenContentIntent(uri: Uri): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            context.applicationContext,
            notificationId,
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    companion object {
        const val CHANNEL_ID = "audiobook_export"

        /** MIME для прослушивания готового файла (WAV и MP4 открываются плеером). */
        const val MIME_TYPE = "audio/*"

        private val idCounter = AtomicInteger(4000)
    }
}
