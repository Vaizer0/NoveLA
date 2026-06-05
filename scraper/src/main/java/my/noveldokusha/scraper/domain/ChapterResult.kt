package my.noveldokusha.scraper.domain

@ConsistentCopyVisibility
data class ChapterResult internal constructor(
    val title: String,
    val url: String,
    val volume: String? = null
)