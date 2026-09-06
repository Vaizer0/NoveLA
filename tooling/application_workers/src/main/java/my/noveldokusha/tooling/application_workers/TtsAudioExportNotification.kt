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
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground notification for chapter TTS export + cinematic video generation.
 *
 * Lifecycle: foregroundNotification (foreground) -> updateProgress ->
 * showComplete / showError -> close. Each instance has its own notification id.
 */
class TtsAudioExportNotification(
    private val chapterTitle: String,
    private val workRequestId: String,
    private val context: Context,
    private val notificationsCenter: NotificationsCenter,
) {
    val notificationId: Int = idCounter.getAndIncrement()

    private var builder: NotificationCompat.Builder? = null
    private var lastPhase: String = "AUDIO"
    private var lastPercent: Int = -1
    private var lastUpdateMs: Long = 0L

    private val channelName = context.getString(StringsR.string.tts_audio_export_channel_name)

    /** Foreground notification shown immediately, with a cancel action for this WorkRequest. */
    fun foregroundNotification(): Notification {
        val notificationBuilder = notificationsCenter.showNotification(
            channelId = CHANNEL_ID,
            channelName = channelName,
            notificationId = notificationId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(context.getString(StringsR.string.tts_audio_export_running, chapterTitle))
            setContentText(stageText("AUDIO", 0))
            setOngoing(true)
            setProgress(100, 0, false)
            addCancelAction()
        }
        builder = notificationBuilder
        lastPhase = "AUDIO"
        lastPercent = 0
        lastUpdateMs = 0L
        return notificationBuilder.build()
    }

    /**
     * Updates the foreground notification progress and explicitly identifies the active stage.
     * Updates are throttled unless the stage or displayed percentage changes.
     */
    fun updateProgress(percent: Int, phase: String = lastPhase) {
        if (!hasNotificationPermission()) return
        val current = builder ?: return
        val safePercent = percent.coerceIn(0, 100)
        val safePhase = phase.uppercase()
        val now = android.os.SystemClock.elapsedRealtime()
        val phaseChanged = safePhase != lastPhase
        val percentChanged = safePercent != lastPercent
        if (!phaseChanged && !percentChanged) return
        if (!phaseChanged && safePercent != 100 && now - lastUpdateMs < 500L) return

        lastPhase = safePhase
        lastPercent = safePercent
        lastUpdateMs = now
        notificationsCenter.modifyNotification(current, notificationId) {
            setProgress(100, safePercent, false)
            setContentText(stageText(safePhase, safePercent))
            setOngoing(safePercent < 100)
        }
    }

    fun showComplete(displayName: String, uri: Uri?) {
        if (!hasNotificationPermission()) return
        val completeId = completeIdCounter.getAndIncrement()
        builder = notificationsCenter.showNotification(
            channelId = CHANNEL_ID,
            channelName = channelName,
            notificationId = completeId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(context.getString(StringsR.string.tts_audio_export_complete, chapterTitle))
            setContentText(displayName)
            setOngoing(false)
            setAutoCancel(true)
            if (uri != null) {
                buildOpenContentIntent(uri, completeId)?.let { setContentIntent(it) }
                addOpenAction(uri, completeId)
                addDeleteAction(uri, completeId)
            }
        }
    }

    fun showError(message: String) {
        if (!hasNotificationPermission()) return
        builder = notificationsCenter.showNotification(
            channelId = CHANNEL_ID,
            channelName = channelName,
            notificationId = notificationId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(context.getString(StringsR.string.tts_audio_export_failed, chapterTitle))
            setContentText(message)
            setOngoing(false)
            setAutoCancel(true)
        }
    }

    fun close() {
        notificationsCenter.close(notificationId)
        builder = null
    }

    private fun stageText(phase: String, percent: Int): String = when (phase) {
        "VIDEO" -> "Generating video • $percent%"
        else -> "Generating audio • $percent%"
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val result = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (result != PackageManager.PERMISSION_GRANTED) {
            Timber.w("POST_NOTIFICATIONS denied, skipping TTS audio notification")
            return false
        }
        return true
    }

    private fun NotificationCompat.Builder.addCancelAction() {
        addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(StringsR.string.tts_audio_export_cancel),
            PendingIntent.getBroadcast(
                context,
                notificationId,
                Intent(context, TtsAudioNotificationReceiver::class.java).apply {
                    action = TtsAudioNotificationReceiver.ACTION_CANCEL
                    putExtra(TtsAudioNotificationReceiver.EXTRA_WORK_REQUEST_ID, workRequestId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    private fun buildOpenContentIntent(uri: Uri, actionId: Int): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            context.applicationContext,
            actionId,
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    private fun NotificationCompat.Builder.addOpenAction(uri: Uri, actionId: Int) {
        addAction(
            android.R.drawable.ic_menu_view,
            context.getString(StringsR.string.tts_audio_export_open),
            PendingIntent.getBroadcast(
                context,
                actionId,
                Intent(context, TtsAudioNotificationReceiver::class.java).apply {
                    action = TtsAudioNotificationReceiver.ACTION_OPEN
                    putExtra(TtsAudioNotificationReceiver.EXTRA_URI, uri.toString())
                    putExtra(TtsAudioNotificationReceiver.EXTRA_NOTIFICATION_ID, actionId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    private fun NotificationCompat.Builder.addDeleteAction(uri: Uri, actionId: Int) {
        addAction(
            android.R.drawable.ic_menu_delete,
            context.getString(StringsR.string.tts_audio_export_delete),
            PendingIntent.getBroadcast(
                context,
                actionId,
                Intent(context, TtsAudioNotificationReceiver::class.java).apply {
                    action = TtsAudioNotificationReceiver.ACTION_DELETE
                    putExtra(TtsAudioNotificationReceiver.EXTRA_URI, uri.toString())
                    putExtra(TtsAudioNotificationReceiver.EXTRA_NOTIFICATION_ID, actionId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    companion object {
        const val CHANNEL_ID = "tts_audio_export"
        const val MIME_TYPE = "audio/wav"

        private val idCounter = AtomicInteger(4000)
        private val completeIdCounter = AtomicInteger(5000)
    }
}
