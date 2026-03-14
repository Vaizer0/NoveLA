package my.noveldokusha.scraper

import android.content.Context
import androidx.compose.runtime.Composable
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
 * Адаптер для Lua источников, реализующий SourceInterface.Catalog.
 *
 * Иерархия классов:
 *
 *   LuaSourceAdapter                         (базовый)
 *     ├── LuaSourceAdapterConfigurable       (+ getSettingsSchema → Configurable)
 *     ├── LuaSourceAdapterFilterable         (+ getFilterList → FilterableCatalog)
 *     └── LuaSourceAdapterFull               (+ оба → Configurable + FilterableCatalog)
 *           extends LuaSourceAdapterConfigurable
 *
 * Создавать только через фабричный метод createLuaSourceAdapter().
 *
 * Фильтры: список фильтров всегда исходит из Lua — getFilterList() вызывает
 * Lua-функцию каждый раз, без кэширования List<LuaFilter> в адаптере.
 */

/**
 * Фабричный метод — определяет нужный подкласс по наличию функций в Lua-скрипте.
 * Только проверяет isnil() — НЕ вызывает getFilterList() / getSettingsSchema().
 */
fun createLuaSourceAdapter(
    context: Context,
    luaScript: LuaValue,
    luaEngine: LuaEngine,
    iconUrlFromYaml: String? = null,
    fileName: String?
): LuaSourceAdapter {
    val hasSettings = !luaScript.get("getSettingsSchema").isnil()
    val hasFilters  = !luaScript.get("getFilterList").isnil()

    // Settings парсятся сразу — они статичны и нужны для UI настроек при открытии экрана
    val schema = if (hasSettings) parseLuaSettingsSchema(luaScript) else null

    return when {
        schema != null && hasFilters ->
            LuaSourceAdapterFull(context, luaScript, luaEngine, iconUrlFromYaml, fileName, schema)
        schema != null ->
            LuaSourceAdapterConfigurable(context, luaScript, luaEngine, iconUrlFromYaml, fileName, schema)
        hasFilters ->
            LuaSourceAdapterFilterable(context, luaScript, luaEngine, iconUrlFromYaml, fileName)
        else ->
            LuaSourceAdapter(context, luaScript, luaEngine, iconUrlFromYaml, fileName)
    }
}

