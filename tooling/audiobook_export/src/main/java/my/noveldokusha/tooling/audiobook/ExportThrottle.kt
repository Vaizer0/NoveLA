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
    private val policy = ThermalThrottlePolicy()

    override suspend fun beforeChunk() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = powerManager ?: return
        val delayMs = policy.delayFor(manager.currentThermalStatus)
        if (delayMs > 0L) delay(delayMs)
    }
}

/** Уровень нагрева 0..5 (0 — троттлинг не нужен). */
internal fun thermalLevel(status: Int): Int = when (status) {
    PowerManager.THERMAL_STATUS_NONE,
    PowerManager.THERMAL_STATUS_LIGHT,
    -> 0

    PowerManager.THERMAL_STATUS_MODERATE -> 1
    PowerManager.THERMAL_STATUS_SEVERE -> 2
    PowerManager.THERMAL_STATUS_CRITICAL -> 3
    PowerManager.THERMAL_STATUS_EMERGENCY -> 4
    PowerManager.THERMAL_STATUS_SHUTDOWN -> 5
    else -> 0
}

/** Пауза в мс для уровня нагрева. */
internal fun thermalLevelDelayMs(level: Int): Long = when (level) {
    1 -> 100L
    2 -> 250L
    3 -> 500L
    4 -> 1_000L
    5 -> 2_000L
    else -> 0L
}

/**
 * Чистая функция «состояние нагрева -> пауза в мс».
 *
 * Константы `PowerManager.THERMAL_STATUS_*` — compile-time константы, поэтому
 * функция безопасно исполняется в JVM-тестах без Android-рантайма.
 */
internal fun thermalDelayMs(status: Int): Long = thermalLevelDelayMs(thermalLevel(status))

/**
 * Гистерезис термического троттлинга.
 *
 * Нагрев реагирует мгновенно (уровень поднимается сразу), а охлаждение —
 * постепенно: уровень опускается на одну ступень только после
 * [coolDownReadings] подряд более холодных замеров. Это не даёт состоянию
 * «дрожать» на границе и не добавляет паузы, пока устройство не нагрелось.
 */
internal class ThermalThrottlePolicy(
    private val coolDownReadings: Int = DEFAULT_COOL_DOWN_READINGS,
) {
    private var level = 0
    private var coolStreak = 0

    /** Возвращает паузу для текущего замера, обновляя внутреннее состояние. */
    fun delayFor(status: Int): Long {
        val target = thermalLevel(status)
        if (target >= level) {
            level = target
            coolStreak = 0
        } else {
            coolStreak++
            if (coolStreak >= coolDownReadings) {
                level = maxOf(target, level - 1)
                coolStreak = 0
            }
        }
        return thermalLevelDelayMs(level)
    }

    companion object {
        const val DEFAULT_COOL_DOWN_READINGS = 5
    }
}
