package com.shihab.fastfind

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class Term(val text: String, val negate: Boolean, val quoted: Boolean)

data class FtsItem(val text: String, val phrase: Boolean, val negate: Boolean)

class Filters {
    val exts = LinkedHashSet<String>()
    val notExts = LinkedHashSet<String>()
    var minSize: Long? = null
    var maxSize: Long? = null
    var after: Long? = null
    var before: Long? = null
    val paths = ArrayList<String>()
    val notPaths = ArrayList<String>()
    var onlyDirs: Boolean? = null

    fun isEmpty(): Boolean =
        exts.isEmpty() && notExts.isEmpty() && minSize == null && maxSize == null &&
            after == null && before == null && paths.isEmpty() && notPaths.isEmpty() && onlyDirs == null
}

class ParsedQuery(
    val plain: List<Term>,
    val explicitName: List<Term>,
    val explicitContent: List<FtsItem>,
    val filters: Filters,
    val regex: Regex?
) {
    fun isEmpty(): Boolean =
        plain.isEmpty() && explicitName.isEmpty() && explicitContent.isEmpty() && filters.isEmpty() && regex == null
}

object QueryParser {

    private val KEYS = setOf(
        "ext", "e", "type", "size", "modified", "date", "m", "after", "before",
        "path", "in", "is", "c", "content", "text", "re", "regex", "name", "n"
    )

