package my.noveldokusha.scraper.templates

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import my.noveldokusha.network.NetworkClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

internal class WtrLabTranslator(
    private val networkClient: NetworkClient,
    private val getSourceLang: () -> String,
    private val baseUrl: String
) {
    private val apiKey = String(
        android.util.Base64.decode(
            "QUl6YVN5QVRCWGFqdnpRTFRESEVRYmNwcTBJaGUwdldESG1PNTIw",
            android.util.Base64.DEFAULT
        )
    ).trim()
    private val translateUrl = "https://translate-pa.googleapis.com/v1/translateHtml"

    suspend fun translateChunks(paragraphs: List<String>, targetLang: String): List<String> {
        val maxChunkChars = 8_000
        val result = paragraphs.toMutableList()

        data class Chunk(val indices: List<Int>, val html: String)
        val chunks = mutableListOf<Chunk>()
        val currentIndices = mutableListOf<Int>()
        val currentHtml = StringBuilder()

        for ((i, para) in paragraphs.withIndex()) {
            val paraHtml = "<p>$para</p>"
            if (currentHtml.isNotEmpty() && currentHtml.length + paraHtml.length > maxChunkChars) {
                chunks.add(Chunk(currentIndices.toList(), currentHtml.toString()))
                currentIndices.clear()
                currentHtml.clear()
            }
            currentIndices.add(i)
            currentHtml.append(paraHtml)
        }
        if (currentHtml.isNotEmpty()) {
            chunks.add(Chunk(currentIndices.toList(), currentHtml.toString()))
        }

        Timber.d("WtrLab: Translating ${paragraphs.size} paragraphs in ${chunks.size} chunks → $targetLang")

        for ((idx, chunk) in chunks.withIndex()) {
            if (idx > 0) delay(500L)
            Timber.d("WtrLab: Chunk ${idx+1}/${chunks.size}: ${chunk.html.length} chars, ${chunk.indices.size} paragraphs")

            val translated = try {
                translateToTarget(chunk.html, targetLang)
            } catch (e: Exception) {
                Timber.e(e, "WtrLab: Chunk ${idx+1} failed, keeping original")
                continue
            }
            if (translated == chunk.html) continue

            val unescaped = android.text.Html.fromHtml(translated, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            val translatedParas = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                .findAll(unescaped)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()
                .ifEmpty { unescaped.split("\n").filter { it.isNotBlank() } }

            val minSize = minOf(translatedParas.size, chunk.indices.size)
            for (pos in 0 until minSize) {
                result[chunk.indices[pos]] = translatedParas[pos]
            }
            if (translatedParas.size != chunk.indices.size) {
                Timber.w("WtrLab: Chunk ${idx+1}: expected ${chunk.indices.size} paragraphs, got ${translatedParas.size}")
            }
        }

        return result
    }

    private suspend fun translateToTarget(text: String, targetLang: String): String =
        withContext(Dispatchers.IO) {
            val sourceLang = getSourceLang()
            Timber.d("WtrLab: Translate $sourceLang → $targetLang (${text.length} chars)")

            val payload = listOf(listOf(text, sourceLang, targetLang), "wt_lib")
            val requestBody = Gson().toJson(payload)
                .toRequestBody("application/json+protobuf".toMediaType())

            val request = Request.Builder()
                .url(translateUrl)
                .addHeader("X-Goog-Api-Key", apiKey)
                .addHeader("Origin", baseUrl.trimEnd('/'))
                .post(requestBody)

            val response = networkClient.call(request)
            if (!response.isSuccessful) {
                response.body?.close()
                Timber.e("WtrLab: Translate HTTP ${response.code} — returning original text")
                return@withContext text
            }

            val responseBody = response.body?.string() ?: return@withContext text
            Timber.d("WtrLab: Translate response: ${responseBody.take(200)}")

            try {
                val arr = JsonParser.parseString(responseBody).asJsonArray
                arr.get(0).asJsonArray.get(0).asString
            } catch (e: Exception) {
                Timber.e(e, "WtrLab: Failed to parse translate response")
                text
            }
        }
}