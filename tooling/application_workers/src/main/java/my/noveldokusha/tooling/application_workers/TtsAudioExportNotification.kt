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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import my.noveldokusha.coreui.states.NotificationsCenter
import my.noveldokusha.strings.R as StringsR
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/** Foreground/progress notifications for the independent AUDIO and VIDEO stages. */
class TtsAudioExportNotification(
    private val chapterTitle: String,
    private val workRequestId: String,
    private val context: Context,
    private val notificationsCenter: NotificationsCenter,
) {
    val notificationId: Int = idCounter.getAndIncrement()
    private var builder: NotificationCompat.Builder? = null
    private var lastPhase = "AUDIO"
    private var lastPercent = -1
    private var lastUpdateMs = 0L
    private val channelName = context.getString(StringsR.string.tts_audio_export_channel_name)

    fun foregroundNotification(): Notification = buildForeground("AUDIO", true)

    fun videoForegroundNotification(): Notification = buildForeground("VIDEO", false)

    private fun buildForeground(phase: String, cancellable: Boolean): Notification {
        val b = notificationsCenter.showNotification(
            channelId = CHANNEL_ID,
            channelName = channelName,
            notificationId = notificationId,
            importance = NotificationManager.IMPORTANCE_LOW,
        ) {
            setContentTitle(context.getString(StringsR.string.tts_audio_export_running, chapterTitle))
            setContentText(stageText(phase, 0))
            setOngoing(true)
            setProgress(100, 0, false)
            if (cancellable) addCancelAction()
        }
        builder = b
        lastPhase = phase
        lastPercent = 0
        lastUpdateMs = 0L
        return b.build()
    }

    /** Progress is always stage-local: AUDIO 0..100, VIDEO 0..100. */
    fun updateProgress(percent: Int, phase: String = lastPhase) {
        if (!hasNotificationPermission()) return
        val current = builder ?: return
        val p = percent.coerceIn(0, 100)
        val ph = phase.uppercase()
        val now = android.os.SystemClock.elapsedRealtime()
        if (ph == lastPhase && p == lastPercent) return
        if (ph == lastPhase && p != 100 && now - lastUpdateMs < 350L) return
        lastPhase = ph; lastPercent = p; lastUpdateMs = now
        notificationsCenter.modifyNotification(current, notificationId) {
            setProgress(100, p, false)
            setContentText(stageText(ph, p))
            setOngoing(p < 100)
        }
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, current.build()) }
            .onFailure { Timber.w(it, "TtsAudio: notification update failed") }
    }

    fun showComplete(displayName: String, uri: Uri?) {
        if (!hasNotificationPermission()) return
        val id = completeIdCounter.getAndIncrement()
        val mime = if (displayName.endsWith(".mp4", true)) MIME_VIDEO else MIME_AUDIO
        notificationsCenter.showNotification(channelId = CHANNEL_ID, channelName = channelName, notificationId = id, importance = NotificationManager.IMPORTANCE_LOW) {
            setContentTitle(context.getString(StringsR.string.tts_audio_export_complete, chapterTitle))
            setContentText(displayName)
            setAutoCancel(true)
            setOngoing(false)
            if (uri != null) {
                buildOpenContentIntent(uri, id, mime)?.let { setContentIntent(it) }
                addOpenAction(uri, id, mime)
                addDeleteAction(uri, id)
            }
        }
    }

    fun showError(message: String) {
        if (!hasNotificationPermission()) return
        notificationsCenter.showNotification(channelId = CHANNEL_ID, channelName = channelName, notificationId = notificationId, importance = NotificationManager.IMPORTANCE_LOW) {
            setContentTitle(context.getString(StringsR.string.tts_audio_export_failed, chapterTitle))
            setContentText(message)
            setAutoCancel(true)
            setOngoing(false)
        }
    }

    fun close() { notificationsCenter.close(notificationId); builder = null }

    private fun stageText(phase: String, percent: Int) = if (phase == "VIDEO") "Generating video • $percent%" else "Generating audio • $percent%"

    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun NotificationCompat.Builder.addCancelAction() {
        addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(StringsR.string.tts_audio_export_cancel), PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, TtsAudioNotificationReceiver::class.java).apply {
                action = TtsAudioNotificationReceiver.ACTION_CANCEL
                putExtra(TtsAudioNotificationReceiver.EXTRA_WORK_REQUEST_ID, workRequestId)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    private fun buildOpenContentIntent(uri: Uri, actionId: Int, mimeType: String): PendingIntent? = runCatching {
        PendingIntent.getActivity(context.applicationContext, actionId, Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }.getOrNull()

    private fun NotificationCompat.Builder.addOpenAction(uri: Uri, actionId: Int, mimeType: String) {
        addAction(android.R.drawable.ic_menu_view, context.getString(StringsR.string.tts_audio_export_open), PendingIntent.getBroadcast(context, actionId, Intent(context, TtsAudioNotificationReceiver::class.java).apply {
            action = TtsAudioNotificationReceiver.ACTION_OPEN
            putExtra(TtsAudioNotificationReceiver.EXTRA_URI, uri.toString())
            putExtra(TtsAudioNotificationReceiver.EXTRA_MIME_TYPE, mimeType)
            putExtra(TtsAudioNotificationReceiver.EXTRA_NOTIFICATION_ID, actionId)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    private fun NotificationCompat.Builder.addDeleteAction(uri: Uri, actionId: Int) {
        addAction(android.R.drawable.ic_menu_delete, context.getString(StringsR.string.tts_audio_export_delete), PendingIntent.getBroadcast(context, actionId, Intent(context, TtsAudioNotificationReceiver::class.java).apply {
            action = TtsAudioNotificationReceiver.ACTION_DELETE
            putExtra(TtsAudioNotificationReceiver.EXTRA_URI, uri.toString())
            putExtra(TtsAudioNotificationReceiver.EXTRA_NOTIFICATION_ID, actionId)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    companion object {
        const val CHANNEL_ID = "tts_audio_export"
        const val MIME_TYPE = MIME_AUDIO
        const val MIME_AUDIO = "audio/wav"
        const val MIME_VIDEO = "video/mp4"
        private val idCounter = AtomicInteger(4000)
        private val completeIdCounter = AtomicInteger(5000)
    }
}
