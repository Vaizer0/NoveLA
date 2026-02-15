# Guide: How to Add a New Source

## Общая архитектура источников

### Что такое SourceInterface

SourceInterface - это основной интерфейс для всех источников в NovelDokushaTT. Он определяет общую структуру и поведение для получения данных из различных источников новелл.

```kotlin
sealed interface SourceInterface {
    val id: String
    @get:StringRes val nameStrId: Int
    val baseUrl: String
    val isLocalSource: Boolean
    val requiresLogin: Boolean
    val charset: String
    
    // Основные методы для получения данных
    suspend fun getChapterText(doc: Document): String?
    
    interface Catalog : SourceInterface {
        val catalogUrl: String
        val language: LanguageCode?
        
        // Методы для работы с каталогом
        suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?>
        suspend fun getBookDescription(bookUrl: String): Response<String?>
        suspend fun getBookTitle(bookUrl: String): Response<String?>
        suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>>
        suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>>
        suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>>
    }
}
```

### Разница между Catalog, Book, Chapter

- **Catalog**: Отвечает за список книг, поиск и навигацию по каталогу
- **Book**: Содержит информацию о конкретной книге (обложка, описание)
- **Chapter**: Отвечает за список глав и текст главы

### Как работают Response, PagedList, tryConnect

- **Response**: Обертка для безопасного выполнения операций с возможностью обработки ошибок
- **PagedList**: Структура для постраничной загрузки данных с поддержкой пагинации
- **tryConnect**: Утилита для безопасного выполнения сетевых запросов с обработкой исключений

## Decision Tree: Выбор типа источника

```
┌─ Есть ли у сайта API (JSON)?
├─ YES → JSON/API источник (RanobeLib)
└─ NO → 
   ┌─ Нужен ли HTML-парсинг?
   ├─ YES → 
   │  ┌─ Нужен ли AJAX для получения глав?
   │  ├─ YES → HTML + AJAX источник (ScribbleHub, ReadNovelFull, NovelBin)
   │  └─ NO → 
   │     ┌─ Нужен ли POST-запрос для поиска?
   │     ├─ YES → HTML + POST Search (FreeWebNovel)
   │     └─ NO → 
   │        ┌─ Требуется ли предварительный HTML-шаг?
   │        ├─ YES → HTML + AJAX (GET + HTML pre-step)
   │        └─ NO → HTML Static (RoyalRoad)
   └─ NO → Custom/Manual источник (RanobeHub)
```

## Детальный анализ источников

### 1. HTML Static - RoyalRoad.kt

**Тип:** HTML Static
**Конфиг:** HtmlSelectors
**Методы:** Делегированы хелперам

```kotlin
class RoyalRoad(private val networkClient: NetworkClient) : SourceInterface.Catalog {
    // Все методы делегированы хелперам
    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,
        
        catalog = CatalogSelectors(
            item = elements(".fiction-list-item"),
            title = text("h2 a"),
            url = attr("href", "h2 a"),
            cover = attr("src", "img")
        ),
        
        chapters = ChapterSelectors(
            list = elements("tr.chapter-row td:first-child a[href]"),
            title = text("a"),
            content = text(".chapter-content")
                .removeElementsDOM("script", "a", ".ads-title")
                .applyStandardContentTransforms(baseUrl)
        ),
        
        chapterPaginationType = ChapterPaginationType.NONE, // Простой случай
        postSearchEnabled = false, // Без POST-поиска
        
        buildCatalogUrl = { index ->
            if (index == 0) "$baseUrl/fictions/best-rated"
            else "$baseUrl/fictions/best-rated?page=${index + 1}"
        },
        
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl)
    )
}
```

**Когда использовать:**
- Простой HTML сайт с прямым доступом
- Нет AJAX-взаимодействий
- Статические селекторы работают надежно

### Quick Update Check - Быстрая проверка обновлений

Для ускорения обновления библиотеки можно добавить `latestChapterHash` в `book` конфигурацию источника. Это позволяет быстро проверить, изменился ли список глав, без загрузки всех глав.

```kotlin
private val config: HtmlSelectors = HtmlSelectors(
    // ... остальная конфигурация ...
    
    book = BookSelectors(
        title = text("h1").Clean(),
        cover = attr("src", "div.img-book > img"),
        description = text("#desc-tab"),
        // Быстрая проверка обновлений - использует заголовок последней главы как хеш
        latestChapterHash = text("div.title:last-child a h2")
    ),
    
    // ... остальная конфигурация ...
)
```

