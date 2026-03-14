package my.noveldokusha.feature.local_database.tables

import androidx.room.Entity
import androidx.room.Index

/**
 * Жанры/теги книги. Связаны с Book через bookUrl.
 *
 * FOREIGN KEY намеренно отсутствует — жанры могут загружаться до того как
 * Book появится в таблице (книга ещё не добавлена в библиотеку).
 * Очистка orphaned записей — через deleteOrphaned() при необходимости,
 * и через deleteByBook() при явном удалении книги.
 */
@Entity(
    primaryKeys = ["bookUrl", "genre"],
    indices = [Index("bookUrl"), Index("genre")]
)
data class BookGenre(
    val bookUrl: String,
    val genre: String,
)