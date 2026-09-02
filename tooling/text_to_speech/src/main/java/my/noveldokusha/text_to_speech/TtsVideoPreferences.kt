package my.noveldokusha.text_to_speech

import android.content.Context
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TtsVideoJobState
import my.noveldokusha.core.appPreferences.TtsVideoJobStatus
import org.json.JSONArray
import org.json.JSONObject

class TtsVideoPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var outputDirectoryUri: String
        get() = prefs.getString(KEY_OUTPUT_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OUTPUT_URI, value).apply()

    fun visualSettings(): TtsVideoVisualSettings = TtsVideoVisualSettings(
        paragraphMode = enum("paragraphMode", ParagraphDisplayMode.DYNAMIC_CONTEXT),
        longParagraphMode = enum("longParagraphMode", LongParagraphMode.AUTO_FIT),
        width = 1920, height = 1080, fps = 30,
        fontSizePx = prefs.getFloat("fontSizePx", 54f), minFontSizePx = prefs.getFloat("minFontSizePx", 30f),
        lineSpacingMultiplier = prefs.getFloat("lineSpacingMultiplier", 1.15f), paragraphSpacingPx = prefs.getFloat("paragraphSpacingPx", 28f),
        letterSpacingEm = prefs.getFloat("letterSpacingEm", 0f), textColor = prefs.getInt("textColor", android.graphics.Color.WHITE),
        cardEnabled = prefs.getBoolean("cardEnabled", true), cardColor = prefs.getInt("cardColor", android.graphics.Color.DKGRAY),
        cardAlpha = prefs.getFloat("cardAlpha", .82f), cardCornerRadiusPx = prefs.getFloat("cardCornerRadiusPx", 28f), cardPaddingPx = prefs.getFloat("cardPaddingPx", 30f),
        cardStrokeWidthPx = prefs.getFloat("cardStrokeWidthPx", 1.5f), cardStrokeColor = prefs.getInt("cardStrokeColor", android.graphics.Color.argb(80,255,255,255)),
        highlightColor = prefs.getInt("highlightColor", android.graphics.Color.YELLOW), highlightAlpha = prefs.getFloat("highlightAlpha", .92f),
        highlightCornerRadiusPx = prefs.getFloat("highlightCornerRadiusPx", 8f), highlightPaddingPx = prefs.getFloat("highlightPaddingPx", 4f),
        safeMarginPx = prefs.getFloat("safeMarginPx", 80f), maxTextWidthFraction = prefs.getFloat("maxTextWidthFraction", .82f),
        backgroundMode = enum("backgroundMode", BackgroundMode.SOLID), backgroundColor = prefs.getInt("backgroundColor", android.graphics.Color.rgb(18,18,22)),
        backgroundUri = prefs.getString("backgroundUri", "") ?: "", artworkMode = enum("artworkMode", ArtworkMode.NONE),
        artworkUris = stringList("artworkUris"), artworkWidthPx = prefs.getFloat("artworkWidthPx", 260f), artworkOpacity = prefs.getFloat("artworkOpacity", .95f),
        artworkCornerRadiusPx = prefs.getFloat("artworkCornerRadiusPx", 24f), artworkBorderWidthPx = prefs.getFloat("artworkBorderWidthPx", 0f),
        artworkBorderColor = prefs.getInt("artworkBorderColor", android.graphics.Color.TRANSPARENT), artworkOverlay = prefs.getBoolean("artworkOverlay", false),
        slideshowEnabled = prefs.getBoolean("slideshowEnabled", false), slideshowIntervalMode = enum("slideshowIntervalMode", SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION),
        slideshowIntervalMs = prefs.getLong("slideshowIntervalMs", 8000L), slideshowTransition = enum("slideshowTransition", SlideshowTransition.CROSSFADE),
        slideshowSeed = prefs.getLong("slideshowSeed", 0L),
    )

    fun putVisual(settings: TtsVideoVisualSettings) {
        prefs.edit()
            .putString("paragraphMode", settings.paragraphMode.name)
            .putString("longParagraphMode", settings.longParagraphMode.name)
            .putFloat("fontSizePx", settings.fontSizePx).putFloat("minFontSizePx", settings.minFontSizePx)
            .putFloat("lineSpacingMultiplier", settings.lineSpacingMultiplier).putFloat("paragraphSpacingPx", settings.paragraphSpacingPx)
            .putFloat("letterSpacingEm", settings.letterSpacingEm).putInt("textColor", settings.textColor)
            .putBoolean("cardEnabled", settings.cardEnabled).putInt("cardColor", settings.cardColor).putFloat("cardAlpha", settings.cardAlpha)
            .putFloat("cardCornerRadiusPx", settings.cardCornerRadiusPx).putFloat("cardPaddingPx", settings.cardPaddingPx)
            .putFloat("cardStrokeWidthPx", settings.cardStrokeWidthPx).putInt("cardStrokeColor", settings.cardStrokeColor)
            .putInt("highlightColor", settings.highlightColor).putFloat("highlightAlpha", settings.highlightAlpha)
            .putFloat("highlightCornerRadiusPx", settings.highlightCornerRadiusPx).putFloat("highlightPaddingPx", settings.highlightPaddingPx)
            .putFloat("safeMarginPx", settings.safeMarginPx).putFloat("maxTextWidthFraction", settings.maxTextWidthFraction)
            .putString("backgroundMode", settings.backgroundMode.name).putInt("backgroundColor", settings.backgroundColor).putString("backgroundUri", settings.backgroundUri)
            .putString("artworkMode", settings.artworkMode.name).putString("artworkUris", JSONArray(settings.artworkUris).toString())
            .putFloat("artworkWidthPx", settings.artworkWidthPx).putFloat("artworkOpacity", settings.artworkOpacity).putFloat("artworkCornerRadiusPx", settings.artworkCornerRadiusPx)
            .putFloat("artworkBorderWidthPx", settings.artworkBorderWidthPx).putInt("artworkBorderColor", settings.artworkBorderColor).putBoolean("artworkOverlay", settings.artworkOverlay)
            .putBoolean("slideshowEnabled", settings.slideshowEnabled).putString("slideshowIntervalMode", settings.slideshowIntervalMode.name)
            .putLong("slideshowIntervalMs", settings.slideshowIntervalMs).putString("slideshowTransition", settings.slideshowTransition.name).putLong("slideshowSeed", settings.slideshowSeed)
            .apply()
    }

    fun jobs(): Map<String, TtsVideoJobState> = runCatching {
        val array = JSONArray(prefs.getString(KEY_JOBS, "[]") ?: "[]")
        buildMap {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i); val id = o.getString("id")
                put(id, TtsVideoJobState(
                    chapterUrl = o.getString("chapterUrl"), novelUrl = o.getString("novelUrl"), chapterTitle = o.getString("chapterTitle"),
                    source = TtsAudioSource.valueOf(o.getString("source")), status = TtsVideoJobStatus.valueOf(o.getString("status")),
                    workRequestId = o.optString("workRequestId"), requestJson = o.optString("requestJson"), progress = o.optInt("progress"),
                    outputUri = o.optString("outputUri"), message = o.optString("message"),
                ))
            }
        }
    }.getOrDefault(emptyMap())

    fun saveJobs(jobs: Map<String, TtsVideoJobState>) {
        val array = JSONArray()
        jobs.forEach { (id, job) -> array.put(JSONObject().apply {
            put("id", id); put("chapterUrl", job.chapterUrl); put("novelUrl", job.novelUrl); put("chapterTitle", job.chapterTitle)
            put("source", job.source.name); put("status", job.status.name); put("workRequestId", job.workRequestId); put("requestJson", job.requestJson)
            put("progress", job.progress); put("outputUri", job.outputUri); put("message", job.message)
        }) }
        prefs.edit().putString(KEY_JOBS, array.toString()).apply()
    }

    private fun stringList(key: String): List<String> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]") ?: "[]")
        buildList { for (i in 0 until a.length()) a.optString(i).takeIf(String::isNotBlank)?.let(::add) }
    }.getOrDefault(emptyList())

    private inline fun <reified E : Enum<E>> enum(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    companion object {
        private const val PREFS_NAME = "novela_tts_video"
        const val KEY_OUTPUT_URI = "TTS_VIDEO_DOWNLOAD_LOCATION_URI"
        const val KEY_JOBS = "TTS_VIDEO_DOWNLOAD_JOBS"
    }
}
