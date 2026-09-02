package my.noveldokusha.video_export

import org.json.JSONObject

/** Вертикальное выравнивание боковой картинки в канвасе 1920×1080. */
enum class ArtworkVerticalAlignment { TOP, CENTER, BOTTOM }

/** Как вписать исходную картинку в прямоугольник арт-зоны. */
enum class ArtworkFitMode {
    /** Заполнить прямоугольник (допустима обрезка краёв). */
    COVER,
    /** Вписать целиком (допустимы пустые поля по одной оси). */
    CONTAIN,
}

/**
 * Боковая иллюстрация видео (left/right). «Лёгкое» описание: хранит только
 * имя файла (ссылку на уже импортированную в приложение картинку) и
 * настройки — байты изображения НЕ дублируются в настройках/слепке.
 *
 * Геометрия детерминирована: прямоугольник занимает
 * [widthFraction] * 1920 по X от края канваса, по Y центрируется в канвасе с
 * высотой, ограниченной [heightCapFraction]. Текстовая колонка при этом
 * автоматически сдвигается вправо/влево (safe region), поэтому картинка
 * никогда не перекрывает текст без явного overlay-режима.
 */
data class VideoArtwork(
    val fileName: String,
    /** Доля ширины канваса, занимаемая по оси X (0..1; дефолт 0.12). */
    val widthFraction: Float,
    /** Ограничение высоты как доля высоты канваса (0..1; дефолт 1.0). */
    val heightCapFraction: Float,
    val verticalAlignment: ArtworkVerticalAlignment,
    /** Непрозрачность арта 0..1. */
    val opacity: Float,
    val fitMode: ArtworkFitMode,
    val cornerRadius: Float,
    val borderWidth: Float,
    val borderColorArgb: Int,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_FILE, fileName)
        put(KEY_WIDTH_FRACTION, widthFraction.toDouble())
        put(KEY_HEIGHT_CAP, heightCapFraction.toDouble())
        put(KEY_ALIGNMENT, verticalAlignment.name)
        put(KEY_OPACITY, opacity.toDouble())
        put(KEY_FIT, fitMode.name)
        put(KEY_RADIUS, cornerRadius.toDouble())
        put(KEY_BORDER, borderWidth.toDouble())
        put(KEY_BORDER_COLOR, borderColorArgb)
    }

    companion object {
        private const val KEY_FILE = "fileName"
        private const val KEY_WIDTH_FRACTION = "widthFraction"
        private const val KEY_HEIGHT_CAP = "heightCapFraction"
        private const val KEY_ALIGNMENT = "verticalAlignment"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_FIT = "fitMode"
        private const val KEY_RADIUS = "cornerRadius"
        private const val KEY_BORDER = "borderWidth"
        private const val KEY_BORDER_COLOR = "borderColorArgb"

        /** Зазор между артом и текстовой колонкой (px). */
        const val GAP_PX = 24f
        /** По умолчанию арт не должен забирать больше 30% ширины канваса с одной стороны. */
        const val MAX_WIDTH_FRACTION = 0.3f

        // Дефолты параметров активного арта.
        internal val DEFAULTS = ArtworkDefaults()

        class ArtworkDefaults(
            val widthFraction: Float = 0.12f,
            val heightCapFraction: Float = 1f,
            val opacity: Float = 1f,
            val cornerRadius: Float = 0f,
            val borderWidth: Float = 0f,
            val borderColorArgb: Int = 0x40FFFFFF.toInt(),
        )

        fun fromJson(obj: JSONObject?): VideoArtwork? {
            if (obj == null) return null
            fun optFloat(key: String, def: Float): Float =
                obj.optDouble(key, def.toDouble()).toFloat()
            val file = obj.optString(KEY_FILE)
            if (file.isBlank()) return null
            val alignment = runCatching {
                ArtworkVerticalAlignment.valueOf(obj.getString(KEY_ALIGNMENT))
            }.getOrDefault(ArtworkVerticalAlignment.CENTER)
            val fit = runCatching {
                ArtworkFitMode.valueOf(obj.getString(KEY_FIT))
            }.getOrDefault(ArtworkFitMode.COVER)
            return VideoArtwork(
                fileName = file,
                widthFraction = optFloat(KEY_WIDTH_FRACTION, DEFAULTS.widthFraction),
                heightCapFraction = optFloat(KEY_HEIGHT_CAP, DEFAULTS.heightCapFraction),
                verticalAlignment = alignment,
                opacity = optFloat(KEY_OPACITY, DEFAULTS.opacity),
                fitMode = fit,
                cornerRadius = optFloat(KEY_RADIUS, DEFAULTS.cornerRadius),
                borderWidth = optFloat(KEY_BORDER, DEFAULTS.borderWidth),
                borderColorArgb = obj.optInt(KEY_BORDER_COLOR, DEFAULTS.borderColorArgb),
            )
        }
    }
}

/** Редактируемая (nullable) версия настроек арта для [VideoStyleSettings]. */
data class VideoArtworkSettings(
    /** Имя импортированного изображения; null/пусто — арт выключен. */
    val fileName: String? = null,
    /** Доля ширины канваса по оси X (null → 0.12). */
    val widthFraction: Float? = null,
    /** Ограничение высоты как доля высоты (null → 1.0). */
    val heightCapFraction: Float? = null,
    val verticalAlignment: ArtworkVerticalAlignment? = null,
    /** Непрозрачность 0..1 (null → 1). */
    val opacity: Float? = null,
    val fitMode: ArtworkFitMode? = null,
    val cornerRadius: Float? = null,
    val borderWidth: Float? = null,
    val borderColorArgb: Int? = null,
)