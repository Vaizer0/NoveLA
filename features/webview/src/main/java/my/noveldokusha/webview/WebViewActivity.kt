package my.noveldokusha.webview

import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
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
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // Все ссылки (включая magic link из почты) открываем внутри этого WebView
                val url = request?.url?.toString() ?: return false
                view?.loadUrl(url)
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                if (cookies.contains("cf_clearance")) {
                    Log.d("WebViewActivity", "CF Cookie detected!")
                }
            }
        }

        setContent {
            var isReady by remember { mutableStateOf(false) }
            var currentUrl by remember { mutableStateOf(urlExtra) }

            LaunchedEffect(Unit) {
                while (true) {
                    val cookies = CookieManager.getInstance().getCookie(urlExtra) ?: ""
                    if (cookies.contains("cf_clearance")) {
                        isReady = true
                    }
                    webView.url?.let { currentUrl = it }
                    delay(500)
                }
            }

            Theme(themeProvider = themeProvider) {
                WebViewScreen(
                    toolbarTitle = currentUrl,
                    isReady = isReady,
                    webViewFactory = { webView },
                    onNavigateToUrl = { url -> webView.loadUrl(url) },
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

    // Когда magic link открывается через Intent — перехватываем и грузим в наш WebView
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.data?.toString()
            ?: intent.getStringExtra("url")
            ?: return
        Log.d("WebViewActivity", "onNewIntent: loading $url")
        webView.loadUrl(url)
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