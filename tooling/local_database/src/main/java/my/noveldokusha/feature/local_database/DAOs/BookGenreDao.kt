package my.noveldokusha.feature.local_database.DAOs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import my.noveldokusha.feature.local_database.tables.BookGenre

@Dao
interface BookGenreDao {

    /** Все жанры конкретной книги, отсортированные алфавитно */
    @Query("SELECT genre FROM BookGenre WHERE bookUrl = :bookUrl ORDER BY genre ASC")
    suspend fun getGenres(bookUrl: String): List<String>

    /** Flow жанров — для реактивного UI */
    @Query("SELECT genre FROM BookGenre WHERE bookUrl = :bookUrl ORDER BY genre ASC")
    fun getGenresFlow(bookUrl: String): Flow<List<String>>

    /** Все уникальные жанры во всей библиотеке — для экрана фильтрации */
    @Query("""
        SELECT DISTINCT BookGenre.genre 
        FROM BookGenre 
        INNER JOIN Book ON Book.url = BookGenre.bookUrl 
        WHERE Book.inLibrary = 1 
        ORDER BY BookGenre.genre ASC
    """)
    fun getAllLibraryGenresFlow(): Flow<List<String>>

    /** URL книг в библиотеке с указанным жанром — для фильтрации библиотеки */
    @Query("""
        SELECT BookGenre.bookUrl 
        FROM BookGenre 
        INNER JOIN Book ON Book.url = BookGenre.bookUrl 
        WHERE Book.inLibrary = 1 AND BookGenre.genre = :genre
    """)
    suspend fun getBooksWithGenre(genre: String): List<String>

    /** Вставить список жанров (IGNORE дубли — safe для повторного вызова) */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(genres: List<BookGenre>)

    /** Заменить все жанры книги: удалить старые + вставить новые */
    @Query("DELETE FROM BookGenre WHERE bookUrl = :bookUrl")
    suspend fun deleteByBook(bookUrl: String)

    /** Удалить жанры книг, которых больше нет в таблице Book (orphan cleanup) */
    @Query("DELETE FROM BookGenre WHERE bookUrl NOT IN (SELECT url FROM Book)")
    suspend fun deleteOrphaned()
}