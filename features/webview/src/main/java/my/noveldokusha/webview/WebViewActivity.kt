package my.noveldokusha.webview

import android.annotation.SuppressLint
import android.content.*
import android.os.Bundle
import android.util.Log
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import my.noveldokusha.coreui.theme.Theme
import my.noveldokusha.coreui.theme.ThemeProvider
import my.noveldokusha.core.Toasty
import my.noveldokusha.network.interceptors.CloudflareBypassSignal
import my.noveldokusha.network.interceptors.GLOBAL_USER_AGENT
import javax.inject.Inject

@AndroidEntryPoint
class WebViewActivity : ComponentActivity() {

    @Inject lateinit var toasty: Toasty
    @Inject lateinit var themeProvider: ThemeProvider

    private val urlExtra by lazy { intent.getStringExtra("url") ?: "" }
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = GLOBAL_USER_AGENT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                if (cookies.contains("cf_clearance")) {
                    Log.d("WebViewActivity", "CF Cookie detected!")
                }
            }
        }

        setContent {
            // Флаг прохождения проверки
            var isReady by remember { mutableStateOf(false) }
            // Динамический URL для заголовка
            var currentUrl by remember { mutableStateOf(urlExtra) }

            // Обновляем состояние каждые 500мс
            LaunchedEffect(Unit) {
                while (true) {
                    val cookies = CookieManager.getInstance().getCookie(urlExtra) ?: ""
                    if (cookies.contains("cf_clearance")) {
                        isReady = true
                    }
                    // Берем актуальный URL из WebView для заголовка
                    webView.url?.let { currentUrl = it }
                    delay(500)
                }
            }

            Theme(themeProvider = themeProvider) {
                WebViewScreen(
                    // Теперь заголовок показывает реальный адрес сайта
                    toolbarTitle = currentUrl,
                    isReady = isReady,
                    webViewFactory = { webView },
                    onBackClicked = { finish() },
                    onDoneClicked = {
                        CookieManager.getInstance().flush()
                        CloudflareBypassSignal.channel.trySend(Unit)
                        finish()
                    },
                    onReloadClicked = { webView.reload() },
                    onClearCookiesClicked = { hardResetSession() },
                    onCopyUrlClicked = { copyToClipboard(webView.url ?: currentUrl) }
                )
            }
        }

        webView.loadUrl(urlExtra)
    }

    private fun hardResetSession() {
        CookieManager.getInstance().removeAllCookies {
            webView.clearCache(true)
            WebStorage.getInstance().deleteAllData()
            webView.loadUrl(urlExtra)
            toasty.show("Session cleared")
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("URL", text)
        clipboard.setPrimaryClip(clip)
        toasty.show("Link copied")
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}