package my.noveldokusha.core

enum class SourceType(val baseUrl: String, val displayName: String, val iconUrl: String? = null) {
    RANOBEHUB("ranobehub.org", "RanobeHub", "https://ranobehub.org/favicon.ico"),
    RANOBELIB("ranobelib.me", "RanobeLib", "https://ranobelib.me/favicon.ico"),
    BOOKHAMSTER("bookhamster.ru", "Bookhamster", "https://bookhamster.ru/favicon.ico"),
    IFREEDOM("ifreedom.su", "Свободный Мир Ранобэ", "https://ifreedom.su/favicon.ico"),
    FREEWEBNOVEL("freewebnovel.com", "FreeWebNovel", "https://freewebnovel.com/favicon.ico"),
    NOVELBIN("novelbin.com", "NovelBin", "https://novelbin.com/favicon.ico"),
    NOVELFULL("novelfull.net", "NovelFull", "https://novelfull.net/favicon.ico"),
    ROYALROAD("royalroad.com", "RoyalRoad", "https://www.royalroad.com/favicon.ico"),
    SCRIBBLEHUB("scribblehub.com", "ScribbleHub", "https://www.scribblehub.com/favicon.ico"),
    WUXIAWORLD("wuxiaworld.site", "WuxiaWorld", "https://wuxiaworld.site/favicon.ico"),
    READNOVELFULL("readnovelfull.com", "ReadNovelFull", "https://readnovelfull.com/favicon.ico"),
    NOVELUPDATES("novelupdates.com", "NovelUpdates", "https://www.novelupdates.com/favicon.ico"),
    REDDIT("reddit.com", "Reddit", "https://www.reddit.com/favicon.ico"),
    LOCAL("local", "Local"),
    UNKNOWN("", "Unknown");

    companion object {
        fun fromUrl(url: String): SourceType {
            val domain = extractDomain(url)
            return entries.find { it.baseUrl.contains(domain) } ?: UNKNOWN
        }

        private fun extractDomain(url: String): String {
            return try {
                val uri = android.net.Uri.parse(url)
                uri.host?.removePrefix("www.") ?: ""
            } catch (e: Exception) {
                ""
            }
        }
    }

    fun getIconUrlValue(): String? = iconUrl

    fun hasIcon(): Boolean = getIconUrlValue() != null
}
