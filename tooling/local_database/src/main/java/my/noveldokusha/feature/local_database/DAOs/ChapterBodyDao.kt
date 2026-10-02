package my.noveldokusha.feature.local_database.DAOs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import my.noveldokusha.feature.local_database.tables.ChapterBody

/**
 * Агрегаты по диапазону глав: число глав и суммарная длина текста.
 *
 * Позволяет оценить объём экспорта, не загружая тела глав в память.
 */
data class RangeStats(
    val chapterCount: Int,
    val charCount: Long,
)

@Dao
interface ChapterBodyDao {
    @Query("SELECT * FROM ChapterBody")
    suspend fun getAll(): List<ChapterBody>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(chapterBody: ChapterBody)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(chapterBody: List<ChapterBody>)

    @Query("SELECT * FROM ChapterBody WHERE url = :url")
    suspend fun get(url: String): ChapterBody?

    @Query("SELECT * FROM ChapterBody WHERE url IN (:urls)")
    suspend fun getBodiesByUrls(urls: List<String>): List<ChapterBody>

    @Query("DELETE FROM ChapterBody WHERE ChapterBody.url NOT IN (SELECT Chapter.url FROM Chapter)")
    suspend fun removeAllNonChapterRows()

    @Query("DELETE FROM ChapterBody WHERE ChapterBody.url IN (:chaptersUrl)")
    suspend fun removeChapterRows(chaptersUrl: List<String>)

    @Query("""
        DELETE FROM ChapterBody 
        WHERE EXISTS (
            SELECT 1 FROM Chapter 
            WHERE Chapter.url = ChapterBody.url 
            AND Chapter.bookUrl IN (:bookUrls)
        )
    """)
    suspend fun removeChapterBodiesByBookUrls(bookUrls: List<String>)

    @Query("SELECT COUNT(*) FROM ChapterBody")
    suspend fun count(): Int

    @Query("SELECT * FROM ChapterBody LIMIT :limit OFFSET :offset")
    suspend fun getChunk(limit: Int, offset: Int): List<ChapterBody>

    @Query("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM ChapterBody")
    suspend fun getCacheSizeBytes(): Long

    @Query("DELETE FROM ChapterBody")
    suspend fun deleteAll(): Int

    @Query("""
        SELECT ChapterBody.url FROM ChapterBody
        INNER JOIN Chapter ON Chapter.url = ChapterBody.url
        WHERE Chapter.bookUrl = :bookUrl
    """)
    fun getDownloadedUrlsFlow(bookUrl: String): Flow<List<String>>

    @Query("""
        SELECT COUNT(*) FROM ChapterBody
        INNER JOIN Chapter ON Chapter.url = ChapterBody.url
        WHERE Chapter.bookUrl = :bookUrl
    """)
    suspend fun countDownloadedBodies(bookUrl: String): Int

    /**
     * Число скачанных глав и суммарная длина их тел в диапазоне позиций.
     *
     * Считается в SQL, поэтому не зависит от размера книги и не требует
     * держать главы в памяти (важно для экспорта на 1000+ глав).
     */
    @Query("""
        SELECT COUNT(*) AS chapterCount,
               COALESCE(SUM(LENGTH(ChapterBody.body)), 0) AS charCount
        FROM ChapterBody
        INNER JOIN Chapter ON Chapter.url = ChapterBody.url
        WHERE Chapter.bookUrl = :bookUrl
        AND Chapter.position BETWEEN :startPosition AND :endPosition
    """)
    suspend fun statsInRange(
        bookUrl: String,
        startPosition: Int,
        endPosition: Int,
    ): RangeStats

    data class UrlSize(val url: String, val sizeBytes: Long)

    @Query("""
        SELECT ChapterBody.url AS url, LENGTH(ChapterBody.body) AS sizeBytes
        FROM ChapterBody
        INNER JOIN Chapter ON Chapter.url = ChapterBody.url
        WHERE Chapter.bookUrl IN (:bookUrls)
    """)
    fun getSizesByBookUrls(bookUrls: List<String>): Flow<List<UrlSize>>
}
