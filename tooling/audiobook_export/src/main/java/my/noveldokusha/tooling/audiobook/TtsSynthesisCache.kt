package my.noveldokusha.tooling.audiobook

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Постоянный кэш синтезированного PCM (16-битный WAV), ключ — точные входные
 * параметры синтеза (движок, голос, скорость, тон, текст).
 *
 * Android TTS-движок недетерминирован: один и тот же текст в разных запусках
 * даёт аудио разной длины (сетевые голоса, обновление модели). Из-за этого
 * WAV- и MP4-экспорт одного и того же текста раньше расходились по
 * длительности, хотя синтез вызывался одинаковое число раз. Кэш делает
 * повторную озвучку байт-в-байт той же, убирает дублирующий синтез в
 * повторных экспортах и заметно ускоряет MP4 после WAV.
 *
 * Файлы лежат в приватном `filesDir` приложения: переживают очистку кэша,
 * но удаляются вместе с приложением. Размер ограничен [maxBytes] с
 * вытеснением самых старых записей (LRU по времени последнего доступа).
 */
class TtsSynthesisCache(context: Context) {

    private val root = File(context.filesDir, DIR_NAME)

    /** Текущий суммарный размер кэша; -1 — ещё не посчитан. */
    private var totalBytes: Long = -1L

    /**
     * Возвращает кэшированный WAV для [key] или `null`, если записи нет.
     * Помечает запись как недавно использованную для LRU-вытеснения.
     */
    fun get(key: String): File? {
        val file = File(root, "$key.wav")
        if (!file.isFile || file.length() <= WAV_HEADER_BYTES) return null
        if (!WavAudio.isRiffWav(file) || !WavAudio.isCompleteWav(file)) {
            // Оборванная/битая запись (в том числе оставшаяся от старой версии)
            // не должна попасть в мердж: удаляем её, следующий синтез перезапишет.
            val size = file.length()
            if (file.delete() && totalBytes >= 0L) {
                totalBytes = (totalBytes - size).coerceAtLeast(0L)
            }
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /**
     * Сохраняет копию [source] под [key], после чего подрезает кэш до
     * [maxBytes]. Запись всегда делается через временный файл: оборванный
     * экспорт не оставит «полу-WAV», который потом попадёт в мердж.
     *
     * Размер учитывается инкрементально, поэтому на каждую порцию не
     * приходится полный обход каталога.
     */
    fun put(key: String, source: File) {
        // В кэш попадает только целый WAV: оборванный сегмент не должен
        // «заражать» последующие экспорты того же текста.
        if (!WavAudio.isRiffWav(source) || !WavAudio.isCompleteWav(source)) return
        if (!root.exists() && !root.mkdirs()) return
        val target = File(root, "$key.wav")
        val tmp = File(root, "$key.tmp")
        val before = if (totalBytes >= 0L) totalBytes else scanTotal()
        val previousSize = if (target.isFile) target.length() else 0L
        val stored = runCatching {
            source.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.length()
        }.getOrElse {
            runCatching { tmp.delete() }
            return
        }
        totalBytes = before - previousSize + stored
        if (totalBytes > maxBytes) trim()
    }

    /** Суммарный размер всех WAV-записей кэша. */
    private fun scanTotal(): Long =
        root.listFiles { file -> file.isFile && file.name.endsWith(WAV_SUFFIX) }
            ?.sumOf { it.length() }
            ?: 0L

    /** Удаляет самые старые записи, пока суммарный размер не уложится в лимит. */
    private fun trim() {
        val files = root.listFiles { file -> file.isFile && file.name.endsWith(WAV_SUFFIX) } ?: return
        if (files.isEmpty()) {
            totalBytes = 0L
            return
        }
        var total = 0L
        for (f in files) total += f.length()
        // Sort by last modified (LRU)
        val list = files.sortedBy { it.lastModified() }
        for (file in list) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
        totalBytes = total
    }

    internal companion object {
        const val DIR_NAME = "audiobook_tts_cache"
        const val WAV_HEADER_BYTES = 44L
        const val WAV_SUFFIX = ".wav"

        /**
         * Потолок кэша. Синтез книги на 20 минут — это десятки мегабайт
         * PCM, поэтому 512 МБ хватает на несколько книг, не раздувая
         * хранилище приложения.
         */
        val maxBytes: Long = 512L * 1024L * 1024L

        private const val CACHE_VERSION = "v2"

        /**
         * Стабильный SHA-256 по всем параметрам, влияющим на аудио.
         * Разделитель `\u0000` исключает склейку разных полей в один ключ.
         */
        fun key(
            enginePackage: String,
            voiceId: String,
            speed: Float,
            pitch: Float,
            text: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun feed(value: String) {
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
            feed(CACHE_VERSION)
            feed(enginePackage)
            feed(voiceId)
            feed(java.lang.Float.floatToIntBits(speed).toString())
            feed(java.lang.Float.floatToIntBits(pitch).toString())
            feed(text)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