**Примеры:**

```kotlin
// Последняя глава по заголовку
book = BookSelectors(
    // ...
    latestChapterHash = text("div.chapters-list .chapter-item:last-child .chapter-title")
)

// Общее количество глав (через attr если в атрибуте)
book = BookSelectors(
    // ...
    latestChapterHash = attr("data-count", ".chapter-count")
)

// Дата последнего обновления
book = BookSelectors(
    // ...
    latestChapterHash = attr("datetime", ".last-update")
)
```

**Также нужно добавить метод в класс источника:**
```kotlin
override suspend fun getChapterListHash(bookUrl: String) = 
    getChapterListHash(config, bookUrl, networkClient)
```

**Когда использовать:**
- Источник медленно загружает список глав (AJAX, пагинация)
- Большая библиотека с частыми обновлениями
- Хотите избежать бана от частых запросов

**Примечание:** Если `latestChapterHash` не настроен, источник будет всегда загружать полный список глав при обновлении библиотеки.

### 2. HTML + POST Search - FreeWebNovel.kt

**Тип:** HTML + POST Search
**Конфиг:** HtmlSelectors
**Особенности:** POST-поиск с заголовками

```kotlin
private val config: HtmlSelectors = HtmlSelectors(
    // ... стандартные селекторы ...
    
    postSearchEnabled = true,
    postSearchUrl = "$baseUrl/search",
    postSearchDataBuilder = { query -> mapOf("searchkey" to query) },
    searchHeaders = mapOf( // Специальные заголовки для POST-поиска
        "User-Agent" to "Mozilla/5.0...",
        "Accept" to "text/html,application/xhtml+xml...",
        "Referer" to "https://freewebnovel.com/"
    ),
    
    buildSearchUrl = { index, query -> "$baseUrl/search" }, // Всегда одинаковый URL для POST
)
```

**Когда использовать:**
- Поиск требует POST-запросов
- Нужна специальная аутентификация или заголовки
- Форма поиска отправляет специальные данные

### 3. HTML + AJAX (GET) - NovelBin.kt

**Тип:** HTML + AJAX (GET)
**Конфиг:** HtmlSelectors
**Особенности:** AJAX-провайдер для списка глав

```kotlin
private val config: HtmlSelectors = HtmlSelectors(
    // ... стандартные селекторы ...
    
    chapterPaginationType = ChapterPaginationType.AJAX_BASED,
    ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl, networkClient ->
        try {
            // Получаем novelId из meta[property=og:url] на странице книги
            val response = networkClient.get(bookUrl)
            val doc = response.toDocument()
            val novelId = doc.selectFirst("meta[property=og:url]")
                ?.attr("content")
                ?.toUrlBuilderSafe()
                ?.build()
                ?.lastPathSegment
                ?: return@ajaxChapterListProvider emptyList()

            val ajaxUrl = "https://novelbin.com/ajax/chapter-archive?novelId=$novelId"
            val ajaxResponse = networkClient.get(ajaxUrl)
            val ajaxDoc = ajaxResponse.toDocument()

            ajaxDoc.select("ul.list-chapter li a").map { element ->
                ChapterResult(
                    title = element.text(),
                    url = element.attr("href")
                )
            }
        } catch (e: Exception) {
            Timber.w("Failed to get chapters for NovelBin URL $bookUrl: ${e.message}")
            emptyList()
        }
    },
)
```

**Когда использовать:**
- Главы подгружаются через AJAX
- Нужно извлекать параметры из HTML и использовать в AJAX-запросе
- Список глав формируется динамически

### 4. HTML + AJAX (GET) - ScribbleHub.kt

**Тип:** HTML + AJAX (GET)
**Конфиг:** HtmlSelectors
**Особенности:** Сложный AJAX с заголовками и POST-данными

