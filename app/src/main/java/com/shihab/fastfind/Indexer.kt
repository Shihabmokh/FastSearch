package com.shihab.fastfind

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.BitSet
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Phase 1 (SCAN): walks all storage volumes with Files.walkFileTree (attributes come with the directory read,
 *   so no extra stat per file) and syncs the `files` table incrementally. Unchanged files are detected with an
 *   in-memory hash map (path hash -> id, size/mtime signature) so a rescan costs almost no database reads.
 * Phase 2 (CONTENT): extracts text from new/changed files with a small thread pool and feeds the FTS4 index.
 * Both phases are resumable: if the process is killed, the next run continues where it stopped.
 */
class Indexer(private val ctx: Context, private val db: Db) {

    enum class Phase { SCAN, CONTENT, DONE }

    fun interface Listener {
        fun onProgress(phase: Phase, done: Long, total: Long)
    }

    @Volatile
    var listener: Listener? = null

    @Volatile
    var running = false
        private set

    @Volatile
    private var cancel = false

    @Volatile
    private var lastPost = 0L

    private val worker = Executors.newSingleThreadExecutor()
    private val pool = Executors.newFixedThreadPool(3)
    private val prefs = ctx.getSharedPreferences("ff", Context.MODE_PRIVATE)

    fun lastScan(): Long = prefs.getLong("last_scan", 0L)

    private class Entry(val id: Int, var sig: Long)

    @Synchronized
    fun start(rebuild: Boolean = false): Boolean {
        if (running) return false
        running = true
        cancel = false
        worker.execute {
            try {
                if (rebuild) {
                    val d = db.writableDatabase
                    d.execSQL("DELETE FROM files")
                    d.execSQL("DELETE FROM content_fts")
                }
                val completed = scan()
                if (completed) prefs.edit().putLong("last_scan", System.currentTimeMillis()).apply()
                if (!cancel) extract()
            } catch (t: Throwable) {
                Log.e("FastFind", "indexing failed", t)
            } finally {
                running = false
                listener?.onProgress(Phase.DONE, 0, 0)
            }
        }
        return true
    }

    fun stop() {
        cancel = true
    }

