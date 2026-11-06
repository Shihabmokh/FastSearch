package com.shihab.fastfind

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale

object Text {

    /** Normalizes a file name / query term: lower-case, Arabic folding (tashkeel, alef forms, ya, ta marbuta, digits). Never changes length except removing marks. */
    fun normName(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in 'ً'..'ٟ', 'ٰ', 'ـ' -> { /* tashkeel, dagger alef, tatweel: drop */ }
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا') // أ إ آ ٱ -> ا
                'ى' -> sb.append('ي')                                 // ى -> ي
                'ة' -> sb.append('ه')                                 // ة -> ه
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))       // Arabic-Indic digits
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))       // Persian digits
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /** Normalization for full text: normName + NFKC for presentation forms + one token per CJK character. */
    fun normText(s: String): String {
        var src = s
        if (src.any { it >= 'ﬀ' }) {
            src = Normalizer.normalize(src, Normalizer.Form.NFKC)
        }
        val n = normName(src)
        val sb = StringBuilder(n.length + 16)
        for (ch in n) {
            if (isCjk(ch)) sb.append(' ').append(ch).append(' ') else sb.append(ch)
        }
        return sb.toString()
    }

    private fun isCjk(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c in '぀'..'ヿ' || c in '가'..'힯'

    fun fmtN(n: Long): String = String.format(Locale.US, "%,d", n)

    fun fmtN(n: Int): String = fmtN(n.toLong())

    fun fmtSize(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024; i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun fmtDate(ms: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    fun fmtDateTime(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun icon(h: Hit): String {
        if (h.isDir) return "📁"
        return when (h.ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "tiff" -> "🖼️"
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v" -> "🎬"
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "amr", "wma" -> "🎵"
            "pdf" -> "📕"
            "doc", "docx", "odt", "rtf" -> "📝"
            "xls", "xlsx", "csv", "ods" -> "📊"
            "ppt", "pptx", "odp" -> "📽️"
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "🗜️"
            "apk", "xapk", "apks" -> "🤖"
            "py", "js", "ts", "java", "kt", "c", "cpp", "h", "cs", "go", "rs", "php", "rb", "sh", "html", "css", "json", "xml", "yml", "yaml", "sql" -> "💻"
            else -> "📄"
        }
    }

    private const val HL = 0x66FFC107

    fun highlightName(name: String, terms: List<String>): CharSequence {
        if (terms.isEmpty()) return name
        val n = normName(name)
        if (n.length != name.length) return name
        val sp = SpannableString(name)
        for (t in terms) {
            if (t.isEmpty()) continue
            var i = n.indexOf(t)
            while (i >= 0) {
                sp.setSpan(BackgroundColorSpan(HL), i, i + t.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(StyleSpan(Typeface.BOLD), i, i + t.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = n.indexOf(t, i + t.length)
            }
        }
        return sp
    }

    /** Converts an FTS snippet using « » markers into a highlighted CharSequence. */
    fun snippetSpans(raw: String): CharSequence {
        val s = raw.replace('\n', ' ').replace('\r', ' ')
        val sb = SpannableStringBuilder()
        var start = -1
        for (ch in s) {
            when (ch) {
                '«' -> start = sb.length
                '»' -> if (start >= 0 && sb.length > start) {
                    sb.setSpan(BackgroundColorSpan(HL), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    start = -1
                }
                else -> sb.append(ch)
            }
        }
        return sb
    }
}
