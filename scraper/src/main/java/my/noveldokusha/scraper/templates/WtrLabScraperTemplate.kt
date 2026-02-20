package my.noveldokusha.scraper.templates

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toJson
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.BookSelectors
import my.noveldokusha.scraper.configs.CatalogSelectors
import my.noveldokusha.scraper.configs.ChapterPaginationType
import my.noveldokusha.scraper.configs.ChapterSelectors
import my.noveldokusha.scraper.configs.Clean
import my.noveldokusha.scraper.configs.HtmlSelectors
import my.noveldokusha.scraper.configs.SearchSelectors
import my.noveldokusha.scraper.configs.applyStandardContentTransforms
import my.noveldokusha.scraper.configs.attr
import my.noveldokusha.scraper.configs.elements
import my.noveldokusha.scraper.configs.removeElementsDOM
import my.noveldokusha.scraper.configs.text
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.helpers.getBookCover
import my.noveldokusha.scraper.helpers.getBookDescription
import my.noveldokusha.scraper.helpers.getBookTitle
import my.noveldokusha.scraper.helpers.getCatalogList
import my.noveldokusha.scraper.helpers.getCatalogSearch
import my.noveldokusha.scraper.helpers.getChapterList
import my.noveldokusha.scraper.helpers.getChapterListHash
import my.noveldokusha.scraper.utils.UrlTransformers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import timber.log.Timber
import kotlin.random.Random

