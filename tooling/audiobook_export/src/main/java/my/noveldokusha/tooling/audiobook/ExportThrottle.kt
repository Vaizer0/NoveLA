package my.noveldokusha.tooling.audiobook

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * Хук, вызываемый перед синтезом каждой TTS-порции.
 *
 * Единственное назначение — дать устройству остыть. Он не управляет
 * параллелизмом (движок всё равно синтезирует строго последовательно) и
 * не влияет на корректность: задержки имеют право быть нулевыми.
 */
interface ExportThrottle {
    suspend fun beforeChunk()

    companion object {
        /** Заглушка: без пауз. */
        val None: ExportThrottle = object : ExportThrottle {
            override suspend fun beforeChunk() = Unit
        }
    }
}

/**
 * Термическая пауза между порциями.
 *
 * Начиная с API 29 система сообщает о нагреве; на более старых версиях
 * метода нет вовсе, поэтому троттлинг просто выключен. Порог тем выше, чем
 * тяжелее состояние: это защищает от троттлинга SoC и обрыва длинного
 * экспорта, не превращая обычный экспорт в медленный.
 */
class ThermalExportThrottle(context: Context) : ExportThrottle {

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var lastStatus: Int = Int.MIN_VALUE

    override suspend fun beforeChunk() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = powerManager ?: return
        val status = manager.currentThermalStatus
        if (status != lastStatus) {
            lastStatus = status
            AudiobookExportDebug.log("thermal status -> $status")
        }
        val delayMs = thermalDelayMs(status)
        if (delayMs > 0L) delay(delayMs)
    }
}

/**
 * Чистая функция «состояние нагрева -> пауза в мс».
 *
 * Константы `PowerManager.THERMAL_STATUS_*` — compile-time константы, поэтому
 * функция безопасно исполняется в JVM-тестах без Android-рантайма.
 */
internal fun thermalDelayMs(status: Int): Long = when (status) {
    PowerManager.THERMAL_STATUS_NONE,
    PowerManager.THERMAL_STATUS_LIGHT,
    -> 0L

    PowerManager.THERMAL_STATUS_MODERATE -> 100L
    PowerManager.THERMAL_STATUS_SEVERE -> 250L
    PowerManager.THERMAL_STATUS_CRITICAL -> 500L
    PowerManager.THERMAL_STATUS_EMERGENCY -> 1_000L
    PowerManager.THERMAL_STATUS_SHUTDOWN -> 2_000L
    else -> 0L
}
