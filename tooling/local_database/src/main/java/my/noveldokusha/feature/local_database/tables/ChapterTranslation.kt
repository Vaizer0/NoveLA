package my.noveldokusha.feature.local_database.tables

import androidx.room.Entity
import androidx.room.Index

/**
 * Stores translated text for chapter paragraphs to avoid re-translating
 * on every reader session.
 *
 * Primary key is composite (chapterUrl + sourceLang + targetLang + originalText)
 * чтобы insertReplace корректно заменял существующие переводы,
 * а не добавлял дубли с autoGenerate id.
 */
@Entity(
    primaryKeys = ["chapterUrl", "sourceLang", "targetLang", "originalText"],
    indices = [
        Index(value = ["chapterUrl", "sourceLang", "targetLang"])
    ]
)
data class ChapterTranslation(
    val chapterUrl: String,
    val sourceLang: String,
    val targetLang: String,
    val originalText: String,
    val translatedText: String,
    val timestamp: Long = System.currentTimeMillis()
)