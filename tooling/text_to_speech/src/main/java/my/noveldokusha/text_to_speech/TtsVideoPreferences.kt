package my.noveldokusha.text_to_speech

import android.content.Context
import androidx.preference.PreferenceManager
import my.noveldokusha.core.appPreferences.TtsAudioSource
import my.noveldokusha.core.appPreferences.TtsVideoJobState
import my.noveldokusha.core.appPreferences.TtsVideoJobStatus
import org.json.JSONArray
import org.json.JSONObject

class TtsVideoPreferences(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    var outputDirectoryUri: String
        get() = prefs.getString(KEY_OUTPUT_URI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OUTPUT_URI, value).apply()

    fun visualSettings(): TtsVideoVisualSettings = TtsVideoVisualSettings(
        paragraphMode = enum("paragraphMode", ParagraphDisplayMode.DYNAMIC_CONTEXT),
        longParagraphMode = enum("longParagraphMode", LongParagraphMode.AUTO_FIT),
        fontSizePx = prefs.getFloat("fontSizePx", 54f),
        minFontSizePx = prefs.getFloat("minFontSizePx", 30f),
        lineSpacingMultiplier = prefs.getFloat("lineSpacingMultiplier", 1.15f),
        textColor = prefs.getInt("textColor", android.graphics.Color.WHITE),
        cardEnabled = prefs.getBoolean("cardEnabled", true),
        cardColor = prefs.getInt("cardColor", android.graphics.Color.DKGRAY),
        cardAlpha = prefs.getFloat("cardAlpha", .82f),
        cardCornerRadiusPx = prefs.getFloat("cardCornerRadiusPx", 28f),
        cardPaddingPx = prefs.getFloat("cardPaddingPx", 30f),
        highlightColor = prefs.getInt("highlightColor", android.graphics.Color.YELLOW),
        highlightAlpha = prefs.getFloat("highlightAlpha", .92f),
        backgroundMode = enum("backgroundMode", BackgroundMode.SOLID),
        backgroundColor = prefs.getInt("backgroundColor", android.graphics.Color.rgb(18, 18, 22)),
        backgroundUri = prefs.getString("backgroundUri", "") ?: "",
        artworkMode = enum("artworkMode", ArtworkMode.NONE),
        artworkUris = prefs.getStringSet("artworkUris", emptySet())?.toList() ?: emptyList(),
        artworkWidthPx = prefs.getFloat("artworkWidthPx", 260f),
        artworkOpacity = prefs.getFloat("artworkOpacity", .95f),
        slideshowEnabled = prefs.getBoolean("slideshowEnabled", false),
        slideshowIntervalMode = enum("slideshowIntervalMode", SlideshowIntervalMode.PERCENT_OF_TOTAL_DURATION),
        slideshowIntervalMs = prefs.getLong("slideshowIntervalMs", 8000L),
        slideshowTransition = enum("slideshowTransition", SlideshowTransition.CROSSFADE),
        slideshowSeed = prefs.getLong("slideshowSeed", 0L),
    )

    fun putVisual(settings: TtsVideoVisualSettings) {
        prefs.edit()
            .putString("paragraphMode", settings.paragraphMode.name).putString("longParagraphMode", settings.longParagraphMode.name)
            .putFloat("fontSizePx", settings.fontSizePx).putFloat("minFontSizePx", settings.minFontSizePx).putFloat("lineSpacingMultiplier", settings.lineSpacingMultiplier)
            .putInt("textColor", settings.textColor).putBoolean("cardEnabled", settings.cardEnabled).putInt("cardColor", settings.cardColor).putFloat("cardAlpha", settings.cardAlpha)
            .putFloat("cardCornerRadiusPx", settings.cardCornerRadiusPx).putFloat("cardPaddingPx", settings.cardPaddingPx).putInt("highlightColor", settings.highlightColor).putFloat("highlightAlpha", settings.highlightAlpha)
            .putString("backgroundMode", settings.backgroundMode.name).putInt("backgroundColor", settings.backgroundColor).putString("backgroundUri", settings.backgroundUri)
            .putString("artworkMode", settings.artworkMode.name).putStringSet("artworkUris", settings.artworkUris.toSet()).putFloat("artworkWidthPx", settings.artworkWidthPx).putFloat("artworkOpacity", settings.artworkOpacity)
            .putBoolean("slideshowEnabled", settings.slideshowEnabled).putString("slideshowIntervalMode", settings.slideshowIntervalMode.name).putLong("slideshowIntervalMs", settings.slideshowIntervalMs)
            .putString("slideshowTransition", settings.slideshowTransition.name).putLong("slideshowSeed", settings.slideshowSeed).apply()
    }

    fun jobs(): Map<String, TtsVideoJobState> = runCatching {
        val a = JSONArray(prefs.getString(KEY_JOBS, "[]")); buildMap {
            for (i in 0 until a.length()) { val o=a.getJSONObject(i); val id=o.getString("id"); put(id, TtsVideoJobState(o.getString("chapterUrl"),o.getString("novelUrl"),o.getString("chapterTitle"),TtsAudioSource.valueOf(o.getString("source")),TtsVideoJobStatus.valueOf(o.getString("status")),o.optString("workRequestId"),o.optInt("progress"),o.optString("outputUri"),o.optString("message"))) }
        }
    }.getOrDefault(emptyMap())

    fun saveJobs(jobs: Map<String, TtsVideoJobState>) {
        val a = JSONArray(); jobs.forEach { (id,j)-> a.put(JSONObject().apply{put("id",id);put("chapterUrl",j.chapterUrl);put("novelUrl",j.novelUrl);put("chapterTitle",j.chapterTitle);put("source",j.source.name);put("status",j.status.name);put("workRequestId",j.workRequestId);put("progress",j.progress);put("outputUri",j.outputUri);put("message",j.message)}) }
        prefs.edit().putString(KEY_JOBS,a.toString()).apply()
    }

    private inline fun <reified E : Enum<E>> enum(key: String, default: E): E = runCatching { enumValueOf<E>(prefs.getString(key, default.name) ?: default.name) }.getOrDefault(default)
    companion object { const val KEY_OUTPUT_URI = "TTS_VIDEO_DOWNLOAD_LOCATION_URI"; const val KEY_JOBS = "TTS_VIDEO_DOWNLOAD_JOBS" }
}
