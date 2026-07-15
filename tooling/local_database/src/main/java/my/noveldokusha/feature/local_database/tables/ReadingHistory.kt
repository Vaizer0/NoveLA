package my.noveldokusha.feature.local_database.tables

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ReadingHistory(
    @PrimaryKey val bookUrl: String,
    val bookTitle: String,
    val bookCoverUrl: String,
    val lastReadChapterUrl: String?,
    val lastReadChapterTitle: String?,
    val lastReadEpochTimeMilli: Long,
    val totalChapters: Int = 0,
    val readChapters: Int = 0,
)
