package my.noveldokusha.scraper

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.noveldokusha.core.ExtensionRepositoryInterface
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.postRequest
import my.noveldokusha.scraper.configs.SourceMetadata
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.luaj.vm2.*
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import org.yaml.snakeyaml.Yaml
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton


// =============================================================================
// LuaEngine
// =============================================================================

@Singleton
class LuaEngine @Inject constructor(
    private val networkClient: NetworkClient
) {
    // gson нужен ТОЛЬКО для json_parse/json_stringify внутри Lua-скриптов,
    // которые работают с JSON API (RanobeHub и т.д.)
    private val gson = Gson()

    suspend fun loadScript(luaCode: String): LuaValue = withContext(Dispatchers.IO) {
        val globals = JsePlatform.standardGlobals()
        registerApi(globals)
        globals.load(luaCode).call()
    }

    private fun registerApi(g: Globals) {
        g.set("http_get",          HttpGetFunction())
        g.set("http_post",         HttpPostFunction())
        g.set("html_parse",        HtmlParseFunction())
        g.set("html_select",       HtmlSelectFunction())
        g.set("url_encode",        UrlEncodeFunction())
        g.set("regex_match",       RegexMatchFunction())
        g.set("log_info",          LogInfoFunction())
        g.set("log_error",         LogErrorFunction())
        g.set("json_parse",        JsonParseFunction())
        g.set("json_stringify",    JsonStringifyFunction())
        g.set("detect_pagination", DetectPaginationFunction())
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private inner class HttpGetFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = runBlocking {
            safeHttp { networkClient.get(arg.checkjstring()) }
        }
    }

    private inner class HttpPostFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = runBlocking {
            safeHttp {
                val body = a2.checkjstring().toRequestBody("application/x-www-form-urlencoded".toMediaType())
                networkClient.call(postRequest(a1.checkjstring(), body = body))
            }
        }
    }

    private suspend fun safeHttp(block: suspend () -> okhttp3.Response): LuaValue = try {
        val r = block()
        val s = if (r.isSuccessful) r.body?.string() ?: "" else ""
        LuaTable().also { t -> t.set("success", LuaValue.valueOf(r.isSuccessful)); t.set("body", LuaValue.valueOf(s)) }
    } catch (e: Exception) {
        Timber.e(e, "HTTP failed")
        LuaTable().also { t -> t.set("success", LuaValue.FALSE); t.set("body", LuaValue.valueOf("")) }
    }

    // ── HTML ──────────────────────────────────────────────────────────────────

    private inner class HtmlParseFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            val doc = Jsoup.parse(arg.checkjstring())
            LuaTable().also { t ->
                t.set("text",  LuaValue.valueOf(doc.text()))
                t.set("html",  LuaValue.valueOf(doc.html()))
                t.set("title", LuaValue.valueOf(doc.title()))
                doc.body()?.let { t.set("body", elementToTable(it)) }
            }
        } catch (e: Exception) { Timber.e(e, "html_parse"); LuaValue.NIL }
    }

    private inner class HtmlSelectFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            val html = if (a1.istable()) a1.checktable().get("html").optjstring("") else a1.checkjstring()
            val elems = Jsoup.parse(html).select(a2.checkjstring())
            LuaTable().also { t -> elems.forEachIndexed { i, el -> t.set(i + 1, elementToTable(el)) } }
        } catch (e: Exception) { Timber.e(e, "html_select"); LuaTable() }
    }

    private fun elementToTable(el: Element): LuaTable = LuaTable().also { t ->
        t.set("text",  LuaValue.valueOf(el.text()))
        t.set("html",  LuaValue.valueOf(el.html()))
        t.set("href",  LuaValue.valueOf(el.attr("abs:href").ifEmpty { el.attr("href") }))
        t.set("src",   LuaValue.valueOf(el.attr("abs:src").ifEmpty  { el.attr("src")  }))
        t.set("title", LuaValue.valueOf(el.attr("title")))
        t.set("class", LuaValue.valueOf(el.attr("class")))
        t.set("id",    LuaValue.valueOf(el.attr("id")))
        t.set("attr",  object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue = try { LuaValue.valueOf(el.attr(a.checkjstring())) } catch (e: Exception) { LuaValue.NIL }
        })
        t.set("remove", object : ZeroArgFunction() {
            override fun call(): LuaValue { el.remove(); return LuaValue.NIL }
        })
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private inner class UrlEncodeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(java.net.URLEncoder.encode(arg.checkjstring(), "UTF-8"))
        } catch (e: Exception) { LuaValue.NIL }
    }

    private inner class RegexMatchFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            LuaTable().also { t ->
                Regex(a2.checkjstring()).findAll(a1.checkjstring())
                    .forEachIndexed { i, m -> t.set(i + 1, LuaValue.valueOf(m.value)) }
            }
        } catch (e: Exception) { LuaTable() }
    }

    private inner class LogInfoFunction  : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue { Timber.i("Lua: ${arg.optjstring("")}"); return LuaValue.NIL }
    }
    private inner class LogErrorFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue { Timber.e("Lua: ${arg.optjstring("")}"); return LuaValue.NIL }
    }

    // ── JSON (для Lua-скриптов с JSON API) ───────────────────────────────────

    private inner class JsonParseFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            convertToLua(gson.fromJson(arg.checkjstring(), Any::class.java))
        } catch (e: Exception) { Timber.e(e, "json_parse"); LuaValue.NIL }
    }

    private inner class JsonStringifyFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(gson.toJson(convertFromLua(arg)))
        } catch (e: Exception) { Timber.e(e, "json_stringify"); LuaValue.NIL }
    }

    private inner class DetectPaginationFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            val html = if (a1.istable()) a1.checktable().get("html").optjstring("") else a1.checkjstring()
            val next = Jsoup.parse(html).select("a[href]:contains(next), a[href]:contains(›), a[href]:contains(»)")
            LuaTable().also { t ->
                t.set("hasNext",  LuaValue.valueOf(next.isNotEmpty()))
                t.set("next_url", if (next.isNotEmpty()) LuaValue.valueOf(next.first().attr("abs:href")) else LuaValue.NIL)
            }
        } catch (e: Exception) { LuaValue.NIL }
    }

    // ── Java ↔ Lua ────────────────────────────────────────────────────────────

    fun convertToLua(obj: Any?): LuaValue = when (obj) {
        null        -> LuaValue.NIL
        is String   -> LuaValue.valueOf(obj)
        is Number   -> LuaValue.valueOf(obj.toDouble())
        is Boolean  -> LuaValue.valueOf(obj)
        is Map<*,*> -> LuaTable().also { t -> obj.forEach { (k,v) -> t.set(LuaValue.valueOf(k.toString()), convertToLua(v)) } }
        is List<*>  -> LuaTable().also { t -> obj.forEachIndexed { i,v -> t.set(i+1, convertToLua(v)) } }
        else        -> LuaValue.valueOf(obj.toString())
    }

    fun convertFromLua(v: LuaValue): Any? = when {
        v.isnil()     -> null
        v.isboolean() -> v.toboolean()
        v.isnumber()  -> v.todouble()
        v.isstring()  -> v.tojstring()
        v.istable()   -> {
            val t = v.checktable(); val keys = t.keys()
            if (keys.all { it.isnumber() && it.toint() > 0 })
                (1..t.length()).map { convertFromLua(t.get(it)) }
            else
                keys.associate { it.tojstring() to convertFromLua(t.get(it)) }
        }
        else -> v.tojstring()
    }
}


