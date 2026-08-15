package org.matrix.chromext

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.matrix.chromext.script.Script
import org.matrix.chromext.script.ScriptDbHelper
import org.matrix.chromext.script.parseScript
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var lvScripts: ListView
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvModuleStatus: TextView
    private lateinit var adapter: ScriptAdapter

    private val allScripts = mutableListOf<Script>()
    private val filteredScripts = mutableListOf<Script>()

    /**
     * Hooked at runtime by MainHook when module is loaded by Xposed/LSPosed
     */
    fun isModuleActive(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lvScripts = findViewById(R.id.lv_scripts)
        etSearch = findViewById(R.id.et_search)
        tvCount = findViewById(R.id.tv_count)
        tvEmpty = findViewById(R.id.tv_empty)
        tvModuleStatus = findViewById(R.id.tv_module_status)

        adapter = ScriptAdapter()
        lvScripts.adapter = adapter

        updateModuleStatusView()
        loadScripts()
        setupSearch()

        findViewById<Button>(R.id.btn_add_script).setOnClickListener { showAddMenu() }
        findViewById<Button>(R.id.btn_open_browser).setOnClickListener { openBrowser() }
        findViewById<Button>(R.id.btn_import_file).setOnClickListener { openFilePicker() }
        findViewById<Button>(R.id.btn_import_url).setOnClickListener { showImportUrlDialog() }
        findViewById<Button>(R.id.btn_create_new).setOnClickListener { showScriptEditor(null) }
        findViewById<Button>(R.id.btn_templates).setOnClickListener { showTemplatesDialog() }
    }

    override fun onResume() {
        super.onResume()
        updateModuleStatusView()
    }

    private fun updateModuleStatusView() {
        val active = runCatching { isModuleActive() }.getOrDefault(false)
        if (active) {
            tvModuleStatus.text = "● Xposed Module Active"
            tvModuleStatus.setTextColor(Color.parseColor("#34D399")) // Emerald green
        } else {
            tvModuleStatus.text = "○ Module Inactive (Enable in LSPosed & reboot browser)"
            tvModuleStatus.setTextColor(Color.parseColor("#FBBF24")) // Amber warning
        }

        tvModuleStatus.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("ChromeXt Xposed Module")
                .setMessage(
                    if (active)
                        "ChromeXt is active and ready to inject userscripts into Chromium-based browsers."
                    else
                        "Module is not detected as active in this process.\n\n" +
                        "1. Open LSPosed / Xposed Manager.\n" +
                        "2. Enable ChromeXt module.\n" +
                        "3. Add your target browsers (e.g. Chrome, Brave, Edge, Kiwi) to the scope.\n" +
                        "4. Force stop and restart the browser."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun openBrowser() {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivity(browserIntent)
        }.onFailure {
            Toast.makeText(this, "No browser found to open", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadScripts() {
        allScripts.clear()
        val dbHelper = ScriptDbHelper(this)
        val db = dbHelper.readableDatabase
        val cursor = db.query("script", null, null, null, null, null, null)
        with(cursor) {
            while (moveToNext()) {
                val meta = getString(getColumnIndexOrThrow("meta"))
                val code = getString(getColumnIndexOrThrow("code"))
                val storage = getString(getColumnIndexOrThrow("storage"))
                val script = parseScript(meta + code, storage)
                if (script != null) allScripts.add(script)
            }
        }
        cursor.close()
        dbHelper.close()
        filterScripts(etSearch.text.toString())
    }

    private fun filterScripts(query: String) {
        filteredScripts.clear()
        if (query.isBlank()) {
            filteredScripts.addAll(allScripts)
        } else {
            val q = query.lowercase()
            filteredScripts.addAll(allScripts.filter {
                it.id.lowercase().contains(q) ||
                it.match.any { m -> m.lowercase().contains(q) }
            })
        }
        adapter.notifyDataSetChanged()
        updateCountView()
    }

    private fun updateCountView() {
        tvCount.text = "${filteredScripts.size} script${if (filteredScripts.size != 1) "s" else ""} installed"
        tvEmpty.visibility = if (filteredScripts.isEmpty()) View.VISIBLE else View.GONE
        lvScripts.visibility = if (filteredScripts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filterScripts(s.toString())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun showAddMenu() {
        AlertDialog.Builder(this)
            .setTitle("Add UserScript")
            .setItems(arrayOf("📁 From File", "🌐 From URL", "✏️ Create New", "⚡ Load Template")) { _, which ->
                when (which) {
                    0 -> openFilePicker()
                    1 -> showImportUrlDialog()
                    2 -> showScriptEditor(null)
                    3 -> showTemplatesDialog()
                }
            }
            .show()
    }

    private fun showTemplatesDialog() {
        val templateNames = arrayOf(
            "🌙 Dark Mode Enabler",
            "🛠️ Eruda Mobile DevTools",
            "🚫 Anti-Adblock Popup Remover",
            "⚡ Fast Video Speed Controller",
            "🔗 URL Parameter Cleaner"
        )
        val templateCodes = arrayOf(
            // Dark Mode
            """// ==UserScript==
// @name         Universal Dark Mode
// @namespace    org.matrix.chromext
// @version      1.0
// @description  Inverts high brightness web elements for dark viewing
// @match        *://*/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(function() {
    const css = 'html { filter: invert(90%) hue-rotate(180deg) !important; background: #111 !important; } img, video, canvas { filter: invert(100%) hue-rotate(180deg) !important; }';
    const style = document.createElement('style');
    style.appendChild(document.createTextNode(css));
    (document.head || document.documentElement).appendChild(style);
})();
""",
            // Eruda Devtools
            """// ==UserScript==
// @name         Eruda DevTools Loader
// @namespace    org.matrix.chromext
// @version      1.0
// @description  Injects Eruda console into web pages for debugging
// @match        *://*/*
// @run-at       document-end
// @grant        none
// ==/UserScript==

(function() {
    if (window.eruda) return;
    const script = document.createElement('script');
    script.src = 'https://cdn.jsdelivr.net/npm/eruda';
    script.onload = () => { window.eruda.init(); };
    document.body.appendChild(script);
})();
""",
            // Anti-Adblock popup remover
            """// ==UserScript==
// @name         Anti-Adblock Popup Cleaner
// @namespace    org.matrix.chromext
// @version      1.0
// @description  Removes common adblock overlay dialogs and re-enables scroll
// @match        *://*/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

(function() {
    setInterval(() => {
        document.body.style.overflow = 'auto';
        document.documentElement.style.overflow = 'auto';
    }, 1500);
})();
""",
            // Video Speed
            """// ==UserScript==
// @name         Video Speed Controller
// @namespace    org.matrix.chromext
// @version      1.0
// @description  Allows setting video playback rate to 2x easily
// @match        *://*/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

(function() {
    window.addEventListener('keydown', (e) => {
        if (e.key === ']' || e.key === '}') {
            document.querySelectorAll('video').forEach(v => v.playbackRate += 0.25);
        } else if (e.key === '[' || e.key === '{') {
            document.querySelectorAll('video').forEach(v => v.playbackRate = Math.max(0.25, v.playbackRate - 0.25));
        }
    });
})();
""",
            // URL Cleaner
            """// ==UserScript==
// @name         Clean Tracking URLs
// @namespace    org.matrix.chromext
// @version      1.0
// @description  Strips utm_* and fbclid tracking parameters from links
// @match        *://*/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

(function() {
    const url = new URL(window.location.href);
    const trackingParams = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content', 'fbclid', 'gclid'];
    let changed = false;
    trackingParams.forEach(p => {
        if (url.searchParams.has(p)) {
            url.searchParams.delete(p);
            changed = true;
        }
    });
    if (changed) {
        window.history.replaceState({}, document.title, url.toString());
    }
})();
"""
        )

        AlertDialog.Builder(this)
            .setTitle("Select Template")
            .setItems(templateNames) { _, which ->
                installScriptFromSource(templateCodes[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/javascript", "application/javascript", "text/plain"))
        }
        startActivityForResult(intent, REQUEST_IMPORT_FILE)
    }

    private fun showImportUrlDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_import_url, null)
        val etUrl = view.findViewById<EditText>(R.id.et_script_url)
        AlertDialog.Builder(this)
            .setTitle("Import from URL")
            .setView(view)
            .setPositiveButton("Download") { _, _ ->
                val url = etUrl.text.toString().trim()
                if (url.isNotEmpty()) downloadScript(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadScript(urlStr: String) {
        Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                val code = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                conn.disconnect()
                Handler(Looper.getMainLooper()).post {
                    installScriptFromSource(code)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installScriptFromSource(source: String) {
        val script = parseScript(source)
        if (script == null) {
            Toast.makeText(this, "Invalid UserScript format (Missing ==UserScript== headers)", Toast.LENGTH_SHORT).show()
            return
        }
        saveScript(script)
    }

    private fun saveScript(script: Script) {
        val dbHelper = ScriptDbHelper(this)
        val db = dbHelper.writableDatabase
        db.delete("script", "id = ?", arrayOf(script.id))
        val values = ContentValues().apply {
            put("id", script.id)
            put("meta", script.meta)
            put("code", script.code)
            if (script.storage != null) put("storage", script.storage.toString())
        }
        val result = runCatching { db.insertOrThrow("script", null, values) }
        dbHelper.close()
        if (result.isSuccess) {
            Toast.makeText(this, "Script saved: ${script.id.substringAfter(":")}", Toast.LENGTH_SHORT).show()
            loadScripts()
        } else {
            Toast.makeText(this, "Failed to save script", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteScript(script: Script) {
        AlertDialog.Builder(this)
            .setTitle("Delete Script")
            .setMessage("Delete \"${script.id.substringAfter(":")}\"?")
            .setPositiveButton("Delete") { _, _ ->
                val dbHelper = ScriptDbHelper(this)
                val db = dbHelper.writableDatabase
                db.delete("script", "id = ?", arrayOf(script.id))
                dbHelper.close()
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                loadScripts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScriptEditor(script: Script?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_script_editor, null)
        val tvTitle = view.findViewById<TextView>(R.id.tv_dialog_title)
        val etCode = view.findViewById<EditText>(R.id.et_script_code)
        tvTitle.text = if (script == null) "Create New Script" else "Edit Script"
        if (script != null) {
            etCode.setText(script.meta + script.code)
        }
        AlertDialog.Builder(this)
            .setTitle(if (script == null) "Create New Script" else "Edit: ${script.id.substringAfter(":")}")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val src = etCode.text.toString()
                installScriptFromSource(src)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportScript(script: Script) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/javascript"
            putExtra(Intent.EXTRA_TITLE, "${script.id.replace(":", "_")}.user.js")
        }
        pendingExportScript = script
        startActivityForResult(intent, REQUEST_EXPORT_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri: Uri = data.data!!
        when (requestCode) {
            REQUEST_IMPORT_FILE -> {
                try {
                    val source = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    installScriptFromSource(source)
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_EXPORT_FILE -> {
                pendingExportScript?.let { script ->
                    try {
                        contentResolver.openOutputStream(uri)?.write((script.meta + script.code).toByteArray())
                        Toast.makeText(this, "Exported successfully", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Failed to export: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    pendingExportScript = null
                }
            }
        }
    }

    private inner class ScriptAdapter : BaseAdapter() {
        override fun getCount() = filteredScripts.size
        override fun getItem(pos: Int) = filteredScripts[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_script, parent, false)
            val script = filteredScripts[pos]

            view.findViewById<TextView>(R.id.tv_script_name).text =
                script.id.substringAfter(":")
            view.findViewById<TextView>(R.id.tv_script_match).text =
                "Matches: " + (script.match.take(2).joinToString(", ") +
                    if (script.match.size > 2) "…" else "")
            val grantsView = view.findViewById<TextView>(R.id.tv_script_grants)
            val gmGrants = script.grant.filter { it.startsWith("GM") }
            if (gmGrants.isEmpty()) {
                grantsView.visibility = View.GONE
            } else {
                grantsView.visibility = View.VISIBLE
                grantsView.text = "${gmGrants.size} GM API${if (gmGrants.size != 1) "s" else ""}"
            }

            view.findViewById<Button>(R.id.btn_edit).setOnClickListener {
                showScriptEditor(script)
            }
            view.findViewById<Button>(R.id.btn_export).setOnClickListener {
                exportScript(script)
            }
            view.findViewById<Button>(R.id.btn_delete).setOnClickListener {
                deleteScript(script)
            }
            return view
        }
    }

    companion object {
        private const val REQUEST_IMPORT_FILE = 1001
        private const val REQUEST_EXPORT_FILE = 1002
        private var pendingExportScript: Script? = null
    }
}