abstract class WtrLabScraperTemplate(
    protected val networkClient: NetworkClient,
    protected val appPreferences: AppPreferences
) : SourceInterface.Catalog {

    override val id = "wtrlab"
    override val nameStrId = R.string.source_name_wtrlab
    override val language = LanguageCode.MULTILANGUAGE
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/wtr-lab.png"
    override val iconResId = null

    override val baseUrl = "https://wtr-lab.com/"

    override val catalogUrl: String
        get() = "${baseUrl}novel-list"

    // Пользовательские режимы → параметр translate в API:
    //   "ai"  (UI) → "ai"  (API) = AI-перевод сайта, получаем английский текст
    //   "raw" (UI) → "web" (API) = сырой контент, после расшифровки получаем китайский
    private val translationMode: String
        get() = appPreferences.WTR_LAB_MODE.value

    private fun apiTranslateParam(): String = when (translationMode) {
        "raw" -> "web"  // "raw" в UI = "web" в API = китайский оригинал
        else  -> "ai"   // "ai"  в UI = "ai"  в API = английский перевод
    }

    // Исходный язык для Google Translate (поверх контента)
    private fun sourceLanguageForGT(): String = when (translationMode) {
        "raw" -> "zh-CN"  // оригинал китайский
        else  -> "en"     // AI-перевод английский
    }

    private val translationLanguage: String
        get() = appPreferences.WTR_LAB_LANGUAGE.value

    // ── Google Translate ──────────────────────────────────────────────────────

    private val translateApiKey = String(
        android.util.Base64.decode(
            "QUl6YVN5QVRCWGFqdnpRTFRESEVRYmNwcTBJaGUwdldESG1PNTIw",
            android.util.Base64.DEFAULT
        )
    ).trim()
    private val translateUrl = "https://translate-pa.googleapis.com/v1/translateHtml"

    // "none" = без Google Translate, иначе код языка для GT
    private fun getTargetLangCode(lang: String): String = when (lang) {
        "es" -> "es"; "ru" -> "ru"; "de" -> "de"
        "pl" -> "pl"; "it" -> "it"; "fr" -> "fr"
        else -> "none"
    }

    private suspend fun translateToTarget(text: String, targetLang: String): String =
        withContext(Dispatchers.IO) {
            val sourceLang = sourceLanguageForGT()
            Timber.d("WtrLab: Translate $sourceLang → $targetLang (${text.length} chars)")

            // API требует application/json+protobuf — именно этот Content-Type принимает эндпоинт
            val payload = listOf(listOf(text, sourceLang, targetLang), "wt_lib")
            val jsonBody = Gson().toJson(payload)
            val requestBody = jsonBody.toRequestBody("application/json+protobuf".toMediaType())

            val requestBuilder = Request.Builder()
                .url(translateUrl)
                .addHeader("X-Goog-Api-Key", translateApiKey)
                .addHeader("Origin", baseUrl.trimEnd('/'))
                .post(requestBody)

            val response = networkClient.call(requestBuilder)
            val responseBody = response.body?.string() ?: return@withContext text
            Timber.d("WtrLab: Translate response: ${responseBody.take(200)}")

            try {
                // Ответ: [["переведённый текст"]]
                val arr = JsonParser.parseString(responseBody).asJsonArray
                arr.get(0).asJsonArray.get(0).asString
            } catch (e: Exception) {
                Timber.e(e, "WtrLab: Failed to parse translate response: $responseBody")
                text
            }
        }

    // ── Chapter fetching ──────────────────────────────────────────────────────

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        val chapterUrl = doc.location()
        Timber.d("WtrLab: getChapterText for $chapterUrl")

        try {
            val parts = chapterUrl.trimEnd('/').split("/")
            val novelIndex = parts.indexOf("novel")
            if (novelIndex == -1) {
                Timber.e("WtrLab: 'novel' not found in URL: $chapterUrl")
                return@withContext ""
            }
            val novelId = parts.getOrNull(novelIndex + 1) ?: run {
                Timber.e("WtrLab: No novelId in URL: $chapterUrl")
                return@withContext ""
            }
            val chapterPart = parts.lastOrNull { it.startsWith("chapter-") } ?: parts.last()
            val chapterNo = chapterPart.removePrefix("chapter-").toIntOrNull() ?: 1

            val apiParam = apiTranslateParam()
            val lang = translationLanguage
            Timber.d("WtrLab: novelId=$novelId, chapterNo=$chapterNo, translate=$apiParam, lang=$lang")

            val jsonBody = Gson().toJson(
                mapOf(
                    "translate"   to apiParam,  // "ai" или "web"
                    "language"    to lang,
                    "raw_id"      to novelId,
                    "chapter_no"  to chapterNo,
                    "retry"       to false,
                    "force_retry" to false,
                )
            )
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val requestBuilder = Request.Builder()
                .url("${baseUrl}api/reader/get")
                .addHeader("Referer", chapterUrl)
                .addHeader("Origin", baseUrl.trimEnd('/'))
                .addHeader("Content-Type", "application/json")
                .post(requestBody)

            val response = networkClient.call(requestBuilder)
            val responseStr = response.body?.string() ?: run {
                Timber.e("WtrLab: Empty response body")
                return@withContext ""
            }
            Timber.d("WtrLab: API response (first 300): ${responseStr.take(300)}")

            val json = try {
                JsonParser.parseString(responseStr).asJsonObject
            } catch (e: Exception) {
                Timber.e(e, "WtrLab: Response is not JSON")
                return@withContext ""
            }

            if (json.has("requireTurnstile") || json.has("turnstile")) {
                Timber.w("WtrLab: Turnstile required — returning URL for WebView")
                return@withContext chapterUrl
            }

            if (json.get("success")?.asBoolean == false) {
                Timber.e("WtrLab: API error: ${json.get("error")?.asString}")
                return@withContext ""
            }

            // Структура: { data: { data: { body, glossary_data, patch } } }
            val outerData = json.getAsJsonObject("data")
            val data: JsonObject? = outerData?.getAsJsonObject("data") ?: outerData
            if (data == null) {
                Timber.e("WtrLab: No 'data' in response")
                return@withContext ""
            }

            val bodyElement = data.get("body")
            val rawBody = when {
                bodyElement == null         -> null
                bodyElement.isJsonArray     -> bodyElement.asJsonArray.toString()
                bodyElement.isJsonPrimitive -> bodyElement.asString
                else                        -> null
            }

            if (rawBody.isNullOrBlank()) {
                Timber.e("WtrLab: Body is empty")
                return@withContext ""
            }

            // Расшифровка через прокси если body зашифрован (формат "arr:BASE64:BASE64")
            val resolvedBody: String = if (rawBody.startsWith("arr:")) {
                Timber.d("WtrLab: Body encrypted, sending to proxy")
                try {
                    val proxyRequestBody = Gson().toJson(mapOf("payload" to rawBody))
                    val proxyRequest = Request.Builder()
                        .url("https://wtr-lab-proxy.fly.dev/chapter")
                        .addHeader("Content-Type", "application/json")
                        .post(proxyRequestBody.toRequestBody("application/json".toMediaType()))
                    val proxyResponse = networkClient.call(proxyRequest)
                    val proxyStr = proxyResponse.body?.string() ?: ""
                    Timber.d("WtrLab: Proxy response (first 100): ${proxyStr.take(100)}")
                    // Прокси возвращает JSON-массив напрямую: ["paragraph1", "paragraph2", ...]
                    // НЕ объект {"body": [...]}
                    val proxyElement = JsonParser.parseString(proxyStr)
                    when {
                        proxyElement.isJsonArray  -> proxyElement.asJsonArray.toString()
                        proxyElement.isJsonObject -> proxyElement.asJsonObject.get("body")
                            ?.asJsonArray?.toString() ?: rawBody
                        else -> rawBody
                    }
                } catch (e: Exception) {
                    Timber.e(e, "WtrLab: Proxy decryption failed")
                    rawBody
                }
            } else {
                rawBody
            }

            val bodyArray = try {
                if (resolvedBody.startsWith("[")) JsonParser.parseString(resolvedBody).asJsonArray
                else null
            } catch (e: Exception) { null }

            // Глоссарий: index → term
            val glossaryTerms = mutableMapOf<Int, String>()
            data.getAsJsonObject("glossary_data")
                ?.getAsJsonArray("terms")
                ?.forEachIndexed { i, el ->
                    runCatching {
                        el.asJsonArray.get(0)?.asString?.takeIf { it.isNotEmpty() }
                            ?.let { glossaryTerms[i] = it }
                    }
                }

            // Патч zh→en
            val patchMap = mutableMapOf<String, String>()
            data.getAsJsonArray("patch")?.forEach { el ->
                runCatching {
                    val obj = el.asJsonObject
                    val zh = obj.get("zh")?.asString
                    val en = obj.get("en")?.asString
                    if (!zh.isNullOrEmpty() && !en.isNullOrEmpty()) patchMap[zh] = en
                }
            }

            // Сборка параграфов
            val paragraphs = mutableListOf<String>()
            if (bodyArray != null) {
                for (i in 0 until bodyArray.size()) {
                    val el = bodyArray.get(i)
                    if (!el.isJsonPrimitive) continue
                    var text = el.asString
                    if (text == "[image]" || text.isBlank()) continue
                    for ((idx, term) in glossaryTerms) {
                        text = text.replace("※$idx⛬", term).replace("※$idx〓", term)
                    }
                    for ((zh, en) in patchMap) text = text.replace(zh, en)
                    if (text.isNotBlank()) paragraphs.add(text)
                }
            } else {
                resolvedBody.split("\n").filter { it.isNotBlank() }.forEach { paragraphs.add(it) }
            }

            if (paragraphs.isEmpty()) {
                Timber.w("WtrLab: 0 paragraphs parsed")
                return@withContext ""
            }

            // Google Translate поверх если язык выбран
            val targetLang = getTargetLangCode(lang)
            val finalParagraphs = if (targetLang != "none") {
                try {
                    // Оборачиваем в <p> теги перед отправкой в GT —
                    // так GT сохраняет структуру абзацев и не склеивает их
                    val htmlToTranslate = paragraphs.joinToString("") { "<p>$it</p>" }
                    val translated = translateToTarget(htmlToTranslate, targetLang)
                    // GT возвращает HTML-энтити (&quot; &amp; и т.д.) — декодируем
                    val unescaped = android.text.Html.fromHtml(translated, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    // Извлекаем обратно текст из <p> тегов
                    Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(unescaped)
                        .map { it.groupValues[1].trim() }
                        .filter { it.isNotBlank() }
                        .toList()
                        .ifEmpty {
                            unescaped.split("\n").filter { it.isNotBlank() }
                        }
                } catch (e: Exception) {
                    Timber.e(e, "WtrLab: GT failed, using original")
                    paragraphs
                }
            } else paragraphs

            finalParagraphs.joinToString("\n") { "<p>$it</p>" }

        } catch (e: Exception) {
            Timber.e(e, "WtrLab: Unexpected error")
            ""
        }
    }

    // ── Catalog / Book delegates ──────────────────────────────────────────────

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    // ── Config ────────────────────────────────────────────────────────────────

    private val config: HtmlSelectors by lazy {
        HtmlSelectors(
            baseUrl  = baseUrl,
            language = language,

            catalog = CatalogSelectors(
                item  = elements("div.serie-item"),
                title = text("a.title").Clean(),
                url   = attr("href", "a.title"),
                cover = attr("src", ".image-wrap img")
            ),

            search = SearchSelectors(
                item  = elements("div.serie-item"),
                title = text("a.title").Clean(),
                url   = attr("href", "a.title"),
                cover = attr("src", ".image-wrap img")
            ),

            book = BookSelectors(
                title             = text("h1.long-title").Clean(),
                cover             = attr("src", ".image-section .image-wrap img"),
                description       = text(".desc-wrap .description"),
                latestChapterHash = text(".detail-line:contains('Chapters')").Clean()
            ),

            chapters = ChapterSelectors(
                list    = elements("a[href*='/chapter-']"),
                title   = text("a"),
                content = text("article, .content, main")
                    .removeElementsDOM("script", "nav", "footer", ".ads", ".advertisement", ".comments")
                    .applyStandardContentTransforms(baseUrl)
            ),

            chapterPaginationType = ChapterPaginationType.AJAX_BASED,

            ajaxChapterListProvider = { bookUrl, networkClient ->
                runCatching {
                    delay(Random.nextLong(200, 500))

                    val novelId = Regex("/novel/(\\d+)/").find(bookUrl)?.groupValues?.get(1)
                        ?: return@runCatching emptyList()
                    val slug = Regex("/novel/\\d+/([^/]+)").find(bookUrl)?.groupValues?.get(1) ?: ""

                    val requestBuilder = Request.Builder()
                        .url("${baseUrl}api/chapters/$novelId")
                        .addHeader("Referer", bookUrl)
                        .get()

                    val chaptersJson = networkClient.call(requestBuilder).toJson() as? JsonObject
                        ?: return@runCatching emptyList()

                    val chaptersArray = chaptersJson.getAsJsonArray("chapters")
                        ?: return@runCatching emptyList()

                    val result = mutableListOf<ChapterResult>()
                    for (i in 0 until chaptersArray.size()) {
                        val chapter = chaptersArray.get(i).asJsonObject
                        val order = chapter.get("order")?.asInt ?: continue
                        val title = chapter.get("title")?.asString ?: "Chapter $order"
                        result.add(ChapterResult(
                            title = "$order: $title",
                            url   = "${baseUrl}novel/$novelId/$slug/chapter-$order"
                        ))
                    }
                    result
                }.onFailure { e ->
                    Timber.e(e, "WtrLab: Failed to load chapters for $bookUrl")
                }.getOrNull() ?: emptyList()
            },

            buildCatalogUrl = { index -> "$catalogUrl?page=${index + 1}" },

            buildSearchUrl = { index, query ->
                "${baseUrl}novel-finder?text=$query&page=${index + 1}"
            },

            transformBookUrl    = UrlTransformers.standardBookUrl(baseUrl),
            transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
            transformCoverUrl   = UrlTransformers.standardCoverUrl(baseUrl)
        )
    }
}