open class LuaSourceAdapter(
    protected val context: Context,
    protected val luaScript: LuaValue,
    protected val luaEngine: LuaEngine,
    protected val iconUrlFromYaml: String? = null,
    protected val fileName: String?
) : SourceInterface.Catalog {

    // Метаданные читаем из Lua один раз при создании
    private val metadata: SourceMetadata = extractMetadata()

    override val id: String      = metadata.id
    override val nameStrId: Int  = 0
    override val name: String    = metadata.name.ifEmpty { "Unknown" }
    override val baseUrl: String = metadata.url.ifEmpty {
        try { luaScript.get("baseUrl").optjstring("") } catch (_: Exception) { "" }
    }
    override val catalogUrl: String = baseUrl
    override val charset: String = metadata.charset ?: "UTF-8"

    override val language: LanguageCode? = when (metadata.language.lowercase().trim()) {
        "mtl", "multi" -> LanguageCode.MTL
        else -> fromIso639_1(metadata.language)
    }

    override val iconResId: Int? = null

    override val iconUrl: String? = iconUrlFromYaml
        ?: metadata.icon.takeIf { it.isNotEmpty() }?.let { icon ->
            if (icon.startsWith("http")) icon
            else "${baseUrl.trimEnd('/')}/$icon"
        }

    init {
        validateLuaScript()
    }

    // ── Метаданные ────────────────────────────────────────────────────────────

    private fun extractMetadata(): SourceMetadata {
        fun s(key: String, def: String = "") = try {
            luaScript.get(key).optjstring(def)
        } catch (_: Exception) { def }

        return SourceMetadata(
            id          = s("id", fileName?.let { "lua_${it}" } ?: "lua_unknown"),
            name        = s("name", "Unknown Source"),
            version     = s("version", "1.0.0"),
            description = s("description"),
            url         = s("baseUrl"),
            icon        = s("icon"),
            language    = s("language", "en"),
            charset     = s("charset").takeIf { it.isNotBlank() }
        )
    }

    private fun validateLuaScript() {
        val required = listOf(
            "getCatalogList", "getCatalogSearch", "getBookTitle",
            "getBookCoverImageUrl", "getBookDescription",
            "getChapterList", "getChapterText"
        )
        required.forEach { fn ->
            if (luaScript.get(fn).isnil())
                Timber.w("LuaSourceAdapter [${metadata.id}]: missing '$fn'")
        }
    }

    // ── SourceInterface.Catalog ───────────────────────────────────────────────

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getCatalogList").call(LuaValue.valueOf(index))
                convertLuaResultToPagedList(result)
            } catch (e: Exception) {
                Timber.e(e, "Lua getCatalogList [${metadata.id}]")
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getCatalogSearch").call(
                    LuaValue.valueOf(index),
                    LuaValue.valueOf(input)
                )
                convertLuaResultToPagedList(result)
            } catch (e: Exception) {
                Timber.e(e, "Lua getCatalogSearch [${metadata.id}]")
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getBookTitle(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            try {
                Response.Success(
                    luaScript.get("getBookTitle").call(LuaValue.valueOf(bookUrl)).optjstring(null)
                )
            } catch (e: Exception) {
                Timber.e(e, "Lua getBookTitle [${metadata.id}]")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            try {
                Response.Success(
                    luaScript.get("getBookCoverImageUrl").call(LuaValue.valueOf(bookUrl)).optjstring(null)
                )
            } catch (e: Exception) {
                Timber.e(e, "Lua getBookCoverImageUrl [${metadata.id}]")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            try {
                Response.Success(
                    luaScript.get("getBookDescription").call(LuaValue.valueOf(bookUrl)).optjstring(null)
                )
            } catch (e: Exception) {
                Timber.e(e, "Lua getBookDescription [${metadata.id}]")
                Response.Error(e.message ?: "Unknown error", e)
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.IO) {
            try {
                val result = luaScript.get("getChapterList").call(LuaValue.valueOf(bookUrl))
                val chapters = mutableListOf<ChapterResult>()
                if (result.istable()) {
                    val table = result.checktable()
                    for (i in 1..table.length()) {
                        val ch = table.get(LuaValue.valueOf(i))
                        if (ch.istable()) chapters.add(convertLuaTableToChapterResult(ch.checktable()))
                    }
                }
                Response.Success(chapters)
            } catch (e: Exception) {
                Timber.e(e, "Lua getChapterList [${metadata.id}]")
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getChapterText(doc: Document): String? {
        val html = doc.outerHtml()
        val url  = doc.location()
        Timber.d("LuaSourceAdapter: url='$url'")
        return luaScript.get("getChapterText").call(
            LuaValue.valueOf(html),
            LuaValue.valueOf(url)
        ).optjstring(null)
    }

    override suspend fun getChapterListHash(bookUrl: String): Response<String?> =
        try {
            val fn = luaScript.get("getChapterListHash")
            if (fn.isnil()) Response.Success(null)
            else Response.Success(fn.call(LuaValue.valueOf(bookUrl)).optjstring(null))
        } catch (e: Exception) {
            Timber.e(e, "Lua getChapterListHash [${metadata.id}]")
            Response.Error(e.message ?: "Unknown error", e)
        }

    // ── Конвертация Lua → Kotlin ──────────────────────────────────────────────

    // protected — доступен подклассам FilterableCatalog
    protected fun convertLuaResultToPagedList(luaResult: LuaValue): Response<PagedList<BookResult>> {
        if (!luaResult.istable()) return Response.Success(PagedList(listOf(), 0, true))
        return try {
            val table = luaResult.checktable()
            val items = mutableListOf<BookResult>()
            val itemsTable = table.get("items").opttable(null)
            if (itemsTable != null) {
                for (i in 1..itemsTable.length()) {
                    val item = itemsTable.get(LuaValue.valueOf(i))
                    if (item.istable()) items.add(convertLuaTableToBookResult(item.checktable()))
                }
            }
            val hasNext = table.get("hasNext").optboolean(false)
            Response.Success(PagedList(items, 0, !hasNext))
        } catch (e: Exception) {
            Timber.e(e, "convertLuaResultToPagedList failed")
            Response.Error(e.message ?: "Conversion error", e)
        }
    }

    private fun convertLuaTableToBookResult(table: LuaTable) = BookResult(
        title         = table.get("title").optjstring(""),
        url           = table.get("url").optjstring(""),
        coverImageUrl = table.get("cover").optjstring("")
    )

    private fun convertLuaTableToChapterResult(table: LuaTable) = ChapterResult(
        title  = table.get("title").optjstring(""),
        url    = table.get("url").optjstring(""),
        volume = table.get("volume").optjstring(null)
    )
}

// ── Подклассы ─────────────────────────────────────────────────────────────────

/**
 * Плагин с getSettingsSchema() — постоянные настройки.
 * UI подхватывает через `is SourceInterface.Configurable`.
 */
open class LuaSourceAdapterConfigurable(
    context: Context,
    luaScript: LuaValue,
    luaEngine: LuaEngine,
    iconUrlFromYaml: String? = null,
    fileName: String?,
    private val schema: List<LuaSetting>
) : LuaSourceAdapter(context, luaScript, luaEngine, iconUrlFromYaml, fileName),
    SourceInterface.Configurable {

    @Composable
    override fun ScreenConfig() {
        LuaSettingsScreen(context, schema, luaScript)
    }
}

/**
 * Плагин с getFilterList() — фильтрация каталога.
 * UI подхватывает через `is SourceInterface.FilterableCatalog`.
 *
 * Ключевой принцип: getFilterList() вызывает Lua каждый раз.
 * Нет кэширования List<LuaFilter> в адаптере — только в ViewModel на время сессии.
 */
class LuaSourceAdapterFilterable(
    context: Context,
    luaScript: LuaValue,
    luaEngine: LuaEngine,
    iconUrlFromYaml: String? = null,
    fileName: String?
) : LuaSourceAdapter(context, luaScript, luaEngine, iconUrlFromYaml, fileName),
    SourceInterface.FilterableCatalog {

    override suspend fun getFilterList(): Response<List<LuaFilter>> =
        withContext(Dispatchers.IO) {
            try {
                val fn = luaScript.get("getFilterList")
                if (fn.isnil()) return@withContext Response.Success(emptyList())
                val result = fn.call()
                Response.Success(parseLuaFilterList(result))
            } catch (e: Exception) {
                Timber.e(e, "Lua getFilterList [$id]")
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getCatalogFiltered(
        index: Int,
        filters: ActiveFilters
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.IO) {
        try {
            val luaFilters = filters.toLuaTable(luaEngine)
            val result = luaScript.get("getCatalogFiltered").call(
                LuaValue.valueOf(index),
                luaFilters
            )
            convertLuaResultToPagedList(result)
        } catch (e: Exception) {
            Timber.e(e, "Lua getCatalogFiltered [$id]")
            Response.Error(e.message ?: "Unknown Lua error", e)
        }
    }
}

/**
 * Плагин с обоими: getSettingsSchema() + getFilterList().
 * Реализует и Configurable и FilterableCatalog.
 * Наследует от LuaSourceAdapterConfigurable (Settings), добавляет FilterableCatalog.
 */
class LuaSourceAdapterFull(
    context: Context,
    luaScript: LuaValue,
    luaEngine: LuaEngine,
    iconUrlFromYaml: String? = null,
    fileName: String?,
    schema: List<LuaSetting>
) : LuaSourceAdapterConfigurable(context, luaScript, luaEngine, iconUrlFromYaml, fileName, schema),
    SourceInterface.FilterableCatalog {

    override suspend fun getFilterList(): Response<List<LuaFilter>> =
        withContext(Dispatchers.IO) {
            try {
                val fn = luaScript.get("getFilterList")
                if (fn.isnil()) return@withContext Response.Success(emptyList())
                val result = fn.call()
                Response.Success(parseLuaFilterList(result))
            } catch (e: Exception) {
                Timber.e(e, "Lua getFilterList [$id]")
                Response.Error(e.message ?: "Unknown Lua error", e)
            }
        }

    override suspend fun getCatalogFiltered(
        index: Int,
        filters: ActiveFilters
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.IO) {
        try {
            val luaFilters = filters.toLuaTable(luaEngine)
            val result = luaScript.get("getCatalogFiltered").call(
                LuaValue.valueOf(index),
                luaFilters
            )
            convertLuaResultToPagedList(result)
        } catch (e: Exception) {
            Timber.e(e, "Lua getCatalogFiltered [$id]")
            Response.Error(e.message ?: "Unknown Lua error", e)
        }
    }
}