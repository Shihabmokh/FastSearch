package com.shihab.fastfind

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    val db by lazy { Db(this) }
    val indexer by lazy { Indexer(this, db) }
    val searcher by lazy { Searcher(db) }

    override fun onCreate() {
        super.onCreate()
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (t: Throwable) {
            // PDF text extraction just won't work; everything else is unaffected.
        }
    }
}
