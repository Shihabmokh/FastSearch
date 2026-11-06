package com.shihab.fastfind

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/** Pulls searchable text out of files. Returns already-normalized text (see Text.normText) or null. */
object Extractors {

    private const val MAX_TEXT_BYTES = 4L * 1024 * 1024
    private const val MAX_OFFICE_BYTES = 80L * 1024 * 1024
    private const val MAX_PDF_BYTES = 40L * 1024 * 1024
    private const val MAX_NOEXT_BYTES = 200L * 1024
    private const val MAX_CHARS = 1_000_000

    private val TEXT = setOf(
        "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "html", "htm", "xhtml", "svg",
        "js", "mjs", "ts", "tsx", "jsx", "py", "java", "kt", "kts", "c", "cc", "cpp", "h", "hpp", "cs",
        "go", "rs", "php", "rb", "sh", "bash", "bat", "cmd", "ps1", "ini", "cfg", "conf", "properties",
        "yml", "yaml", "toml", "sql", "css", "scss", "srt", "vtt", "tex", "rst", "gradle", "env",
        "gitignore", "pl", "lua", "swift", "dart", "r", "m", "vcf", "ics", "nfo", "lst"
    )
    private val MARKUP = setOf("html", "htm", "xhtml", "xml", "svg")
    private val OFFICE = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")

    private val WS_TAGS = setOf(
        "w:p", "w:tc", "w:tr", "w:br", "w:tab", "a:p", "a:br", "text:p", "text:h", "text:tab",
        "text:line-break", "si", "row", "p", "br", "div", "li", "tr", "td", "th", "h1", "h2", "h3",
        "h4", "h5", "h6", "table", "ul", "ol", "title", "section", "article", "blockquote", "pre"
    )

    private val CP1256: Charset? = try {
        Charset.forName("windows-1256")
    } catch (e: Exception) {
        null
    }

    private val pdfLock = Any()

    fun canExtract(ext: String, size: Long): Boolean {
        if (size <= 0L) return false
        return when {
            ext in TEXT -> size <= MAX_TEXT_BYTES
            ext in OFFICE -> size <= MAX_OFFICE_BYTES
            ext == "pdf" -> size <= MAX_PDF_BYTES
            ext.isEmpty() -> size <= MAX_NOEXT_BYTES
            else -> false
        }
    }

    fun extract(f: File, ext: String): String? {
        return try {
            val raw = when {
                ext in OFFICE -> office(f, ext)
                ext == "pdf" -> pdf(f)
                else -> text(f, ext)
            }
            if (raw.isNullOrBlank()) {
                null
            } else {
                val capped = if (raw.length > MAX_CHARS) raw.substring(0, MAX_CHARS) else raw
                Text.normText(capped)
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- plain text

    private fun readCapped(ins: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (total < max) {
            val n = ins.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun text(f: File, ext: String): String? {
        val max = MAX_TEXT_BYTES.toInt()
        val bytes = FileInputStream(f).use { readCapped(it, max) }
        val truncated = f.length() > bytes.size
        val s = decode(if (truncated && bytes.size > 4) bytes.copyOf(bytes.size - 4) else bytes) ?: return null
        return if (ext in MARKUP) stripTags(s) else s
    }

    private fun decode(b: ByteArray): String? {
        if (b.isEmpty()) return null
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, StandardCharsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, StandardCharsets.UTF_16BE)
        val sniff = minOf(b.size, 4096)
        for (i in 0 until sniff) if (b[i] == 0.toByte()) return null // binary
        var off = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) off = 3
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, b.size - off))
                .toString()
        } catch (e: CharacterCodingException) {
            // Windows Arabic text files (cp1256) are common; fall back to that.
            String(b, off, b.size - off, CP1256 ?: StandardCharsets.ISO_8859_1)
        }
    }

    // ---------------------------------------------------------------- markup / office

    fun stripTags(s: String): String {
        val n = s.length
        val sb = StringBuilder(n / 2)
        var i = 0
        while (i < n) {
            val c = s[i]
            if (c == '<') {
                val end = s.indexOf('>', i + 1)
                if (end < 0) break
                var j = i + 1
                val closing = j < end && s[j] == '/'
                if (closing) j++
                var k = j
                while (k < end && !s[k].isWhitespace() && s[k] != '/') k++
                val name = s.substring(j, k).lowercase()
                if (!closing && (name == "script" || name == "style")) {
                    val close = s.indexOf("</$name", end, ignoreCase = true)
                    if (close < 0) break
                    val gt = s.indexOf('>', close)
                    i = if (gt < 0) n else gt + 1
                    continue
                }
                if (name in WS_TAGS) sb.append(' ')
                i = end + 1
            } else if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 2)..(i + 9)) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent == "nbsp" -> " "
                        ent.startsWith("#x") -> ent.substring(2).toIntOrNull(16)
                            ?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()
                            ?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) {
                        sb.append(rep)
                        i = semi + 1
                        continue
                    }
                }
                sb.append(c)
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun office(f: File, ext: String): String? {
        return ZipFile(f).use { z ->
            val sb = StringBuilder()
            fun add(entryName: String) {
                val e = z.getEntry(entryName) ?: return
                z.getInputStream(e).use { ins ->
                    sb.append(stripTags(String(readCapped(ins, 12 * 1024 * 1024), StandardCharsets.UTF_8)))
                    sb.append(' ')
                }
            }
            when (ext) {
                "docx" -> add("word/document.xml")
                "xlsx" -> add("xl/sharedStrings.xml")
                "odt", "ods", "odp" -> add("content.xml")
                "pptx" -> {
                    val slides = z.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                        .sorted()
                        .toList()
                    for (name in slides) {
                        add(name)
                        if (sb.length > MAX_CHARS) break
                    }
                }
            }
            sb.toString()
        }
    }

    // ---------------------------------------------------------------- PDF

    private fun pdf(f: File): String? = synchronized(pdfLock) {
        PDDocument.load(f).use { doc ->
            val stripper = PDFTextStripper()
            stripper.endPage = 400
            stripper.getText(doc)
        }
    }
}
