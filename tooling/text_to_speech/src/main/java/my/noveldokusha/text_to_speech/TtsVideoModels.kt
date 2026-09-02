package my.noveldokusha.text_to_speech

import android.graphics.Color

/** Immutable video-export settings. Values are deliberately primitive/URI based so they can be persisted safely. */
data class TtsVideoVisualSettings(
    val paragraphMode: ParagraphDisplayMode = ParagraphDisplayMode.DYNAMIC_CONTEXT,
    val longParagraphMode: LongParagraphMode = LongParagraphMode.AUTO_FIT,
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val fontSizePx: Float = 54f,
    val minFontSizePx: Float = 30f,
    val lineSpacingMultiplier: Float = 1.15f,
    val paragraphSpacingPx: Float = 28f,
    val letterSpacingEm: Float = 0f,
    val textColor: Int = Color.WHITE,
    val cardEnabled: Boolean = true,
    val cardColor: Int = Color.argb(210, 20, 20, 24),
    val cardAlpha: Float = 0.82f,
    val cardCornerRadiusPx: Float = 28f,
    val cardPaddingPx: Float = 30f,
    val cardStrokeWidthPx: Float = 1.5f,
    val cardStrokeColor: Int = Color.argb(80, 255, 255, 255),
    val highlightColor: Int = Color.argb(255, 255, 210, 75),
    val highlightAlpha: Float = 0.92f,
    val highlightCornerRadiusPx: Float = 8f,
    val highlightPaddingPx: Float = 4f,
    val safeMarginPx: Float = 80f,
    val maxTextWidthFraction: Float = 0.82f,
    val artworkMode: ArtworkMode = ArtworkMode.NONE,
    val backgroundMode: BackgroundMode = BackgroundMode.SOLID,
    val backgroundColor: Int = Color.rgb(18, 18, 22),
    val backgroundUri: String = "",
    val artworkUris: List<String> = emptyList(),
    val artworkWidthPx: Float = 260f,
    val artworkOpacity: Float = 0.95f,
    val artworkCornerRadiusPx: Float = 24f,
    val artworkBorderWidthPx: Float = 0f,
    val artworkBorderColor: Int = Color.TRANSPARENT,
    val artworkOverlay: Boolean = false,
    val slideshowEnabled: Boolean = false,
    val slideshowIntervalMode: SlideshowIntervalMode = SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION,
    val slideshowIntervalMs: Long = 8_000L,
    val slideshowTransition: SlideshowTransition = SlideshowTransition.CROSSFADE,
    val slideshowSeed: Long = 0L,
)

enum class ParagraphDisplayMode { CURRENT_ONLY, CURRENT_WITH_CONTEXT, DYNAMIC_CONTEXT }
enum class LongParagraphMode { AUTO_FIT, SMOOTH_SCROLL }
enum class ArtworkMode { NONE, LEFT, RIGHT, BOTH }
enum class BackgroundMode { SOLID, PRESET, IMAGE }
enum class SlideshowIntervalMode { FIXED_INTERVAL, PERCENT_OF_TOTAL_DURATION, RANDOM_INTERVAL }
enum class SlideshowTransition { NONE, FADE, CROSSFADE, SUBTLE_SLIDE, SUBTLE_ZOOM }
enum class TimelineTimingMode { EXACT, APPROXIMATE }

data class VideoParagraph(
    val id: String,
    val displayText: String,
    val preparedText: String,
    val startUs: Long,
    val endUs: Long,
    val blockIndex: Int,
    val chunkIndex: Int = 0,
    val timingMode: TimelineTimingMode = TimelineTimingMode.EXACT,
    val spokenRanges: List<VideoSpokenRange> = emptyList(),
)

data class VideoSpokenRange(
    val startUs: Long,
    val endUs: Long,
    val preparedStart: Int,
    val preparedEnd: Int,
    val displayStart: Int,
    val displayEnd: Int,
    val timingMode: TimelineTimingMode,
)