```kotlin
ajaxChapterListProvider = { bookUrl, networkClient ->
    // Extract series ID from URL
    val seriesId = Regex("series/(\\d+)/").find(bookUrl)?.groupValues?.get(1)
        ?: throw Exception("Invalid ScribbleHub book URL: $bookUrl")

    // AJAX URL for chapter list
    val ajaxUrl = buildUrl(baseUrl, "wp-admin/admin-ajax.php")

    // POST request with form data and AJAX headers
    val doc = POST(
        url = ajaxUrl,
        data = mapOf(
            "action" to "wi_getreleases_pagination",
            "pagenum" to "-1", // Get all chapters
            "mypostid" to seriesId
        ),
        headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest", // Важный заголовок для AJAX
            "Origin" to baseUrl.removeSuffix("/"),
            "Referer" to bookUrl,
            "Accept" to "*/*"
        ),
        networkClient = networkClient
    )

    // Parse chapters from AJAX response
    doc.select(".toc_w a[href]").reversed().map { element ->
        ChapterResult(
            title = element.text(),
            url = element.attr("href")
        )
    }
},
```

**Когда использовать:**
- WordPress-сайты с AJAX-функциональностью
- Сложные AJAX-запросы с специальными заголовками
- Нужна обработка специфичных параметров

### 5. HTML + AJAX (GET) - ReadNovelFull.kt

**Тип:** HTML + AJAX (GET)
**Конфиг:** HtmlSelectors
**Особенности:** Извлечение параметров из HTML-элемента

```kotlin
ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl, networkClient ->
    try {
        // Extract novelId from #rating[data-novel-id]
        val pageDoc = networkClient.get(bookUrl).toDocument()
        val novelId = pageDoc.selectFirst("#rating[data-novel-id]")
            ?.attr("data-novel-id")
            ?: return@ajaxChapterListProvider emptyList()

        // AJAX request to chapter-archive
        val ajaxUrl = "$baseUrl/ajax/chapter-archive?novelId=$novelId"
        val ajaxDoc = networkClient.get(ajaxUrl).toDocument()

        // Parse chapters from AJAX response
        ajaxDoc.select("a[href]").map { element ->
            ChapterResult(
                title = element.attr("title").ifBlank { element.text() }.trim(),
                url = element.attr("href").let { href ->
                    if (href.startsWith("http")) href else baseUrl + href.removePrefix("/")
                }
            )
        }
    } catch (e: Exception) {
        Timber.w("Failed to get chapters for ReadNovelFull URL $bookUrl: ${e.message}")
        emptyList()
    }
},
```

**Когда использовать:**
- Сайты с data-атрибутами в HTML
- Нужна предварительная загрузка страницы для извлечения параметров
- AJAX-запросы с динамическими параметрами

### 6. HTML + Page-based Pagination - AllNovel.kt

**Тип:** HTML + Page-based Pagination
**Конфиг:** HtmlSelectors
**Особенности:** Page-based пагинация глав

```kotlin
chapterPaginationType = ChapterPaginationType.PAGE_BASED,
chapterPaginationConfig = ChapterPaginationConfig(
    maxPageExtractor = { doc ->
        val lastPageElement = doc.selectFirst("#list-chapter > ul:nth-child(3) > li.last > a")
        val href = lastPageElement?.attr("href")
        href?.substringAfter("?page=", "")?.toIntOrNull() ?: 1
    },
    pageUrlBuilder = { bookUrl, page -> "$bookUrl?page=$page" },
    chapterSelector = "ul.list-chapter li a"
),
```

**Когда использовать:**
- Сайты с постраничной навигацией глав
- Главы распределены по нескольким страницам
- Нужно посетить каждую страницу для получения всех глав

### 7. HTML + Reverse Chapters - BacaLightnovel.kt

**Тип:** HTML + Reverse Chapters
**Конфиг:** HtmlSelectors
**Особенности:** Обратный порядок глав

```kotlin
// Chapters order - reverse (newest first)
reverseChapters = true,

chapters = ChapterSelectors(
    list = elements(".eplister li > a:not(.dlpdf)"),
    title = text(".epl-title"),
    content = text(".epcontent[itemprop=text] .text-left")
        .removeElementsDOM("script", ".ads")
        .applyStandardContentTransforms(baseUrl)
),
```

**Когда использовать:**
- Сайты, где главы отображаются в обратном порядке (новые первыми)
- Нужно вернуть главы в хронологическом порядке (старые первыми)

### 8. HTML + POST Search + GBK Encoding - Shuba69.kt

**Тип:** HTML + POST Search + GBK Encoding
**Конфиг:** HtmlSelectors
**Особенности:** GBK кодировка для китайского контента

