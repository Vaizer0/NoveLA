package my.noveldokusha.features.reader.features

import android.content.SharedPreferences
import org.json.JSONObject
import timber.log.Timber

/**
 * Self-learning TTS duration predictor.
 *
 * Tracks per-character-type speaking costs (ms per char) learned from
 * completed chapters. Predicts chapter duration before playback starts.
 * Learning happens at chapter boundaries — not during playback.
 */
internal class TTSDurationTracker(
    private val preferences: SharedPreferences,
) {

    private data class ModelData(
        val costs: MutableMap<String, Double> = mutableMapOf(),
        var sampleCount: Int = 0,
    )

    private var model = loadModel()

    // Chapter tracking state
    private var chapterStartTimeMs: Long = 0L
    private var chapterPauseAccumMs: Long = 0L
    private var chapterPauseStartMs: Long = 0L
    private var isPaused: Boolean = false
    private var currentChapterTexts: List<String> = emptyList()
    private var currentSpeed: Float = 1.0f

    // Prediction cache
    var cachedChapterDurationMs: Long = 0L
        private set
    private var cachedProgress: Float = 0f
    private var cachedElapsedMs: Long = 0L
    private var cachedRemainingMs: Long = 0L

    companion object {
        private const val PREF_KEY = "TTS_DURATION_MODEL"
        private const val LEARNING_RATE = 0.25
        private const val MIN_COST = 15.0
        private const val MAX_COST = 500.0

        private val DEFAULT_COSTS = mapOf(
            "cjk" to 85.0,
            "latin" to 45.0,
            "digit" to 60.0,
            "space" to 20.0,
            "comma" to 150.0,
            "period" to 250.0,
            "question" to 300.0,
            "exclamation" to 300.0,
            "ellipsis" to 400.0,
            "other_punct" to 200.0,
        )
    }

    fun predictChapterDuration(texts: List<String>, speed: Float): Long {
        currentSpeed = speed
        val totalMs = texts.sumOf { predictParagraphDuration(it, speed) }
        cachedChapterDurationMs = totalMs
        return totalMs
    }

    fun onChapterStart(texts: List<String>, speed: Float) {
        chapterStartTimeMs = System.currentTimeMillis()
        chapterPauseAccumMs = 0L
        chapterPauseStartMs = 0L
        isPaused = false
        currentChapterTexts = texts
        currentSpeed = speed
    }

    fun onPause() {
        if (!isPaused) {
            isPaused = true
            chapterPauseStartMs = System.currentTimeMillis()
        }
    }

    fun onResume() {
        if (isPaused) {
            chapterPauseAccumMs += System.currentTimeMillis() - chapterPauseStartMs
            isPaused = false
        }
    }

    fun onChapterFinish() {
        if (isPaused) {
            chapterPauseAccumMs += System.currentTimeMillis() - chapterPauseStartMs
            isPaused = false
        }
        val actualDurationMs = System.currentTimeMillis() - chapterStartTimeMs - chapterPauseAccumMs
        if (actualDurationMs < 1000 || currentChapterTexts.isEmpty()) return

        val predictedMs = predictChapterDuration(currentChapterTexts, currentSpeed)
        if (predictedMs < 100) return

        updateModel(currentChapterTexts, actualDurationMs)
        Timber.d("TTSDurationTracker: chapter learned actual=${actualDurationMs}ms predicted=${predictedMs}ms error=${((actualDurationMs - predictedMs) * 100 / predictedMs).toInt()}%")
    }

    fun getProgress(): Float {
        if (cachedChapterDurationMs <= 0) return 0f
        val elapsed = getElapsedMs()
        cachedProgress = (elapsed.toFloat() / cachedChapterDurationMs).coerceIn(0f, 1f)
        return cachedProgress
    }

    fun getElapsedMs(): Long {
        if (chapterStartTimeMs == 0L) return 0L
        val now = if (isPaused) chapterPauseStartMs else System.currentTimeMillis()
        return now - chapterStartTimeMs - chapterPauseAccumMs
    }

    fun getRemainingMs(): Long {
        return (cachedChapterDurationMs - getElapsedMs()).coerceAtLeast(0L)
    }

    fun reset() {
        chapterStartTimeMs = 0L
        chapterPauseAccumMs = 0L
        chapterPauseStartMs = 0L
        isPaused = false
        currentChapterTexts = emptyList()
        cachedChapterDurationMs = 0L
        cachedProgress = 0f
        cachedElapsedMs = 0L
        cachedRemainingMs = 0L
    }

    private fun predictParagraphDuration(text: String, speed: Float): Long {
        val costs = model.costs
        var totalMs = 0.0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val type = charType(c)
            totalMs += costs[type] ?: (DEFAULT_COSTS[type] ?: 50.0)
            i++
        }
        return (totalMs / speed).toLong()
    }

    private fun charType(c: Char): String = when {
        c.isWhitespace() -> "space"
        c.category == CharCategory.OTHER_LETTER -> "cjk"
        c == '\uFF0C' || c == '\u3001' -> "comma"        // ，、
        c == '\u3002' || c == '\uFF0E' -> "period"       // 。
        c == '\uFF1F' || c == '?' -> "question"           // ？
        c == '\uFF01' || c == '!' -> "exclamation"        // ！
        c == '\u2026' -> "ellipsis"                       // …
        c.isLetter() -> "latin"
        c.isDigit() -> "digit"
        c.category in setOf(
            CharCategory.OTHER_PUNCTUATION,
            CharCategory.DASH_PUNCTUATION,
            CharCategory.START_PUNCTUATION,
            CharCategory.END_PUNCTUATION,
            CharCategory.CONNECTOR_PUNCTUATION,
            CharCategory.INITIAL_QUOTE_PUNCTUATION,
            CharCategory.FINAL_QUOTE_PUNCTUATION,
        ) -> "other_punct"
        else -> "other_punct"
    }

    private fun updateModel(texts: List<String>, actualDurationMs: Long) {
        val fullText = texts.joinToString("")
        if (fullText.isEmpty()) return

        val predictedMs = predictChapterDuration(texts, currentSpeed)
        if (predictedMs <= 0) return

        val charCounts = mutableMapOf<String, Int>()
        for (c in fullText) {
            val type = charType(c)
            charCounts[type] = (charCounts[type] ?: 0) + 1
        }
        val totalChars = charCounts.values.sum().toDouble()
        if (totalChars <= 0) return

        val errorMs = actualDurationMs - predictedMs

        charCounts.forEach { (type, count) ->
            val weight = count.toDouble() / totalChars
            val currentCost = model.costs[type] ?: (DEFAULT_COSTS[type] ?: 50.0)
            val typeErrorShare = errorMs * weight
            val perCharAdjustment = typeErrorShare / count
            val newCost = (currentCost + perCharAdjustment * LEARNING_RATE)
                .coerceIn(MIN_COST, MAX_COST)
            model.costs[type] = newCost
        }

        model.sampleCount++
        saveModel()
    }

    private fun loadModel(): ModelData {
        return try {
            val json = preferences.getString(PREF_KEY, null) ?: return ModelData()
            val obj = JSONObject(json)
            val costs = mutableMapOf<String, Double>()
            val costsObj = obj.optJSONObject("costs")
            if (costsObj != null) {
                for (key in costsObj.keys()) {
                    costs[key] = costsObj.getDouble(key)
                }
            }
            ModelData(costs = costs, sampleCount = obj.optInt("sampleCount", 0))
        } catch (e: Exception) {
            Timber.w(e, "TTSDurationTracker: failed to load model, using defaults")
            ModelData()
        }
    }

    private fun saveModel() {
        try {
            val costsObj = JSONObject()
            model.costs.forEach { (key, value) -> costsObj.put(key, value) }
            val obj = JSONObject().apply {
                put("costs", costsObj)
                put("sampleCount", model.sampleCount)
            }
            preferences.edit().putString(PREF_KEY, obj.toString()).apply()
        } catch (e: Exception) {
            Timber.w(e, "TTSDurationTracker: failed to save model")
        }
    }
}