data class TtsVideoTimeline(
    val paragraphs: List<VideoParagraph>,
    val durationUs: Long,
    val timingMode: TimelineTimingMode,
) {
    init {
        require(durationUs >= 0L)
        require(paragraphs.zipWithNext().all { (a, b) -> a.endUs <= b.startUs })
    }

    fun paragraphAt(timeUs: Long): VideoParagraph? =
        paragraphs.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }
            ?: paragraphs.lastOrNull { timeUs >= it.endUs }

    fun activeRangeAt(timeUs: Long): VideoSpokenRange? =
        paragraphAt(timeUs)?.spokenRanges?.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }
}

data class TtsVideoRequest(
    val jobId: String,
    val novelUrl: String,
    val novelTitle: String,
    val chapterUrl: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val source: TtsAudioSource,
    val translationSourceLang: String = "",
    val translationTargetLang: String = "",
    val enginePackage: String,
    val voiceId: String,
    val speed: Float,
    val pitch: Float,
    val visual: TtsVideoVisualSettings,
    val outputDirectoryUri: String,
) 

fun TtsVideoRequest.serialize(): String = org.json.JSONObject().apply {
    put("jobId", jobId); put("novelUrl", novelUrl); put("novelTitle", novelTitle)
    put("chapterUrl", chapterUrl); put("chapterIndex", chapterIndex); put("chapterTitle", chapterTitle)
    put("source", source.name); put("translationSourceLang", translationSourceLang); put("translationTargetLang", translationTargetLang)
    put("enginePackage", enginePackage); put("voiceId", voiceId); put("speed", speed); put("pitch", pitch)
    put("outputDirectoryUri", outputDirectoryUri)
    put("visual", visual.toJson())
}.toString()

fun TtsVideoVisualSettings.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("paragraphMode", paragraphMode.name); put("longParagraphMode", longParagraphMode.name)
    put("width", width); put("height", height); put("fps", fps)
    put("fontSizePx", fontSizePx); put("minFontSizePx", minFontSizePx); put("lineSpacingMultiplier", lineSpacingMultiplier)
    put("paragraphSpacingPx", paragraphSpacingPx); put("letterSpacingEm", letterSpacingEm)
    put("textColor", textColor); put("cardEnabled", cardEnabled); put("cardColor", cardColor); put("cardAlpha", cardAlpha)
    put("cardCornerRadiusPx", cardCornerRadiusPx); put("cardPaddingPx", cardPaddingPx); put("cardStrokeWidthPx", cardStrokeWidthPx)
    put("cardStrokeColor", cardStrokeColor); put("highlightColor", highlightColor); put("highlightAlpha", highlightAlpha)
    put("highlightCornerRadiusPx", highlightCornerRadiusPx); put("highlightPaddingPx", highlightPaddingPx)
    put("safeMarginPx", safeMarginPx); put("maxTextWidthFraction", maxTextWidthFraction)
    put("artworkMode", artworkMode.name); put("backgroundMode", backgroundMode.name); put("backgroundColor", backgroundColor)
    put("backgroundUri", backgroundUri); put("artworkWidthPx", artworkWidthPx); put("artworkOpacity", artworkOpacity)
    put("artworkCornerRadiusPx", artworkCornerRadiusPx); put("artworkBorderWidthPx", artworkBorderWidthPx); put("artworkBorderColor", artworkBorderColor)
    put("artworkOverlay", artworkOverlay); put("slideshowEnabled", slideshowEnabled); put("slideshowIntervalMode", slideshowIntervalMode.name)
    put("slideshowIntervalMs", slideshowIntervalMs); put("slideshowTransition", slideshowTransition.name); put("slideshowSeed", slideshowSeed)
    put("artworkUris", org.json.JSONArray(artworkUris))
}

fun String.toTtsVideoRequest(): TtsVideoRequest? = runCatching {
    val o = org.json.JSONObject(this)
    val v = o.getJSONObject("visual")
    TtsVideoRequest(
        jobId = o.getString("jobId"), novelUrl = o.getString("novelUrl"), novelTitle = o.getString("novelTitle"),
        chapterUrl = o.getString("chapterUrl"), chapterIndex = o.getInt("chapterIndex"), chapterTitle = o.getString("chapterTitle"),
        source = TtsAudioSource.valueOf(o.getString("source")), translationSourceLang = o.optString("translationSourceLang"),
        translationTargetLang = o.optString("translationTargetLang"), enginePackage = o.getString("enginePackage"),
        voiceId = o.getString("voiceId"), speed = o.getDouble("speed").toFloat(), pitch = o.getDouble("pitch").toFloat(),
        outputDirectoryUri = o.getString("outputDirectoryUri"), visual = v.toVisualSettings()
    )
}.getOrNull()

