package my.noveldokusha.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.postRequest
import my.noveldokusha.scraper.configs.LanguageIndex
import my.noveldokusha.scraper.configs.RepositoryIndex
import my.noveldokusha.scraper.configs.SourceMetadata
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.luaj.vm2.*
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import org.yaml.snakeyaml.Yaml
import timber.log.Timber
import java.io.IOException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri

/**
 * Lua движок для выполнения Lua скриптов источников
 */
@Singleton
class LuaEngine @Inject constructor(
    private val networkClient: NetworkClient
) {

    init {
        // Регистрируем API функции для Lua скриптов
        //registerLuaApi()
    }

    /**
     * Загрузка и выполнение Lua скрипта
     */
    suspend fun loadScript(luaCode: String): LuaValue = withContext(Dispatchers.IO) {
        try {
            // Создаем отдельный Globals для каждого вызова (thread-safe)
            val globals = JsePlatform.standardGlobals()
            registerLuaApi(globals)
            val chunk = globals.load(luaCode)
            chunk.call()
        } catch (e: Exception) {
            Timber.e(e, "Failed to load Lua script")
            throw e
        }
    }

    /**
     * Регистрация API функций для Lua
     */
    private fun registerLuaApi() {
        // Этот метод больше не нужен, т.к. регистрируем для каждого globals отдельно
    }

    /**
     * Регистрация API функций для конкретного Globals
     */
    private fun registerLuaApi(globals: Globals) {
        // HTTP функции
        globals.set("http_get", HttpGetFunction())
        globals.set("http_post", HttpPostFunction())
        
        // HTML функции
        globals.set("html_parse", HtmlParseFunction())
        globals.set("html_select", HtmlSelectFunction())
        
        // Утилиты
        globals.set("url_encode", UrlEncodeFunction())
        globals.set("regex_match", RegexMatchFunction())
        globals.set("log_info", LogInfoFunction())
        globals.set("log_error", LogErrorFunction())
    }

    /**
     * HTTP GET для Lua
     */
    private inner class HttpGetFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            return runBlocking {
                try {
                    val url = arg.checkjstring()
                    val response = networkClient.get(url)
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        LuaValue.valueOf(body)
                    } else {
                        LuaValue.NIL
                    }
                } catch (e: Exception) {
                    Timber.e(e, "HTTP GET failed")
                    LuaValue.NIL
                }
            }
        }
    }

    /**
     * HTTP POST для Lua
     */
    private inner class HttpPostFunction : TwoArgFunction() {
        override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
            return runBlocking {
                try {
                    val url = arg1.checkjstring()
                    val data = arg2.checkjstring()
                    val requestBody = data.toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    val response = networkClient.call(postRequest(url, body = requestBody))
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        LuaValue.valueOf(body)
                    } else {
                        LuaValue.NIL
                    }
                } catch (e: Exception) {
                    Timber.e(e, "HTTP POST failed")
                    LuaValue.NIL
                }
            }
        }
    }

    /**
     * HTML парсинг для Lua
     */
    private inner class HtmlParseFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            return try {
                val html = arg.checkjstring()
                val doc = Jsoup.parse(html)
                // Создаем Lua таблицу с базовыми методами
                val luaTable = LuaValue.tableOf()
                luaTable.set("text", LuaValue.valueOf(doc.text()))
                luaTable.set("title", LuaValue.valueOf(doc.title()))
                luaTable.set("html", LuaValue.valueOf(doc.html()))
                luaTable
            } catch (e: Exception) {
                Timber.e(e, "HTML parse failed")
                LuaValue.NIL
            }
        }
    }

    /**
     * HTML select для Lua
     */
    private inner class HtmlSelectFunction : TwoArgFunction() {
        override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
            return try {
                val html = arg1.checkjstring()
                val selector = arg2.checkjstring()
                val doc = Jsoup.parse(html)
                val elements = doc.select(selector)
                val results = LuaValue.tableOf()
                elements.forEachIndexed { index, element ->
                    val elementTable = LuaValue.tableOf()
                    elementTable.set("text", LuaValue.valueOf(element.text()))
                    elementTable.set("html", LuaValue.valueOf(element.html()))
                    elementTable.set("attr", HtmlAttrFunction(element))
                    results.set(LuaValue.valueOf(index + 1), elementTable)
                }
                results
            } catch (e: Exception) {
                Timber.e(e, "HTML select failed")
                LuaValue.NIL
            }
        }
    }

    /**
     * HTML attr для Lua
     */
    private inner class HtmlAttrFunction(private val element: Element) : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            return try {
                val attrName = arg.checkjstring()
                LuaValue.valueOf(element.attr(attrName))
            } catch (e: Exception) {
                Timber.e(e, "HTML attr failed")
                LuaValue.NIL
            }
        }
    }

    /**
     * Regex для Lua
     */
    private inner class RegexMatchFunction : TwoArgFunction() {
        override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
            return try {
                val text = arg1.checkjstring()
                val pattern = arg2.checkjstring()
                val regex = pattern.toRegex()
                val matches = regex.findAll(text).map { it.value }.toList()
                val table = LuaValue.tableOf()
                matches.forEachIndexed { index, match ->
                    table.set(LuaValue.valueOf(index + 1), LuaValue.valueOf(match))
                }
                table
            } catch (e: Exception) {
                Timber.e(e, "Regex match failed")
                LuaValue.NIL
            }
        }
    }

    /**
     * URL encode для Lua
     */
    private inner class UrlEncodeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            return try {
                val url = arg.checkjstring()
                val encoded = java.net.URLEncoder.encode(url, "UTF-8")
                LuaValue.valueOf(encoded)
            } catch (e: Exception) {
                Timber.e(e, "URL encode failed")
                LuaValue.NIL
            }
        }
    }

    /**
     * Логирование info для Lua
     */
    private inner class LogInfoFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val message = arg.checkjstring()
            Timber.i("Lua: $message")
            return LuaValue.NIL
        }
    }

    /**
     * Логирование error для Lua
     */
    private inner class LogErrorFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val message = arg.checkjstring()
            Timber.e("Lua Error: $message")
            return LuaValue.NIL
        }
    }
}

