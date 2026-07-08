package my.noveldokusha.network.interceptors

import okhttp3.Interceptor
import okhttp3.Response

internal class BrowserHeadersInterceptor : Interceptor {

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
            builder.header(
                "Sec-CH-UA",
                "\"Chromium\";v=\"120\", \"Not(A:Brand\";v=\"24\", " +
                    "\"Google Chrome\";v=\"120\""
            )
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
