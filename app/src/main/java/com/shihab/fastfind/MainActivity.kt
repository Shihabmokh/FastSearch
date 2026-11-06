package com.shihab.fastfind

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shihab.fastfind.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Indexer.Listener {

    private lateinit var b: ActivityMainBinding
    private val app get() = application as App
    private val handler = Handler(Looper.getMainLooper())
    private val searchExec = Executors.newSingleThreadExecutor()
    private var seq = 0
    private var lastPhase: Indexer.Phase? = null

    private val adapter = ResultAdapter(
        onClick = { FileOps.open(this, it) },
        onLong = { showActions(it) }
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val searchRunnable = Runnable { runSearch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rv.layoutManager = LinearLayoutManager(this)
        b.rv.adapter = adapter

        b.etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                triggerSearch(120)
            }
        })
        b.etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch(0)
                hideKeyboard()
                true
            } else {
                false
            }
        }
        b.chipScope.setOnCheckedStateChangeListener { _, _ -> triggerSearch(0) }
        b.btnMore.setOnClickListener { showMenu(it) }
        b.btnGrant.setOnClickListener { requestAccess() }
    }

    override fun onResume() {
        super.onResume()
        app.indexer.listener = this
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (app.indexer.listener === this) app.indexer.listener = null
    }

    // ------------------------------------------------------------------ permission

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun refresh() {
        val ok = hasAccess()
        b.layoutPerm.visibility = if (ok) View.GONE else View.VISIBLE
        if (!ok) return
        val ix = app.indexer
        if (!ix.running && System.currentTimeMillis() - ix.lastScan() > 10 * 60_000L) {
            ix.start()
        }
        updateStatus()
        triggerSearch(0)
    }

    // ------------------------------------------------------------------ indexing progress

    override fun onProgress(phase: Indexer.Phase, done: Long, total: Long) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            when (phase) {
                Indexer.Phase.SCAN -> {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    b.progress.visibility = View.VISIBLE
                    b.progress.isIndeterminate = true
                    b.tvStatus.text = getString(R.string.status_scan, Text.fmtN(done))
                }
                Indexer.Phase.CONTENT -> {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    b.progress.visibility = View.VISIBLE
                    b.progress.isIndeterminate = false
                    b.progress.max = 1000
                    b.progress.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                    b.tvStatus.text = getString(R.string.status_content, Text.fmtN(done), Text.fmtN(total))
                }
                Indexer.Phase.DONE -> {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    b.progress.visibility = View.GONE
                    updateStatus()
                }
            }
            if (phase != lastPhase) {
                lastPhase = phase
                triggerSearch(0)
            }
        }
    }

    private fun updateStatus() {
        if (app.indexer.running) return
        searchExec.execute {
            val (all, withContent) = try {
                app.db.stats()
            } catch (t: Throwable) {
                Pair(0L, 0L)
            }
            runOnUiThread {
                if (!app.indexer.running) {
                    b.tvStatus.text = getString(R.string.status_idle, Text.fmtN(all), Text.fmtN(withContent))
                }
            }
        }
    }

    // ------------------------------------------------------------------ search

    private fun currentScope(): Scope = when (b.chipScope.checkedChipId) {
        R.id.chipContents -> Scope.CONTENTS
        R.id.chipBoth -> Scope.BOTH
        else -> Scope.NAMES
    }

    private fun triggerSearch(delayMs: Long) {
        handler.removeCallbacks(searchRunnable)
        handler.postDelayed(searchRunnable, delayMs)
    }

    private fun runSearch() {
        if (!hasAccess()) return
        val q = b.etQuery.text?.toString() ?: ""
        val scope = currentScope()
        val my = ++seq
        searchExec.execute {
            val r = try {
                app.searcher.search(q, scope)
            } catch (t: Throwable) {
                null
            }
            runOnUiThread {
                if (my != seq) return@runOnUiThread
                if (r == null) {
                    b.tvResults.text = getString(R.string.status_bad)
                } else {
                    show(r)
                }
            }
        }
    }

    private fun show(r: SearchResult) {
        adapter.submit(r.hits, r.terms)
        b.rv.scrollToPosition(0)
        val n = Text.fmtN(r.hits.size)
        var s = when {
            r.recent -> getString(R.string.status_recent)
            r.capped -> getString(R.string.status_results_capped, n, r.ms)
            else -> getString(R.string.status_results, n, r.ms)
        }
        if (r.fuzzy) s += " • " + getString(R.string.status_fuzzy)
        b.tvResults.text = s
        if (r.hits.isEmpty()) {
            b.tvEmpty.visibility = View.VISIBLE
            b.tvEmpty.text = getString(if (app.indexer.running) R.string.empty_index else R.string.empty_none)
        } else {
            b.tvEmpty.visibility = View.GONE
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.etQuery.windowToken, 0)
    }

    // ------------------------------------------------------------------ menus & dialogs

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.menu_rescan)
        m.menu.add(0, 2, 1, R.string.menu_stop)
        m.menu.add(0, 3, 2, R.string.menu_rebuild)
        m.menu.add(0, 4, 3, R.string.menu_help)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> if (hasAccess()) app.indexer.start()
                2 -> app.indexer.stop()
                3 -> if (hasAccess() && !app.indexer.running) app.indexer.start(rebuild = true)
                4 -> showHelp()
                else -> {}
            }
            true
        }
        m.show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showActions(h: Hit) {
        val items = arrayOf(
            getString(R.string.act_open),
            getString(R.string.act_folder),
            getString(R.string.act_share),
            getString(R.string.act_copy),
            getString(R.string.act_details)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(h.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> FileOps.open(this, h)
                    1 -> FileOps.showInFolder(this, h)
                    2 -> FileOps.share(this, h)
                    3 -> FileOps.copyPath(this, h.path)
                    4 -> MaterialAlertDialogBuilder(this)
                        .setMessage(FileOps.details(this, h))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    else -> {}
                }
            }
            .show()
    }
}
