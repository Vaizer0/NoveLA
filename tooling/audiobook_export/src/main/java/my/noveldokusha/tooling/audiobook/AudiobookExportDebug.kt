package my.noveldokusha.tooling.audiobook

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Простой файловый журнал экспорта аудиокниги.
 *
 * Пишется в `Android/media/<package>/audiobook_export_debug.log`, который
 * доступен извне приложения без root (в отличие от `filesDir` и `logcat`),
 * что позволяет диагностировать проблемы на устройстве пользователя.
 *
 * Журнал никогда не должен ломать сам экспорт, поэтому все операции
 * обёрнуты в [runCatching].
 */
object AudiobookExportDebug {

    private const val FILE_NAME = "audiobook_export_debug.log"
    private const val MAX_BYTES = 512L * 1024L

    @Volatile
    private var file: File? = null

    @Volatile
    private var initialised = false

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        file = runCatching {
            context.getExternalMediaDirs()?.firstOrNull()?.let { dir ->
                dir.mkdirs()
                File(dir, FILE_NAME)
            }
        }.getOrNull()
    }

    fun log(message: String) {
        append("[${timestamp()}] $message")
    }

    fun log(message: String, throwable: Throwable) {
        append("[${timestamp()}] $message\n${throwable.stackTraceToString()}")
    }

    @Synchronized
    private fun append(line: String) {
        val target = file ?: return
        runCatching {
            if (target.length() > MAX_BYTES) target.writeText("")
            target.appendText(line + "\n")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
