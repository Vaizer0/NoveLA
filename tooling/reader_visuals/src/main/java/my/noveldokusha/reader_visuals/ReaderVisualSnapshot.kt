package my.noveldokusha.reader_visuals

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Замороженный слепок визуальных настроек читалки на момент постановки экспорта
 * видео в очередь. Сериализуется в JSON один раз при enqueue и кладётся в
 * WorkRequest inputData; воркер ТОЛЬКО десериализует и потребляет его.
 *
 * Изменение настроек читалки после enqueue НЕ влияет на поставленные задачи:
 * здесь зафиксированы шрифт/типографика/цвет текста/фон/цвет подсветки и
 * производный базовый размер шрифта для видео.
 *
 * [textColorArgb] == null означает «Авто»: воркер выводит цвет текста из
 * фактического рендер-фона (пресет/плоский/картинка) детерминированным способом.
 */
data class ReaderVisualSnapshot(
    val fontFamily: String,
    val fontSizeSp: Float,
    val lineHeight: Float,
    val letterSpacing: Float,
    val paragraphSpacing: Float,
    val textColorArgb: Int?,
    val backgroundType: BackgroundType,
    val presetId: String,
    val presetColorsArgb: List<Int>,
    val backgroundFileName: String,
    val ttsHighlightColorArgb: Int,
    val derivedBaseFontPx: Float,
) {

    fun toJson(): String = JSONObject().apply {
        put(KEY_FONT_FAMILY, fontFamily)
        put(KEY_FONT_SIZE, fontSizeSp.toDouble())
        put(KEY_LINE_HEIGHT, lineHeight.toDouble())
        put(KEY_LETTER_SPACING, letterSpacing.toDouble())
        put(KEY_PARAGRAPH_SPACING, paragraphSpacing.toDouble())
        put(KEY_TEXT_COLOR, textColorArgb ?: JSONObject.NULL)
        put(KEY_BACKGROUND_TYPE, backgroundType.name)
        put(KEY_PRESET_ID, presetId)
        put(
            KEY_PRESET_COLORS,
            JSONArray().apply { presetColorsArgb.forEach { put(it) } }
        )
        put(KEY_BACKGROUND_FILE, backgroundFileName)
        put(KEY_HIGHLIGHT_COLOR, ttsHighlightColorArgb)
        put(KEY_BASE_FONT_PX, derivedBaseFontPx.toDouble())
    }.toString()

    /** Рендер-ветка фона для этого слепока (файл резолвится на рендере). */
    fun backgroundLayer(fileResolver: (String) -> File?): BackgroundLayer = when (backgroundType) {
        BackgroundType.NONE -> BackgroundLayer.None
        BackgroundType.PRESET ->
            ReaderBackgroundPresets.firstOrNull { it.id == presetId }
                ?.let { BackgroundLayer.Preset(it) }
                ?: flatFallbackLayer()
        BackgroundType.IMAGE ->
            fileResolver(backgroundFileName)
                ?.let { BackgroundLayer.Image(it) }
                ?: flatFallbackLayer()
    }

    private fun flatFallbackLayer(): BackgroundLayer = BackgroundLayer.None

    companion object {
        private const val KEY_FONT_FAMILY = "fontFamily"
        private const val KEY_FONT_SIZE = "fontSizeSp"
        private const val KEY_LINE_HEIGHT = "lineHeight"
        private const val KEY_LETTER_SPACING = "letterSpacing"
        private const val KEY_PARAGRAPH_SPACING = "paragraphSpacing"
        private const val KEY_TEXT_COLOR = "textColorArgb"
        private const val KEY_BACKGROUND_TYPE = "backgroundType"
        private const val KEY_PRESET_ID = "presetId"
        private const val KEY_PRESET_COLORS = "presetColorsArgb"
        private const val KEY_BACKGROUND_FILE = "backgroundFileName"
        private const val KEY_HIGHLIGHT_COLOR = "ttsHighlightColorArgb"
        private const val KEY_BASE_FONT_PX = "derivedBaseFontPx"

        /** Базовый размер шрифта видео: сохраняет пропорции читалки при 1080p. */
        fun computeBaseFontPx(fontSizeSp: Float): Float = (fontSizeSp / 14f) * 62f

        fun fromJson(json: String): ReaderVisualSnapshot {
            val obj = JSONObject(json)
            val backgroundType = runCatching {
                BackgroundType.valueOf(obj.getString(KEY_BACKGROUND_TYPE))
            }.getOrDefault(BackgroundType.NONE)

            val colorsJson = obj.optJSONArray(KEY_PRESET_COLORS)
            val presetColors = buildList {
                if (colorsJson != null) {
                    for (i in 0 until colorsJson.length()) add(colorsJson.getInt(i))
                }
            }

            return ReaderVisualSnapshot(
                fontFamily = obj.getString(KEY_FONT_FAMILY),
                fontSizeSp = obj.getDouble(KEY_FONT_SIZE).toFloat(),
                lineHeight = obj.getDouble(KEY_LINE_HEIGHT).toFloat(),
                letterSpacing = obj.getDouble(KEY_LETTER_SPACING).toFloat(),
                paragraphSpacing = obj.getDouble(KEY_PARAGRAPH_SPACING).toFloat(),
                textColorArgb = if (obj.isNull(KEY_TEXT_COLOR)) null else obj.getInt(KEY_TEXT_COLOR),
                backgroundType = backgroundType,
                presetId = obj.optString(KEY_PRESET_ID, ""),
                presetColorsArgb = presetColors,
                backgroundFileName = obj.optString(KEY_BACKGROUND_FILE, ""),
                ttsHighlightColorArgb = obj.getInt(KEY_HIGHLIGHT_COLOR),
                derivedBaseFontPx = obj.getDouble(KEY_BASE_FONT_PX).toFloat(),
            )
        }

        /**
         * Детерминированный автоматический цвет текста: тёмный на светлом,
         * светлый на тёмном, по средней яркости фоновых стопов/картинки.
         * Принимает средний ARGB цвета фона.
         */
        fun autoTextColorForLuminance(backgroundArgb: Int): Int {
            val r = (backgroundArgb shr 16) and 0xFF
            val g = (backgroundArgb shr 8) and 0xFF
            val b = backgroundArgb and 0xFF
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
            return if (lum > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        /** Средний ARGB по списку цветов (для оценки освещённости фона). */
        fun averageArgb(colors: List<Int>): Int {
            if (colors.isEmpty()) return 0xFFFFFFFF.toInt()
            var r = 0L; var g = 0L; var b = 0L
            for (c in colors) {
                r += ((c shr 16) and 0xFF).toLong()
                g += ((c shr 8) and 0xFF).toLong()
                b += (c and 0xFF).toLong()
            }
            val n = colors.size
            val a = 0xFFL shl 24
            val rr = (((r / n) and 0xFF) shl 16).toLong()
            val gg = (((g / n) and 0xFF) shl 8).toLong()
            val bb = ((b / n) and 0xFF).toLong()
            return (a or rr or gg or bb).toInt()
        }
    }
}

enum class BackgroundType {
    NONE,
    PRESET,
    IMAGE,
}