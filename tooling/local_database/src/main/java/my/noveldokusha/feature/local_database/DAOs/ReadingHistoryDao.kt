package my.noveldokusha.feature.local_database.DAOs

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import my.noveldokusha.feature.local_database.tables.ReadingHistory

@Dao
interface ReadingHistoryDao {

    @Upsert
    suspend fun upsert(history: ReadingHistory)

    @Query("SELECT * FROM ReadingHistory ORDER BY lastReadEpochTimeMilli DESC")
    fun getAllFlow(): Flow<List<ReadingHistory>>

    @Query("DELETE FROM ReadingHistory WHERE bookUrl = :bookUrl")
    suspend fun delete(bookUrl: String)

    @Query("DELETE FROM ReadingHistory")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM ReadingHistory")
    suspend fun count(): Int
}
