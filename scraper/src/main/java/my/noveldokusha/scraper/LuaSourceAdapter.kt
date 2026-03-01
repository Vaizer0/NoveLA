package my.noveldokusha.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.core.fromIso639_1
import my.noveldokusha.scraper.configs.SourceMetadata
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.luaj.vm2.LuaValue
import org.luaj.vm2.LuaTable
import org.jsoup.nodes.Document
import timber.log.Timber

/**
 * Адаптер для Lua источников, реализующий SourceInterface.Catalog
 */
class LuaSourceAdapter(
    private val luaScript: LuaValue,
    private val metadata: SourceMetadata
) : SourceInterface.Catalog {

    override val id: String = metadata.id
    override val nameStrId: Int = 0  // Lua источники не используют строковые ресурсы
    override val name: String = metadata.name  // Динамическое имя из метаданных
    override val baseUrl: String = getBaseUrlFromScript()
    override val catalogUrl: String = baseUrl
    override val language: LanguageCode = fromIso639_1(metadata.language)
    override val iconUrl: Any = metadata.icon
    override val iconResId: Int? = null

    init {
        // Проверяем наличие обязательных функций в Lua скрипте
        validateLuaScript()
    }

    /**
     * Получение baseUrl из Lua скрипта
     */
    private fun getBaseUrlFromScript(): String {
        return try {
            luaScript.get("baseUrl")?.checkjstring() ?: metadata.url
        } catch (e: Exception) {
            Timber.w(e, "Failed to get baseUrl from Lua script, using metadata URL")
            metadata.url
        }
    }

    /**
     * Валидация Lua скрипта
     */
    private fun validateLuaScript() {
        val requiredFunctions = listOf(
            "getCatalogList", "getCatalogSearch", "getBookTitle",
            "getBookCoverImageUrl", "getBookDescription",
            "getChapterList", "getChapterText"
        )

        requiredFunctions.forEach { funcName ->
            if (luaScript.get(funcName).isnil()) {
                throw IllegalStateException("Missing required function: $funcName")
            }
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> {
        return withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getCatalogList").call(LuaValue.valueOf(index))
                convertLuaResultToPagedList(result)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get catalog list for ${metadata.id}")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> {
        return withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getCatalogSearch").call(
                    LuaValue.valueOf(index),
                    LuaValue.valueOf(input)
                )
                convertLuaResultToPagedList(result)
            } catch (e: Exception) {
                Timber.e(e, "Failed to search catalog for ${metadata.id}")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    override suspend fun getBookTitle(bookUrl: String): Response<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getBookTitle").call(LuaValue.valueOf(bookUrl))
                Response.Success(result.optjstring(null))
            } catch (e: Exception) {
                Timber.e(e, "Failed to get book title for ${metadata.id}")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getBookCoverImageUrl").call(LuaValue.valueOf(bookUrl))
                Response.Success(result.optjstring(null))
            } catch (e: Exception) {
                Timber.e(e, "Failed to get book cover for ${metadata.id}")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getBookDescription").call(LuaValue.valueOf(bookUrl))
                Response.Success(result.optjstring(null))
            } catch (e: Exception) {
                Timber.e(e, "Failed to get book description for ${metadata.id}")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> {
        return withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getChapterList").call(LuaValue.valueOf(bookUrl))
                val chapters = mutableListOf<ChapterResult>()

                if (result.istable()) {
                    val table = result.checktable()
                    for (i in 1..table.length()) {
                        val chapter = table.get(LuaValue.valueOf(i))
                        if (chapter.istable()) {
                            chapters.add(convertLuaTableToChapterResult(chapter.checktable()))
                        }
                    }
                }

                Response.Success(chapters)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get chapter list for ${metadata.id}")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }
    }

    override suspend fun getChapterText(doc: Document): String? {
        return try {
            val html = doc.html()
            val result = luaScript.get("getChapterText").call(LuaValue.valueOf(html))
            result.optjstring(null)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get chapter text for ${metadata.id}")
            null
        }
    }

    override suspend fun getChapterListHash(bookUrl: String): Response<String?> {
        return try {
            val hashFunction = luaScript.get("getChapterListHash")
            if (hashFunction.isnil()) {
                Response.Success(null)
            } else {
                val result = hashFunction.call(LuaValue.valueOf(bookUrl))
                Response.Success(result.optjstring(null))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get chapter list hash for ${metadata.id}")
            Response.Error(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Конвертация результата Lua в PagedList<BookResult>
     */
    private fun convertLuaResultToPagedList(luaResult: LuaValue): Response<PagedList<BookResult>> {
        return try {
            if (!luaResult.istable()) {
                return Response.Success(PagedList(listOf(), 0, true))
            }

            val table = luaResult.checktable()
            val items = mutableListOf<BookResult>()
            val itemsTable = table.get("items")?.checktable()

            if (itemsTable != null) {
                for (i in 1..itemsTable.length()) {
                    val item = itemsTable.get(LuaValue.valueOf(i))
                    if (item.istable()) {
                        items.add(convertLuaTableToBookResult(item.checktable()))
                    }
                }
            }

            val hasNext = table.get("hasNext")?.optboolean(false) ?: false
            Response.Success(PagedList(items, 0, !hasNext))
        } catch (e: Exception) {
            Timber.e(e, "Failed to convert Lua result to PagedList")
            Response.Error(e.message ?: "Unknown error", e)
        }
    }

    /**
     * Конвертация Lua таблицы в BookResult
     */
    private fun convertLuaTableToBookResult(table: LuaTable): BookResult {
        return BookResult(
            title = table.get("title")?.optjstring("") ?: "",
            url = table.get("url")?.optjstring("") ?: "",
            coverImageUrl = table.get("cover")?.optjstring("") ?: ""
        )
    }

    /**
     * Конвертация Lua таблицы в ChapterResult
     */
    private fun convertLuaTableToChapterResult(table: LuaTable): ChapterResult {
        return ChapterResult(
            title = table.get("title")?.optjstring("") ?: "",
            url = table.get("url")?.optjstring("") ?: ""
        )
    }
}