```kotlin
charset = "GBK", // GBK encoding for Chinese content

postSearchEnabled = true,
postSearchUrl = "https://www.69shuba.com/modules/article/search.php",
postSearchDataBuilder = { query ->
    // Encode search query in GBK
    val encodedQuery = java.net.URLEncoder.encode(query, "GBK")
    mapOf(
        "searchkey" to encodedQuery,
        "searchtype" to "all" // Required parameter for this site
    )
},

// Special URL processing for AJAX
ajaxChapterListProvider = { bookUrl, networkClient ->
    // Convert /txt/A43616.htm to /A43616/ for chapter list
    val chapterListUrl = bookUrl.replace("/txt/", "/").replace(".htm", "/")
    
    // Site lists chapters newest-first; reverse so we return oldest-first
    networkClient.get(chapterListUrl).toDocument("GBK")
        .select("div#catalog ul li a")
        .map { element ->
            ChapterResult(
                title = element.text().trim(),
                url = URI(baseUrl).resolve(element.attr("href")).toString()
            )
        }
        .asReversed()
},
```

**Когда использовать:**
- Сайты с китайским/японским/корейским контентом
- Нужна специфичная кодировка (GBK, UTF-16)
- POST-поиск с кодированием

### 9. HTML + AJAX JSON Parsing - Ttkan.kt

**Тип:** HTML + AJAX JSON Parsing
**Конфиг:** HtmlSelectors
**Особенности:** Регекс-парсинг JSON для извлечения глав

```kotlin
ajaxChapterListProvider = ajaxChapterListProvider@{ bookUrl, networkClient ->
    try {
        // Extract novel ID from /novel/chapters/{novel_id}
        val novelId = bookUrl.substringAfter("/novel/chapters/").substringBefore("?").trim()

        // Use API endpoint to get all chapters
        val apiUrl = "https://www.ttkan.co/api/nq/amp_novel_chapters?language=tw&novel_id=$novelId"

        val response = networkClient.get(apiUrl)
        val jsonText = response.body.string()

        // Parse JSON response manually
        val chapters = mutableListOf<ChapterResult>()

        // Simple regex-based JSON parsing for chapter data
        val itemPattern = """"chapter_name"\s*:\s*"([^"]+)"\s*,\s*"chapter_id"\s*:\s*(\d+)""".toRegex()
        var index = 1
        itemPattern.findAll(jsonText).forEach { match ->
            val chapterName = match.groupValues[1]
            val chapterUrl = "https://www.ttkan.co/novel/pagea/${novelId}_${index}.html"

            chapters.add(
                ChapterResult(
                    title = chapterName,
                    url = chapterUrl
                )
            )
            index++
        }

        chapters
    } catch (e: Exception) {
        Timber.w("Failed to load chapters for Ttkan: ${e.message}")
        emptyList()
    }
},
```

**Когда использовать:**
- Сайты с API, возвращающим JSON в нестандартном формате
- Нужен кастомный парсинг JSON
- AJAX через специфичные эндпоинты

### 10. JSON / API (стабильный контракт) - RanobeLib.kt

**Тип:** JSON API
**Конфиг:** JsonApiScraperConfig
**Особенности:** Сложная обработка JSON, проксирование изображений, парсинг сложных структур

