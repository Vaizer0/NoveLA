package my.noveldokusha.network.interceptors

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.selects.select
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.domain.CloudfareVerificationBypassFailedException
import my.noveldokusha.core.domain.WebViewCookieManagerInitializationFailedException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import timber.log.Timber
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val ERROR_CODES = listOf(
    202,
    HttpsURLConnection.HTTP_FORBIDDEN,
    429,
    HttpsURLConnection.HTTP_BAD_GATEWAY,
    HttpsURLConnection.HTTP_UNAVAILABLE,
)

private const val TAG = "CloudflareInterceptor"
private const val MAX_MANUAL_ATTEMPTS = 3
private const val MAX_BYPASS_ATTEMPTS = 3
private val MAX_CHALLENGE_WAIT = 20.seconds
private val CHALLENGE_POLL_INTERVAL = 300.milliseconds
private val FORCE_NAVIGATION_DELAY = 5.seconds

private val SERVER_HEADER_VALUES = arrayOf(
    "cloudflare-nginx",
    "cloudflare",
    "cloudflare-iad",
    "ddos-guard",
    "ddos-guard.net",
)

private val CHALLENGE_HEADER_NAMES = arrayOf("cf-mitigated")

private val CHALLENGE_BODY_MARKERS = arrayOf(
    "cf-browser-verification",
    "cf-challenge-running",
    "/cdn-cgi/challenge-platform/",
    "cf-please-wait",
    "cf_chl_opt",
    "cf-mitigated",
    "Attention Required! | Cloudflare",
    "challenge-platform",
    "id=\"challenge-running\"",
    "id=\"cf-challenge-running\"",
    "id=\"turnstile-wrapper\"",
    "cf-turnstile",
    "challenges.cloudflare.com/turnstile",
    "ddos-guard.net",
    ".ddos-guard.net",
)

private val IP_BLOCKED_URL_MARKERS = arrayOf(
    "/cdn-cgi/error/",
    "error=1020",
    "error=1015",
)

private val TURNSTILE_CLICK_JS = """
    (function() {
        try {
            var frames = document.querySelectorAll('iframe[src*="challenges.cloudflare.com"], iframe[src*="cdn-cgi/challenge-platform"]');
            for (var i = 0; i < frames.length; i++) {
                var f = frames[i];
                var rect = f.getBoundingClientRect();
                if (rect.width < 20 || rect.height < 20) continue;
                var cx = rect.left + rect.width / 2;
                var cy = rect.top + rect.height / 2;
                var ev = new MouseEvent('click', {
                    bubbles: true, cancelable: true, view: window,
                    clientX: cx, clientY: cy
                });
                f.dispatchEvent(ev);
                var pe = new PointerEvent('pointerdown', {
                    bubbles: true, cancelable: true, view: window,
                    clientX: cx, clientY: cy, pointerId: 1
                });
                f.dispatchEvent(pe);
                return true;
            }
            var widget = document.querySelector('.cf-turnstile, [data-sitekey]');
            if (widget) {
                var r = widget.getBoundingClientRect();
                widget.dispatchEvent(new MouseEvent('click', {
                    bubbles: true, cancelable: true, view: window,
                    clientX: r.left + r.width / 2, clientY: r.top + r.height / 2
                }));
                return true;
            }
        } catch (e) { }
        return false;
    })();
""".trimIndent()

private val CLOUDFLARE_WHITELIST = listOf(
    "github.com",
    "raw.githubusercontent.com"
)

data class CfDomainOptions(
    val whitelist: Boolean = false,
    val ignoreMarkers: Set<String> = emptySet()
)

object LuaCfOptionsRegistry {
    private val options = ConcurrentHashMap<String, CfDomainOptions>()

    fun register(domain: String, cfOptions: CfDomainOptions) {
        val key = domain.removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").trimEnd('/')
        options[key] = cfOptions
        Timber.d( "CF options registered for $key: $cfOptions")
    }

    fun getForHost(host: String): CfDomainOptions? {
        val key = host.removePrefix("www.")
        return options[key]
    }

    fun clear(domain: String) {
        val key = domain.removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").trimEnd('/')
        options.remove(key)
    }
}

object CloudflareBypassSignal {
    val channel = Channel<Unit>(Channel.CONFLATED)

    private val _bypassCompleted = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val bypassCompleted: SharedFlow<String> = _bypassCompleted

    fun notifyBypassCompleted(host: String) {
        _bypassCompleted.tryEmit(host)
    }
}

