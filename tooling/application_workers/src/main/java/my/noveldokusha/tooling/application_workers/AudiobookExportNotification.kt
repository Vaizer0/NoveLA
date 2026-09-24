package my.noveldokusha.tooling.application_workers

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.strings.R as StringsR
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

class AudiobookExportNotification(
    private val bookTitle: String,
    private val context: Context,
) {
    val notificationId: Int = idCounter.getAndIncrement()
    private var builder: NotificationCompat.Builder? = null

    fun showProgress(progress: my.noveldokusha.text_to_speech.AudiobookExportProgress) {
        if (!allowed()) return
        val text = buildProgressText(progress)
        val current = builder
        if (current == null) {
            builder = notifyCenter().showNotification(
                channelId = CHANNEL_ID,
                channelName = context.getString(StringsR.string.book_export_channel_name),
                notificationId = notificationId,
                importance = NotificationManager.IMPORTANCE_LOW,
            ) {
                setContentTitle(bookTitle)
                setContentText(text)
                setProgress(100, progress.percent, false)
                setOngoing(true)
                addCancel(this)
            }
        } else {
            notifyCenter().modifyNotification(current, notificationId) {
                setContentText(text)
                setProgress(100, progress.percent, false)
            }
        }
    }

    fun foregroundNotification(total: Int): Notification =
        notifyCenter().showNotification(
            channelId = CHANNEL_ID,
            channelName = context.getString(StringsR.string.book_export_channel_name),
            notificationId = notificationId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(bookTitle)
            setContentText(context.getString(StringsR.string.audiobook_export_starting))
            setProgress(100, 0, false)
            setOngoing(true)
            addCancel(this)
        }.build()

    fun showComplete(name: String) {
        if (!allowed()) return
        notifyCenter().showNotification(
            channelId = CHANNEL_ID,
            channelName = context.getString(StringsR.string.book_export_channel_name),
            notificationId = idCounter.getAndIncrement(),
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(bookTitle)
            setContentText(context.getString(StringsR.string.book_export_complete, name))
            setAutoCancel(true)
        }
    }

    fun showError(message: String) {
        if (!allowed()) return
        notifyCenter().showNotification(
            channelId = CHANNEL_ID,
            channelName = context.getString(StringsR.string.book_export_channel_name),
            notificationId = notificationId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(bookTitle)
            setContentText(message)
            setAutoCancel(true)
        }
    }

    fun close() {
        notifyCenter().close(notificationId)
        builder = null
    }

    private fun buildProgressText(progress: my.noveldokusha.text_to_speech.AudiobookExportProgress): String {
        val eta = progress.estimatedRemainingMs?.let(::formatDuration)
            ?: "calculating…"
        val generated = formatDuration(progress.generatedAudioMs)
        return context.getString(
            StringsR.string.audiobook_export_progress,
            progress.percent,
            progress.currentChapter,
            progress.totalChapters,
            progress.chapterTitle,
            generated,
            eta,
        )
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> "%dh %02dm".format(hours, minutes)
            minutes > 0L -> "%dm %02ds".format(minutes, seconds)
            else -> "%ds".format(seconds)
        }
    }

    private fun addCancel(builder: NotificationCompat.Builder) {
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(StringsR.string.book_export_cancel),
            PendingIntent.getBroadcast(
                context,
                notificationId,
                android.content.Intent(context, AudiobookExportNotificationReceiver::class.java).apply {
                    action = AudiobookExportNotificationReceiver.ACTION_CANCEL
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Timber.w("POST_NOTIFICATIONS denied; skipping audiobook notification")
            return false
        }
        return true
    }

    private fun notifyCenter(): NotificationsCenter {
        return NotificationsHolder.get(context)
    }

    companion object {
        const val CHANNEL_ID = "audiobook_export"
        private val idCounter = AtomicInteger(4000)
    }
}

private object NotificationsHolder {
    @Volatile
    private var instance: NotificationsCenter? = null

    fun get(context: Context): NotificationsCenter {
        return instance ?: synchronized(this) {
            instance ?: NotificationsCenter(context.applicationContext).also { instance = it }
        }
    }
}
