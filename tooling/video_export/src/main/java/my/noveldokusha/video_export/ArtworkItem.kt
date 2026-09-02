package my.noveldokusha.video_export

import org.json.JSONArray
import org.json.JSONObject

/**
 * Слайдовая иллюстрация на весь кадр (Phase G). Отличается от боковой
 * [VideoArtwork]: занимает значимую площадь кадра (по умолчанию половину
 * высоты), центрируется и не сдвигает текстовую колонку — текст по-прежнему
 * ложится по безопасной области (сверху и снизу / между слайдов).
 *
 * «Лёгкая» модель: хранит только имя файла (ссылку на импортированную
 * картинку) и настройки показа; байты изображения не дублируются.
 */
data class ArtworkItem(
    /** Стабильный идентификатор слота в списке (не путать с fileName). */
    val stableId: String,
    val fileName: String,
    /** Слот участвует в показе (false — пропускается при планировании). */
    val enabled: Boolean,
    /** Позиция в кадре (низ слайда в долях высоты канваса 0..1). */
    val position: Float,
    /** Доля ширины канваса, занимаемая слайдом (0..1). */
    val size: Float,
    /** Непрозрачность слайда 0..1. */
    val opacity: Float,
    /** Как вписать исходную картинку в прямоугольник слайда. */
    val cropMode: ArtworkFitMode,
    val cornerRadius: Float,
    /** Толщина рамки слайда (px; 0 — без рамки). */
    val borderWidth: Float,
    val borderColorArgb: Int,
    val shadow: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, stableId)
        put(KEY_FILE, fileName)
        put(KEY_ENABLED, enabled)
        put(KEY_POSITION, position.toDouble())
        put(KEY_SIZE, size.toDouble())
        put(KEY_OPACITY, opacity.toDouble())
        put(KEY_CROP, cropMode.name)
        put(KEY_RADIUS, cornerRadius.toDouble())
        put(KEY_BORDER, borderWidth.toDouble())
        put(KEY_BORDER_COLOR, borderColorArgb)
        put(KEY_SHADOW, shadow)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_FILE = "fileName"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_POSITION = "position"
        private const val KEY_SIZE = "size"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_CROP = "cropMode"
        private const val KEY_RADIUS = "cornerRadius"
        private const val KEY_BORDER = "borderWidth"
        private const val KEY_BORDER_COLOR = "borderColorArgb"
        private const val KEY_SHADOW = "shadow"

        /** Дефолты активного слайда. */
        internal val DEFAULTS = SlideDefaults()

        class SlideDefaults(
            val position: Float = 0.75f,
            val size: Float = 0.5f,
            val opacity: Float = 0.9f,
            val cornerRadius: Float = 16f,
            val borderWidth: Float = 2f,
            val borderColorArgb: Int = 0x60FFFFFF.toInt(),
            val shadow: Boolean = true,
        )

        fun fromJson(obj: JSONObject?): ArtworkItem? {
            if (obj == null) return null
            fun optFloat(key: String, def: Float): Float =
                obj.optDouble(key, def.toDouble()).toFloat()
            val id = obj.optString(KEY_ID)
            val file = obj.optString(KEY_FILE)
            if (file.isBlank()) return null
            val crop = runCatching {
                ArtworkFitMode.valueOf(obj.getString(KEY_CROP))
            }.getOrDefault(ArtworkFitMode.COVER)
            return ArtworkItem(
                stableId = id.takeIf { it.isNotBlank() } ?: file,
                fileName = file,
                enabled = obj.optBoolean(KEY_ENABLED, true),
                position = optFloat(KEY_POSITION, DEFAULTS.position).coerceIn(0.2f, 1f),
                size = optFloat(KEY_SIZE, DEFAULTS.size).coerceIn(0.1f, 1.2f),
                opacity = optFloat(KEY_OPACITY, DEFAULTS.opacity).coerceIn(0f, 1f),
                cropMode = crop,
                cornerRadius = optFloat(KEY_RADIUS, DEFAULTS.cornerRadius).coerceIn(0f, 200f),
                borderWidth = optFloat(KEY_BORDER, DEFAULTS.borderWidth).coerceIn(0f, 20f),
                borderColorArgb = obj.optInt(KEY_BORDER_COLOR, DEFAULTS.borderColorArgb),
                shadow = obj.optBoolean(KEY_SHADOW, DEFAULTS.shadow),
            )
        }
    }
}

/** Список слайдов проще хранить/передавать массивом JSON. */
fun List<ArtworkItem>.toJsonArray(): JSONArray = JSONArray().apply {
    forEach { put(it.toJson()) }
}

/** Десериализует список слайдов; null/пусто — слайдшоу без изображений. */
fun fromArtworkJsonArray(arr: JSONArray?): List<ArtworkItem> {
    if (arr == null) return emptyList()
    val out = mutableListOf<ArtworkItem>()
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.let { ArtworkItem.fromJson(it)?.let { out += it } }
    }
    return out
}
