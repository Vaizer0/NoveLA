package my.noveldokusha.scraper.utils

import java.net.URI

/**
 * Normalize a book URL for consistent identification in DownloadManager and Book table.
 *
 * Unlike [normalizeUrl], this function is intentionally conservative:
 * it does NOT re-encode query parameters, because that could change
 * the URL in ways that break matching with scraper output.
 *
 * Transformations applied:
 * - Scheme → lowercase
 * - Host → lowercase
 * - Trailing slash removed from path (unless path is just "/")
 *
 * Fragment (#...) is preserved — some sources use hash-based routing.
 * Query parameters are preserved as-is (not re-encoded).
 *
 * `local://` URLs are returned as-is.
 */
fun normalizeBookUrl(url: String): String {
    if (url.startsWith("local://")) return url
    return try {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return url
        val host = uri.host?.lowercase() ?: return url
        val path = uri.rawPath?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""
        "$scheme://$host$path$query$fragment"
    } catch (_: Exception) {
        url // fallback — leave untouched
    }
}
