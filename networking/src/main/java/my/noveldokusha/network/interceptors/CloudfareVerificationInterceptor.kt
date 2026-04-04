package my.noveldokusha.network.interceptors

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
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
import my.noveldokusha.core.domain.CloudfareVerificationBypassFailedException
import my.noveldokusha.core.domain.WebViewCookieManagerInitializationFailedException
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

private val ERROR_CODES = listOf(HttpsURLConnection.HTTP_FORBIDDEN, HttpsURLConnection.HTTP_UNAVAILABLE)
private const val TAG = "CloudflareInterceptor"
private const val MAX_MANUAL_ATTEMPTS = 3

private val CLOUDFLARE_WHITELIST = listOf(
    "github.com",
    "raw.githubusercontent.com"
)

object CloudflareBypassSignal {
    // Сигнал от кнопки в WebViewActivity — пользователь нажал "готово"
    val channel = Channel<Unit>(Channel.CONFLATED)

    // SharedFlow вместо Channel — сигнал получают ВСЕ подписчики одновременно.
    // Channel забирает сообщение у одного получателя, поэтому если открыты
    // читалка + каталог одновременно — обновился бы только один из них.
    private val _bypassCompleted = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val bypassCompleted: SharedFlow<String> = _bypassCompleted

    fun notifyBypassCompleted(host: String) {
        _bypassCompleted.tryEmit(host)
    }
}

internal class CloudFareVerificationInterceptor(
    @ApplicationContext private val appContext: Context
) : Interceptor {

    private val lock = ReentrantLock()
    private val resolvedDomains = mutableSetOf<String>()
    private val manualAttempts = ConcurrentHashMap<String, Int>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)
        val bodyPreview = peekBodySafe(response)

        if (isNotCloudflare(response, bodyPreview)) {
            return response
        }

        Log.d(TAG, "CF: Challenge detected. URL: ${originalRequest.url}")

        return lock.withLock {
            response.close()

            val siteUrl = originalRequest.url.toString()
            val host = originalRequest.url.host
            val cookieManager = CookieManager.getInstance()
                ?: throw WebViewCookieManagerInitializationFailedException()
            val userAgent = GLOBAL_USER_AGENT

            val existingCookie = cookieManager.getCookie(siteUrl) ?: ""
            if (resolvedDomains.contains(host) || existingCookie.contains("cf_clearance")) {
                Log.d(TAG, "CF: cf_clearance cached for $host, trying direct retry")
                val retryRequest = originalRequest.newBuilder()
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

            proceedWithBypass(chain, originalRequest, siteUrl, host, cookieManager, userAgent)
        }
    }

    private fun proceedWithBypass(
        chain: Interceptor.Chain,
        originalRequest: okhttp3.Request,
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

        // 1. АВТОМАТИКА
        runBlocking(Dispatchers.Main) {
            withTimeoutOrNull(15_000) {
                resolveWithWebViewAutomatic(webViewUrl, cookieManager)
            }
        }

        val firstCookies = cookieManager.getCookie(siteUrl) ?: ""
        val firstRetryRequest = originalRequest.newBuilder()
            .header("Cookie", formatCookies(firstCookies))
            .header("User-Agent", userAgent)
            .build()

        val firstRetryResponse = chain.proceed(firstRetryRequest)

        if (isNotCloudflare(firstRetryResponse, peekBodySafe(firstRetryResponse))) {
            resolvedDomains.add(host)
            manualAttempts.remove(host)
            CloudflareBypassSignal.notifyBypassCompleted(host)
            return firstRetryResponse
        }

        // 2. РУЧНОЙ ВВОД
        firstRetryResponse.close()

        val attempts = manualAttempts.getOrDefault(host, 0)
        if (attempts >= MAX_MANUAL_ATTEMPTS) {
            Log.e(TAG, "CF: Max manual attempts ($MAX_MANUAL_ATTEMPTS) reached for $host, giving up")
            manualAttempts.remove(host)
            throw CloudfareVerificationBypassFailedException()
        }
        manualAttempts[host] = attempts + 1
        Log.d(TAG, "CF: Step 2 - manual attempt ${attempts + 1}/$MAX_MANUAL_ATTEMPTS for $host, webViewUrl=$webViewUrl")

        clearCookiesForDomain(siteUrl, cookieManager)

        runBlocking(Dispatchers.IO) {
            resolveWithWebViewManual(webViewUrl, siteUrl, cookieManager)
        }

        cookieManager.flush()
        val finalCookies = cookieManager.getCookie(siteUrl) ?: ""

        val finalRetryRequest = originalRequest.newBuilder()
            .header("Cookie", formatCookies(finalCookies))
            .header("User-Agent", userAgent)
            .build()

        val finalResponse = chain.proceed(finalRetryRequest)

        if (!isNotCloudflare(finalResponse, peekBodySafe(finalResponse))) {
            finalResponse.close()
            throw CloudfareVerificationBypassFailedException()
        }

        resolvedDomains.add(host)
        manualAttempts.remove(host)
        CloudflareBypassSignal.notifyBypassCompleted(host)
        return finalResponse
    }

    private fun isNotCloudflare(response: Response, body: String): Boolean {
        val host = response.request.url.host
        if (CLOUDFLARE_WHITELIST.any { host.contains(it) }) return true

        val hasMarkers = body.contains("cf-challenge", true) ||
                body.contains("turnstile", true) ||
                body.contains("requireTurnstile", true) ||
                body.contains("Security Check Required", true) ||
                body.contains("__cf_chl_", true) ||
                body.contains("Ray ID", true) ||
                body.contains("but-captcha", true)

        val isError = response.code in ERROR_CODES || (response.code == 200 && hasMarkers)
        val isCfServer = response.header("Server")?.contains("cloudflare", true) == true
        return !(isError && (isCfServer || hasMarkers))
    }

    private fun clearCookiesForDomain(url: String, cm: CookieManager) {
        cm.setCookie(url, "cf_clearance=; Max-Age=0")
        cm.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebViewAutomatic(webViewUrl: String, cm: CookieManager) {
        withContext(Dispatchers.Main) {
            val webView = WebView(appContext)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = GLOBAL_USER_AGENT
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { cm.flush() }
            }
            webView.loadUrl(webViewUrl)
            for (i in 1..30) {
                delay(500)
                if (cm.getCookie(webViewUrl)?.contains("cf_clearance") == true) {
                    Log.d(TAG, "CF: Auto WebView success on iteration $i")
                    break
                }
            }
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun resolveWithWebViewManual(
        webViewUrl: String,
        siteUrl: String,
        cm: CookieManager
    ) {
        while (CloudflareBypassSignal.channel.tryReceive().isSuccess) {}

        withContext(Dispatchers.Main) {
            val intent = Intent().apply {
                setClassName(appContext, "my.noveldokusha.webview.WebViewActivity")
                putExtra("url", webViewUrl)
                putExtra("isBypassMode", true)
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
}