    private fun post(phase: Phase, done: Long, total: Long, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastPost > 400) {
            lastPost = now
            listener?.onProgress(phase, done, total)
        }
    }

    private fun roots(): List<File> {
        val out = LinkedHashSet<String>()
        out.add(Environment.getExternalStorageDirectory().absolutePath)
        for (f in ctx.getExternalFilesDirs(null)) {
            if (f == null) continue
            val p = f.absolutePath
            val i = p.indexOf("/Android/data")
            if (i > 0) out.add(p.substring(0, i))
        }
        return out.map { File(it) }.filter { it.isDirectory && it.canRead() }
    }

    private fun hash(s: String): Long {
        var h = -3750763034362895579L
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= 1099511628211L
        }
        return h
    }

    private fun sig(size: Long, mtime: Long): Long = mtime * 31 + size

    private fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        if (i <= 0 || i == name.length - 1) return ""
        val e = name.substring(i + 1)
        return if (e.length > 12) "" else e.lowercase()
    }

    // ------------------------------------------------------------------ phase 1

    private fun scan(): Boolean {
        val sdb = db.writableDatabase
        val known = HashMap<Long, Entry>(1 shl 16)
        sdb.rawQuery("SELECT id,h,size,mtime FROM files", null).use { c ->
            while (c.moveToNext()) known[c.getLong(1)] = Entry(c.getInt(0), sig(c.getLong(2), c.getLong(3)))
        }
        val seen = BitSet()
        val roots = roots()

        val ins = sdb.compileStatement(
            "INSERT INTO files(h,path,name,nname,ext,dir,size,mtime,cstate) VALUES(?,?,?,?,?,?,?,?,?)"
        )
        val upd = sdb.compileStatement("UPDATE files SET size=?,mtime=?,cstate=? WHERE id=?")
        val delFts = sdb.compileStatement("DELETE FROM content_fts WHERE docid=?")

        var count = 0L
        var ops = 0

        fun handle(path: String, isDir: Boolean, size: Long, mtime: Long) {
            count++
            val h = hash(path)
            val e = known[h]
            if (e != null) {
                seen.set(e.id)
                if (!isDir) {
                    val s = sig(size, mtime)
                    if (s != e.sig) {
                        val ext = extOf(path.substring(path.lastIndexOf('/') + 1))
                        val st = if (Extractors.canExtract(ext, size)) 0L else 2L
                        delFts.bindLong(1, e.id.toLong())
                        delFts.execute()
                        upd.bindLong(1, size)
                        upd.bindLong(2, mtime)
                        upd.bindLong(3, st)
                        upd.bindLong(4, e.id.toLong())
                        upd.execute()
                        e.sig = s
                        ops++
                    }
                }
            } else {
                val name = path.substring(path.lastIndexOf('/') + 1)
                val ext = if (isDir) "" else extOf(name)
                ins.bindLong(1, h)
                ins.bindString(2, path)
                ins.bindString(3, name)
                ins.bindString(4, Text.normName(name))
                ins.bindString(5, ext)
                ins.bindLong(6, if (isDir) 1L else 0L)
                ins.bindLong(7, size)
                ins.bindLong(8, mtime)
                ins.bindLong(9, if (!isDir && Extractors.canExtract(ext, size)) 0L else 2L)
                val id = ins.executeInsert()
                if (id > 0) {
                    seen.set(id.toInt())
                    known[h] = Entry(id.toInt(), sig(size, mtime))
                }
                ops++
            }
            if (count % 2000L == 0L) post(Phase.SCAN, count, 0)
            if (ops >= 5000) {
                ops = 0
                sdb.setTransactionSuccessful()
                sdb.endTransaction()
                sdb.beginTransactionNonExclusive()
            }
        }

        sdb.beginTransactionNonExclusive()
        try {
            for (root in roots) {
                val rootStr = root.absolutePath
                val skip1 = "$rootStr/Android/data"
                val skip2 = "$rootStr/Android/obb"
                val visitor = object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (cancel) return FileVisitResult.TERMINATE
                        val p = dir.toString()
                        if (p == skip1 || p == skip2) return FileVisitResult.SKIP_SUBTREE
                        if (p != rootStr) handle(p, true, 0L, attrs.lastModifiedTime().toMillis())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (cancel) return FileVisitResult.TERMINATE
                        handle(file.toString(), false, attrs.size(), attrs.lastModifiedTime().toMillis())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                }
                try {
                    Files.walkFileTree(root.toPath(), emptySet<java.nio.file.FileVisitOption>(), 64, visitor)
                } catch (e: Exception) {
                    Log.w("FastFind", "walk failed for $rootStr", e)
                }
                if (cancel) break
            }
            sdb.setTransactionSuccessful()
        } finally {
            sdb.endTransaction()
        }
        post(Phase.SCAN, count, 0, force = true)
        if (cancel) return false

        // Sweep entries that no longer exist (only inside volumes that were actually scanned).
        val prefixes = roots.map { it.absolutePath + "/" }
        val gone = ArrayList<Long>()
        sdb.rawQuery("SELECT id,path FROM files", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                if (!seen.get(id)) {
                    val p = c.getString(1)
                    if (prefixes.any { p.startsWith(it) }) gone.add(id.toLong())
                }
            }
        }
        var i = 0
        while (i < gone.size) {
            val chunk = gone.subList(i, minOf(i + 400, gone.size))
            val ids = chunk.joinToString(",")
            sdb.beginTransactionNonExclusive()
            try {
                sdb.execSQL("DELETE FROM content_fts WHERE docid IN ($ids)")
                sdb.execSQL("DELETE FROM files WHERE id IN ($ids)")
                sdb.setTransactionSuccessful()
            } finally {
                sdb.endTransaction()
            }
            i += 400
        }
        return true
    }

    // ------------------------------------------------------------------ phase 2

    private fun extract() {
        val sdb = db.writableDatabase
        val ids = ArrayList<Long>()
        sdb.rawQuery("SELECT id FROM files WHERE cstate=0 AND dir=0 ORDER BY mtime DESC", null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        val total = ids.size.toLong()
        if (total == 0L) return

        val delFts = sdb.compileStatement("DELETE FROM content_fts WHERE docid=?")
        val insFts = sdb.compileStatement("INSERT INTO content_fts(docid, body) VALUES(?,?)")
        val setState = sdb.compileStatement("UPDATE files SET cstate=? WHERE id=?")

        var done = 0L
        var i = 0
        while (i < ids.size && !cancel) {
            val chunk = ids.subList(i, minOf(i + 64, ids.size))
            i += 64

            val tasks = ArrayList<Pair<Long, Future<String?>?>>(chunk.size)
            for (id in chunk) {
                var path: String? = null
                var ext = ""
                sdb.rawQuery("SELECT path,ext FROM files WHERE id=?", arrayOf(id.toString())).use { c ->
                    if (c.moveToFirst()) {
                        path = c.getString(0)
                        ext = c.getString(1)
                    }
                }
                val p = path
                if (p == null) {
                    tasks.add(Pair(id, null))
                } else {
                    val e = ext
                    tasks.add(Pair(id, pool.submit(Callable<String?> { Extractors.extract(File(p), e) })))
                }
            }

            sdb.beginTransactionNonExclusive()
            try {
                for ((id, fut) in tasks) {
                    var text: String? = null
                    if (fut != null) {
                        text = try {
                            fut.get(60, TimeUnit.SECONDS)
                        } catch (t: Throwable) {
                            fut.cancel(true)
                            null
                        }
                    }
                    if (!text.isNullOrBlank()) {
                        delFts.bindLong(1, id)
                        delFts.execute()
                        insFts.bindLong(1, id)
                        insFts.bindString(2, text)
                        insFts.executeInsert()
                        setState.bindLong(1, 1L)
                    } else {
                        setState.bindLong(1, 2L)
                    }
                    setState.bindLong(2, id)
                    setState.execute()
                    done++
                }
                sdb.setTransactionSuccessful()
            } finally {
                sdb.endTransaction()
            }
            post(Phase.CONTENT, done, total)
        }
        post(Phase.CONTENT, done, total, force = true)
    }
}
