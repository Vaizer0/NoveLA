package my.noveldokusha.feature.local_database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import my.noveldokusha.feature.local_database.migrations.MigrationsList
import my.noveldokusha.feature.local_database.migrations._1stKissNovelDomainChange_1_org
import my.noveldokusha.feature.local_database.migrations.readLightNovelDomainChange_1_today
import my.noveldokusha.feature.local_database.migrations.readLightNovelDomainChange_2_meme

// Helper function to check if a column exists in a table
private fun SupportSQLiteDatabase.columnExists(tableName: String, columnName: String): Boolean {
    val cursor = query("PRAGMA table_info($tableName)")
    cursor.use {
        val nameIndex = it.getColumnIndex("name")
        while (it.moveToNext()) {
            if (it.getString(nameIndex) == columnName) {
                return true
            }
        }
    }
    return false
}

// Helper function to safely add a column if it doesn't exist
private fun SupportSQLiteDatabase.addColumnIfNotExists(tableName: String, columnName: String, columnDef: String) {
    if (!columnExists(tableName, columnName)) {
        execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $columnDef")
    }
}

internal fun databaseMigrations() = arrayOf(
    migration(1) {
        it.addColumnIfNotExists("Chapter", "position", "INTEGER NOT NULL DEFAULT 0")
    },
    migration(2) {
        it.addColumnIfNotExists("Book", "inLibrary", "INTEGER NOT NULL DEFAULT 0")
        it.execSQL("UPDATE Book SET inLibrary = 1")
    },
    migration(3) {
        it.addColumnIfNotExists("Book", "coverImageUrl", "TEXT NOT NULL DEFAULT ''")
        it.addColumnIfNotExists("Book", "description", "TEXT NOT NULL DEFAULT ''")
    },
    migration(4) {
        it.addColumnIfNotExists("Book", "lastReadEpochTimeMilli", "INTEGER NOT NULL DEFAULT 0")
    },
    migration(5, MigrationsList::readLightNovelDomainChange_1_today),
    migration(6, MigrationsList::readLightNovelDomainChange_2_meme),
    migration(7, MigrationsList::_1stKissNovelDomainChange_1_org),
    migration(8) {
        it.execSQL("""
            CREATE TABLE IF NOT EXISTS ChapterTranslation (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                chapterUrl TEXT NOT NULL,
                sourceLang TEXT NOT NULL,
                targetLang TEXT NOT NULL,
                originalText TEXT NOT NULL,
                translatedText TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """)
        it.execSQL("""
            CREATE INDEX IF NOT EXISTS index_ChapterTranslation_chapterUrl_sourceLang_targetLang
            ON ChapterTranslation (chapterUrl, sourceLang, targetLang)
        """)
    },
    migration(9) {
        it.addColumnIfNotExists("Book", "addedToLibraryEpochTimeMilli", "INTEGER NOT NULL DEFAULT 0")
        it.addColumnIfNotExists("Book", "lastUpdateEpochTimeMilli", "INTEGER NOT NULL DEFAULT 0")
    },
    migration(10) {
        it.addColumnIfNotExists("Book", "category", "TEXT NOT NULL DEFAULT ''")
    },
    migration(11) {
        it.execSQL("""
            CREATE TABLE IF NOT EXISTS Extension (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                fileName TEXT NOT NULL,
                imageURL TEXT NOT NULL,
                language TEXT NOT NULL,
                version TEXT NOT NULL,
                md5 TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                installed INTEGER NOT NULL,
                chapterType TEXT NOT NULL,
                settings TEXT NOT NULL
            )
        """)
    },
    migration(12) {
        // Migrate Extension table: change id from INTEGER to TEXT
        it.execSQL("ALTER TABLE Extension RENAME TO Extension_old")
        it.execSQL("""
            CREATE TABLE Extension (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                fileName TEXT NOT NULL,
                imageURL TEXT NOT NULL,
                language TEXT NOT NULL,
                version TEXT NOT NULL,
                md5 TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                installed INTEGER NOT NULL,
                chapterType TEXT NOT NULL,
                settings TEXT NOT NULL
            )
        """)
        // Copy data: use name field as the new id (contains string IDs like "novelbin")
        it.execSQL("""
            INSERT INTO Extension (id, name, fileName, imageURL, language, version, md5, enabled, installed, chapterType, settings)
            SELECT name, name, fileName, imageURL, language, version, md5, enabled, installed, chapterType, settings
            FROM Extension_old
        """)
        it.execSQL("DROP TABLE Extension_old")
    },
    migration(13) {
        it.addColumnIfNotExists("Book", "chaptersListHash", "TEXT")
    },
)

internal fun migration(vi: Int, migrate: (SupportSQLiteDatabase) -> Unit) =
    object : Migration(vi, vi + 1) {
        override fun migrate(db: SupportSQLiteDatabase) = migrate(db)
    }