/**
 * Загрузчик Lua источников из внешнего репозитория
 */
@Singleton
class LuaSourceLoader @Inject constructor(
    private val networkClient: NetworkClient,
    private val luaEngine: LuaEngine
) {

    private val yaml = Yaml()
    private val cache = ConcurrentHashMap<String, SourceInterface>()
    private var lastUpdateTime = 0L
    private val cacheTimeout = 5 * 60 * 1000L // 5 минут

    /**
     * Загрузка всех источников из репозитория
     */
    suspend fun loadAllSources(repositoryUrl: String = DEFAULT_REPOSITORY_URL): Result<List<SourceInterface>> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Loading sources from repository: $repositoryUrl")
                
                // Загрузка главного индекса
                val repositoryIndex = loadRepositoryIndex(repositoryUrl)
                val allSources = mutableListOf<SourceInterface>()
                
                // Загрузка источников для каждого языка
                val languages = repositoryIndex["languages"] as Map<String, Map<String, Any>>
                languages.forEach { (langCode, langInfo) ->
                    try {
                        val languageSources = loadLanguageSources(langInfo["url"] as String, langCode)
                        allSources.addAll(languageSources)
                        Timber.d("Loaded ${languageSources.size} sources for language $langCode")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load sources for language $langCode")
                    }
                }
                
                Timber.d("Total loaded sources: ${allSources.size}")
                Result.success(allSources)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load sources from repository")
                Result.failure(e)
            }
        }
    }

    /**
     * Загрузка индекса репозитория
     */
    private suspend fun loadRepositoryIndex(url: String): Map<String, Map<String, Any>> {
        val response = networkClient.get(url)
        if (!response.isSuccessful) {
            throw IOException("Failed to load repository index: ${response.code}")
        }
        
        val yamlContent = response.body?.string()
            ?: throw IOException("Empty response from repository")
        
        return yaml.loadAs(yamlContent, Map::class.java) as Map<String, Map<String, Any>>
    }

    /**
     * Загрузка источников для конкретного языка
     */
    private suspend fun loadLanguageSources(languageUrl: String, languageCode: String): List<SourceInterface> {
        val response = networkClient.get(languageUrl)
        if (!response.isSuccessful) {
            throw IOException("Failed to load language index: ${response.code}")
        }
        
        val yamlContent = response.body?.string()
            ?: throw IOException("Empty response from language index")
        
        val languageData = yaml.loadAs(yamlContent, Map::class.java) as Map<String, Any>
        val sources = languageData["sources"] as List<Map<String, Any>>
        
        return sources.mapNotNull { source ->
            try {
                createSourceFromMap(source, languageData)
            } catch (e: Exception) {
                Timber.w(e, "Failed to create source from map")
                null
            }
        }
    }
    
    /**
     * Создание источника из Map данных
     */
    private fun createSourceFromMap(source: Map<String, Any>, languageData: Map<String, Any>): SourceInterface? {
        val id = source["id"] as String
        val name = source["name"] as String
        val baseUrl = source["url"] as String
        val iconUrl = source["icon"] as String
        
        return try {
            // Получаем язык из источника (если есть) или из данных языка
            val sourceLanguageCode = source["language"] as String? ?: languageData["language"] as String
            val lang = parseLanguageCode(sourceLanguageCode)
            
            // Создаем простой источник на основе данных
            object : SourceInterface.Catalog {
                override val id = id
                override val nameStrId: Int = 0 // Для динамических источников нет строковых ресурсов
                override val baseUrl = baseUrl
                override val catalogUrl = baseUrl
                override val iconUrl = iconUrl
                override val language: LanguageCode = lang
                
                override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
                    Response.Success(null)
                
                override suspend fun getBookDescription(bookUrl: String): Response<String?> = 
                    Response.Success(null)
                
                override suspend fun getBookTitle(bookUrl: String): Response<String?> = 
                    Response.Success(null)
                
                override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
                    Response.Success(emptyList())
                
                override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
                    Response.Success(PagedList(emptyList(), index = index, isLastPage = true))
                
                override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> =
                    Response.Success(PagedList(emptyList(), index = index, isLastPage = true))
                
                override suspend fun getChapterListHash(bookUrl: String): Response<String?> = 
                    Response.Success(null)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create source for $name")
            null
        }
    }
    
    /**
     * Преобразование languageCode в LanguageCode
     * Создает маппинг на основе поддерживаемых языков в системе
     */
    private fun parseLanguageCode(languageCode: String): LanguageCode {
        // Создаем маппинг из enum LanguageCode
        val languageMap = LanguageCode.values().associateBy { it.iso639_1.lowercase() }
        return languageMap[languageCode.lowercase()] ?: LanguageCode.ENGLISH
    }

    /**
     * Загрузка отдельного Lua источника
     */
    private suspend fun loadLuaSource(metadata: SourceMetadata): SourceInterface? {
        return withContext(Dispatchers.IO) {
            try {
                // Валидация URL
                val url = URL(metadata.url)
                if (url.protocol !in listOf("http", "https")) {
                    throw SecurityException("Unsupported protocol: ${url.protocol}")
                }
                
                // Загрузка Lua скрипта
                val response = networkClient.get(metadata.url)
                if (!response.isSuccessful) {
                    throw IOException("Failed to load Lua script: ${response.code}")
                }
                
                val luaCode = response.body?.string()
                    ?: throw IOException("Empty Lua script")
                
                // Компиляция Lua скрипта
                val luaScript = luaEngine.loadScript(luaCode)
                
                // Создание адаптера
                LuaSourceAdapter(luaScript, metadata)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load Lua source: ${metadata.id}")
                null
            }
        }
    }

    /**
     * Получение источника по ID из кэша
     */
    fun getSourceById(id: String): SourceInterface? {
        return cache[id]
    }

    /**
     * Очистка кэша
     */
    fun clearCache() {
        cache.clear()
        Timber.d("Lua sources cache cleared")
    }

    /**
     * Получение всех закэшированных источников
     */
    fun getCachedSources(): List<SourceInterface> {
        return cache.values.toList()
    }

    companion object {
        private const val DEFAULT_REPOSITORY_URL = 
            "https://raw.githubusercontent.com/HnDK0/external-sources/main/index.yaml"
    }
}
