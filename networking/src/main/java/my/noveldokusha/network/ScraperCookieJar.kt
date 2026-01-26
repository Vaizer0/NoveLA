package my.noveldokusha.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal class ScraperCookieJar : CookieJar {

    private val manager = CookieManager.getInstance().apply {
        setAcceptCookie(true)
    }

    private fun baseUrl(url: HttpUrl): String = "${url.scheme}://${url.host}"

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieString = manager.getCookie(baseUrl(url)) ?: return emptyList()
        return cookieString.split(";")
            .mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val baseUrl = baseUrl(url)
        cookies.forEach { cookie ->
            manager.setCookie(baseUrl, cookie.toString())
        }
        manager.flush()
    }
}