// =============================================================================
// LuaSourceLoader
// =============================================================================

@Singleton
class LuaSourceLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkClient: NetworkClient,
    private val luaEngine: LuaEngine,
    private val extensionRepository: ExtensionRepositoryInterface
) {
    // YAML — единственный формат конфигов: index.yaml, settings расширений
    private val yaml = Yaml()
    private val cache = ConcurrentHashMap<String, SourceInterface>()

    private val luaDir: File
        get() = File(context.filesDir, "lua_extensions").also { it.mkdirs() }

    fun clearCache() { cache.clear(); Timber.d("Lua source cache cleared") }

    /**
     * Загрузить все включённые установленные расширения.
     * Читает .lua с диска, при необходимости скачивает заново.
     */
    suspend fun loadAllSources(): Result<List<SourceInterface>> = withContext(Dispatchers.IO) {
        runCatching {
            val sources = loadInstalledSources()
            sources.forEach { cache[it.id] = it }
            Timber.d("Loaded ${sources.size} Lua sources")
            sources
        }
    }

    /**
     * Скачать .lua файл по URL и сохранить на диск.
     * Вызывается из ExtensionsManagerViewModel при установке.
     */
    suspend fun downloadAndCacheScript(id: String, codeUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = networkClient.get(codeUrl)
            if (!response.isSuccessful) { Timber.e("Download failed $id: HTTP ${response.code}"); return@withContext false }
            val code = response.body?.string() ?: run { Timber.e("Empty body for $id"); return@withContext false }
            luaFile(id).writeText(code, Charsets.UTF_8)
            cache.remove(id)
            Timber.d("Saved $id.lua")
            true
        } catch (e: Exception) {
            Timber.e(e, "downloadAndCacheScript failed for $id")
            false
        }
    }

    /** Удалить .lua с диска и из кэша */
    fun removeScript(id: String) { luaFile(id).delete(); cache.remove(id) }

    // ── Приватные ─────────────────────────────────────────────────────────────

    private suspend fun loadInstalledSources(): List<SourceInterface> {
        val enabled = try { extensionRepository.getEnabledExtensions() } catch (e: Exception) {
            Timber.e(e, "getEnabledExtensions failed"); return emptyList()
        }
        return enabled.mapNotNull { ext ->
            try {
                loadFromDisk(ext.id) ?: run {
                    val codeUrl = extractCodeUrl(ext)
                    if (codeUrl != null && downloadAndCacheScript(ext.id, codeUrl)) loadFromDisk(ext.id)
                    else { Timber.w("Cannot load ${ext.id}: no .lua file and no codeUrl"); null }
                }
            } catch (e: Exception) { Timber.e(e, "Failed to load ${ext.id}"); null }
        }
    }

    private suspend fun loadFromDisk(id: String): SourceInterface? {
        val file = luaFile(id)
        if (!file.exists()) return null
        return try {
            val script   = luaEngine.loadScript(file.readText(Charsets.UTF_8))
            val metadata = metadataFromScript(id, script)
            LuaSourceAdapter(script, metadata).also { cache[id] = it; Timber.d("Loaded from disk: $id") }
        } catch (e: Exception) { Timber.e(e, "Compile error for $id"); null }
    }

    /**
     * settings расширения — YAML строка:
     *   codeUrl: "https://raw.githubusercontent.com/..."
     */
    private suspend fun extractCodeUrl(ext: my.noveldokusha.core.Extension): String? {
        // settings хранится отдельно в БД, core.Extension его не содержит
        val raw = try {
            extensionRepository.getExtensionSettings(ext.id)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get settings for ${ext.id}")
            return null
        }
        if (raw.isNullOrBlank() || raw == "{}") return null
        return try {
            @Suppress("UNCHECKED_CAST")
            // yaml.load() вместо loadAs() — нет ambiguity
            val map = yaml.load<Any>(raw) as? Map<String, Any>
            map?.get("codeUrl")?.toString()
        } catch (e: Exception) {
            Timber.w(e, "Bad settings YAML for ${ext.id}: $raw")
            null
        }
    }

    private fun metadataFromScript(fallbackId: String, script: LuaValue): SourceMetadata {
        fun s(key: String, def: String = "") = try { script.get(key).optjstring(def) } catch (e: Exception) { def }
        return SourceMetadata(
            id          = s("id", fallbackId),
            name        = s("name"),
            version     = s("version", "1.0.0"),
            description = s("description"),
            url         = s("baseUrl"),
            icon        = s("icon"),
            language    = s("language", "en")
        )
    }

    private fun luaFile(id: String) = File(luaDir, "$id.lua")
}