private fun org.json.JSONObject.toVisualSettings(): TtsVideoVisualSettings = TtsVideoVisualSettings(
    paragraphMode = runCatching { ParagraphDisplayMode.valueOf(optString("paragraphMode")) }.getOrDefault(ParagraphDisplayMode.DYNAMIC_CONTEXT),
    longParagraphMode = runCatching { LongParagraphMode.valueOf(optString("longParagraphMode")) }.getOrDefault(LongParagraphMode.AUTO_FIT),
    width = optInt("width", 1920), height = optInt("height", 1080), fps = optInt("fps", 30), fontSizePx = optDouble("fontSizePx", 54.0).toFloat(),
    minFontSizePx = optDouble("minFontSizePx", 30.0).toFloat(), lineSpacingMultiplier = optDouble("lineSpacingMultiplier", 1.15).toFloat(),
    paragraphSpacingPx = optDouble("paragraphSpacingPx", 28.0).toFloat(), letterSpacingEm = optDouble("letterSpacingEm", 0.0).toFloat(),
    textColor = optInt("textColor", Color.WHITE), cardEnabled = optBoolean("cardEnabled", true), cardColor = optInt("cardColor", Color.DKGRAY),
    cardAlpha = optDouble("cardAlpha", .82).toFloat(), cardCornerRadiusPx = optDouble("cardCornerRadiusPx", 28.0).toFloat(),
    cardPaddingPx = optDouble("cardPaddingPx", 30.0).toFloat(), cardStrokeWidthPx = optDouble("cardStrokeWidthPx", 1.5).toFloat(),
    cardStrokeColor = optInt("cardStrokeColor", Color.TRANSPARENT), highlightColor = optInt("highlightColor", Color.YELLOW),
    highlightAlpha = optDouble("highlightAlpha", .92).toFloat(), highlightCornerRadiusPx = optDouble("highlightCornerRadiusPx", 8.0).toFloat(),
    highlightPaddingPx = optDouble("highlightPaddingPx", 4.0).toFloat(), safeMarginPx = optDouble("safeMarginPx", 80.0).toFloat(),
    maxTextWidthFraction = optDouble("maxTextWidthFraction", .82).toFloat(), artworkMode = runCatching { ArtworkMode.valueOf(optString("artworkMode")) }.getOrDefault(ArtworkMode.NONE),
    backgroundMode = runCatching { BackgroundMode.valueOf(optString("backgroundMode")) }.getOrDefault(BackgroundMode.SOLID), backgroundColor = optInt("backgroundColor", Color.DKGRAY),
    backgroundUri = optString("backgroundUri"), artworkUris = buildList { val a = optJSONArray("artworkUris") ?: return@buildList; for (i in 0 until a.length()) add(a.getString(i)) },
    artworkWidthPx = optDouble("artworkWidthPx", 260.0).toFloat(), artworkOpacity = optDouble("artworkOpacity", .95).toFloat(),
    artworkCornerRadiusPx = optDouble("artworkCornerRadiusPx", 24.0).toFloat(), artworkBorderWidthPx = optDouble("artworkBorderWidthPx", 0.0).toFloat(),
    artworkBorderColor = optInt("artworkBorderColor", Color.TRANSPARENT), artworkOverlay = optBoolean("artworkOverlay", false),
    slideshowEnabled = optBoolean("slideshowEnabled", false), slideshowIntervalMode = runCatching { SlideshowIntervalMode.valueOf(optString("slideshowIntervalMode")) }.getOrDefault(SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION),
    slideshowIntervalMs = optLong("slideshowIntervalMs", 8000L), slideshowTransition = runCatching { SlideshowTransition.valueOf(optString("slideshowTransition")) }.getOrDefault(SlideshowTransition.CROSSFADE),
    slideshowSeed = optLong("slideshowSeed", 0L)
)