internal class CloudFareVerificationInterceptor(
    @ApplicationContext private val appContext: Context,
    private val appPreferences: AppPreferences
) : Interceptor {

    private val lock = ReentrantLock()
    private val resolvedDomains = mutableSetOf<String>()
    private val manualAttempts = ConcurrentHashMap<String, Int>()

    private val LEGACY_CF_MARKERS = listOf(
        "cf-challenge",
        "turnstile",
        "requireTurnstile",
        "__cf_chl_",
        "but-captcha",
        "recaptcha-accessible-status"
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val bufferedRequest = if (originalRequest.body != null) {
            val buffer = Buffer()
            originalRequest.body!!.writeTo(buffer)
            val bodyBytes = buffer.readByteArray()
            val replayableBody = object : RequestBody() {
                override fun contentType() = originalRequest.body!!.contentType()
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: BufferedSink) { sink.write(bodyBytes) }
            }
            originalRequest.newBuilder()
                .method(originalRequest.method, replayableBody)
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(bufferedRequest)
        val bodyPreview = peekBodySafe(response)

        if (isNotCloudflare(response, bodyPreview)) {
            return response
        }

        Timber.d( "CF: Challenge detected. URL: ${bufferedRequest.url}")

        return lock.withLock {
            response.close()

            val siteUrl = bufferedRequest.url.toString()
            val host = bufferedRequest.url.host
            val cookieManager = CookieManager.getInstance()
                ?: throw WebViewCookieManagerInitializationFailedException()
            val userAgent = resolveUserAgent(appPreferences)

            val existingCookie = cookieManager.getCookie(siteUrl) ?: ""
            if (resolvedDomains.contains(host) || existingCookie.contains("cf_clearance")) {
                Timber.d( "CF: cf_clearance cached for $host, trying direct retry")
                val retryRequest = bufferedRequest.newBuilder()
                    .header("Cookie", formatCookies(existingCookie))
                    .header("User-Agent", userAgent)
                    .build()
                val retryResponse = chain.proceed(retryRequest)
                if (isNotCloudflare(retryResponse, peekBodySafe(retryResponse))) {
                    return@withLock retryResponse
                }
                retryResponse.close()
                resolvedDomains.remove(host)
                clearCookiesForDomain(siteUrl, cookieManager)
            }

            proceedWithBypass(chain, bufferedRequest, siteUrl, host, cookieManager, userAgent)
        }
    }

    private fun proceedWithBypass(
        chain: Interceptor.Chain,
        originalRequest: Request,
        siteUrl: String,
        host: String,
        cookieManager: CookieManager,
        userAgent: String
    ): Response {
        val referer = originalRequest.header("Referer")
        val webViewUrl = when {
            siteUrl.contains("/api/") && !referer.isNullOrEmpty() -> referer
            else -> siteUrl
        }

        var lastFailure: Exception? = null
        repeat(MAX_BYPASS_ATTEMPTS) { attempt ->
            try {
                runBlocking(Dispatchers.IO) {
                    resolveWithWebView(webViewUrl, cookieManager, userAgent, originalRequest)
                }

                val retryRequest = originalRequest.newBuilder()
                    .header("User-Agent", userAgent)
                    .build()
                val retryResponse = chain.proceed(retryRequest)
                if (isNotCloudflare(retryResponse, peekBodySafe(retryResponse))) {
                    resolvedDomains.add(host)
                    manualAttempts.remove(host)
                    CloudflareBypassSignal.notifyBypassCompleted(host)
                    return retryResponse
                }
                retryResponse.close()
                lastFailure = CloudfareVerificationBypassFailedException(
                    "Attempt ${attempt + 1}/$MAX_BYPASS_ATTEMPTS: " +
                        "still challenged after WebView bypass"
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastFailure = e
            }
        }

        clearCookiesForDomain(siteUrl, cookieManager)

        val manualAttemptsCount = manualAttempts.getOrDefault(host, 0)
        if (manualAttemptsCount >= MAX_MANUAL_ATTEMPTS) {
            Timber.e( "CF: Max manual attempts ($MAX_MANUAL_ATTEMPTS) reached for $host, giving up")
            manualAttempts.remove(host)
            throw lastFailure ?: CloudfareVerificationBypassFailedException()
        }
        manualAttempts[host] = manualAttemptsCount + 1
        Timber.d( "CF: Manual attempt ${manualAttemptsCount + 1}/$MAX_MANUAL_ATTEMPTS for $host, webViewUrl=$webViewUrl")

        runBlocking(Dispatchers.IO) {
            resolveWithWebViewManual(webViewUrl, siteUrl, cookieManager)
        }

        cookieManager.flush()
        val finalCookies = cookieManager.getCookie(siteUrl) ?: ""

        val finalRetryRequest = originalRequest.newBuilder()
            .header("User-Agent", userAgent)
            .build()

        val finalResponse = chain.proceed(finalRetryRequest)

        if (!isNotCloudflare(finalResponse, peekBodySafe(finalResponse))) {
            finalResponse.close()
            throw lastFailure ?: CloudfareVerificationBypassFailedException()
        }

        resolvedDomains.add(host)
        manualAttempts.remove(host)
        CloudflareBypassSignal.notifyBypassCompleted(host)
        return finalResponse
    }

    private val STATIC_EXTENSIONS = setOf("js", "css", "png", "jpg", "svg", "woff", "woff2", "ttf", "ico", "webp", "json", "txt", "lua")

    private fun isNotCloudflare(response: Response, body: String): Boolean {
        val host = response.request.url.host

        val pathExt = response.request.url.pathSegments.lastOrNull()
            ?.substringAfterLast('.', "")?.lowercase()
        if (pathExt != null && pathExt in STATIC_EXTENSIONS) return true

        if (CLOUDFLARE_WHITELIST.any { host.contains(it) }) return true

        val domainOptions = LuaCfOptionsRegistry.getForHost(host)
        if (domainOptions?.whitelist == true) return true

        val ignoredMarkers = domainOptions?.ignoreMarkers ?: emptySet()

        val challengeInfo = classifyResponse(response, body, ignoredMarkers)
        return !challengeInfo.isCloudflare
    }

    private fun classifyResponse(
        response: Response,
        body: String,
        ignoredMarkers: Set<String>
    ): ChallengeInfo {
        val code = response.code

        for (headerName in CHALLENGE_HEADER_NAMES) {
            if (response.header(headerName) != null) {
                return ChallengeInfo(isCloudflare = true)
            }
        }

        val serverHeader = response.header("Server")
        val serverLooksLikeCloudflare = serverHeader != null &&
            SERVER_HEADER_VALUES.any { serverHeader.equals(it, ignoreCase = true) }

        if (code in ERROR_CODES && serverLooksLikeCloudflare) {
            return ChallengeInfo(isCloudflare = true)
        }

        return ChallengeInfo(
            isCloudflare = bodyLooksLikeChallenge(body, ignoredMarkers)
        )
    }

    private fun bodyLooksLikeChallenge(body: String, ignoredMarkers: Set<String>): Boolean {
        if (body.isBlank()) return false

        for (marker in CHALLENGE_BODY_MARKERS) {
            if (marker in ignoredMarkers) continue
            if (body.contains(marker, ignoreCase = true)) return true
        }
        for (marker in LEGACY_CF_MARKERS) {
            if (marker in ignoredMarkers) continue
            if (body.contains(marker, ignoreCase = true)) return true
        }
        return false
    }

    private fun clearCookiesForDomain(url: String, cm: CookieManager) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val host = httpUrl.host

        cm.setCookie(url, "cf_clearance=; Max-Age=0; Path=/")

        val parts = host.split('.')
        for (i in 0 until parts.size - 1) {
            val domain = parts.subList(i, parts.size).joinToString(".")
            if (domain.contains('.')) {
                cm.setCookie("${httpUrl.scheme}://$domain", "cf_clearance=; Max-Age=0; Domain=.$domain; Path=/")
                cm.setCookie("${httpUrl.scheme}://$domain", "cf_clearance=; Max-Age=0; Domain=$domain; Path=/")
            }
        }
        cm.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        webViewUrl: String,
        cookieManager: CookieManager,
        userAgent: String,
        originalRequest: Request
    ) = withContext(Dispatchers.Default) {
        val headersMap = mutableMapOf<String, String>()
        originalRequest.header("Accept")?.let { headersMap["Accept"] = it }
        originalRequest.header("Accept-Language")?.let { headersMap["Accept-Language"] = it }
        originalRequest.header("Accept-Encoding")?.let { headersMap["Accept-Encoding"] = it }
        originalRequest.header("Referer")?.let { headersMap["Referer"] = it }
        originalRequest.header("Sec-CH-UA")?.let { headersMap["Sec-CH-UA"] = it }
        originalRequest.header("Sec-CH-UA-Mobile")?.let { headersMap["Sec-CH-UA-Mobile"] = it }
        originalRequest.header("Sec-CH-UA-Platform")?.let { headersMap["Sec-CH-UA-Platform"] = it }
        originalRequest.header("Sec-Fetch-Dest")?.let { headersMap["Sec-Fetch-Dest"] = it }
        originalRequest.header("Sec-Fetch-Mode")?.let { headersMap["Sec-Fetch-Mode"] = it }
        originalRequest.header("Sec-Fetch-Site")?.let { headersMap["Sec-Fetch-Site"] = it }

        cookieManager.setAcceptCookie(true)

        withContext(Dispatchers.Main) {
            val webView = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mediaPlaybackRequiresUserGesture = true
                settings.blockNetworkImage = true
                settings.setSupportZoom(false)
                settings.userAgentString = userAgent
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {}
            }

            try {
                if (cookieManager.getCookie(webViewUrl)?.contains("cf_clearance") == true) {
                    Timber.d( "CF: cf_clearance already present, skipping WebView load")
                    return@withContext
                }

                webView.loadUrl(webViewUrl, headersMap)

                val deadline = System.currentTimeMillis() +
                    MAX_CHALLENGE_WAIT.inWholeMilliseconds
                var cfClearanceAt: Long? = null
                var lastClickAttempt = 0L

                while (System.currentTimeMillis() < deadline) {
                    delay(CHALLENGE_POLL_INTERVAL)

                    val currentUrl = runCatching { webView.url }.getOrNull()
                    if (currentUrl != null && IP_BLOCKED_URL_MARKERS.any {
                            currentUrl.contains(it, ignoreCase = true)
                        }) {
                        throw CloudfareVerificationBypassFailedException(
                            "IP blocked by Cloudflare (error 1020/1015). " +
                                "Try a different network."
                        )
                    }

                    val currentCookies = cookieManager.getCookie(webViewUrl) ?: ""
                    if (currentCookies.contains("cf_clearance")) {
                        if (cfClearanceAt == null) {
                            cfClearanceAt = System.currentTimeMillis()
                        }
                        delay(500)
                        val urlAfterCookie = runCatching { webView.url }.getOrNull()
                        if (urlAfterCookie == null ||
                            !urlAfterCookie.contains("challenge", ignoreCase = true) &&
                            !urlAfterCookie.contains("__cf_chl", ignoreCase = true) &&
                            !urlAfterCookie.contains("cdn-cgi/challenge-platform", ignoreCase = true)
                        ) {
                            break
                        }
                    }

                    if (cfClearanceAt != null &&
                        System.currentTimeMillis() - cfClearanceAt!! >=
                        FORCE_NAVIGATION_DELAY.inWholeMilliseconds
                    ) {
                        runCatching {
                            webView.loadUrl(webViewUrl, headersMap)
                        }
                        delay(1_000)
                        break
                    }

                    if (currentUrl != null && currentUrl != webViewUrl &&
                        !currentUrl.contains("challenge", ignoreCase = true) &&
                        !currentUrl.contains("__cf_chl", ignoreCase = true) &&
                        !currentUrl.contains("cdn-cgi/challenge-platform", ignoreCase = true) &&
                        IP_BLOCKED_URL_MARKERS.none {
                            currentUrl.contains(it, ignoreCase = true)
                        }
                    ) {
                        delay(300)
                        break
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastClickAttempt >= 3_000) {
                        lastClickAttempt = now
                        runCatching {
                            webView.evaluateJavascript(TURNSTILE_CLICK_JS, null)
                        }
                    }
                }
            } finally {
                runCatching { webView.stopLoading() }
                runCatching { webView.webViewClient = WebViewClient() }
                runCatching { webView.removeAllViews() }
                runCatching { webView.destroy() }
            }
        }
    }

    private suspend fun resolveWithWebViewManual(
        webViewUrl: String,
        siteUrl: String,
        cm: CookieManager
    ) {
        while (CloudflareBypassSignal.channel.tryReceive().isSuccess) {}

        val oldCfClearance = extractCfClearance(cm.getCookie(siteUrl))

        withContext(Dispatchers.Main) {
            val intent = Intent().apply {
                setClassName(appContext, "my.noveldokusha.webview.WebViewActivity")
                putExtra("url", webViewUrl)
                putExtra("isBypassMode", true)
                putExtra("oldCfClearance", oldCfClearance)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            appContext.startActivity(intent)
        }

        withTimeoutOrNull(180.seconds) {
            coroutineScope {
                val signalJob = launch { CloudflareBypassSignal.channel.receive() }
                val cookieJob = launch {
                    while (isActive) {
                        delay(1500)
                        cm.flush()
                        if (cm.getCookie(siteUrl)?.contains("cf_clearance") == true) break
                    }
                }
                select<Unit> {
                    signalJob.onJoin {}
                    cookieJob.onJoin {}
                }
                signalJob.cancel()
                cookieJob.cancel()
            }
        }
    }

    private fun extractCfClearance(cookies: String?): String {
        if (cookies.isNullOrEmpty()) return ""
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("cf_clearance=") }
            ?.removePrefix("cf_clearance=")
            ?: ""
    }

    private fun formatCookies(cookies: String?): String {
        if (cookies.isNullOrEmpty()) return ""
        return cookies.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ")
    }

    private fun peekBodySafe(response: Response): String {
        return try { response.peekBody(65536).string() } catch (e: Exception) { "" }
    }

    private data class ChallengeInfo(
        val isCloudflare: Boolean,
    )
}
