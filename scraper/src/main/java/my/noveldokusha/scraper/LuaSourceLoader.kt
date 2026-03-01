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
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.luaj.vm2.*
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
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
    @ApplicationContext private val context: Context,
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

    suspend fun loadFromScript(scriptContent: String, iconUrl: String? = null): SourceInterface.Catalog {
        val globals = JsePlatform.standardGlobals()
        registerApi(globals)
        val chunk = globals.load(scriptContent)
        val result = chunk.call()
        return LuaSourceAdapter(context, result, this, iconUrl)
    }

    private fun registerApi(g: Globals) {
        g.set("http_get",          HttpGetFunction() as LuaValue)
        g.set("http_post",         HttpPostFunction() as LuaValue)
        g.set("get_cookies",       GetCookiesFunction() as LuaValue)
        g.set("set_cookies",       SetCookiesFunction() as LuaValue)
        g.set("get_preference",    GetPreferenceFunction() as LuaValue)
        g.set("set_preference",    SetPreferenceFunction() as LuaValue)
        g.set("aes_decrypt",       AesDecryptFunction() as LuaValue)
        g.set("html_parse",        HtmlParseFunction() as LuaValue)
        g.set("html_select",       HtmlSelectFunction() as LuaValue)
        g.set("html_text",         HtmlTextFunction() as LuaValue)
        g.set("url_encode",        UrlEncodeFunction() as LuaValue)
        g.set("url_resolve",       UrlResolveFunction() as LuaValue)
        g.set("unescape_unicode",  UnescapeUnicodeFunction() as LuaValue)
        g.set("regex_match",       RegexMatchFunction() as LuaValue)
        g.set("regex_replace",     RegexReplaceFunction() as LuaValue)
        g.set("string_normalize",  StringNormalizeFunction() as LuaValue)
        g.set("base64_decode",     Base64DecodeFunction() as LuaValue)
        g.set("log_info",          LogInfoFunction() as LuaValue)
        g.set("log_error",         LogErrorFunction() as LuaValue)
        g.set("json_parse",        JsonParseFunction() as LuaValue)
        g.set("json_stringify",    JsonStringifyFunction() as LuaValue)
        g.set("detect_pagination", DetectPaginationFunction() as LuaValue)
        g.set("google_translate",  GoogleTranslateFunction() as LuaValue)
    }

    private inner class GoogleTranslateFunction : ThreeArgFunction() {
        override fun call(text: LuaValue, sourceLang: LuaValue, targetLang: LuaValue): LuaValue {
            val t = text.tojstring()
            val sl = sourceLang.tojstring()
            val tl = targetLang.tojstring()
            
            val apiKey = String(android.util.Base64.decode("QUl6YVN5QVRCWGFqdnpRTFRESEVRYmNwcTBJaGUwdldESG1PNTIw", android.util.Base64.DEFAULT)).trim()
            val url = "https://translate-pa.googleapis.com/v1/translateHtml"
            
            val payload = listOf(listOf(t, sl, tl), "wt_lib")
            val requestBody = gson.toJson(payload).toRequestBody("application/json+protobuf".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .addHeader("X-Goog-Api-Key", apiKey)
                .post(requestBody)
                .build()

            return try {
                networkClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use LuaValue.valueOf(t)
                    val body = response.body?.string() ?: return@use LuaValue.valueOf(t)
                    val arr = JsonParser.parseString(body).asJsonArray
                    LuaValue.valueOf(arr.get(0).asJsonArray.get(0).asString)
                }
            } catch (e: Exception) {
                Timber.e(e, "LuaEngine: Google Translate failed")
                LuaValue.valueOf(t)
            }
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private inner class HttpGetFunction : ThreeArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue, a3: LuaValue): LuaValue = runBlocking {
            val url = a1.checkjstring()
            val config = if (a2.istable()) a2.checktable() else LuaTable()
            
            val headersMap = convertHeaders(config.get("headers").opttable(LuaTable()))
            val charset = config.get("charset").optjstring(if (a3.isstring()) a3.tojstring() else "UTF-8")
            
            try {
                networkClient.getWithHeaders(url, headersMap).use { r ->
                    val bytes = r.body?.bytes() ?: byteArrayOf()
                    val s = String(bytes, java.nio.charset.Charset.forName(charset))
                    LuaTable().also { t ->
                        t.set("success", LuaValue.valueOf(r.isSuccessful))
                        t.set("body", LuaValue.valueOf(s))
                        t.set("code", LuaValue.valueOf(r.code))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "HTTP GET failed")
                LuaTable().also { t ->
                    t.set("success", LuaValue.FALSE)
                    t.set("body", LuaValue.valueOf(e.message ?: "Unknown HTTP error"))
                    t.set("code", LuaValue.valueOf(-1))
                }
            }
        }
    }

    private inner class HttpPostFunction : ThreeArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue, a3: LuaValue): LuaValue = runBlocking {
            val url = a1.checkjstring()
            val bodyStr = a2.checkjstring()
            val config = if (a3.istable()) a3.checktable() else LuaTable()
            
            val headersMap = convertHeaders(config.get("headers").opttable(LuaTable()))
            val charset = config.get("charset").optjstring("UTF-8")
            
            try {
                val mediaType = (headersMap["Content-Type"] ?: "application/x-www-form-urlencoded").toMediaType()
                val body = bodyStr.toRequestBody(mediaType)
                networkClient.call(postRequest(url, body = body, headers = headersMap.toHeaders())).use { r ->
                    val bytes = r.body?.bytes() ?: byteArrayOf()
                    val s = String(bytes, java.nio.charset.Charset.forName(charset))
                    LuaTable().also { t ->
                        t.set("success", LuaValue.valueOf(r.isSuccessful))
                        t.set("body", LuaValue.valueOf(s))
                        t.set("code", LuaValue.valueOf(r.code))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "HTTP POST failed")
                LuaTable().also { t ->
                    t.set("success", LuaValue.FALSE)
                    t.set("body", LuaValue.valueOf(e.message ?: "Unknown HTTP error"))
                    t.set("code", LuaValue.valueOf(-1))
                }
            }
        }
    }

    private fun convertHeaders(table: LuaTable): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val keys = table.keys()
        for (key in keys) {
            headers[key.tojstring()] = table.get(key).tojstring()
        }
        return headers
    }

    private inner class GetPreferenceFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val key = arg.checkjstring()
            val prefs = context.getSharedPreferences("lua_preferences", Context.MODE_PRIVATE)
            return LuaValue.valueOf(prefs.getString(key, "") ?: "")
        }
    }

    private inner class SetPreferenceFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue {
            val key = a1.checkjstring()
            val value = a2.tojstring()
            val prefs = context.getSharedPreferences("lua_preferences", Context.MODE_PRIVATE)
            prefs.edit().putString(key, value).apply()
            return LuaValue.NIL
        }
    }

    private inner class GetCookiesFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val url = arg.checkjstring()
            val httpUrl = url.toHttpUrl()
            val cookies = networkClient.cookieJar.loadForRequest(httpUrl)
            val table = LuaTable()
            cookies.forEach { cookie: okhttp3.Cookie ->
                table.set(cookie.name, cookie.value)
            }
            return table
        }
    }

    private inner class SetCookiesFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue {
            val url = a1.checkjstring()
            val cookiesTable = a2.checktable()
            val httpUrl = url.toHttpUrl()
            val cookies = mutableListOf<okhttp3.Cookie>()
            val keys = cookiesTable.keys()
            for (key in keys) {
                cookies.add(okhttp3.Cookie.Builder()
                    .domain(httpUrl.host)
                    .name(key.tojstring())
                    .value(cookiesTable.get(key).tojstring())
                    .build())
            }
            networkClient.cookieJar.saveFromResponse(httpUrl, cookies)
            return LuaValue.NIL
        }
    }

    private suspend fun safeHttp(block: suspend () -> okhttp3.Response): LuaValue = try {
        block().use { r ->
            val s = if (r.isSuccessful) r.body?.string() ?: "" else ""
            LuaTable().also { t ->
                t.set("success", LuaValue.valueOf(r.isSuccessful))
                t.set("body", LuaValue.valueOf(s))
                t.set("code", LuaValue.valueOf(r.code))
            }
        }
    } catch (e: Exception) {
        Timber.e(e, "HTTP failed")
        LuaTable().also { t ->
            t.set("success", LuaValue.FALSE)
            t.set("body", LuaValue.valueOf(e.message ?: "Unknown HTTP error"))
            t.set("code", LuaValue.valueOf(-1))
        }
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

        // Позволяем обращаться к полям как к методам для совместимости с плагинами
        t.set("get_text", object : ZeroArgFunction() { override fun call(): LuaValue = LuaValue.valueOf(el.text()) })
        t.set("get_html", object : ZeroArgFunction() { override fun call(): LuaValue = LuaValue.valueOf(el.html()) })

        t.set("attr",  object : OneArgFunction() {
            override fun call(a: LuaValue): LuaValue = try { LuaValue.valueOf(el.attr(a.checkjstring())) } catch (e: Exception) { LuaValue.NIL }
        })
        t.set("remove", object : ZeroArgFunction() {
            override fun call(): LuaValue { el.remove(); return LuaValue.NIL }
        })
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private inner class HtmlTextFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            // Если передана таблица элемента (результат html_select)
            val html = if (arg.istable()) arg.checktable().get("html").optjstring("") else arg.checkjstring()
            val doc = Jsoup.parse(html)
            LuaValue.valueOf(TextExtractor.get(doc.body()))
        } catch (e: Exception) { Timber.e(e, "html_text"); LuaValue.NIL }
    }

    private inner class RegexReplaceFunction : ThreeArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue, a3: LuaValue): LuaValue = try {
            val text = a1.checkjstring()
            val pattern = a2.checkjstring()
            val replacement = a3.checkjstring()
            LuaValue.valueOf(text.replace(Regex(pattern), replacement))
        } catch (e: Exception) { a1 }
    }

    private inner class StringNormalizeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            LuaValue.valueOf(java.text.Normalizer.normalize(arg.checkjstring(), java.text.Normalizer.Form.NFKC))
        } catch (e: Exception) { arg }
    }

    private inner class AesDecryptFunction : ThreeArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue, a3: LuaValue): LuaValue = try {
            val data = a1.checkjstring()
            val key = a2.checkjstring()
            val iv = a3.checkjstring()

            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = javax.crypto.spec.SecretKeySpec(key.toByteArray(), "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(iv.toByteArray())
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decodedData = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            val decrypted = cipher.doFinal(decodedData)
            LuaValue.valueOf(String(decrypted, Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.e(e, "aes_decrypt failed")
            LuaValue.NIL
        }
    }

    private inner class Base64DecodeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            val decoded = android.util.Base64.decode(arg.checkjstring(), android.util.Base64.DEFAULT)
            LuaValue.valueOf(String(decoded, Charsets.UTF_8))
        } catch (e: Exception) { LuaValue.NIL }
    }
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

    private inner class UrlResolveFunction : TwoArgFunction() {
        override fun call(a1: LuaValue, a2: LuaValue): LuaValue = try {
            val base = a1.checkjstring()
            val relative = a2.checkjstring()
            LuaValue.valueOf(java.net.URI(base).resolve(relative).toString())
        } catch (e: Exception) { a2 }
    }

    private inner class UnescapeUnicodeFunction : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue = try {
            val s = arg.checkjstring()
            val regex = Regex("\\\\u([0-9a-fA-F]{4})")
            LuaValue.valueOf(regex.replace(s) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            })
        } catch (e: Exception) { arg }
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