```kotlin
private val config = JsonApiScraperConfig(
    baseUrl = baseUrl,
    apiBaseUrl = apiBaseUrl,
    language = language,
    siteId = "3",
    headers = mapOf("Site-Id" to "3"),

    // Ключи для парсинга JSON
    catalogDataKey = "data",
    catalogTitleKeys = listOf("rus_name", "eng_name", "name"),
    catalogUrlKey = "slug",
    catalogCoverKey = "cover.default",
    catalogHasNextKey = "meta.has_next_page",

    // URL билдеры для API
    buildCatalogUrl = { page ->
        "$apiBaseUrl?site_id[0]=3&page=$page&sort_by=rating_score&sort_type=desc&chapters[min]=1"
    },
    buildSearchUrl = { page, query -> "$apiBaseUrl?site_id[0]=3&page=$page&q=$query" },
    buildBookUrl = { slug -> "$apiBaseUrl$slug" },
    buildChapterListUrl = { slug -> "$apiBaseUrl$slug/chapters" },

    // Парсеры данных - вся сложная логика здесь
    parseBookData = { json ->
        val data = json.getAsJsonObject("data")
        val names = data?.getAsJsonObject("names")
        val title = names?.get("rus")?.asString
            ?: names?.get("eng")?.asString
            ?: names?.get("original")?.asString
            ?: data?.get("rus_name")?.asString
            ?: data?.get("eng_name")?.asString
            ?: data?.get("name")?.asString
            ?: ""

        val cover = data?.getAsJsonObject("cover")?.get("default")?.asString
        val description = data?.get("summary")?.asString

        BookData(title, cover?.let { proxiedImageUrl(it) }, description)
    },

    parseChapterData = { json, slug ->
        if (slug == null) {
            emptyList<my.noveldokusha.scraper.domain.ChapterResult>()
        } else {
            // Сложная логика обработки томов и глав
            val volumes = json.getAsJsonArray("volumes") ?: com.google.gson.JsonArray()
            val chapterItems = mutableListOf<ChapterItem>()

            volumes.forEachIndexed { volumeIndex, volumeElement ->
                val volume = volumeElement.asJsonObject
                val volumeNum = volume.get("num")?.asInt ?: volumeIndex + 1

                volume.getAsJsonArray("chapters")?.forEachIndexed { chapterIndex, chapterElement ->
                    val chapter = chapterElement.asJsonObject
                    val chapterNum = chapter.get("num")?.asFloat ?: (chapterIndex + 1).toFloat()
                    val chapterId = chapterItems.size + 1

                    chapterItems.add(ChapterItem(
                        chapterResult = ChapterResult(
                            title = chapter.get("name")?.asString ?: "Chapter $chapterNum",
                            url = "${baseUrl}ru/$slug/read/v$volumeNum/c$chapterNum"
                        ),
                        index = chapterId
                    ))
                }
            }
            chapterItems.sortedBy { it.index }.map { it.chapterResult }
        }
    },

    parseChapterContent = { json ->
        val data = json.getAsJsonObject("data")
        val contentElement = data?.get("content")
        val attachments = data?.getAsJsonArray("attachments")

        when {
            contentElement?.isJsonObject == true &&
            contentElement.asJsonObject.get("type")?.asString == "doc" -> {
                val body = contentElement.asJsonObject.getAsJsonArray("content")
                jsonToHtml(body, attachments) // Специальный парсер JSON->HTML
            }
            contentElement?.isJsonPrimitive == true -> {
                // Готовый HTML - нужно проксировать img src
                val htmlContent = contentElement.asString
                // Заменяем все img src на проксированные URL
                htmlContent.replace(Regex("""src="([^"]+)"""")) { match ->
                    val originalUrl = match.groupValues[1]
                    val proxiedUrl = proxiedImageUrl(originalUrl)
                    """src="$proxiedUrl"""
                }
            else -> ""
        }
    }
)

// Вспомогательные функции для сложной обработки
private fun proxiedImageUrl(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val trimmed = raw.removePrefix("https://").removePrefix("http://")
    val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
    return "https://images.weserv.nl/?url=$encoded&https=1"
}

private fun jsonToHtml(elements: JsonArray?, attachments: JsonArray?): String {
    // Сложная рекурсивная обработка JSON-структуры в HTML
    // Поддержка форматирования, изображений, списков и т.д.
}
```

**Когда использовать:**
- Сайты с полноценным JSON API
- Нужна сложная обработка данных
- Требуется проксирование изображений или специальная обработка контента

### 11. Custom / Manual Source - RanobeHub.kt

**Тип:** Custom Manual
**Конфиг:** Ручная реализация всего
**Особенности:** Смешанный подход API + HTML, уникальная логика

