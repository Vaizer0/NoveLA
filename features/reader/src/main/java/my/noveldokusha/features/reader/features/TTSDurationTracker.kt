package my.noveldokusha.features.reader.features

import android.content.SharedPreferences
import android.os.Environment
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

internal class TTSDurationTracker(
    private val preferences: SharedPreferences,
    private val modelFile: File? = null,
) {

    @Serializable
    private data class ModelData(
        val costs: Map<String, Double> = mapOf(),
        val sampleCounts: Map<String, Int> = mapOf(),
        val totalSamples: Int = 0,
    )

    private val modelLock = Any()
    private var model = loadModel()

    private var chapterStartTimeMs: Long = 0L
    private var chapterPauseAccumMs: Long = 0L
    private var chapterPauseStartMs: Long = 0L
    private var isPaused: Boolean = false
    private var currentChapterTexts: List<String> = emptyList()
    private var currentSpeed: Float = 1.0f
    private var chapterStartSpeed: Float = 1.0f

    @Volatile
    var cachedChapterDurationMs: Long = 0L
        private set

    private var cachedProgress: Float = 0f
    private var cachedElapsedMs: Long = 0L
    private var cachedRemainingMs: Long = 0L

    companion object {
        private const val PREF_KEY = "TTS_DURATION_MODEL"
        private const val MODEL_DIR = "NoveLA"
        private const val MODEL_FILE_NAME = "tts_model.json"
        private const val LEARNING_RATE = 0.15
        private const val MIN_COST = 10.0
        private const val MAX_COST = 800.0

        fun createDefaultExternalFile(): File? {
            return try {
                File(Environment.getExternalStorageDirectory(), "$MODEL_DIR/$MODEL_FILE_NAME")
            } catch (e: Exception) {
                null
            }
        }

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
        val totalMs = texts.sumOf { predictParagraphDuration(it, speed) }
        cachedChapterDurationMs = totalMs
        return totalMs
    }

    fun onChapterStart(texts: List<String>, speed: Float) {
        synchronized(modelLock) {
            chapterStartTimeMs = System.currentTimeMillis()
            chapterPauseAccumMs = 0L
            chapterPauseStartMs = 0L
            isPaused = false
            currentChapterTexts = texts
            currentSpeed = speed
            chapterStartSpeed = speed
        }
    }

    fun onPause() {
        synchronized(modelLock) {
            if (!isPaused) {
                isPaused = true
                chapterPauseStartMs = System.currentTimeMillis()
            }
        }
    }

    fun onResume() {
        synchronized(modelLock) {
            if (isPaused) {
                chapterPauseAccumMs += System.currentTimeMillis() - chapterPauseStartMs
                isPaused = false
            }
        }
    }

    fun onChapterFinish() {
        synchronized(modelLock) {
            if (isPaused) {
                chapterPauseAccumMs += System.currentTimeMillis() - chapterPauseStartMs
                isPaused = false
            }
            val actualDurationMs = System.currentTimeMillis() - chapterStartTimeMs - chapterPauseAccumMs
            if (actualDurationMs < 1000 || currentChapterTexts.isEmpty()) {
                resetChapterState()
                return
            }

            val predictedMs = predictChapterDuration(currentChapterTexts, chapterStartSpeed)
            if (predictedMs < 100) {
                resetChapterState()
                return
            }

            updateModel(currentChapterTexts, actualDurationMs, chapterStartSpeed)
            Timber.d("TTSDurationTracker: chapter learned actual=${actualDurationMs}ms predicted=${predictedMs}ms error=${((actualDurationMs - predictedMs) * 100 / predictedMs).toInt()}% samples=${model.totalSamples}")
            resetChapterState()
        }
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
        return (now - chapterStartTimeMs - chapterPauseAccumMs).coerceAtLeast(0L)
    }

    fun getRemainingMs(): Long {
        return (cachedChapterDurationMs - getElapsedMs()).coerceAtLeast(0L)
    }

    fun reset() {
        synchronized(modelLock) {
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
    }

    fun clearLearningCache() {
        synchronized(modelLock) {
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
    }

    private fun resetChapterState() {
        chapterStartTimeMs = 0L
        chapterPauseAccumMs = 0L
        chapterPauseStartMs = 0L
        isPaused = false
        currentChapterTexts = emptyList()
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
        c == '\uFF0C' || c == '\u3001' || c == ',' -> "comma"
        c == '\u3002' || c == '\uFF0E' || c == '.' -> "period"
        c == '\uFF1F' || c == '?' -> "question"
        c == '\uFF01' || c == '!' -> "exclamation"
        c == '\u2026' -> "ellipsis"
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

    private fun updateModel(texts: List<String>, actualDurationMs: Long, speed: Float) {
        val fullText = texts.joinToString("")
        if (fullText.isEmpty()) return

        val predictedMs = predictChapterDuration(texts, speed)
        if (predictedMs <= 0) return

        val charCounts = mutableMapOf<String, Int>()
        for (c in fullText) {
            val type = charType(c)
            charCounts[type] = (charCounts[type] ?: 0) + 1
        }
        val totalChars = charCounts.values.sum().toDouble()
        if (totalChars <= 0) return

        val errorMs = actualDurationMs - predictedMs

        val newCosts = model.costs.toMutableMap()
        val newSampleCounts = model.sampleCounts.toMutableMap()

        charCounts.forEach { (type, count) ->
            val weight = count.toDouble() / totalChars
            val currentCost = model.costs[type] ?: (DEFAULT_COSTS[type] ?: 50.0)
            val typeErrorShare = errorMs * weight
            val perCharAdjustment = typeErrorShare / count
            val newCost = (currentCost + perCharAdjustment * LEARNING_RATE)
                .coerceIn(MIN_COST, MAX_COST)
            newCosts[type] = newCost
            newSampleCounts[type] = (model.sampleCounts[type] ?: 0) + 1
        }

        model = ModelData(
            costs = newCosts,
            sampleCounts = newSampleCounts,
            totalSamples = model.totalSamples + 1
        )
        saveModel()
    }

    private fun loadModel(): ModelData {
        modelFile?.let { file ->
            try {
                if (file.exists()) {
                    val json = file.readText()
                    val jsonConfig = Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                    return jsonConfig.decodeFromString(ModelData.serializer(), json)
                }
            } catch (e: Exception) {
                Timber.w(e, "TTSDurationTracker: failed to load model from external file")
            }
        }
        return try {
            val json = preferences.getString(PREF_KEY, null) ?: return ModelData()
            val jsonConfig = Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            jsonConfig.decodeFromString(ModelData.serializer(), json)
        } catch (e: Exception) {
            Timber.w(e, "TTSDurationTracker: failed to load model, using defaults")
            ModelData()
        }
    }

    private fun saveModel() {
        try {
            val jsonConfig = Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
            }
            val json = jsonConfig.encodeToString(ModelData.serializer(), model)
            preferences.edit().putString(PREF_KEY, json).apply()
        } catch (e: Exception) {
            Timber.w(e, "TTSDurationTracker: failed to save model to SharedPreferences")
        }
        modelFile?.let { file ->
            try {
                file.parentFile?.mkdirs()
                val jsonConfig = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                }
                val json = jsonConfig.encodeToString(ModelData.serializer(), model)
                file.writeText(json)
            } catch (e: Exception) {
                Timber.w(e, "TTSDurationTracker: failed to save model to external file")
            }
        }
    }
}