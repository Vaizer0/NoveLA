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
import kotlinx.coroutines.selects.select
import my.noveldokusha.core.domain.CloudfareVerificationBypassFailedException
import my.noveldokusha.core.domain.WebViewCookieManagerInitializationFailedException
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.seconds

private val ERROR_CODES = listOf(HttpsURLConnection.HTTP_FORBIDDEN, HttpsURLConnection.HTTP_UNAVAILABLE)
private const val TAG = "CloudflareInterceptor"

// Объект сигнала вынесен за пределы класса для доступа из Activity
object CloudflareBypassSignal {
    val channel = Channel<Unit>(Channel.CONFLATED)
}

internal class CloudFareVerificationInterceptor(
    @ApplicationContext private val appContext: Context
) : Interceptor {

    private val lock = ReentrantLock()

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
            val cookieManager = CookieManager.getInstance()
                ?: throw WebViewCookieManagerInitializationFailedException()
            val userAgent = GLOBAL_USER_AGENT

            // 1. АВТОМАТИКА
            runBlocking(Dispatchers.Main) {
                withTimeoutOrNull(10_000) {
                    resolveWithWebViewAutomatic(siteUrl, cookieManager)
                }
            }

            val firstRetryRequest = originalRequest.newBuilder()
                .header("Cookie", formatCookies(cookieManager.getCookie(siteUrl) ?: ""))
                .header("User-Agent", userAgent)
                .build()

            val firstRetryResponse = chain.proceed(firstRetryRequest)
            val firstRetryBody = peekBodySafe(firstRetryResponse)

            // 2. РУЧНОЙ ВВОД
            if (!isNotCloudflare(firstRetryResponse, firstRetryBody)) {
                firstRetryResponse.close()
                Log.d(TAG, "CF: Step 2 - Launching manual Activity...")

                clearCookiesForDomain(siteUrl, cookieManager)

                runBlocking(Dispatchers.IO) {
                    resolveWithWebViewManual(siteUrl, cookieManager)
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
                return finalResponse
            }

            return firstRetryResponse
        }
    }

    private fun isNotCloudflare(response: Response, body: String): Boolean {
        val hasMarkers = body.contains("cf-challenge", true) ||
                body.contains("turnstile", true) ||
                body.contains("requireTurnstile", true) ||
                body.contains("Security Check Required", true) ||
                body.contains("__cf_chl_", true) ||
                body.contains("Ray ID", true)
        val isError = response.code in ERROR_CODES || (response.code == 200 && hasMarkers)
        val isCfServer = response.header("Server")?.contains("cloudflare", true) == true
        return !(isError && (isCfServer || hasMarkers))
    }

    private fun clearCookiesForDomain(url: String, cm: CookieManager) {
        cm.setCookie(url, "cf_clearance=; Max-Age=0")
        cm.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebViewAutomatic(url: String, cm: CookieManager) {
        withContext(Dispatchers.Main) {
            val webView = WebView(appContext)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = GLOBAL_USER_AGENT
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { cm.flush() }
            }
            webView.loadUrl(url)
            for (i in 1..20) {
                delay(500)
                if (cm.getCookie(url)?.contains("cf_clearance") == true) break
            }
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun resolveWithWebViewManual(url: String, cm: CookieManager) {
        // Очищаем старые сигналы перед началом
        while (CloudflareBypassSignal.channel.tryReceive().isSuccess) {}

        withContext(Dispatchers.Main) {
            val intent = Intent().apply {
                setClassName(appContext, "my.noveldokusha.webview.WebViewActivity")
                putExtra("url", url)
                putExtra("isBypassMode", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            appContext.startActivity(intent)
        }

        withTimeoutOrNull(180.seconds) {
            coroutineScope {
                // ПЛАН А: Сигнал от кнопки (Мгновенно)
                val signalJob = launch {
                    CloudflareBypassSignal.channel.receive()
                }

                // ПЛАН Б: Проверка куки (Фоновый цикл - сохраняем старый функционал)
                val cookieJob = launch {
                    while (isActive) {
                        delay(1500)
                        cm.flush()
                        if (cm.getCookie(url)?.contains("cf_clearance") == true) break
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