```kotlin
class RanobeHub(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    private val apiBase = "https://ranobehub.org/api/"

    override suspend fun getBookTitle(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect null
                val url = "${apiBase}ranobe/$id"
                val data = networkClient
                    .call(getRequest(url))
                    .toJson()
                    .asJsonObject
                    .getAsJsonObject("data")

                // На сайте бывают названия на русском, английском и оригинале
                data?.getAsJsonObject("names")?.get("rus")?.asString
                    ?: data?.getAsJsonObject("names")?.get("eng")?.asString
                    ?: data?.getAsJsonObject("names")?.get("original")?.asString
                    ?: data?.get("name")?.asString
            }
        }

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        val indexA = doc.html().indexOf("<div class=\"title-wrapper\">")
        val indexB = doc.html().indexOf("<div class=\"ui text container\"", indexA)

        if (indexA != -1 && indexB != -1) {
            val chapterHtml = doc.html().substring(indexA, indexB)
            // Replace media IDs with proper URLs
            val processedHtml = chapterHtml.replace(
                Regex("<img data-media-id=\"(.*?)\".*?>"),
                "<img src=\"/api/media/$1\">"
            )

            // Try to extract content more precisely
            val parsed = Jsoup.parse(processedHtml)
            val body = parsed.body()

            if (body != null) {
                // Remove unwanted elements
                body.select("script, .ads, .advertisement, .title-wrapper").remove()

                // Look for actual content
                val contentSelectors = listOf(
                    ".text",
                    ".content",
                    ".chapter-content",
                    ".reader-text",
                    "[class*='text']",
                    "[class*='content']",
                    ".ui.text.container p"
                )

                for (selector in contentSelectors) {
                    val contentElement = body.selectFirst(selector)
                    if (contentElement != null && contentElement.text().trim().isNotEmpty()) {
                        return@withContext TextExtractor.get(contentElement)
                    }
                }

                // Fallback to the whole body
                val text = TextExtractor.get(body)
                if (text.trim().isNotEmpty()) {
                    return@withContext text
                }
            }

            // Final fallback
            return@withContext Jsoup.parse(processedHtml).body()?.let { TextExtractor.get(it) } ?: processedHtml
        } else {
            ""
        }
    }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect emptyList()
                val url = "${apiBase}ranobe/$id/contents"

                val volumes = networkClient
                    .call(getRequest(url))
                    .toJson()
                    .asJsonObject
                    .getAsJsonArray("volumes")

                val chapters = mutableListOf<ChapterItem>()
                volumes?.forEach { volumeElement ->
                    val volume = volumeElement.asJsonObject
                    val volumeNum = volume.get("num")?.asInt ?: 0

                    volume.getAsJsonArray("chapters")?.forEach { chapterElement ->
                        val chapter = chapterElement.asJsonObject
                        val chapterNum = chapter.get("num")?.asFloat ?: 0f
                        val chapterId = chapters.size + 1

                        chapters.add(ChapterItem(
                            chapterResult = ChapterResult(
                                title = chapter.get("name")?.asString ?: "Chapter $chapterNum",
                                url = "$baseUrl/ranobe/$id/$volumeNum/$chapterNum"
                            ),
                            index = chapterId
                        ))
                    }
                }

                chapters.sortedBy { it.index }.map { it.chapterResult }
            }
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val page = index + 1
                val url = "${apiBase}search?page=$page&sort=computed_rating&status=0&take=40"

                val resource = networkClient
                    .call(getRequest(url))
                    .toJson()
                    .asJsonObject
                    .getAsJsonArray("resource")

                val books = resource?.mapNotNull { element ->
                    val novel = element.asJsonObject

                    val names = novel.getAsJsonObject("names")
                    val title = names?.get("rus")?.asString
                        ?: names?.get("eng")?.asString
                        ?: names?.get("original")?.asString

                    val id = novel.get("id")?.asString
                    val poster = novel.getAsJsonObject("poster")?.get("medium")?.asString

                    if (title != null && id != null) {
                        BookResult(
                            title = title,
                            url = "$baseUrl/ranobe/$id",
                            coverImageUrl = poster ?: ""
                        )
                    } else null
                } ?: emptyList()

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty()
                )
            }
        }

    private fun extractId(url: String): String? =
        url.removePrefix(baseUrl).trim('/').split("/").getOrNull(1)

    private data class ChapterItem(
        val chapterResult: ChapterResult,
        val index: Int,
    )
}
```

**Когда использовать:**
- Смешанный подход (API + HTML)
- Уникальная архитектура сайта
- Сложная логика обработки
- Нестандартные требования

### 12. Сводка по типам источников

