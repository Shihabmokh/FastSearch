package com.shihab.fastfind

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * files        : one row per file/folder (names index, substring search via LIKE on normalized name)
 * content_fts  : FTS4 full-text index of file contents (docid == files.id)
 * cstate       : 0 = content pending, 1 = content indexed, 2 = not extractable / skipped
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "fastfind.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files(" +
                "id INTEGER PRIMARY KEY, " +
                "h INTEGER NOT NULL, " +
                "path TEXT NOT NULL, " +
                "name TEXT NOT NULL, " +
                "nname TEXT NOT NULL, " +
                "ext TEXT NOT NULL, " +
                "dir INTEGER NOT NULL, " +
                "size INTEGER NOT NULL, " +
                "mtime INTEGER NOT NULL, " +
                "cstate INTEGER NOT NULL DEFAULT 2)"
        )
        db.execSQL("CREATE INDEX idx_files_ext ON files(ext)")
        db.execSQL("CREATE INDEX idx_files_pending ON files(cstate) WHERE cstate=0")
        db.execSQL("CREATE VIRTUAL TABLE content_fts USING fts4(body, tokenize=unicode61)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS content_fts")
        db.execSQL("DROP TABLE IF EXISTS files")
        onCreate(db)
    }

    fun scalar(sql: String): Long =
        readableDatabase.rawQuery(sql, null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /** (all entries, files with indexed content) */
    fun stats(): Pair<Long, Long> =
        Pair(scalar("SELECT COUNT(*) FROM files"), scalar("SELECT COUNT(*) FROM files WHERE cstate=1"))
}
