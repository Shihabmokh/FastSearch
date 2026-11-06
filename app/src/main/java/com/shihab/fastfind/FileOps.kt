package com.shihab.fastfind

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object FileOps {

    private const val PRIMARY = "/storage/emulated/0"

    private fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun uriFor(ctx: Context, f: File) =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

    fun open(ctx: Context, h: Hit) {
        if (h.isDir) {
            openFolder(ctx, h.path)
            return
        }
        val f = File(h.path)
        if (!f.exists()) {
            toast(ctx, ctx.getString(R.string.toast_missing))
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(ctx, f), mime(h.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast(ctx, ctx.getString(R.string.toast_noapp))
        } catch (e: Exception) {
            toast(ctx, e.message ?: "error")
        }
    }

    /** Opens the folder in the system Files app (works for internal storage on most devices); otherwise copies the path. */
    fun openFolder(ctx: Context, folderPath: String) {
        try {
            if (!folderPath.startsWith(PRIMARY)) throw IllegalStateException("not primary")
            val rel = folderPath.removePrefix(PRIMARY).trim('/')
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            copyPath(ctx, folderPath)
        }
    }

    fun showInFolder(ctx: Context, h: Hit) {
        openFolder(ctx, if (h.isDir) h.path else h.path.substringBeforeLast('/'))
    }

    fun share(ctx: Context, h: Hit) {
        if (h.isDir) {
            copyPath(ctx, h.path)
            return
        }
        val f = File(h.path)
        if (!f.exists()) {
            toast(ctx, ctx.getString(R.string.toast_missing))
            return
        }
        try {
            val i = Intent(Intent.ACTION_SEND)
                .setType(mime(h.name))
                .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, h.name))
        } catch (e: Exception) {
            toast(ctx, e.message ?: "error")
        }
    }

    fun copyPath(ctx: Context, path: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", path))
        toast(ctx, ctx.getString(R.string.toast_copied))
    }

    fun details(ctx: Context, h: Hit): String {
        val sb = StringBuilder()
        sb.append(h.name).append("\n\n")
        sb.append(h.path).append("\n\n")
        if (h.isDir) {
            sb.append(ctx.getString(R.string.type_folder))
        } else {
            sb.append(Text.fmtSize(h.size)).append(" (").append(Text.fmtN(h.size)).append(" B)")
            if (h.ext.isNotEmpty()) sb.append("\n").append(h.ext.uppercase())
        }
        sb.append("\n").append(Text.fmtDateTime(h.mtime))
        return sb.toString()
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