| Тип источника | Количество | Примеры |
|---------------|------------|---------|
| HTML Static | 5 | RoyalRoad, NoBadNovel, NovelHall, FreeWebNovel, WuxiaWorld_site |
| HTML + Page-based Pagination | 2 | AllNovel, NovelFull |
| HTML + AJAX | 7 | NovelBin, ReadNovelFull, ScribbleHub, NovLove, Shuba69, Twkan, WuxiaWorld_site |
| HTML + Reverse Chapters | 6 | BacaLightnovel, Bookhamster, Ifreedom, Jaomix, Novelku, WuxiaWorld_site |
| HTML + POST Search | 2 | FreeWebNovel, Shuba69 |
| JSON API | 1 | RanobeLib |
| Custom Manual | 1 | RanobeHub |

**Специфичные особенности:**
- **GBK кодировка:** Shuba69.kt
- **AJAX через JSON парсинг:** Ttkan.kt
- **WordPress структура:** ScribbleHub.kt, Novelku.kt, WuxiaWorld_site.kt
- **Сложные трансформеры:** NovelBin, RanobeLib
- **POST-поиск с заголовками:** FreeWebNovel, Shuba69
- **Обратный порядок глав:** BacaLightnovel, Bookhamster, Ifreedom, Jaomix, Novelku, WuxiaWorld_site
- **Page-based пагинация:** AllNovel, NovelFull
- **AJAX-пагинация:** NovelBin, ReadNovelFull, ScribbleHub, NovLove, Shuba69, Twkan, WuxiaWorld_site

## Практические советы

### Анализ сайта перед созданием источника

Перед тем как начать писать конфигурацию источника, необходимо провести комплексный анализ сайта. Минимальный набор для анализа:

**1. Главная страница и структура каталога:**
- Структура списка книг (CSS-классы, контейнеры)
- Как выглядят элементы каталога (обложка, название, ссылка)
- Пагинация каталога (как переключаются страницы)
- Фильтры и сортировки

**2. Страница книги:**
- Расположение обложки (img, meta теги, CSS-классы)
- Расположение описания (теги, классы, структура)
- Дополнительная информация (тэги, жанры, автор, рейтинг)
- Список глав (структура, пагинация, ссылки)

**3. Страница главы:**
- Основной контент главы (CSS-классы, теги)
- Заголовок главы
- Медиа-элементы (картинки, видео)
- Скрипты и рекламные блоки (что нужно удалить)
- Навигация между главами

**4. Поиск:**
- Тип поиска (GET параметры, POST-форма, AJAX)
- Структура результатов поиска
- Пагинация результатов поиска
- Обработка специальных символов и кодировки

**5. API (если доступен):**
- Доступные эндпоинты
- Формат запросов и ответов
- Требования к аутентификации
- Ограничения и рейт-лимиты

**6. Дополнительные динамические элементы:**
- AJAX-пагинация (как подгружаются дополнительные главы)
- Lazy-loading (отложенная загрузка контента)
- Скрытые главы (требующие аутентификации или специальных действий)
- Динамические параметры (CSRF токены, сессии)

### Обработка ошибок
- Всегда используйте `tryConnect` для сетевых операций
- Обрабатывайте пустые результаты
- Логируйте ошибки с помощью Timber
- Возвращайте корректные `Response.Error` при проблемах

### Работа с JSON
- Используйте Gson для парсинга
- Обрабатывайте nullable поля
- Проверяйте структуру JSON перед доступом
- Используйте безопасный доступ к вложенным объектам

### Парсинг HTML
- Используйте конкретные CSS-селекторы
- Обрабатывайте разные варианты структуры
- Проверяйте наличие элементов перед доступом
- Используйте fallback-селекторы

### Fallback-стратегии
- Предоставляйте запасные варианты для селекторов
- Используйте множественные селекторы через запятую
- Обрабатывайте разные форматы данных
- Возвращайте пустые списки вместо ошибок

### Пагинация
- Корректно определяйте последнюю страницу
- Обрабатывайте случай пустых результатов
- Используйте правильные индексные значения
- Сохраняйте состояние пагинации

### URL-трансформации
- Используйте `UrlTransformers` для стандартных преобразований
- Обрабатывайте относительные и абсолютные URL
- Проксируйте изображения при необходимости
- Проверяйте корректность сгенерированных URL

### Кодстайл и читаемость
- Используйте осмысленные имена переменных
- Комментируйте сложные участки кода
- Следуйте общему стилю проекта
- Разделяйте логические блоки кода
