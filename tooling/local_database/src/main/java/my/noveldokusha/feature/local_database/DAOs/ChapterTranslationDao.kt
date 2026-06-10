package my.noveldokusha.feature.local_database.DAOs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import my.noveldokusha.feature.local_database.tables.ChapterTranslation

data class ChapterTitleTranslation(
    val chapterUrl: String,
    val translatedText: String
)

@Dao
interface ChapterTranslationDao {

    @Query("""
        SELECT * FROM ChapterTranslation 
        WHERE chapterUrl = :chapterUrl 
        AND sourceLang = :sourceLang 
        AND targetLang = :targetLang
        ORDER BY paragraphIndex
    """)
    suspend fun getTranslations(
        chapterUrl: String,
        sourceLang: String,
        targetLang: String
    ): List<ChapterTranslation>

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(translations: List<ChapterTranslation>)

    @Query("""
        DELETE FROM ChapterTranslation 
        WHERE chapterUrl NOT IN (SELECT url FROM Chapter)
    """)
    suspend fun removeOrphanedTranslations()

    @Query("DELETE FROM ChapterTranslation WHERE chapterUrl = :chapterUrl")
    suspend fun deleteChapterTranslations(chapterUrl: String)

    @Query("""
        DELETE FROM ChapterTranslation 
        WHERE chapterUrl IN (
            SELECT Chapter.url 
            FROM Chapter 
            WHERE Chapter.bookUrl IN (:bookUrls)
        )
    """)
    suspend fun deleteTranslationsByBookUrls(bookUrls: List<String>)

    @Query("""
        DELETE FROM ChapterTranslation 
        WHERE sourceLang = :sourceLang 
        AND targetLang = :targetLang
    """)
    suspend fun deleteTranslationsByLanguagePair(
        sourceLang: String,
        targetLang: String
    ): Int

    @Query("DELETE FROM ChapterTranslation")
    suspend fun deleteAllTranslations(): Int

    @Query("""
        SELECT COUNT(*) FROM ChapterTranslation 
        WHERE chapterUrl = :chapterUrl 
        AND sourceLang = :sourceLang 
        AND targetLang = :targetLang
    """)
    suspend fun getTranslationCount(
        chapterUrl: String,
        sourceLang: String,
        targetLang: String
    ): Int

    /**
     * Переведённые названия глав книги — originalText совпадает с title главы.
     * Используется для отображения переведённых названий в списке глав.
     */
    @Query("""
        SELECT ChapterTranslation.chapterUrl, ChapterTranslation.translatedText
        FROM ChapterTranslation
        INNER JOIN Chapter ON Chapter.url = ChapterTranslation.chapterUrl
        WHERE Chapter.bookUrl = :bookUrl
        AND ChapterTranslation.targetLang = :targetLang
        AND ChapterTranslation.originalText = Chapter.title
    """)
    fun getTranslatedTitlesFlow(
        bookUrl: String,
        targetLang: String
    ): Flow<List<ChapterTitleTranslation>>
}
