package my.noveldokusha.features.reader.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import my.noveldokusha.core.utils.isServiceRunning
import javax.inject.Inject


@AndroidEntryPoint
internal class NarratorMediaControlsService : Service() {

    companion object {
        fun start(ctx: Context) {
            if (!isRunning(ctx))
                ContextCompat.startForegroundService(
                    ctx,
                    Intent(ctx, NarratorMediaControlsService::class.java)
                )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NarratorMediaControlsService::class.java))
        }

        private fun isRunning(context: Context): Boolean =
            context.isServiceRunning(NarratorMediaControlsService::class.java)
    }

    @Inject
    lateinit var narratorNotification: NarratorMediaControlsNotification

    override fun onCreate() {
        super.onCreate()

        val notification = narratorNotification.createNotificationMediaControls(this)
        if (notification != null) {
            startForeground(narratorNotification.notificationId, notification)
        } else {
            // Создаем минимальное уведомление, чтобы удовлетворить требования foreground сервиса
            val defaultNotification = narratorNotification.createDefaultNotification(this)
            startForeground(narratorNotification.notificationId, defaultNotification)
        }
    }

    override fun onDestroy() {
        narratorNotification.close()
        super.onDestroy()
    }

    @SuppressLint("MissingSuperCall")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        narratorNotification.handleCommand(intent)
        if (intent == null) return START_NOT_STICKY
        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? = null
}
