package my.noveldokusha.network.interceptors

import my.noveldokusha.core.appPreferences.AppPreferences
import okhttp3.Interceptor
import okhttp3.Response

internal class BrowserHeadersInterceptor(
    private val appPreferences: AppPreferences
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        if (original.header("Accept").isNullOrBlank()) {
            builder.header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9," +
                    "image/avif,image/webp,*/*;q=0.8"
            )
        }
        if (original.header("Accept-Language").isNullOrBlank()) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
        }
        if (original.header("Accept-Encoding").isNullOrBlank()) {
            builder.header("Accept-Encoding", "gzip, deflate, br")
        }
        if (original.header("Cache-Control").isNullOrBlank()) {
            builder.header("Cache-Control", "no-cache")
        }
        if (original.header("Pragma").isNullOrBlank()) {
            builder.header("Pragma", "no-cache")
        }
        if (original.header("Upgrade-Insecure-Requests").isNullOrBlank()) {
            builder.header("Upgrade-Insecure-Requests", "1")
        }

        if (original.header("Sec-Fetch-Dest").isNullOrBlank()) {
            builder.header("Sec-Fetch-Dest", "document")
        }
        if (original.header("Sec-Fetch-Mode").isNullOrBlank()) {
            builder.header("Sec-Fetch-Mode", "navigate")
        }
        if (original.header("Sec-Fetch-Site").isNullOrBlank()) {
            builder.header("Sec-Fetch-Site", "none")
        }
        if (original.header("Sec-Fetch-User").isNullOrBlank()) {
            builder.header("Sec-Fetch-User", "?1")
        }

        if (original.header("Sec-CH-UA").isNullOrBlank()) {
            val userAgent = resolveUserAgent(appPreferences)
            val chromeVersion = "Chrome/(\\d+)".toRegex().find(userAgent)?.groupValues?.get(1)
            val secChUa = if (chromeVersion != null) {
                "\"Chromium\";v=\"$chromeVersion\", \"Not(A:Brand\";v=\"24\", " +
                    "\"Google Chrome\";v=\"$chromeVersion\""
            } else {
                "\"Chromium\";v=\"120\", \"Not(A:Brand\";v=\"24\", " +
                    "\"Google Chrome\";v=\"120\""
            }
            builder.header("Sec-CH-UA", secChUa)
        }
        if (original.header("Sec-CH-UA-Mobile").isNullOrBlank()) {
            builder.header("Sec-CH-UA-Mobile", "?1")
        }
        if (original.header("Sec-CH-UA-Platform").isNullOrBlank()) {
            builder.header("Sec-CH-UA-Platform", "\"Android\"")
        }

        return chain.proceed(builder.build())
    }
}
