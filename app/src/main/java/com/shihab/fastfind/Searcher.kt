package com.shihab.fastfind

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock

enum class Scope { NAMES, CONTENTS, BOTH }

data class Hit(
    val id: Long,
    val path: String,
    val name: String,
    val ext: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val snippet: String? = null
)

data class SearchResult(
    val hits: List<Hit>,
    val terms: List<String>,
    val ms: Long,
    val capped: Boolean,
    val fuzzy: Boolean,
    val recent: Boolean
)

class Searcher(private val db: Db) {

    private val cols = "f.id,f.path,f.name,f.ext,f.dir,f.size,f.mtime"

    fun search(raw: String, scope: Scope, limit: Int = 1000): SearchResult {
        val t0 = SystemClock.elapsedRealtime()
        val q = QueryParser.parse(raw)
        val rdb = db.readableDatabase

        if (q.isEmpty()) {
            val hits = query(
                rdb,
                "SELECT $cols FROM files f WHERE f.dir=0 ORDER BY f.mtime DESC LIMIT 100",
                emptyArray(), null, 100
            )
            return SearchResult(hits, emptyList(), SystemClock.elapsedRealtime() - t0, false, false, true)
        }

        val names = q.plain + q.explicitName
        val plainItems = q.plain.map { FtsItem(it.text, it.quoted, it.negate) }
        val contentItems = plainItems + q.explicitContent

        var hits: List<Hit> = emptyList()
        when (scope) {
            Scope.NAMES -> hits = run(rdb, names, q.explicitContent, q, limit, null)
            Scope.CONTENTS -> hits = run(rdb, q.explicitName, contentItems, q, limit, null)
            Scope.BOTH -> {
                val a = run(rdb, names, q.explicitContent, q, limit, null)
                val b = if (contentItems.any { !it.negate }) {
                    run(rdb, q.explicitName, contentItems, q, limit, null)
                } else {
                    emptyList()
                }
                val ids = HashSet<Long>()
                for (h in a) ids.add(h.id)
                hits = a + b.filter { it.id !in ids }
            }
        }

        // Typo-tolerant fallback: single name term, nothing found -> subsequence match (e.g. "rprt" finds "report").
        var fuzzy = false
        if (hits.isEmpty() && scope != Scope.CONTENTS && q.explicitContent.isEmpty() && q.regex == null) {
            val pos = names.filter { !it.negate }
            if (pos.size == 1) {
                val t = Text.normName(pos[0].text).trim()
                if (t.length in 3..40) {
                    val pattern = "%" + t.map { esc(it.toString()) }.joinToString("%") + "%"
                    val h2 = run(rdb, names.filter { it.negate }, emptyList(), q, 200, pattern)
                    if (h2.isNotEmpty()) {
                        hits = h2
                        fuzzy = true
                    }
                }
            }
        }

        val terms = LinkedHashSet<String>()
        for (t in names) if (!t.negate) Text.normName(t.text).trim().takeIf { it.isNotEmpty() }?.let { terms.add(it) }
        for (c in contentItems) if (!c.negate) {
            for (w in Text.normName(c.text).split(Regex("\\s+"))) if (w.isNotEmpty()) terms.add(w)
        }

        return SearchResult(
            hits, terms.toList(), SystemClock.elapsedRealtime() - t0,
            hits.size >= limit, fuzzy, false
        )
    }

    // ------------------------------------------------------------------------------------------