    private val TYPES: Map<String, List<String>> = mapOf(
        "image" to listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tiff"),
        "video" to listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v"),
        "audio" to listOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "amr", "wma"),
        "doc" to listOf("pdf", "doc", "docx", "odt", "rtf", "txt", "md"),
        "document" to listOf("pdf", "doc", "docx", "odt", "rtf", "txt", "md"),
        "sheet" to listOf("xls", "xlsx", "csv", "ods"),
        "slide" to listOf("ppt", "pptx", "odp"),
        "archive" to listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz"),
        "apk" to listOf("apk", "xapk", "apks"),
        "code" to listOf(
            "py", "js", "ts", "java", "kt", "c", "cpp", "h", "cs", "go", "rs", "php", "rb",
            "sh", "html", "css", "json", "xml", "yml", "yaml", "sql"
        )
    )

    private class Tok(val text: String, val quoted: Boolean)

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        val sb = StringBuilder()
        var inQ = false
        var quoted = false
        fun flush() {
            if (sb.isNotEmpty()) out.add(Tok(sb.toString(), quoted))
            sb.setLength(0)
            quoted = false
        }
        for (ch in s) {
            when {
                ch == '"' || ch == '“' || ch == '”' -> {
                    inQ = !inQ
                    quoted = true
                }
                ch.isWhitespace() && !inQ -> flush()
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    fun parse(input: String): ParsedQuery {
        val plain = ArrayList<Term>()
        val names = ArrayList<Term>()
        val content = ArrayList<FtsItem>()
        val f = Filters()
        var regex: Regex? = null

        for (tok in tokenize(input)) {
            var t = tok.text
            var neg = false
            if (t.length > 1 && t[0] == '-') {
                neg = true
                t = t.substring(1)
            }
            val ci = t.indexOf(':')
            val key = if (ci > 0) t.substring(0, ci).lowercase() else null
            if (key != null && key in KEYS) {
                val v = t.substring(ci + 1).trim()
                if (v.isEmpty()) continue
                when (key) {
                    "ext", "e" -> {
                        val list = v.split(';', ',', '|').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }
                        if (neg) f.notExts.addAll(list) else f.exts.addAll(list)
                    }
                    "type" -> {
                        val list = TYPES[v.lowercase()] ?: listOf(v.lowercase())
                        if (neg) f.notExts.addAll(list) else f.exts.addAll(list)
                    }
                    "size" -> parseSize(v, f)
                    "modified", "date", "m" -> parseDate(v, f)
                    "after" -> parseDay(v)?.let { f.after = it }
                    "before" -> parseDay(v)?.let { f.before = it }
                    "path", "in" -> if (neg) f.notPaths.add(v) else f.paths.add(v)
                    "is" -> when (v.lowercase()) {
                        "folder", "dir", "directory" -> f.onlyDirs = !neg
                        "file" -> f.onlyDirs = neg
                    }
                    "c", "content", "text" -> content.add(FtsItem(v, tok.quoted || v.contains(' '), neg))
                    "re", "regex" -> regex = try {
                        Regex(v, RegexOption.IGNORE_CASE)
                    } catch (e: Exception) {
                        null
                    }
                    "name", "n" -> names.add(Term(v, neg, tok.quoted))
                }
                continue
            }
            if (t.isNotBlank()) plain.add(Term(t, neg, tok.quoted))
        }
        return ParsedQuery(plain, names, content, f, regex)
    }

    private val SIZE_RE = Regex("""(\d+(?:\.\d+)?)\s*(b|kb|mb|gb|tb)?""")

    private fun sizeVal(s: String): Long? {
        val m = SIZE_RE.matchEntire(s.trim().lowercase()) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mul = when (m.groupValues[2]) {
            "kb" -> 1L shl 10
            "mb" -> 1L shl 20
            "gb" -> 1L shl 30
            "tb" -> 1L shl 40
            else -> 1L
        }
        return (n * mul).toLong()
    }

    private fun parseSize(v: String, f: Filters) {
        when {
            v.contains("..") -> {
                val parts = v.split("..", limit = 2)
                sizeVal(parts[0])?.let { f.minSize = it }
                sizeVal(parts[1])?.let { f.maxSize = it }
            }
            v.startsWith(">=") -> sizeVal(v.substring(2))?.let { f.minSize = it }
            v.startsWith("<=") -> sizeVal(v.substring(2))?.let { f.maxSize = it }
            v.startsWith(">") -> sizeVal(v.substring(1))?.let { f.minSize = it + 1 }
            v.startsWith("<") -> sizeVal(v.substring(1))?.let { f.maxSize = (it - 1).coerceAtLeast(0) }
            v.startsWith("=") -> sizeVal(v.substring(1))?.let { f.minSize = it; f.maxSize = it }
            else -> sizeVal(v)?.let { f.minSize = it }
        }
    }

    private const val DAY = 86_400_000L

    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun parseDay(s: String): Long? = try {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.isLenient = false
        fmt.parse(s.trim().replace('/', '-'))?.time
    } catch (e: Exception) {
        null
    }

    private val REL_RE = Regex("""(\d+)([dwmy])""")

    private fun parseDate(v: String, f: Filters) {
        val now = System.currentTimeMillis()
        val today = startOfDay(now)
        when (v.lowercase()) {
            "today" -> f.after = today
            "yesterday" -> {
                f.after = today - DAY
                f.before = today
            }
            "week" -> f.after = today - 6 * DAY
            "month" -> f.after = today - 29 * DAY
            "year" -> f.after = today - 364 * DAY
            else -> {
                val rel = REL_RE.matchEntire(v.lowercase())
                if (rel != null) {
                    val n = rel.groupValues[1].toLong()
                    val mul = when (rel.groupValues[2]) {
                        "w" -> 7L
                        "m" -> 30L
                        "y" -> 365L
                        else -> 1L
                    }
                    f.after = now - n * mul * DAY
                    return
                }
                var s = v
                var op = '='
                if (s.startsWith(">")) {
                    op = '>'; s = s.substring(1)
                } else if (s.startsWith("<")) {
                    op = '<'; s = s.substring(1)
                }
                val t = parseDay(s) ?: return
                when (op) {
                    '>' -> f.after = t
                    '<' -> f.before = t
                    else -> {
                        f.after = t
                        f.before = t + DAY
                    }
                }
            }
        }
    }
}
