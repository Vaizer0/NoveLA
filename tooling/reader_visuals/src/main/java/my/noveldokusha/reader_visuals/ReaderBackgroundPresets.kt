package my.noveldokusha.reader_visuals

import java.io.File

/**
 * Пресет фона читалки/видео — процедурный вертикальный градиент (top→bottom),
 * заданный кодом, без растровых ресурсов. Список цветов читается сверху вниз.
 * [textColor] — ARGB-hex (без "#") цвет текста, подобранный под контраст фона.
 *
 * Цвета хранятся как ARGB Int. Same data powers the live reader UI, the video
 * visual snapshot and the video renderer.
 */
data class ReaderBackgroundPreset(
    val id: String,
    val colors: List<Int>,
    val textColor: String,
)

val ReaderBackgroundPresets: List<ReaderBackgroundPreset> = listOf(
    ReaderBackgroundPreset(
        id = "paper",
        colors = listOf(0xFFFEFDFB.toInt(), 0xFFFBF9F4.toInt(), 0xFFF7F4ED.toInt()),
        textColor = "FF2C2C34",
    ),
    ReaderBackgroundPreset(
        id = "cream",
        colors = listOf(0xFFFBF7F0.toInt(), 0xFFF8F2E8.toInt(), 0xFFF3ECE0.toInt()),
        textColor = "FF3B2F1E",
    ),
    ReaderBackgroundPreset(
        id = "linen",
        colors = listOf(0xFFFAF5EF.toInt(), 0xFFF5EEE4.toInt(), 0xFFEFE6D8.toInt()),
        textColor = "FF3A3529",
    ),
    ReaderBackgroundPreset(
        id = "mist",
        colors = listOf(0xFFF4F6F8.toInt(), 0xFFEEF1F4.toInt(), 0xFFE8ECF0.toInt()),
        textColor = "FF2A3040",
    ),
    ReaderBackgroundPreset(
        id = "sage",
        colors = listOf(0xFFF3F7F4.toInt(), 0xFFEEF3EF.toInt(), 0xFFE7EDE8.toInt()),
        textColor = "FF1F2E22",
    ),
    ReaderBackgroundPreset(
        id = "twilight",
        colors = listOf(0xFF2A2535.toInt(), 0xFF242030.toInt(), 0xFF1E1B28.toInt()),
        textColor = "FFD8CCE8",
    ),
    ReaderBackgroundPreset(
        id = "dusk",
        colors = listOf(0xFF2D2418.toInt(), 0xFF252018.toInt(), 0xFF1E1A14.toInt()),
        textColor = "FFE0D4C0",
    ),
    ReaderBackgroundPreset(
        id = "cocoa",
        colors = listOf(0xFF2B2320.toInt(), 0xFF252018.toInt(), 0xFF1F1B16.toInt()),
        textColor = "FFE5D8CC",
    ),
    ReaderBackgroundPreset(
        id = "night",
        colors = listOf(0xFF1A1D24.toInt(), 0xFF171A20.toInt(), 0xFF14161C.toInt()),
        textColor = "FFE0E4EC",
    ),
    ReaderBackgroundPreset(
        id = "ocean",
        colors = listOf(0xFF0F1A24.toInt(), 0xFF0D1820.toInt(), 0xFF0B161C.toInt()),
        textColor = "FFC0D0E0",
    ),
    ReaderBackgroundPreset(
        id = "arctic",
        colors = listOf(0xFF1A2030.toInt(), 0xFF182028.toInt(), 0xFF162020.toInt()),
        textColor = "FFD0E0E8",
    ),
    ReaderBackgroundPreset(
        id = "forest",
        colors = listOf(0xFF182018.toInt(), 0xFF161E16.toInt(), 0xFF141C14.toInt()),
        textColor = "FFD0E0D0",
    ),
    ReaderBackgroundPreset(
        id = "rose",
        colors = listOf(0xFF2A1E22.toInt(), 0xFF261C20.toInt(), 0xFF221A1E.toInt()),
        textColor = "FFE8D0D8",
    ),
    ReaderBackgroundPreset(
        id = "midnight",
        colors = listOf(0xFF0D0D0D.toInt(), 0xFF0A0A0A.toInt(), 0xFF070707.toInt()),
        textColor = "FFE0E0E0",
    ),
)

/** Ветка рендера фона: ничего, градиентный пресет или импортированная картинка. */
sealed interface BackgroundLayer {
    data object None : BackgroundLayer
    data class Preset(val preset: ReaderBackgroundPreset) : BackgroundLayer
    data class Image(val file: File) : BackgroundLayer
}

/**
 * Чистая функция решения ветки фона: пусто -> None, пресет по id -> Preset,
 * иначе файл -> Image (для `background_file:` значений через [fileResolver]).
 */
fun backgroundLayer(
    bg: String,
    presets: List<ReaderBackgroundPreset> = ReaderBackgroundPresets,
    fileResolver: (String) -> File? = { ReaderBackgroundResolver.resolveFile(it) },
): BackgroundLayer = when {
    bg.isBlank() -> BackgroundLayer.None
    else -> presets.firstOrNull { it.id == bg }?.let { BackgroundLayer.Preset(it) }
        ?: fileResolver(bg)?.let { BackgroundLayer.Image(it) }
        ?: BackgroundLayer.None
}

/** Парсит ARGB-hex без "#" в Int (null при ошибке). */
fun parseArgb(hex: String): Int? =
    runCatching { android.graphics.Color.parseColor("#$hex") }.getOrNull()

/** Свойство пресета: цвет текста как ARGB Int (fallback при битом значении). */
val ReaderBackgroundPreset.textColorArgb: Int
    get() = parseArgb(textColor) ?: 0xFFFFFFFF.toInt()