    private fun run(
        db: SQLiteDatabase,
        names: List<Term>,
        content: List<FtsItem>,
        q: ParsedQuery,
        limit: Int,
        fuzzyPattern: String?
    ): List<Hit> {
        val whereArgs = ArrayList<String>()
        val where = buildWhere(names, q.filters, whereArgs, fuzzyPattern)
        val lim = if (q.regex != null) 500_000 else limit

        if (content.isNotEmpty()) {
            val positive = buildMatch(content.filter { !it.negate }) ?: return emptyList()
            val negatives = content.filter { it.negate }.mapNotNull { buildMatch(listOf(it.copy(negate = false))) }
            val notIn = negatives.joinToString("") {
                " AND f.id NOT IN (SELECT docid FROM content_fts WHERE content_fts MATCH ?)"
            }
            val sql = "SELECT $cols, snippet(content_fts,'«','»','…',-1,14) " +
                "FROM content_fts JOIN files f ON f.id=content_fts.docid " +
                "WHERE content_fts MATCH ? AND $where$notIn ORDER BY f.mtime DESC LIMIT $lim"
            // placeholder order in the SQL: positive MATCH, then the name/filter args, then the NOT IN matches
            val args = ArrayList<String>()
            args.add(positive)
            args.addAll(whereArgs)
            args.addAll(negatives)
            return query(db, sql, args.toTypedArray(), q.regex, limit)
        }

        val first = names.firstOrNull { !it.negate }
            ?.let { Text.normName(it.text).trim() }
            ?.takeIf { it.isNotEmpty() }
        val args = ArrayList<String>(whereArgs)
        val order = if (first != null) {
            args.add(first)
            args.add(esc(first) + "%")
            "ORDER BY CASE WHEN f.nname=? THEN 0 WHEN f.nname LIKE ? ESCAPE '\\' THEN 1 ELSE 2 END, " +
                "length(f.nname), f.mtime DESC"
        } else {
            "ORDER BY f.mtime DESC"
        }
        val sql = "SELECT $cols FROM files f WHERE $where $order LIMIT $lim"
        return query(db, sql, args.toTypedArray(), q.regex, limit)
    }

    private fun query(db: SQLiteDatabase, sql: String, args: Array<String>, regex: Regex?, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        db.rawQuery(sql, args).use { c ->
            val hasSnippet = c.columnCount > 7
            while (c.moveToNext()) {
                val name = c.getString(2)
                if (regex != null && !regex.containsMatchIn(name)) continue
                out.add(
                    Hit(
                        c.getLong(0), c.getString(1), name, c.getString(3), c.getInt(4) == 1,
                        c.getLong(5), c.getLong(6), if (hasSnippet) c.getString(7) else null
                    )
                )
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun buildWhere(names: List<Term>, f: Filters, args: MutableList<String>, fuzzy: String?): String {
        val w = StringBuilder("1=1")
        for (t in names) {
            val n = Text.normName(t.text).trim()
            if (n.isEmpty()) continue
            w.append(if (t.negate) " AND f.nname NOT LIKE ? ESCAPE '\\'" else " AND f.nname LIKE ? ESCAPE '\\'")
            args.add("%" + esc(n) + "%")
        }
        if (fuzzy != null) {
            w.append(" AND f.nname LIKE ? ESCAPE '\\'")
            args.add(fuzzy)
        }
        if (f.exts.isNotEmpty()) {
            w.append(" AND f.ext IN (").append(f.exts.joinToString(",") { "?" }).append(")")
            args.addAll(f.exts)
        }
        if (f.notExts.isNotEmpty()) {
            w.append(" AND f.ext NOT IN (").append(f.notExts.joinToString(",") { "?" }).append(")")
            args.addAll(f.notExts)
        }
        f.minSize?.let { w.append(" AND f.dir=0 AND f.size>=").append(it) }
        f.maxSize?.let { w.append(" AND f.dir=0 AND f.size<=").append(it) }
        f.after?.let { w.append(" AND f.mtime>=").append(it) }
        f.before?.let { w.append(" AND f.mtime<").append(it) }
        for (p in f.paths) {
            w.append(" AND f.path LIKE ? ESCAPE '\\'")
            args.add("%" + esc(p) + "%")
        }
        for (p in f.notPaths) {
            w.append(" AND f.path NOT LIKE ? ESCAPE '\\'")
            args.add("%" + esc(p) + "%")
        }
        f.onlyDirs?.let { w.append(" AND f.dir=").append(if (it) 1 else 0) }
        return w.toString()
    }

    /**
     * Builds an FTS4 MATCH expression from positive items. Every item is a quoted phrase; plain words get a
     * trailing '*' (prefix match) so typing "secur" finds "security". Returns null when nothing usable is left.
     * (Exclusions are NOT expressed in MATCH: the '-' operator is not reliable across SQLite builds.)
     */
    private fun buildMatch(items: List<FtsItem>): String? {
        val parts = ArrayList<String>()
        for (item in items) {
            val tokens = Text.normText(item.text).split(Regex("[^\\p{L}\\p{N}]+")).filter { s -> s.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val words = tokens.toMutableList()
            if (!item.phrase) words[words.size - 1] = words[words.size - 1] + "*"
            parts.add("\"" + words.joinToString(" ") + "\"")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}
