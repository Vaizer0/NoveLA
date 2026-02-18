# SOURCE GUIDE v2 — Полное руководство по написанию источников для NovelDokushaTT

> **Цель документа:** Этот гайд самодостаточен. Имея только его, любая LLM (или человек) может написать рабочий источник без доступа к исходному коду проекта.

---

## Содержание

1. [Введение и чеклист](#1-введение-и-чеклист)
2. [Шаг 1: Анализ сайта](#2-шаг-1-анализ-сайта)
3. [Шаг 2: Структура Kotlin-файла](#3-шаг-2-структура-kotlin-файла)
4. [Шаг 3: HtmlSelectors — полный справочник](#4-шаг-3-htmlselectors--полный-справочник)
5. [Шаг 4: SelectorRule — фабрики и трансформеры](#5-шаг-4-selectorrule--фабрики-и-трансформеры)
6. [Шаг 5: Извлечение текста главы](#6-шаг-5-извлечение-текста-главы)
7. [Сценарии 1–11](#7-сценарии-111)
8. [Антипаттерны](#8-антипаттерны)
9. [Регистрация источника](#9-регистрация-источника)
10. [Дополнительные AJAX-паттерны](#10-дополнительные-ajax-паттерны)
11. [Трюки и нестандартные приёмы](#11-трюки-и-нестандартные-приёмы)

---

## 1. Введение и чеклист

### Что такое источник?

Источник (source) — это Kotlin-класс, реализующий интерфейс `SourceInterface.Catalog`. Он умеет:
- Отдавать список книг с каталога (постранично)
- Искать книги по запросу
- Получать обложку, заголовок и описание книги
- Возвращать список глав книги
- Извлекать текст конкретной главы

### Чеклист: 5 шагов создания источника

- [ ] **Шаг 1:** Проанализировать сайт (URL-структура, pagination, API или HTML)
- [ ] **Шаг 2:** Создать Kotlin-файл в `scraper/src/main/java/my/noveldokusha/scraper/sources/`
- [ ] **Шаг 3:** Написать `HtmlSelectors` конфиг (или `JsonApiScraperConfig` для JSON API)
- [ ] **Шаг 4:** Реализовать все методы интерфейса `SourceInterface.Catalog`
- [ ] **Шаг 5:** Зарегистрировать источник в `Scraper.kt` и добавить строку имени в `strings-no-translatable.xml`

---

## 2. Шаг 1: Анализ сайта

### Decision Tree: какой тип источника нужен?

```
Сайт отдаёт данные?
├── HTML (стандартная загрузка страниц)
│   ├── Список глав прямо на странице книги → Сценарий 1 (NONE)
│   ├── Список глав постранично (кнопки 1,2,3...) → Сценарий 3 (PAGE_BASED)
│   ├── Список глав подгружается AJAX GET → Сценарий 4 (AJAX GET)
│   ├── Список глав через WordPress admin-ajax POST → Сценарий 5 (AJAX POST)
│   ├── Поиск через POST-форму → Сценарий 6 (POST Search)
│   ├── Сайт на китайском с кодировкой GBK → Сценарий 7
│   └── Список глав в обратном порядке → Сценарий 8 (reverseChapters)
└── JSON API
    ├── Есть стандартная пагинация → Сценарий 9 (JsonApiScraperConfig)
    └── Нестандартная структура → Сценарий 10 (полностью ручной)
```

### Что изучить в DevTools (F12)

| Что ищем | Где смотреть | Что записать |
|---|---|---|
| URL каталога с пагинацией | Network → XHR/Doc | Паттерн URL (`?page=2`, `/page/2/`) |
| URL поиска | Вкладка Network при поиске | Метод (GET/POST), параметры |
| Список глав подгружается? | Network при открытии книги | XHR запросы → URL, метод, тело |
| CSS-селектор карточки книги | Elements → Inspect | `.class` или `tag.class` |
| CSS-селектор названия | внутри карточки | `h3 a`, `div.title` и т.д. |
| CSS-селектор ссылки | внутри карточки | `a[href]` |
| CSS-селектор обложки | внутри карточки | `img[src]`, `img[data-src]` |
| CSS-селектор контента главы | на странице главы | `.chapter-content`, `.text` |

### Таблица типов URL пагинации

| Паттерн | buildCatalogUrl пример |
|---|---|
| `?page=N` | `"$baseUrl/novels?page=${index + 1}"` |
| `/page/N/` | `"$baseUrl/novels/page/${index + 1}/"` |
| `&p=N` | `"$baseUrl/novels&p=${index + 1}"` |
| index 0 = первая страница | `if (index == 0) "$baseUrl/novels" else "$baseUrl/novels?page=${index + 1}"` |

> **Важно:** `index` начинается с 0 в коде, но обычно сайты считают с 1. Всегда передавайте `index + 1` в URL, если сайт считает с 1.

---

## 3. Шаг 2: Структура Kotlin-файла

### Жёсткий порядок секций в файле

```kotlin
package my.noveldokusha.scraper.sources

// 1. IMPORTS — обязательные
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document

// 2. CLASS DECLARATION
class MySiteName(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    // 3. IDENTITY — идентификаторы источника
    override val id = "my_site_name"           // snake_case, уникальный
    override val nameStrId = R.string.source_name_my_site_name
    override val baseUrl = "https://mysite.com"
    override val catalogUrl = "https://mysite.com/novels"  // URL первой страницы каталога
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://mysite.com/favicon.ico"  // или ссылка на внешний PNG

    // 4. DELEGATE METHODS — делегируют в ScraperHelpers
    override suspend fun getCatalogList(index: Int) =
        getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) =
        getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) =
        getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) =
        getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) =
        getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) =
        getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) =
        getChapterText(config, doc)
    override suspend fun getChapterListHash(bookUrl: String) =
        getChapterListHash(config, bookUrl, networkClient)

    // 5. CONFIG — HtmlSelectors конфиг
    private val config: HtmlSelectors = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,
        // ... все параметры
    )
}
```

### Что такое ScraperHelpers?

`ScraperHelpers` — объект-хелпер в `scraper/helpers/`. Содержит суспенд-функции, которые принимают `HtmlSelectors` конфиг и `NetworkClient`, скачивают страницу и возвращают данные через конфиг:

| Функция хелпера | Что делает |
|---|---|
| `getCatalogList(config, index, nc)` | Скачивает каталог на странице `index`, возвращает `Response<PagedList<BookResult>>` |
| `getCatalogSearch(config, index, input, nc)` | Поиск книг |
| `getBookTitle(config, bookUrl, nc)` | Заголовок книги |
| `getBookCover(config, bookUrl, nc)` | URL обложки |
| `getBookDescription(config, bookUrl, nc)` | Описание книги |
| `getChapterList(config, bookUrl, nc)` | Список глав |
| `getChapterText(config, doc)` | Извлекает текст из уже загруженного `Document` |
| `getChapterListHash(config, bookUrl, nc)` | Хеш для быстрой проверки обновлений |

---

## 4. Шаг 3: HtmlSelectors — полный справочник

### Полная сигнатура data class

```kotlin
data class HtmlSelectors(
    // === ОБЯЗАТЕЛЬНЫЕ ===
    val baseUrl: String,
    val language: LanguageCode,
    val catalog: CatalogSelectors,
    val book: BookSelectors,
    val chapters: ChapterSelectors,
    val buildCatalogUrl: (Int) -> String,
    val buildSearchUrl: (Int, String) -> String,

    // === ОПЦИОНАЛЬНЫЕ — кодировка ===
    val charset: String? = null,              // null = UTF-8, "GBK" для китайских сайтов

    // === ОПЦИОНАЛЬНЫЕ — поиск ===
    val search: SearchSelectors? = null,      // отдельные селекторы для страницы поиска
    val searchNoPagination: Boolean = false,  // true = поиск не постраничный
    val postSearchEnabled: Boolean = false,   // true = поиск через POST
    val postSearchUrl: String? = null,        // URL для POST поиска
    val postSearchDataBuilder: ((String) -> Map<String, String>)? = null,  // тело POST
    val searchHeaders: Map<String, String> = emptyMap(), // заголовки поиска
    val customSearchProvider: (suspend (String, Int, NetworkClient) -> PagedList<BookResult>)? = null,

    // === ОПЦИОНАЛЬНЫЕ — список глав ===
    val chapterPaginationType: ChapterPaginationType = ChapterPaginationType.NONE,
    val chapterPaginationConfig: ChapterPaginationConfig? = null,  // для PAGE_BASED
    val ajaxChapterListProvider: (suspend (String, NetworkClient) -> List<ChapterResult>)? = null,
    val reverseChapters: Boolean = false,     // true = перевернуть список глав

    // === ОПЦИОНАЛЬНЫЕ — URL трансформеры ===
    val transformBookUrl: (String) -> String = { it },
    val transformChapterUrl: (String) -> String = { it },
    val transformCoverUrl: (String, String) -> String = { cover, _ -> cover },

    // === ОПЦИОНАЛЬНЫЕ — Cloudflare ===
    val cloudflareConfig: CloudflareConfig? = null,
)
```

### Описание каждого параметра

#### `baseUrl: String`
Корневой URL сайта без слеша в конце. Используется для преобразования относительных URL.
```kotlin
baseUrl = "https://www.royalroad.com"
```

#### `language: LanguageCode`
Язык контента. Константы: `LanguageCode.ENGLISH`, `LanguageCode.RUSSIAN`, `LanguageCode.CHINESE`, `LanguageCode.KOREAN`, `LanguageCode.JAPANESE`, `LanguageCode.INDONESIAN`, и др.

#### `charset: String?`
Кодировка страниц. По умолчанию `null` (UTF-8). Для китайских сайтов используйте `"GBK"`. Также устанавливается в `override val charset = "GBK"` на уровне класса.

#### `catalog: CatalogSelectors`
Селекторы для страницы каталога (списка книг):
```kotlin
data class CatalogSelectors(
    val item: SelectorRule,   // CSS-селектор одной карточки книги (elements())
    val title: SelectorRule,  // название внутри карточки
    val url: SelectorRule,    // href ссылки на книгу
    val cover: SelectorRule   // src/data-src обложки
)
```

#### `search: SearchSelectors?`
Отдельные селекторы для страницы **поиска**, если она отличается от каталога. Если `null` — используются `catalog`-селекторы.
```kotlin
data class SearchSelectors(
    val item: SelectorRule,
    val title: SelectorRule,
    val url: SelectorRule,
    val cover: SelectorRule
)
```

#### `book: BookSelectors`
Селекторы для страницы книги:
```kotlin
data class BookSelectors(
    val title: SelectorRule?,             // заголовок книги (null если не нужен)
    val cover: SelectorRule,              // обложка
    val description: SelectorRule,        // описание/синопсис
    val latestChapterHash: SelectorRule? = null  // текст для хеша обновлений
)
```

`latestChapterHash` — любой элемент, который меняется при выходе новой главы (например, номер последней главы, дата). Используется для быстрой проверки обновлений без загрузки всего списка глав.

#### `chapters: ChapterSelectors`
Селекторы для списка и контента глав:
```kotlin
data class ChapterSelectors(
    val list: SelectorRule,      // список ссылок на главы (elements())
    val content: SelectorRule,   // контент главы (text() или html())
    val title: SelectorRule?     // заголовок главы внутри элемента списка (null если href сам содержит заголовок)
)
```

#### `chapterPaginationType: ChapterPaginationType`

| Значение | Когда использовать |
|---|---|
| `NONE` | Все главы на одной странице книги |
| `PAGE_BASED` | Главы постранично (кнопки 1, 2, 3...) |
| `AJAX_BASED` | Список глав подгружается отдельным запросом |
| `CUSTOM` | Нестандартная логика (редко) |

#### `chapterPaginationConfig: ChapterPaginationConfig?`
Только для `PAGE_BASED`. Содержит:
```kotlin
data class ChapterPaginationConfig(
    val maxPageExtractor: (Document) -> Int,    // извлекает максимальный номер страницы из документа
    val pageUrlBuilder: (String, Int) -> String, // строит URL для страницы N (bookUrl, pageNum) -> url
    val chapterSelector: String                  // CSS-селектор ссылок на главы
)
```

#### `ajaxChapterListProvider`
Только для `AJAX_BASED`. Лямбда `suspend (bookUrl: String, networkClient: NetworkClient) -> List<ChapterResult>`. Получает URL книги и сетевой клиент, возвращает полный список глав.

`ChapterResult` — data class `(title: String, url: String)`.

#### `reverseChapters: Boolean`
Если `true` — список глав переворачивается после получения. Нужно, когда сайт отдаёт главы в порядке «новые первые», а нам нужны «старые первые».

#### `buildCatalogUrl: (Int) -> String`
Лямбда, которая по номеру страницы (0-based index) строит URL каталога.
```kotlin
buildCatalogUrl = { index -> "$baseUrl/novels?page=${index + 1}" }
```

#### `buildSearchUrl: (Int, String) -> String`
Лямбда `(pageIndex, searchQuery) -> url`.
```kotlin
buildSearchUrl = { index, query -> "$baseUrl/search?q=$query&page=${index + 1}" }
```

#### `transformBookUrl: (String) -> String`
Трансформирует URL книги, извлечённый из каталога. Используйте, если сайт отдаёт относительные URL.
Готовые трансформеры из `UrlTransformers`:
- `UrlTransformers.standardBookUrl(baseUrl)` — URI.resolve относительных URL

#### `transformChapterUrl: (String) -> String`
Аналогично для URL глав.
- `UrlTransformers.standardChapterUrl(baseUrl)` — URI.resolve

#### `transformCoverUrl: (String, String) -> String`
Лямбда `(coverUrl, bookUrl) -> transformedUrl`. Второй параметр — URL книги (контекст).

**Полный список готовых трансформеров из `UrlTransformers`:**

| Трансформер | Сигнатура | Что делает | Когда использовать |
|---|---|---|---|
| `standardCoverUrl(baseUrl)` | `(coverUrl, _) -> String` | Prepend baseUrl для относительных, protocol-relative через `https:` | Большинство сайтов с относительными URL обложек |
| `resolveCoverUrl(baseUrl)` | `(coverUrl, _) -> String` | URI.resolve вариант (точнее для сложных путей) | Если `standardCoverUrl` даёт неверные URL |
| `simpleCoverUrl(baseUrl)` | `(coverUrl, _) -> String` | Простое `$baseUrl$coverUrl` (без URI.resolve) | Когда нужен буквальный prepend |
| `jaomixCoverUrl()` | `(coverUrl, _) -> String` | Удаляет `-150x150` из URL → полный размер | WordPress-сайты с миниатюрами `-150x150` |
| `novelBinCoverUrl()` | `(coverUrl, bookUrl) -> String` | Строит URL обложки по slug книги: `images.novelbin.me/novel/$slug.jpg` | NovelBin и клоны (каталог даёт только slug) |
| `novelBinCatalogCoverUrl()` | `(coverUrl, _) -> String` | Заменяет `novel_200_89` → `novel` в URL thumbnail | Сайты с thumbnail в каталоге и полным фото на странице книги |
| `readNovelFullCoverUrl()` | `(coverUrl, _) -> String` | Заменяет `t-200x89` → `t-300x439` в URL | ReadNovelFull и аналоги |
| `ttkanCoverUrl()` | `(coverUrl, bookUrl) -> String` | Строит `static.ttkan.co/cover/$slug.jpg?w=250&h=300` | Ttkan.co |
| `weservProxyCoverUrl()` | `(coverUrl, _) -> String` | Проксирует через `images.weserv.nl/?url=...` | Сайты с hotlink-защитой на CDN (RanobeLib, RanobeHub) |
| `proxiedCoverUrl("weserv")` | `(coverUrl, _) -> String` | Обёртка над weservProxyCoverUrl | То же, что выше |

**Пример кастомного трансформера:**
```kotlin
// Инлайн лямбда когда ни один готовый не подходит
transformCoverUrl = { coverUrl, bookUrl ->
    when {
        coverUrl.isBlank() -> ""
        coverUrl.startsWith("http") -> coverUrl
        coverUrl.startsWith("//") -> "https:$coverUrl"
        else -> buildUrl(baseUrl, coverUrl)
    }
}
```

#### `postSearchEnabled`, `postSearchUrl`, `postSearchDataBuilder`, `searchHeaders`
Группа для POST-поиска:
```kotlin
postSearchEnabled = true,
postSearchUrl = "$baseUrl/search",
postSearchDataBuilder = { query -> mapOf("searchkey" to query) },
searchHeaders = mapOf("Referer" to baseUrl),
searchNoPagination = true,  // обычно вместе с POST-поиском
```

---

## 5. Шаг 4: SelectorRule — фабрики и трансформеры

### Фабричные функции

Фабрики создают `SelectorRule` — правило выбора элемента и извлечения значения.

#### `text(vararg css: String): SelectorRule`
Извлекает **текстовое содержимое** (без HTML-тегов) первого найденного элемента.
```kotlin
text("h1.title")              // <h1 class="title">Моя книга</h1> → "Моя книга"
text("h1 a", "h2 a", "h3")   // fallback: пробует по очереди
```

#### `attr(attr: String, vararg css: String): SelectorRule`
Извлекает значение **атрибута** HTML-элемента.
```kotlin
attr("href", "a.book-link")    // <a href="/novel/123"> → "/novel/123"
attr("src", "img.cover")       // <img src="cover.jpg"> → "cover.jpg"
attr("data-src", "img.lazy")   // для lazy-load изображений
attr("content", "meta[property='og:image']")  // meta теги
```

#### `html(vararg css: String): SelectorRule`
Извлекает **внутренний HTML** элемента. Используется для описания (синопсиса) и контента глав, когда нужно сохранить форматирование.
```kotlin
html(".description")           // <div class="description"><p>Текст</p></div> → "<p>Текст</p>"
html(".chapter-content")
```

#### `elements(vararg css: String): SelectorRule`
Возвращает **все совпадающие элементы** (для списков). Используется для `catalog.item`, `chapters.list`.
```kotlin
elements(".fiction-list-item")  // все карточки книг
elements("tr.chapter-row td:first-child a[href]")  // все ссылки на главы
```

### Цепочка трансформеров

После фабрики можно применить трансформеры через точечную нотацию:

```kotlin
text("h1").Clean().trim()
```

#### `.Clean(): SelectorRule`
Нормализует Unicode (NFKC) + коллапсирует множественные пробелы + trim. Используйте для названий книг и заголовков.
```kotlin
title = text("h2 a").Clean()
```

#### `.trim(): SelectorRule`
Простой trim() строки.

#### `.normalizeUnicode(): SelectorRule`
Только NFKC-нормализация Unicode (убирает ligatures, нормализует пробелы Unicode).

#### `.applyStandardContentTransforms(baseUrl: String): SelectorRule`
**Основной трансформер для контента глав.** Применяет: `normalizeUnicode` + regex-чистки + `removeHiddenContent` + `trim`. Убирает рекламный мусор, скрытые элементы, типичный HTML-мусор.
```kotlin
content = text(".chapter-content")
    .removeElementsDOM("script", "a", ".ads-title")
    .applyStandardContentTransforms(baseUrl)
```

#### `.removeElementsDOM(vararg selectors: String): SelectorRule`
Удаляет DOM-узлы по CSS-селекторам **до** извлечения текста. Критически важно для удаления рекламы, навигации и мусора внутри контента.
```kotlin
content = text(".chapter-content")
    .removeElementsDOM("script", "style", ".ads", "nav", ".chapter-nav", "a[href]")
```

#### `.removeHiddenContent(): SelectorRule`
Удаляет CSS-скрытые элементы (`display:none`, `visibility:hidden`). Полезно против контента для поисковиков, скрытого мусора.

#### `.cleanHtml(): SelectorRule`
Очищает HTML от опасных тегов (sanitize).

#### `.regexReplace(pattern: String, replacement: String): SelectorRule`
Заменяет паттерн regex в результирующей строке.
```kotlin
text(".chapter-title").regexReplace("^Chapter \\d+: ", "")  // убирает "Chapter 5: "
```

#### `.removePatternsTEXT(vararg regexPatterns: String): SelectorRule`
Удаляет несколько паттернов из текста.
```kotlin
description = text(".synopsis")
    .removePatternsTEXT(
        "\\[.+?\\]",          // квадратные скобки
        "Read .+ on .+\\.com" // рекламные фразы
    )
```

#### `.transform(transform: (String) -> String): SelectorRule`
Произвольный трансформер строки.
```kotlin
text("span.rating").transform { it.replace(",", ".") }
```

#### `.contextTransform(transform: (Element, String) -> String): SelectorRule`
Трансформер с доступом к исходному DOM-элементу. Используйте, когда нужна дополнительная информация из элемента.
```kotlin
attr("href", "a").contextTransform { element, href ->
    if (href.startsWith("/")) "$baseUrl$href" else href
}
```

### Правило выбора трансформеров

| Что нужно | Трансформер |
|---|---|
| Название книги/главы | `.Clean()` |
| Описание книги (HTML сайта сохраняет форматирование) | `html()` + `.applyStandardContentTransforms(baseUrl)` |
| Описание книги (только текст) | `text()` + `.Clean()` |
| Контент главы (стандартный) | `text()` + `.removeElementsDOM(...)` + `.applyStandardContentTransforms(baseUrl)` |
| Контент главы (много рекламных DOM-нод) | `html()` + `.removeElementsDOM(...)` + `.applyStandardContentTransforms(baseUrl)` |
| URL из атрибута (относительный) | `attr("href", ...)` + `transformBookUrl` или `transformChapterUrl` |
| Обложка через lazy-load | `attr("data-src", "img")` или `attr("data-lazy-src", "img")` |

---

## 6. Шаг 5: Извлечение текста главы

### Как работает getChapterText

Когда читатель открывает главу, приложение:
1. Загружает URL главы через WebView или NetworkClient
2. Получает готовый `Document` (Jsoup)
3. Вызывает `getChapterText(doc)` на вашем источнике

### Стандартный случай: делегирование в хелпер

```kotlin
override suspend fun getChapterText(doc: Document) = getChapterText(config, doc)
```

`getChapterText(config, doc)` применяет `config.chapters.content` SelectorRule к документу и возвращает `String?`.

### Когда нужно переопределить getChapterText

Переопределяйте, если:
- Контент главы разбит на несколько подстраниц (Novel543-стиль)
- Контент загружается через отдельный API-запрос (WtrLab-стиль)
- Нужна постобработка текста (замены, склейка)

### Паттерн: TextExtractor.get(element)

**ВСЕГДА** используйте `TextExtractor.get(element)` для извлечения текста из DOM-элемента. **Никогда** не используйте `element.text()` напрямую.

```kotlin
import my.noveldokusha.scraper.helpers.TextExtractor

// ПРАВИЛЬНО:
val text = TextExtractor.get(doc.body()!!)

// НЕПРАВИЛЬНО:
val text = doc.body()?.text()  // теряет форматирование абзацев
```

`TextExtractor.get()` сохраняет переносы строк между блочными элементами (`<p>`, `<br>`, `<div>`), правильно обрабатывает вложенные элементы.

### Паттерн: склейка подстраниц

```kotlin
override suspend fun getChapterText(doc: Document): String {
    val chapterFile = doc.location().substringAfterLast("/").removeSuffix(".html")
    val baseDir = doc.location().substringBeforeLast("/") + "/"
    val subPagePattern = Regex("${Regex.escape(chapterFile)}_\\d+\\.html$")

    // Начинаем с первой страницы
    val allContent = StringBuilder(getChapterText(config, doc) ?: "")
    var currentDoc = doc

    // Ищем ссылки на подстраницы (максимум 20)
    repeat(20) {
        val subLink = currentDoc.select("a[href]").firstOrNull { el ->
            el.attr("href").substringAfterLast("/").matches(subPagePattern)
        } ?: return@repeat  // выход если нет следующей страницы

        val subFile = subLink.attr("href").substringAfterLast("/")
        currentDoc = networkClient.get(baseDir + subFile).toDocument()
        allContent.append("\n\n").append(getChapterText(config, currentDoc) ?: "")
    }

    return allContent.toString()
}
```

### Паттерн: JSON API для контента главы

```kotlin
override suspend fun getChapterText(doc: Document): String {
    val chapterUrl = doc.location()
    // Трансформируем URL страницы в URL API
    val apiUrl = chapterUrl.replace("/chapter/", "/api/chapter/")

    return withContext(Dispatchers.Default) {
        tryConnect {
            val json = fetchJson(apiUrl, emptyMap(), networkClient)
            val content = json.asJsonObject.get("content").asString
            // Парсим HTML из JSON и извлекаем текст
            Jsoup.parse(content).body()?.let { TextExtractor.get(it) } ?: ""
        } ?: ""
    }
}
```

### tryConnect — обёртка для безопасных запросов

```kotlin
import my.noveldokusha.core.Response
import my.noveldokusha.scraper.helpers.tryConnect

// Возвращает Response<T>
val result: Response<String?> = withContext(Dispatchers.Default) {
    tryConnect {
        // Тут ваш код, который может бросить исключение
        val doc = networkClient.get(url).toDocument()
        doc.title()
    }
}
```

`tryConnect` оборачивает блок в `try/catch` и возвращает `Response.Success(value)` или `Response.Error(exception)`.

---

## 7. Сценарии 1–11

---

### Сценарий 1: Минимальный HTML-источник (NONE pagination)

**Когда:** Простой сайт, все главы на странице книги, поиск GET, стандартная пагинация.

**Пример сайта:** RoyalRoad, большинство англоязычных сайтов.

```kotlin
package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document

class MyNovelSite(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override val id = "my_novel_site"
    override val nameStrId = R.string.source_name_my_novel_site
    override val baseUrl = "https://mynovelsite.com"
    override val catalogUrl = "$baseUrl/novels"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "$baseUrl/favicon.ico"

    override suspend fun getCatalogList(index: Int) =
        getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) =
        getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) =
        getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) =
        getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) =
        getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) =
        getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: Document) =
        getChapterText(config, doc)
    override suspend fun getChapterListHash(bookUrl: String) =
        getChapterListHash(config, bookUrl, networkClient)

    private val config = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        // Каталог: карточки книг
        catalog = CatalogSelectors(
            item = elements(".novel-item"),        // каждая карточка
            title = text(".novel-title a").Clean(),
            url = attr("href", ".novel-title a"),
            cover = attr("src", "img.cover")
        ),

        // Страница книги
        book = BookSelectors(
            title = text("h1.novel-name").Clean(),
            cover = attr("src", ".novel-cover img"),
            description = text(".novel-description").Clean(),
            latestChapterHash = text(".latest-chapter a").Clean()  // для проверки обновлений
        ),

        // Главы: список + контент
        chapters = ChapterSelectors(
            list = elements(".chapter-list li a"),
            title = null,  // заголовок берётся из текста <a>
            content = text(".chapter-content")
                .removeElementsDOM("script", "style", ".ads", ".chapter-nav", "a[href*='mynovelsite']")
                .applyStandardContentTransforms(baseUrl)
        ),

        // Пагинация глав: нет (все главы на одной странице)
        chapterPaginationType = ChapterPaginationType.NONE,

        // URL-строители
        buildCatalogUrl = { index ->
            if (index == 0) "$baseUrl/novels"
            else "$baseUrl/novels?page=${index + 1}"
        },
        buildSearchUrl = { index, query ->
            "$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=${index + 1}"
        },

        // URL-трансформеры (относительные → абсолютные)
        transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
        transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
        transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl),
    )
}
```

---

### Сценарий 2: Отдельные селекторы для страницы поиска

**Когда:** Страница каталога и страница поиска имеют разную HTML-структуру карточек.

Добавьте `search = SearchSelectors(...)` в конфиг:

```kotlin
private val config = HtmlSelectors(
    // ...
    catalog = CatalogSelectors(
        item = elements(".book-card"),
        title = text(".book-title").Clean(),
        url = attr("href", "a.book-link"),
        cover = attr("src", "img.book-cover")
    ),

    // Отдельные селекторы для страницы поиска
    search = SearchSelectors(
        item = elements(".search-result-item"),     // другой класс!
        title = text(".result-title span").Clean(),
        url = attr("href", "a.result-link"),
        cover = attr("data-src", "img.lazy-cover") // lazy-load на поиске
    ),

    // buildSearchUrl должен возвращать URL страницы поиска
    buildSearchUrl = { index, query ->
        "$baseUrl/search?keyword=$query&p=${index + 1}"
    },
    // ...
)
```

---

### Сценарий 3: Пагинация глав PAGE_BASED

**Когда:** Список глав разбит на страницы с кнопками 1, 2, 3... Типичный паттерн на крупных сайтах.

**Пример сайта:** NovelFire.

```kotlin
chapters = ChapterSelectors(
    list = elements("a[href*='/chapter-']"),  // CSS-селектор не используется напрямую для PAGE_BASED,
    title = null,                              // но должен быть задан
    content = text(".chapter-body")
        .removeElementsDOM("script", ".ads", "a.chapter-warn")
        .applyStandardContentTransforms(baseUrl)
),

chapterPaginationType = ChapterPaginationType.PAGE_BASED,
chapterPaginationConfig = ChapterPaginationConfig(
    // Извлекаем максимальный номер страницы из документа
    maxPageExtractor = { doc ->
        doc.select(".pagination a[href*='?page=']")
            .mapNotNull { el ->
                Regex("page=(\\d+)").find(el.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 1
    },
    // Строим URL для конкретной страницы
    pageUrlBuilder = { bookUrl, page ->
        val bookSlug = bookUrl.trimEnd('/').substringAfterLast("/")
        "$baseUrl/book/$bookSlug/chapters?page=$page"
    },
    // CSS-селектор ссылок на главы на каждой странице пагинации
    chapterSelector = "a[href*='/chapter-']"
),
```

**Как это работает:**
1. Хелпер загружает первую страницу книги
2. Вызывает `maxPageExtractor` → получает число страниц (например, 5)
3. В цикле от 1 до 5 вызывает `pageUrlBuilder(bookUrl, page)` и загружает каждую страницу
4. На каждой странице находит элементы по `chapterSelector` и собирает `ChapterResult`

---

### Сценарий 4: AJAX GET список глав + склейка подстраниц

**Когда:** Список глав загружается отдельным GET-запросом по URL `/dir` или аналогичному. Контент главы разбит на несколько подстраниц.

**Пример сайта:** Novel543 (novel543.com).

```kotlin
package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import org.jsoup.nodes.Document
import java.net.URI

class Novel543(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override val id = "novel543"
    override val nameStrId = R.string.source_name_novel543
    override val baseUrl = "https://novel543.com"
    override val catalogUrl = "$baseUrl/book"
    override val language = LanguageCode.CHINESE

    override suspend fun getCatalogList(index: Int) =
        getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) =
        getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) =
        getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) =
        getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) =
        getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) =
        getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterListHash(bookUrl: String) =
        getChapterListHash(config, bookUrl, networkClient)

    // ПЕРЕОПРЕДЕЛЯЕМ getChapterText для склейки подстраниц
    override suspend fun getChapterText(doc: Document): String {
        val chapterFile = doc.location().substringAfterLast("/").removeSuffix(".html")
        val baseDir = doc.location().substringBeforeLast("/") + "/"
        val subPagePattern = Regex("${Regex.escape(chapterFile)}_\\d+\\.html$")

        val allContent = StringBuilder(getChapterText(config, doc) ?: "")
        var currentDoc = doc

        repeat(20) {
            val subLink = currentDoc.select("a[href]").firstOrNull { el ->
                el.attr("href").substringAfterLast("/").matches(subPagePattern)
            } ?: return@repeat

            val subFile = subLink.attr("href").substringAfterLast("/")
            currentDoc = networkClient.get(baseDir + subFile).toDocument()
            allContent.append("\n\n").append(getChapterText(config, currentDoc) ?: "")
        }

        return allContent.toString()
    }

    private val config = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,

        catalog = CatalogSelectors(
            item = elements("ul.bookList li"),
            title = text("p.bookName").Clean(),
            url = attr("href", "a"),
            cover = attr("src", "img")
        ),

        book = BookSelectors(
            title = text("h1.bookTitle").Clean(),
            cover = attr("src", "div.bookCover img"),
            description = text("div.bookIntro").Clean(),
            latestChapterHash = text("div.bookChapterInfo span").Clean()
        ),

        chapters = ChapterSelectors(
            list = elements("ul.all li a"),
            title = null,
            content = text("div#chapterContent")
                .removeElementsDOM("script", "style", "div.adBox")
                .applyStandardContentTransforms(baseUrl)
        ),

        // AJAX: получаем список глав по URL /dir
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, nc ->
            val dirUrl = bookUrl.trimEnd('/') + "/dir"
            nc.get(dirUrl).toDocument()
                .select("ul.all li a")
                .map { el ->
                    ChapterResult(
                        title = el.text().trim(),
                        url = URI(baseUrl).resolve(el.attr("href")).toString()
                    )
                }
        },

        buildCatalogUrl = { index -> "$baseUrl/book?p=${index + 1}" },
        buildSearchUrl = { index, query ->
            "$baseUrl/search?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&p=${index + 1}"
        },
        transformBookUrl = { url ->
            if (url.startsWith("http")) url else "$baseUrl$url"
        },
    )
}
```

---

### Сценарий 5: AJAX WordPress admin-ajax POST

**Когда:** Сайт на WordPress, список глав загружается через `wp-admin/admin-ajax.php` POST-запросом. Требует специальных заголовков: `X-Requested-With`, `Origin`, `Referer`.

**Пример сайта:** Jaomix (jaomix.ru).

```kotlin
// Только ajaxChapterListProvider, всё остальное как в Сценарии 1

chapterPaginationType = ChapterPaginationType.AJAX_BASED,
ajaxChapterListProvider = { bookUrl, networkClient ->
    import kotlinx.coroutines.delay
    import kotlin.random.Random

    // 1. Загружаем страницу книги, чтобы узнать количество страниц
    val bookDoc = networkClient.get(bookUrl).toDocument()
    val maxPage = bookDoc.selectFirst("select.sel-toc")
        ?.select("option")?.size ?: 10

    val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
    val allChapters = mutableListOf<ChapterResult>()

    // 2. Итерируем страницы В ОБРАТНОМ ПОРЯДКЕ (WordPress отдаёт новые главы первыми)
    for (page in maxPage downTo 1) {
        val ajaxDoc = POST(
            url = ajaxUrl,
            data = mapOf(
                "action" to "loadpagenavchapstt",  // action для данного WordPress плагина
                "page" to page.toString()
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to baseUrl.removeSuffix("/"),
                "Referer" to bookUrl,
                "Accept" to "text/html, */*; q=0.01"
            ),
            networkClient = networkClient
        )

        val chapters = ajaxDoc.select("div.title a[href]")
            .map { element ->
                ChapterResult(
                    title = element.selectFirst("h2")?.text()?.trim()
                        ?: element.text().trim(),
                    url = element.attr("href")
                )
            }
            .reversed()  // перевернуть внутри каждой страницы

        if (chapters.isEmpty()) break
        allChapters.addAll(chapters)

        // 3. Вежливая пауза между запросами
        delay(Random.nextLong(150, 351))
    }

    allChapters
},
```

**Ключевые моменты:**
- `POST(url, data, headers, networkClient)` — функция из `HttpHelpers.kt`
- `action` в теле POST — имя WordPress action для конкретного плагина (найдите в DevTools)
- Заголовки `X-Requested-With: XMLHttpRequest` и `Referer` **обязательны**
- Пауза между запросами предотвращает блокировку сервером

---

### Сценарий 6: POST-поиск (POST Search)

**Когда:** Сайт принимает поисковые запросы через HTML-форму с методом POST, а не GET.

**Пример сайта:** FreeWebNovel.

```kotlin
private val config = HtmlSelectors(
    baseUrl = baseUrl,
    language = language,

    catalog = CatalogSelectors(
        item = elements(".novel-item"),
        title = text(".novel-title").Clean(),
        url = attr("href", "a.novel-item"),
        cover = attr("src", "img.novel-cover")
    ),

    // Для POST-поиска страница поиска и каталога обычно имеют одинаковую структуру
    // поэтому search = null (используем catalog-селекторы)

    book = BookSelectors(
        title = text("h1.novel-title").Clean(),
        cover = attr("src", ".cover-wrap img"),
        description = text(".summary__content").Clean(),
    ),

    chapters = ChapterSelectors(
        list = elements("ul.chapter-list li a"),
        title = null,
        content = text(".chapter-content")
            .removeElementsDOM("script", ".ads")
            .applyStandardContentTransforms(baseUrl)
    ),

    chapterPaginationType = ChapterPaginationType.NONE,

    // === POST-поиск ===
    searchNoPagination = true,        // поиск возвращает все результаты сразу
    postSearchEnabled = true,
    postSearchUrl = "$baseUrl/search",
    postSearchDataBuilder = { query ->
        mapOf("searchkey" to query)   // тело POST-запроса
    },
    searchHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer" to "$baseUrl/"
    ),

    // buildSearchUrl должен быть задан (используется как fallback),
    // но для POST-поиска реально не вызывается
    buildSearchUrl = { _, _ -> "$baseUrl/search" },

    buildCatalogUrl = { index -> "$baseUrl/most-popular?page=${index + 1}" },
    transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
    transformCoverUrl = UrlTransformers.standardCoverUrl(baseUrl),
)
```

**Ключевые моменты:**
- `postSearchEnabled = true` + `postSearchUrl` + `postSearchDataBuilder` — триада POST-поиска
- `searchNoPagination = true` — если сайт возвращает все результаты сразу (без пагинации поиска)
- `searchHeaders` — добавляйте `Referer` и `User-Agent` если сайт их проверяет
- Ключи в `postSearchDataBuilder` (`"searchkey"`) — найдите в DevTools в теле POST-запроса

---

### Сценарий 7: Нестандартная кодировка (GBK/китайские сайты)

**Когда:** Сайт использует кодировку GBK (или другую не-UTF-8). Типично для старых китайских сайтов.

**Пример сайта:** Shuba69.

```kotlin
package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import java.net.URI

class Shuba69(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override val id = "shuba69"
    override val nameStrId = R.string.source_name_shuba69
    override val baseUrl = "https://www.shuba69.com"
    override val catalogUrl = "$baseUrl/paihang/"
    override val language = LanguageCode.CHINESE

    // ВАЖНО: указываем кодировку на уровне класса
    override val charset = "GBK"

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterText(doc: org.jsoup.nodes.Document) = getChapterText(config, doc)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    private val config = HtmlSelectors(
        baseUrl = baseUrl,
        language = language,
        charset = charset,  // передаём кодировку в конфиг

        catalog = CatalogSelectors(
            item = elements("ul.list li"),
            title = text("a.name").Clean(),
            url = attr("href", "a.name"),
            cover = attr("src", "img")
        ),

        book = BookSelectors(
            title = text("h1.bookTitle").Clean(),
            cover = attr("src", "div.bookImg img"),
            description = text("div.intro").Clean(),
        ),

        chapters = ChapterSelectors(
            list = elements("div#catalog ul li a"),
            title = null,
            content = text("div#content")
                .removeElementsDOM("script", "style")
                .applyStandardContentTransforms(baseUrl)
        ),

        // AJAX список глав — загружаем отдельную страницу с каталогом
        chapterPaginationType = ChapterPaginationType.AJAX_BASED,
        ajaxChapterListProvider = { bookUrl, nc ->
            // Строим URL каталога глав из URL книги
            val chapterListUrl = bookUrl
                .replace("/txt/", "/")
                .replace(".htm", "/")
            // Загружаем с явной кодировкой GBK
            nc.get(chapterListUrl).toDocument("GBK")
                .select("div#catalog ul li a")
                .map { element ->
                    ChapterResult(
                        title = element.text().trim(),
                        url = URI(baseUrl).resolve(element.attr("href")).toString()
                    )
                }
                .asReversed()  // старые главы первыми
        },

        // POST-поиск с кодировкой GBK для запроса
        postSearchEnabled = true,
        postSearchUrl = "$baseUrl/search.php",
        postSearchDataBuilder = { query ->
            // ВАЖНО: кодируем поисковый запрос в GBK
            val encodedQuery = java.net.URLEncoder.encode(query, "GBK")
            mapOf(
                "searchkey" to encodedQuery,
                "searchtype" to "all"
            )
        },
        searchNoPagination = true,

        buildCatalogUrl = { index -> "$baseUrl/paihang/${index + 1}.htm" },
        buildSearchUrl = { _, _ -> "$baseUrl/search.php" },
        transformBookUrl = { url ->
            if (url.startsWith("http")) url else URI(baseUrl).resolve(url).toString()
        },
        transformCoverUrl = { cover, _ ->
            if (cover.startsWith("http")) cover else "$baseUrl$cover"
        },
    )
}
```

**Ключевые моменты:**
- `override val charset = "GBK"` — на уровне класса (используется при загрузке страниц)
- `charset = charset` — передаём в `HtmlSelectors` конфиг
- `nc.get(url).toDocument("GBK")` — явно указываем кодировку при ручной загрузке страниц
- `java.net.URLEncoder.encode(query, "GBK")` — кодируем поисковый запрос в нужной кодировке
- Поддерживаемые кодировки: `"GBK"`, `"GB2312"`, `"Big5"`, `"EUC-KR"`, `"Shift_JIS"`

---

### Сценарий 8: Обратный порядок глав (reverseChapters)

**Когда:** Сайт отдаёт главы в порядке «новые первые» (descending), а читателю нужны «старые первые».

**Пример сайта:** BacaLightnovel и аналогичные индонезийские сайты.

```kotlin
// Просто добавьте reverseChapters = true в конфиг — всё остальное как в Сценарии 1

private val config = HtmlSelectors(
    baseUrl = baseUrl,
    language = language,

    catalog = CatalogSelectors(
        item = elements(".col-novel-main .list-novel .row"),
        title = text(".novel-title a").Clean(),
        url = attr("href", ".novel-title a"),
        cover = attr("src", "img.cover")
    ),

    book = BookSelectors(
        title = text("h3.title").Clean(),
        cover = attr("src", ".book img"),
        description = text(".desc-text").Clean(),
    ),

    chapters = ChapterSelectors(
        list = elements("#list-chapter li a"),
        title = null,
        content = text(".chr-c")
            .removeElementsDOM("script", "style", ".ads", "a")
            .applyStandardContentTransforms(baseUrl)
    ),

    chapterPaginationType = ChapterPaginationType.NONE,
    reverseChapters = true,  // ← единственное отличие от Сценария 1

    buildCatalogUrl = { index -> "$baseUrl/novel-list?page=${index + 1}" },
    buildSearchUrl = { index, query -> "$baseUrl/?s=$query&post_type=novel&paged=${index + 1}" },
    transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
    transformChapterUrl = UrlTransformers.standardChapterUrl(baseUrl),
)
```

> **Совет:** `reverseChapters` работает только с `NONE` и `PAGE_BASED` пагинацией. Для `AJAX_BASED` переворачивайте список вручную в `ajaxChapterListProvider` через `.asReversed()`.

---

### Сценарий 9: JSON API источник (JsonApiScraperConfig)

**Когда:** Сайт имеет REST API, отдающее JSON. Структура стандартная: список книг с пагинацией, детали книги, список глав, контент главы — всё через API.

**Пример сайта:** RanobeLib (lib.shikimori.one).

```kotlin
package my.noveldokusha.scraper.sources

import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.Response
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.JsonApiScraperConfig
import my.noveldokusha.scraper.helpers.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

class RanobeLib(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override val id = "ranobelib"
    override val nameStrId = R.string.source_name_ranobelib
    override val baseUrl = "https://lib.shikimori.one"
    private val apiBaseUrl = "https://api.lib.shikimori.one/api/manga"
    override val catalogUrl = "$baseUrl/ranobe"
    override val language = LanguageCode.RUSSIAN
    override val iconUrl = "https://lib.shikimori.one/favicon.ico"

    // Для JSON API используются другие хелперы
    override suspend fun getCatalogList(index: Int) =
        getCatalogListJson(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) =
        getCatalogSearchJson(config, index, input, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) =
        getBookCoverJson(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) =
        getBookDescriptionJson(config, bookUrl, networkClient)
    override suspend fun getBookTitle(bookUrl: String) =
        getBookTitleJson(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) =
        getChapterListJson(config, bookUrl, networkClient)

    // Контент главы загружается через API и приходит в виде HTML-строки
    override suspend fun getChapterText(doc: Document): String {
        val chapterUrl = doc.location()
        val result = getChapterTextJson(config, chapterUrl, networkClient)
        return when (result) {
            is Response.Success -> {
                Jsoup.parse(result.data ?: "").body()
                    ?.let { TextExtractor.get(it) } ?: ""
            }
            is Response.Error -> ""
        }
    }

    override suspend fun getChapterListHash(bookUrl: String): Response<String?> =
        Response.Success(null)

    private val config = JsonApiScraperConfig(
        baseUrl = baseUrl,
        apiBaseUrl = apiBaseUrl,
        language = language,

        // Идентификатор сайта для API
        siteId = "3",
        headers = mapOf("Site-Id" to "3"),

        // Ключи для разбора JSON ответов каталога
        catalogDataKey = "data",              // массив книг находится в поле "data"
        catalogTitleKeys = listOf("rus_name", "eng_name", "name"),  // приоритет названий
        catalogUrlKey = "slug",               // поле со slug книги
        catalogCoverKey = "cover.default",    // поле с URL обложки (поддерживает dot-notation)
        catalogHasNextKey = "meta.has_next_page",  // флаг наличия следующей страницы

        // URL-строители
        buildCatalogUrl = { page ->
            "$apiBaseUrl?site_id[0]=3&page=$page&type[0]=ranobe&sort_by=rate_avg&sort_type=desc"
        },
        buildSearchUrl = { page, query ->
            val encoded = URLEncoder.encode(query, "UTF-8")
            "$apiBaseUrl?site_id[0]=3&page=$page&q=$encoded"
        },
        buildBookUrl = { slug -> "$apiBaseUrl$slug" },
        buildChapterListUrl = { slug -> "$apiBaseUrl$slug/chapters" },

        // Парсеры JSON-ответов
        parseBookData = { json ->
            // Принимает JsonElement книги, возвращает BookData(title, cover, description)
            val obj = json.asJsonObject
            val title = obj.get("rus_name")?.asString
                ?: obj.get("eng_name")?.asString
                ?: obj.get("name")?.asString ?: ""
            val cover = obj.getAsJsonObject("cover")?.get("default")?.asString ?: ""
            val description = obj.get("summary")?.asString ?: ""
            BookData(title = title, cover = proxiedImageUrl(cover), description = description)
        },

        parseChapterData = { json, slug ->
            // Принимает JsonElement со списком глав, возвращает List<ChapterResult>
            json.asJsonObject.getAsJsonArray("data")?.mapNotNull { item ->
                val chapter = item.asJsonObject
                val volume = chapter.get("volume")?.asString ?: "0"
                val number = chapter.get("number")?.asString ?: "0"
                val chapterName = chapter.get("name")?.asString
                val title = if (chapterName.isNullOrBlank()) "Том $volume. Глава $number"
                            else "Том $volume. Глава $number: $chapterName"
                val chapterId = chapter.get("id")?.asString ?: return@mapNotNull null
                ChapterResult(
                    title = title,
                    url = "$baseUrl/ranobe/$slug/v$volume/c$number?bid=$chapterId"
                )
            }?.reversed() ?: emptyList()
        },

        parseChapterContent = { json ->
            // Принимает JsonElement контента главы, возвращает HTML-строку
            val pages = json.asJsonObject.getAsJsonArray("data") ?: return@JsonApiScraperConfig ""
            val sb = StringBuilder()
            pages.forEach { page ->
                val obj = page.asJsonObject
                // Каждый элемент — строка текста или изображение
                when (obj.get("type")?.asString) {
                    "text" -> sb.append("<p>${obj.get("content")?.asString ?: ""}</p>")
                    "image" -> sb.append("<img src='${obj.get("url")?.asString ?: ""}'>")
                }
            }
            sb.toString()
        }
    )

    // Проксируем обложки через weserv.nl (часто нужно для защищённых CDN)
    private fun proxiedImageUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val stripped = raw.removePrefix("https://").removePrefix("http://")
        val encoded = URLEncoder.encode(stripped, "UTF-8")
        return "https://images.weserv.nl/?url=$encoded&https=1"
    }
}
```

**Ключевые моменты:**
- Используйте `getCatalogListJson`, `getCatalogSearchJson`, `getBookCoverJson`, etc. вместо HTML-хелперов
- `JsonApiScraperConfig` принимает лямбды-парсеры JSON
- `proxiedImageUrl` через `weserv.nl` — стандартный способ обойти hotlink-защиту на обложках

---

### Сценарий 10: Полностью ручной источник (без конфига)

**Когда:** API сайта слишком нестандартное, чтобы использовать `HtmlSelectors` или `JsonApiScraperConfig`. Реализуете все методы вручную.

**Пример сайта:** RanobeHub.

```kotlin
package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.Response
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.helpers.TextExtractor
import my.noveldokusha.scraper.helpers.tryConnect
import my.noveldokusha.scraper.utils.HttpHelpers.fetchJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class RanobeHub(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override val id = "ranobehub"
    override val nameStrId = R.string.source_name_ranobehub
    override val baseUrl = "https://ranobehub.org"
    private val apiBase = "https://api.ranobehub.org/"
    override val catalogUrl = "$baseUrl/ranobe"
    override val language = LanguageCode.RUSSIAN

    // НЕТ private val config — всё реализуем вручную

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val json = fetchJson(
                    "${apiBase}ranobe?sort=date&order=desc&page=${index + 1}",
                    emptyMap(), networkClient
                ).asJsonObject
                val items = json.getAsJsonArray("data") ?: return@tryConnect PagedList.empty()
                val hasNext = json.getAsJsonObject("meta")
                    ?.get("has_next_page")?.asBoolean ?: false
                PagedList(
                    list = items.map { item ->
                        val obj = item.asJsonObject
                        BookResult(
                            title = obj.get("title_ru")?.asString ?: obj.get("title_en")?.asString ?: "",
                            url = "$baseUrl/ranobe/${obj.get("id")?.asInt}",
                            coverImageUrl = obj.get("poster")?.asString ?: ""
                        )
                    },
                    index = index,
                    isLastPage = !hasNext
                )
            }
        }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val encoded = java.net.URLEncoder.encode(input, "UTF-8")
                val json = fetchJson(
                    "${apiBase}search?query=$encoded&page=${index + 1}",
                    emptyMap(), networkClient
                ).asJsonObject
                val items = json.getAsJsonArray("data") ?: return@tryConnect PagedList.empty()
                PagedList(
                    list = items.map { item ->
                        val obj = item.asJsonObject
                        BookResult(
                            title = obj.get("title_ru")?.asString ?: "",
                            url = "$baseUrl/ranobe/${obj.get("id")?.asInt}",
                            coverImageUrl = obj.get("poster")?.asString ?: ""
                        )
                    },
                    index = index,
                    isLastPage = true
                )
            }
        }

    override suspend fun getBookTitle(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect null
                val data = fetchJson("${apiBase}ranobe/$id", emptyMap(), networkClient)
                    .asJsonObject.getAsJsonObject("data")
                data?.get("title_ru")?.asString ?: data?.get("title_en")?.asString
            }
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect null
                fetchJson("${apiBase}ranobe/$id", emptyMap(), networkClient)
                    .asJsonObject.getAsJsonObject("data")?.get("poster")?.asString
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect null
                fetchJson("${apiBase}ranobe/$id", emptyMap(), networkClient)
                    .asJsonObject.getAsJsonObject("data")?.get("description")?.asString
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val id = extractId(bookUrl) ?: return@tryConnect emptyList()
                fetchJson("${apiBase}ranobe/$id/chapters", emptyMap(), networkClient)
                    .asJsonObject.getAsJsonArray("data")
                    ?.map { item ->
                        val obj = item.asJsonObject
                        ChapterResult(
                            title = "Том ${obj.get("volume")?.asInt}. Глава ${obj.get("number")?.asString}",
                            url = "$baseUrl/ranobe/$id/${obj.get("id")?.asInt}"
                        )
                    } ?: emptyList()
            }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            // Ищем контент в HTML документа
            val selectors = listOf(".text-content", ".chapter-content", ".reader-text", ".content")
            for (selector in selectors) {
                val el = doc.body()?.selectFirst(selector)
                if (el != null && el.text().isNotBlank()) {
                    // Удаляем мусор
                    el.select("script, style, .ads, nav").remove()
                    return@withContext TextExtractor.get(el)
                }
            }
            // Fallback: весь body
            doc.body()?.let {
                it.select("script, style, nav, header, footer").remove()
                TextExtractor.get(it)
            } ?: ""
        }

    override suspend fun getChapterListHash(bookUrl: String): Response<String?> =
        Response.Success(null)

    // Вспомогательная функция: извлекает числовой ID из URL
    private fun extractId(url: String): String? =
        url.removePrefix(baseUrl).trim('/').split("/").getOrNull(1)
}
```

**Ключевые моменты:**
- Все методы реализуются вручную через `withContext(Dispatchers.Default) { tryConnect { ... } }`
- `tryConnect { }` автоматически оборачивает в `Response.Success`/`Response.Error`
- `fetchJson(url, headers, networkClient)` — GET-запрос, возвращает `JsonElement`
- `TextExtractor.get(element)` — всегда для извлечения текста
- `PagedList(list, index, isLastPage)` — обёртка для постраничных результатов
- `BookResult(title, url, coverImageUrl)` — модель книги в каталоге
- `ChapterResult(title, url)` — модель главы

---

### Сценарий 11: Абстрактный шаблон для нескольких языков

**Когда:** Один и тот же сайт доступен на нескольких языках (разные поддомены, разные URL), и логика работы идентична. Создаётся `abstract class` с общей логикой и конкретные классы для каждого языка.

**Пример сайта:** WtrLab (wtr-lab.com) — EN, RU, etc.

```kotlin
// === ШАБЛОН (abstract class) ===
// Файл: scraper/src/main/java/my/noveldokusha/scraper/templates/WtrLabScraperTemplate.kt

package my.noveldokusha.scraper.templates

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.*
import my.noveldokusha.scraper.helpers.*
import org.jsoup.nodes.Document

abstract class WtrLabScraperTemplate(
    protected val networkClient: NetworkClient
) : SourceInterface.Catalog {

    // Абстрактные поля — задаются в конкретных классах
    abstract override val id: String
    abstract override val nameStrId: Int
    abstract override val language: LanguageCode
    abstract override val baseUrl: String
    protected abstract val translationLanguage: String  // "en", "ru", etc.

    override val catalogUrl get() = "$baseUrl/serie-ranking"

    override suspend fun getCatalogList(index: Int) = getCatalogList(config, index, networkClient)
    override suspend fun getCatalogSearch(index: Int, input: String) = getCatalogSearch(config, index, input, networkClient)
    override suspend fun getBookTitle(bookUrl: String) = getBookTitle(config, bookUrl, networkClient)
    override suspend fun getBookCoverImageUrl(bookUrl: String) = getBookCover(config, bookUrl, networkClient)
    override suspend fun getBookDescription(bookUrl: String) = getBookDescription(config, bookUrl, networkClient)
    override suspend fun getChapterList(bookUrl: String) = getChapterList(config, bookUrl, networkClient)
    override suspend fun getChapterListHash(bookUrl: String) = getChapterListHash(config, bookUrl, networkClient)

    // Переопределяем getChapterText — здесь может быть сложная логика,
    // одинаковая для всех языков
    override suspend fun getChapterText(doc: Document): String {
        return getChapterText(config, doc) ?: ""
    }

    // Конфиг — общий для всех языков, но использует abstract поля
    protected val config: HtmlSelectors by lazy {
        HtmlSelectors(
            baseUrl = baseUrl,
            language = language,
            catalog = CatalogSelectors(
                item = elements(".serie-card"),
                title = text(".serie-card__title").Clean(),
                url = attr("href", "a.serie-card__link"),
                cover = attr("src", "img.serie-card__cover")
            ),
            book = BookSelectors(
                title = text("h1.serie-title").Clean(),
                cover = attr("src", ".serie-cover img"),
                description = text(".serie-description").Clean(),
            ),
            chapters = ChapterSelectors(
                list = elements(".chapter-item a"),
                title = null,
                content = text(".chapter-text")
                    .removeElementsDOM("script", ".ads")
                    .applyStandardContentTransforms(baseUrl)
            ),
            chapterPaginationType = ChapterPaginationType.NONE,
            buildCatalogUrl = { index ->
                "$baseUrl/serie-ranking?lang=$translationLanguage&page=${index + 1}"
            },
            buildSearchUrl = { index, query ->
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                "$baseUrl/search?q=$encoded&lang=$translationLanguage&page=${index + 1}"
            },
        )
    }
}

// === КОНКРЕТНЫЙ КЛАСС — Английский ===
// Файл: scraper/src/main/java/my/noveldokusha/scraper/sources/WtrLabEn.kt

package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.templates.WtrLabScraperTemplate

class WtrLabEn(networkClient: NetworkClient) : WtrLabScraperTemplate(networkClient) {
    override val id = "wtrlab_en"
    override val nameStrId = R.string.source_name_wtrlab_en
    override val language = LanguageCode.ENGLISH
    override val baseUrl = "https://wtr-lab.com"
    override val translationLanguage = "en"
}

// === КОНКРЕТНЫЙ КЛАСС — Русский ===
// Файл: scraper/src/main/java/my/noveldokusha/scraper/sources/WtrLabRu.kt

package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.templates.WtrLabScraperTemplate

class WtrLabRu(networkClient: NetworkClient) : WtrLabScraperTemplate(networkClient) {
    override val id = "wtrlab_ru"
    override val nameStrId = R.string.source_name_wtrlab_ru
    override val language = LanguageCode.RUSSIAN
    override val baseUrl = "https://wtr-lab.com"
    override val translationLanguage = "ru"
}
```

**Ключевые моменты:**
- `abstract class` хранит общий конфиг и логику
- `by lazy { }` для `config` — чтобы абстрактные поля уже были инициализированы при создании конфига
- Каждый конкретный класс задаёт только `id`, `nameStrId`, `language`, `baseUrl` и специфические поля
- Все конкретные классы регистрируются отдельно в `Scraper.kt`
- Все конкретные классы требуют отдельной строки в `strings-no-translatable.xml`

---

## 8. Антипаттерны

### ❌ НЕ используйте `element.text()` напрямую

```kotlin
// НЕПРАВИЛЬНО — теряет форматирование абзацев
val text = doc.body()?.text()
val text = element.text()

// ПРАВИЛЬНО
val text = TextExtractor.get(element)
```

### ❌ НЕ забывайте про `removeElementsDOM` перед извлечением текста

```kotlin
// НЕПРАВИЛЬНО — реклама войдёт в текст главы
content = text(".chapter-content")
    .applyStandardContentTransforms(baseUrl)

// ПРАВИЛЬНО — сначала удаляем мусор из DOM
content = text(".chapter-content")
    .removeElementsDOM("script", "style", ".ads", ".chapter-nav", "a[href*='site.com']")
    .applyStandardContentTransforms(baseUrl)
```

### ❌ НЕ хардкодьте абсолютные URL, если сайт отдаёт относительные

```kotlin
// НЕПРАВИЛЬНО — будет "/novel/123" вместо "https://site.com/novel/123"
url = attr("href", "a.book-link"),
// ... без transformBookUrl

// ПРАВИЛЬНО
url = attr("href", "a.book-link"),
transformBookUrl = UrlTransformers.standardBookUrl(baseUrl),
```

### ❌ НЕ используйте `index` напрямую в URL без `+ 1`

```kotlin
// НЕПРАВИЛЬНО — страница 0 не существует на большинстве сайтов
buildCatalogUrl = { index -> "$baseUrl/novels?page=$index" }

// ПРАВИЛЬНО
buildCatalogUrl = { index -> "$baseUrl/novels?page=${index + 1}" }
```

### ❌ НЕ делайте сетевые запросы без `withContext(Dispatchers.Default)`

```kotlin
// НЕПРАВИЛЬНО — блокирует главный поток
override suspend fun getBookTitle(bookUrl: String): Response<String?> {
    return tryConnect {
        networkClient.get(bookUrl).toDocument().title()
    }
}

// ПРАВИЛЬНО
override suspend fun getBookTitle(bookUrl: String): Response<String?> =
    withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument().title()
        }
    }
```

### ❌ НЕ создавайте `config` как `val` в `abstract class` без `by lazy`

```kotlin
// НЕПРАВИЛЬНО — абстрактные поля ещё не инициализированы при вызове конструктора
abstract class MyTemplate : SourceInterface.Catalog {
    abstract override val baseUrl: String
    private val config = HtmlSelectors(baseUrl = baseUrl, ...) // baseUrl = null!
}

// ПРАВИЛЬНО — lazy гарантирует инициализацию после конструктора
abstract class MyTemplate : SourceInterface.Catalog {
    abstract override val baseUrl: String
    protected val config: HtmlSelectors by lazy {
        HtmlSelectors(baseUrl = baseUrl, ...)
    }
}
```

### ❌ НЕ игнорируйте `charset` для не-UTF-8 сайтов

```kotlin
// НЕПРАВИЛЬНО — китайские иероглифы будут кракозябрами
class MyChinese(nc: NetworkClient) : SourceInterface.Catalog {
    // без charset
}

// ПРАВИЛЬНО
class MyChinese(nc: NetworkClient) : SourceInterface.Catalog {
    override val charset = "GBK"
    private val config = HtmlSelectors(
        charset = charset,
        // ...
    )
}
```

### ❌ НЕ дублируйте код — используйте абстрактный шаблон для похожих источников

```kotlin
// НЕПРАВИЛЬНО — один и тот же сайт, просто другой язык
class WtrLabEn(nc: NetworkClient) : SourceInterface.Catalog {
    // 100 строк кода...
}
class WtrLabRu(nc: NetworkClient) : SourceInterface.Catalog {
    // те же 100 строк кода с минимальными отличиями...
}

// ПРАВИЛЬНО — см. Сценарий 11
abstract class WtrLabTemplate(nc: NetworkClient) : SourceInterface.Catalog { ... }
class WtrLabEn(nc: NetworkClient) : WtrLabTemplate(nc) { /* 5 строк */ }
class WtrLabRu(nc: NetworkClient) : WtrLabTemplate(nc) { /* 5 строк */ }
```

### ❌ НЕ забывайте про `reverseChapters` или `.asReversed()`

```kotlin
// НЕПРАВИЛЬНО — пользователь видит "Глава 100, 99, 98..." вместо "1, 2, 3..."
// (если сайт отдаёт в descending порядке)
chapterPaginationType = ChapterPaginationType.NONE,
// reverseChapters не указан (default = false)

// ПРАВИЛЬНО
reverseChapters = true,
// или для AJAX_BASED:
ajaxChapterListProvider = { bookUrl, nc ->
    // ...
    chapters.asReversed()
}
```

### ❌ НЕ указывайте дублирующийся `id`

ID должен быть глобально уникальным. Перед добавлением проверьте `builtInSourcesList` в `Scraper.kt`.

```kotlin
// НЕПРАВИЛЬНО — если "royalroad" уже существует
override val id = "royalroad"

// ПРАВИЛЬНО — уникальный, описательный
override val id = "my_new_site_name"
```

---

## 9. Регистрация источника

После написания класса источника нужно выполнить два действия:

### Шаг 9.1: Добавить строку имени

Файл: `strings/src/main/res/values/strings-no-translatable.xml`

```xml
<!-- Добавьте в конец списка, перед закрывающим тегом </resources> -->
<string translatable="false" name="source_name_my_site_name">MySite (mysite.com)</string>
```

**Правила именования:**
- `name` — должен совпадать с `R.string.source_name_XXX` в вашем классе
- Формат значения: `ИмяСайта (домен.com)` — так пользователи видят источник в UI
- `translatable="false"` — **обязательно**, названия сайтов не переводятся

### Шаг 9.2: Зарегистрировать в Scraper.kt

Файл: `scraper/src/main/java/my/noveldokusha/scraper/Scraper.kt`

```kotlin
val builtInSourcesList = setOf(
    // ... существующие источники ...
    MySiteName(networkClient),       // простой источник
    WtrLabEn(networkClient),         // конкретный класс из шаблона
    WtrLabRu(networkClient),         // второй конкретный класс
)
```

**Важно:**
- Добавляйте **экземпляр** класса (с `networkClient`), не сам класс
- Порядок в `setOf` не важен — источники сортируются по языку в UI
- Каждый конкретный класс (в т.ч. из абстрактного шаблона) добавляется отдельно

### Шаг 9.3: Добавить иконку (опционально)

Если фавикон сайта недоступен или некрасивый, загрузите PNG-иконку в репозиторий:
```
https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/mysitename.png
```

И укажите в классе:
```kotlin
override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/mysitename.png"
```

### Полная схема файловой структуры

```
Новый HTML-источник:
├── scraper/src/main/java/my/noveldokusha/scraper/sources/
│   └── MySiteName.kt                    ← новый файл
├── strings/src/main/res/values/
│   └── strings-no-translatable.xml     ← добавить строку
└── scraper/src/main/java/my/noveldokusha/scraper/
    └── Scraper.kt                       ← добавить в builtInSourcesList

Абстрактный шаблон + несколько языков:
├── scraper/src/main/java/my/noveldokusha/scraper/templates/
│   └── MySiteTemplate.kt               ← абстрактный класс
├── scraper/src/main/java/my/noveldokusha/scraper/sources/
│   ├── MySiteEn.kt                     ← конкретный класс EN
│   └── MySiteRu.kt                     ← конкретный класс RU
├── strings/src/main/res/values/
│   └── strings-no-translatable.xml     ← добавить ДВЕ строки (EN и RU)
└── scraper/.../Scraper.kt              ← добавить ОБА экземпляра
```

---

## 10. Дополнительные AJAX-паттерны

В этом разделе собраны конкретные реализации `ajaxChapterListProvider`, встречающиеся в реальных источниках.

---

### 10.1: AJAX GET с novelId из meta-тега (NovelBin-стиль)

**Когда:** Сайт загружает список глав через GET-запрос `/ajax/chapter-archive?novelId=XXX`, а `novelId` нужно извлечь из мета-тега `og:url` страницы книги.

**Пример сайтов:** NovelBin, ReadNovelFull.

```kotlin
chapterPaginationType = ChapterPaginationType.AJAX_BASED,
ajaxChapterListProvider = { bookUrl, networkClient ->
    val doc = networkClient.get(bookUrl).toDocument()
    // novelId — последний сегмент канонического URL из og:url
    val novelId = doc.selectFirst("meta[property=og:url]")
        ?.attr("content")
        ?.toUrlBuilderSafe()?.build()?.lastPathSegment
        ?: return@ajaxChapterListProvider emptyList()

    val ajaxUrl = "https://novelbin.com/ajax/chapter-archive?novelId=$novelId"
    networkClient.get(ajaxUrl).toDocument()
        .select("ul.list-chapter li a")
        .map { element ->
            ChapterResult(title = element.text(), url = element.attr("href"))
        }
},
```

**Вариант — novelId из `data`-атрибута (ReadNovelFull):**
```kotlin
ajaxChapterListProvider = { bookUrl, networkClient ->
    val pageDoc = networkClient.get(bookUrl).toDocument()
    val novelId = pageDoc.selectFirst("#rating[data-novel-id]")?.attr("data-novel-id")
        ?: return@ajaxChapterListProvider emptyList()
    val ajaxUrl = "$baseUrl/ajax/chapter-archive?novelId=$novelId"
    networkClient.get(ajaxUrl).toDocument()
        .select("ul.list-chapter li a")
        .map { element ->
            ChapterResult(title = element.text(), url = element.attr("href"))
        }
},
```

> **Импорт:** `import my.noveldokusha.scraper.utils.UrlHelpers.toUrlBuilderSafe`

---

### 10.2: AJAX GET с novelId = slug из URL (NovLove-стиль)

**Когда:** Идентификатор книги — это slug, являющийся последним сегментом URL книги. GET-запрос строится как `{baseUrl}/ajax/chapter-archive?novelId={slug}`.

```kotlin
chapterPaginationType = ChapterPaginationType.AJAX_BASED,
ajaxChapterListProvider = { bookUrl, networkClient ->
    // slug — это последний сегмент URL после финального "/"
    val novelId = bookUrl.removeSuffix("/").substringAfterLast("/")
    val ajaxUrl = "${baseUrl.removeSuffix("/")}/ajax/chapter-archive?novelId=$novelId"
    networkClient.get(ajaxUrl).toDocument()
        .select("ul.list-chapter li a")
        .map { element ->
            ChapterResult(
                title = element.text(),
                url = element.attr("abs:href")  // Jsoup трюк — абсолютный URL
            )
        }
},
```

> **Ключевое:** `element.attr("abs:href")` — Jsoup автоматически преобразует относительный href в абсолютный, если базовый URL документа известен (после `toDocument()`). Не требует `transformChapterUrl`.

---

### 10.3: AJAX GET с bookId из `<script>` через Regex (NovelBuddy-стиль)

**Когда:** Числовой ID книги вшит в `<script>` тег на странице книги в виде `bookId = 12345`. Список глав загружается через API `/api/manga/{bookId}/chapters`.

```kotlin
chapterPaginationType = ChapterPaginationType.AJAX_BASED,
ajaxChapterListProvider = { bookUrl, networkClient ->
    // Шаг 1: загружаем страницу книги и ищем bookId в скриптах
    val bookDoc = networkClient.get(bookUrl).toDocument()
    val scriptHtml = bookDoc.select("script").html()
    val bookId = Regex("""bookId\s*=\s*(\d+)""")
        .find(scriptHtml)?.groupValues?.getOrNull(1)

    if (bookId.isNullOrEmpty()) {
        emptyList()
    } else {
        // Шаг 2: запрашиваем список глав через API
        val ajaxUrl = buildUrl(baseUrl, "api/manga/$bookId/chapters?source=detail")
        networkClient.get(ajaxUrl).toDocument()
            .select("li")
            .map { element ->
                ChapterResult(
                    title = element.select("strong.chapter-title").text().trim(),
                    url = baseUrl + element.select("a").attr("href")
                )
            }
            .asReversed()  // API отдаёт новые первыми
    }
},
```

> **Паттерн поиска:** Regex `"""bookId\s*=\s*(\d+)"""` ищет присваивание переменной. Адаптируйте под конкретный сайт: `"""novel_id\s*:\s*(\d+)"""`, `""""id"\s*:\s*(\d+)"""` и т.д.

---

### 10.4: WordPress `wi_getreleases_pagination` (ScribbleHub-стиль)

**Когда:** WordPress-сайт с плагином для глав и `admin-ajax.php`. Специальный action `wi_getreleases_pagination` с параметром `pagenum=-1` возвращает **все главы за один запрос** (без пагинации).

```kotlin
chapterPaginationType = ChapterPaginationType.AJAX_BASED,
ajaxChapterListProvider = { bookUrl, networkClient ->
    // Извлекаем числовой ID серии из URL вида /series/12345/
    val seriesId = Regex("series/(\\d+)/")
        .find(bookUrl)?.groupValues?.get(1)
        ?: throw Exception("Не удалось найти ID серии в URL: $bookUrl")

    val doc = POST(
        url = "$baseUrl/wp-admin/admin-ajax.php",
        data = mapOf(
            "action"   to "wi_getreleases_pagination",
            "pagenum"  to "-1",        // -1 = вернуть ВСЕ главы сразу
            "mypostid" to seriesId
        ),
        headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Origin"           to baseUrl.removeSuffix("/"),
            "Referer"          to bookUrl,
            "Accept"           to "*/*"
        ),
        networkClient = networkClient
    )

    // Порядок: новые главы первые → разворачиваем
    doc.select(".toc_w a[href]").reversed().map { element ->
        ChapterResult(title = element.text(), url = element.attr("href"))
    }
},
```

> **Ключевые отличия от Сценария 5 (Jaomix):**
> - Один запрос вместо цикла (потому что `pagenum=-1`)
> - Action: `wi_getreleases_pagination` (другой WordPress-плагин)
> - ID серии в URL в формате `/series/12345/` (а не в теге на странице)

---

### 10.5: WordPress `{bookUrl}/ajax/chapters/` POST (WuxiaWorld-стиль)

**Когда:** WordPress Madara theme. Список глав загружается POST-запросом на `{bookUrl}/ajax/chapters/` (без дополнительных параметров).

```kotlin
chapterPaginationType = ChapterPaginationType.AJAX_BASED,
ajaxChapterListProvider = { bookUrl, networkClient ->
    val ajaxUrl = "${bookUrl.removeSuffix("/")}/ajax/chapters/"
    networkClient.call(postRequest(ajaxUrl)).toDocument()
        .select("li.wp-manga-chapter a[href]")
        .map { element ->
            ChapterResult(
                title = element.text().trim(),
                url = element.attr("href").let { href ->
                    if (href.startsWith("http")) href
                    else baseUrl.removeSuffix("/") + href
                }
            )
        }
},
reverseChapters = true,  // WordPress Madara отдаёт новые главы первыми
```

> **Важно:**
> - URL запроса: `{URL_книги}/ajax/chapters/` (слэш на конце обязателен)
> - `postRequest(ajaxUrl)` — функция из HttpHelpers, POST без тела
> - CSS-селектор для WordPress Madara: `li.wp-manga-chapter a[href]`
> - `reverseChapters = true` — главы сортируются descending

---

### 10.6: JSON API + Regex-парсинг + ручное построение URL глав (Ttkan-стиль)

**Когда:** Сайт отдаёт список глав через JSON API, но ответ — не стандартный массив объектов, а плоский JSON, который удобнее распарсить Regex. URL каждой главы строится по шаблону из ID книги и порядкового номера главы.

```kotlin
chapterPaginationType = ChapterPaginationType.AJAX_BASED,
ajaxChapterListProvider = { bookUrl, networkClient ->
    // Извлекаем ID новеллы из URL вида /novel/chapters/my-novel-id?
    val novelId = bookUrl
        .substringAfter("/novel/chapters/")
        .substringBefore("?")
        .trim()

    val apiUrl = "https://www.ttkan.co/api/nq/amp_novel_chapters" +
                 "?language=tw&novel_id=$novelId"
    val jsonText = networkClient.get(apiUrl).body.string()

    val chapters = mutableListOf<ChapterResult>()
    var index = 1
    // Regex-парсинг JSON без Gson: ищем все значения "chapter_name"
    Regex(""""chapter_name"\s*:\s*"([^"]+)"""")
        .findAll(jsonText)
        .forEach { match ->
            chapters.add(ChapterResult(
                title = match.groupValues[1],
                // URL главы строится по шаблону: novelId + порядковый номер
                url = "https://www.ttkan.co/novel/pagea/${novelId}_${index}.html"
            ))
            index++
        }
    chapters  // уже в правильном порядке (старые первые)
},
```

> **Когда использовать Regex-парсинг JSON:**
> - Ответ API не стандартный JSON-объект, а строка/JSONP
> - Нет доступа к Gson в данном контексте (лямбда внутри конфига)
> - Структура простая: один тип полей, нет вложенности
>
> **Предупреждение:** Regex-парсинг хрупкий. Если структура JSON изменится, парсер сломается. Для сложных случаев лучше использовать Сценарий 10 (полностью ручной) с `fetchJson` и Gson.

---

## 11. Трюки и нестандартные приёмы

### 11.1: `element.attr("abs:href")` — Jsoup абсолютные URL

Jsoup поддерживает специальный псевдоатрибут `abs:` — он автоматически преобразует относительный URL в абсолютный, используя базовый URL документа.

```kotlin
// Стандартный подход — вручную
val href = element.attr("href")                     // "/novel/123"
val absoluteUrl = if (href.startsWith("http")) href else "$baseUrl$href"

// Jsoup-трюк — автоматически
val absoluteUrl = element.attr("abs:href")          // "https://site.com/novel/123"
val absoluteSrc = element.attr("abs:src")           // "https://site.com/images/cover.jpg"
```

**Работает для:** `abs:href`, `abs:src`, `abs:action`, любой атрибут с URL.

**Условие:** Документ должен быть создан через `toDocument()` — тогда Jsoup знает базовый URL.

**Используется в:** `ajaxChapterListProvider` лямбдах, где `transformChapterUrl` недоступен.

---

### 11.2: `attr("title", "img")` — название из атрибута img

Некоторые сайты хранят название книги не в тексте элемента, а в атрибуте `title` тега `<img>`.

```html
<!-- Структура на сайте BacaLightnovel -->
<div class="book-item">
    <img src="cover.jpg" title="Название книги" alt="Название книги">
    <a href="/novel/123">...</a>
</div>
```

```kotlin
catalog = CatalogSelectors(
    item = elements(".book-item"),
    title = attr("title", "img").Clean(),   // из атрибута title тега img
    url = attr("href", "a"),
    cover = attr("src", "img")
),
```

> Аналогично можно использовать `attr("alt", "img")` — иногда alt-текст содержит название.

---

### 11.3: `attr("content", "meta[name=description]")` — описание из мета-тега

Если страница книги не имеет явного блока с описанием, но имеет SEO-мета-теги, описание можно взять оттуда:

```kotlin
book = BookSelectors(
    title = text("h1.book-title").Clean(),
    cover = attr("src", ".cover img"),
    // Описание из мета-тега description
    description = attr("content", "meta[name=description]").Clean(),
),
```

**Варианты:**
```kotlin
attr("content", "meta[name=description]")          // стандартный мета-тег
attr("content", "meta[property='og:description']") // Open Graph
attr("content", "meta[name='twitter:description']") // Twitter Card
```

> **Ограничение:** Мета-описание часто короче полного синопсиса и может быть обрезано.

---

### 11.4: `attr("src", ":not(*)")` — CSS-селектор "нет обложки"

Если сайт не предоставляет обложки в каталоге, используйте CSS-псевдокласс `:not(*)` — он никогда не совпадает ни с одним элементом, т.е. всегда вернёт пустую строку.

```kotlin
catalog = CatalogSelectors(
    item = elements(".novel-item"),
    title = text(".title").Clean(),
    url = attr("href", "a"),
    cover = attr("src", ":not(*)")   // обложек нет — всегда ""
),
```

> **Зачем это нужно:** `cover` — обязательное поле в `CatalogSelectors`. Если написать пустой селектор `attr("src", "")`, может произойти непредвиденное поведение. `:not(*)` — явный и безопасный способ сказать «обложки нет».

---

### 11.5: `companion object` для buildUrl-функций

Если URL-логика сложная и переиспользуется в нескольких местах (например, и в `buildCatalogUrl`, и в `getCatalogList`), вынесите её в `companion object`.

```kotlin
class NovelFull(private val networkClient: NetworkClient) : SourceInterface.Catalog {

    override val id = "novel_full"
    // ...

    private val config = HtmlSelectors(
        // ...
        buildCatalogUrl = { index -> buildCatalogUrl(baseUrl, index) },
        buildSearchUrl  = { index, input -> buildSearchUrl(baseUrl, index, input) },
    )

    companion object {
        fun buildCatalogUrl(baseUrl: String, index: Int): String =
            if (index == 0) "$baseUrl/novel-list"
            else "$baseUrl/novel-list?page=${index + 1}"

        fun buildSearchUrl(baseUrl: String, index: Int, input: String): String {
            val encoded = java.net.URLEncoder.encode(input, "UTF-8")
            return "$baseUrl/search?keyword=$encoded" +
                   if (index > 0) "&page=${index + 1}" else ""
        }
    }
}
```

> **Когда использовать:** Когда нужно тестировать URL-логику отдельно, или когда URL строится из нескольких условий.

---

### 11.6: `toUrlBuilderSafe()` + `builder.add()` для URL с параметрами

Вместо ручной конкатенации строк используйте `toUrlBuilderSafe()` — он возвращает `HttpUrl.Builder`, который правильно кодирует параметры и добавляет их к URL.

```kotlin
import my.noveldokusha.scraper.utils.UrlHelpers.toUrlBuilderSafe
import my.noveldokusha.scraper.utils.UrlHelpers.buildUrl

// Пример: строим URL поиска с несколькими параметрами
buildSearchUrl = { index, query ->
    val builder = buildUrl(baseUrl, "search").toUrlBuilderSafe()
    builder.add("keyword", query)       // автоматически URL-кодирует
    if (index > 0) builder.add("page", "${index + 1}")
    builder.toString()
},

// Пример: условное добавление параметра
buildCatalogUrl = { index ->
    val builder = buildUrl(baseUrl, "novel-list").toUrlBuilderSafe()
    if (index > 0) builder.add("page", "${index + 1}")
    builder.toString()
},
```

> **Преимущество перед URLEncoder:** `builder.add()` правильно обрабатывает спецсимволы, пробелы и Unicode без ручного кодирования. `buildUrl(base, path)` — хелпер из `UrlHelpers.kt`.

---

### 11.7: `postRequest(url)` vs `POST(url, data, headers, nc)` — два вида POST

В проекте есть два способа делать POST-запросы:

| Функция | Когда использовать | Тело |
|---|---|---|
| `postRequest(url)` | WordPress Madara `/ajax/chapters/` | Пустое тело |
| `POST(url, data, headers, nc)` | `admin-ajax.php` с form-data | `Map<String, String>` |

```kotlin
// Пустой POST (WordPress Madara):
import my.noveldokusha.scraper.utils.HttpHelpers.postRequest
networkClient.call(postRequest(bookUrl + "/ajax/chapters/")).toDocument()

// POST с данными (admin-ajax.php):
import my.noveldokusha.scraper.utils.HttpHelpers.POST
POST(
    url = "$baseUrl/wp-admin/admin-ajax.php",
    data = mapOf("action" to "wi_getreleases_pagination", "pagenum" to "-1"),
    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
    networkClient = networkClient
)
```

---

### 11.8: `networkClient.get(url).body.string()` — сырой текст ответа

Для JSON API без Gson (или для Regex-парсинга) можно получить тело ответа как сырую строку:

```kotlin
ajaxChapterListProvider = { bookUrl, networkClient ->
    val apiUrl = "..."
    val rawJson: String = networkClient.get(apiUrl).body.string()
    // Парсинг через Regex или вручную
    Regex(""""title"\s*:\s*"([^"]+)"""").findAll(rawJson).map { ... }
}
```

> **Обратите внимание:** `body.string()` потребляет тело — вызвать дважды нельзя.

---

## Быстрый справочник: что импортировать

```kotlin
// Обязательные импорты для любого источника
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.networking.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.configs.CatalogSelectors
import my.noveldokusha.scraper.configs.BookSelectors
import my.noveldokusha.scraper.configs.ChapterSelectors
import my.noveldokusha.scraper.configs.HtmlSelectors
import my.noveldokusha.scraper.configs.ChapterPaginationType
import my.noveldokusha.scraper.configs.SelectorConfig.attr
import my.noveldokusha.scraper.configs.SelectorConfig.elements
import my.noveldokusha.scraper.configs.SelectorConfig.text
import my.noveldokusha.scraper.configs.SelectorTransforms.Clean
import my.noveldokusha.scraper.configs.SelectorTransforms.applyStandardContentTransforms
import my.noveldokusha.scraper.configs.SelectorTransforms.removeElementsDOM
import my.noveldokusha.scraper.helpers.ScraperHelpers.getCatalogList
import my.noveldokusha.scraper.helpers.ScraperHelpers.getCatalogSearch
import my.noveldokusha.scraper.helpers.ScraperHelpers.getBookTitle
import my.noveldokusha.scraper.helpers.ScraperHelpers.getBookCover
import my.noveldokusha.scraper.helpers.ScraperHelpers.getBookDescription
import my.noveldokusha.scraper.helpers.ScraperHelpers.getChapterList
import my.noveldokusha.scraper.helpers.ScraperHelpers.getChapterText
import my.noveldokusha.scraper.helpers.ScraperHelpers.getChapterListHash
import my.noveldokusha.scraper.utils.UrlTransformers
import org.jsoup.nodes.Document

// Для AJAX/POST источников
import my.noveldokusha.scraper.utils.HttpHelpers.POST
import my.noveldokusha.scraper.utils.HttpHelpers.fetchJson
import kotlinx.coroutines.delay
import kotlin.random.Random
import java.net.URI

// Для полностью ручных источников
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.Response
import my.noveldokusha.scraper.helpers.tryConnect
import my.noveldokusha.scraper.helpers.TextExtractor
import org.jsoup.Jsoup

// Для JSON API источников
import my.noveldokusha.scraper.configs.JsonApiScraperConfig
import my.noveldokusha.scraper.helpers.ScraperHelpers.getCatalogListJson
import my.noveldokusha.scraper.helpers.ScraperHelpers.getCatalogSearchJson
import my.noveldokusha.scraper.helpers.ScraperHelpers.getBookTitleJson
import my.noveldokusha.scraper.helpers.ScraperHelpers.getBookCoverJson
import my.noveldokusha.scraper.helpers.ScraperHelpers.getBookDescriptionJson
import my.noveldokusha.scraper.helpers.ScraperHelpers.getChapterListJson
import my.noveldokusha.scraper.helpers.ScraperHelpers.getChapterTextJson
import java.net.URLEncoder
```

> **Примечание:** В проекте используются wildcard-импорты (`import my.noveldokusha.scraper.configs.*`, `import my.noveldokusha.scraper.helpers.*`), поэтому на практике достаточно указать их — и все публичные функции из этих пакетов будут доступны.

---

*SOURCE GUIDE v2 — конец документа. Для дополнения гайда новыми источниками используйте следующую сессию.*




