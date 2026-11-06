package com.shihab.fastfind

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shihab.fastfind.databinding.ItemResultBinding

class ResultAdapter(
    private val onClick: (Hit) -> Unit,
    private val onLong: (Hit) -> Unit
) : RecyclerView.Adapter<ResultAdapter.VH>() {

    private var items: List<Hit> = emptyList()
    private var terms: List<String> = emptyList()

    class VH(val b: ItemResultBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(hits: List<Hit>, t: List<String>) {
        items = hits
        terms = t
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val h = items[position]
        val b = holder.b
        b.tvIcon.text = Text.icon(h)
        b.tvName.text = Text.highlightName(h.name, terms)
        b.tvPath.text = h.path.substringBeforeLast('/').removePrefix("/storage/emulated/0").ifEmpty { "/" }
        b.tvMeta.text = if (h.isDir) {
            b.root.context.getString(R.string.type_folder) + " • " + Text.fmtDate(h.mtime)
        } else {
            Text.fmtSize(h.size) + " • " + Text.fmtDate(h.mtime)
        }
        if (h.snippet.isNullOrBlank()) {
            b.tvSnippet.visibility = View.GONE
        } else {
            b.tvSnippet.visibility = View.VISIBLE
            b.tvSnippet.text = Text.snippetSpans(h.snippet)
        }
        b.root.setOnClickListener { onClick(h) }
        b.root.setOnLongClickListener {
            onLong(h)
            true
        }
    }
}
