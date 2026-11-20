package com.logan.runtimeintelligence

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var engine: DiagnosticsEngine
    private lateinit var appPicker: Spinner
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var endpoint: EditText
    private var apps: List<AppTarget> = emptyList()
    private var latest: RuntimeSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = DiagnosticsEngine(this)
        buildDashboard()
        loadApps()
    }

    private fun buildDashboard() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        val scroll = ScrollView(this).apply { addView(page) }
        val title = TextView(this).apply {
            text = "RUNTIME INTELLIGENCE"
            textSize = 24f
            setTextColor(0xFF102A43.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "Selected-app diagnostics • integrity • runtime events"
            textSize = 13f
            setTextColor(0xFF52606D.toInt())
            setPadding(0, 6, 0, 18)
        }
        status = TextView(this).apply {
            text = "Initializing diagnostic engine…"
            textSize = 14f
            setTextColor(0xFF087E8B.toInt())
            setPadding(0, 10, 0, 14)
        }
        appPicker = Spinner(this)
        val scan = Button(this).apply {
            text = "SCAN SELECTED APP"
            setOnClickListener { scanSelected() }
        }
        val usage = Button(this).apply {
            text = "GRANT USAGE ACCESS"
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        val report = Button(this).apply {
            text = "SAVE JSON DIAGNOSTIC REPORT"
            setOnClickListener { saveLatest() }
        }
        endpoint = EditText(this).apply {
            hint = "Optional HTTPS cloud endpoint"
            textSize = 14f
            singleLine = true
        }
        val upload = Button(this).apply {
            text = "UPLOAD LATEST REPORT"
            setOnClickListener { uploadLatest() }
        }
        val refresh = Button(this).apply {
            text = "REFRESH APP LIST"
            setOnClickListener { loadApps() }
        }
        output = TextView(this).apply {
            text = "Choose an installed app, then run a scan. Detailed private-process metrics require root; non-root mode still provides usage and APK integrity diagnostics."
            textSize = 14f
            setTextColor(0xFF243B53.toInt())
            setPadding(16, 18, 16, 18)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        page.addView(title)
        page.addView(subtitle)
        page.addView(status)
        page.addView(section("TARGET APPLICATION"))
        page.addView(appPicker)
        page.addView(scan)
        page.addView(usage)
        page.addView(refresh)
        page.addView(section("DIAGNOSTIC OUTPUT"))
        page.addView(output)
        page.addView(section("CLOUD REPORTING"))
        page.addView(endpoint)
        page.addView(upload)
        page.addView(report)
        setContentView(scroll)
    }

    private fun section(label: String) = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFF087E8B.toInt())
        setPadding(0, 20, 0, 8)
    }

    private fun loadApps() {
        status.text = "Discovering installed applications…"
        worker.execute {
            val found = engine.installedApps()
            runOnUiThread {
                apps = found
                appPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps)
                status.text = "${apps.size} apps available • Whole-device RAM collection is disabled"
            }
        }
    }

    private fun scanSelected() {
        val target = apps.getOrNull(appPicker.selectedItemPosition) ?: return
        status.text = "Scanning ${target.packageName}…"
        output.text = "Collecting selected-app usage, process and code-integrity diagnostics…"
        worker.execute {
            val snapshot = engine.scan(target)
            latest = snapshot
            runOnUiThread {
                val time = if (snapshot.lastUsed > 0) DateFormat.getDateTimeInstance().format(Date(snapshot.lastUsed)) else "Not available"
                val memory = if (snapshot.rssKb != null) {
                    "PID: ${snapshot.pid}\nRSS: ${snapshot.rssKb} KB\nPSS: ${snapshot.pssKb ?: "—"} KB\nPrivate/data: ${snapshot.privateDirtyKb ?: "—"} KB\nFile-backed/shared: ${snapshot.sharedKb ?: "—"} KB\nProcess state: ${snapshot.processState ?: "—"}"
                } else "Process memory: unavailable (root access and a running target process are required)"
                output.text = buildString {
                    appendLine("TARGET: ${snapshot.packageName}")
                    appendLine("Captured: ${DateFormat.getDateTimeInstance().format(Date(snapshot.capturedAt))}")
                    appendLine("Foreground time (24h): ${snapshot.foregroundMs24h / 1000 / 60} minutes")
                    appendLine("Last used: $time")
                    appendLine()
                    appendLine("SELECTED PROCESS")
                    appendLine(memory)
                    appendLine()
                    appendLine("CODE INTEGRITY")
                    appendLine("APK size: ${snapshot.apkBytes} bytes")
                    appendLine("SHA-256: ${snapshot.apkSha256}")
                    appendLine("Code paths: ${snapshot.dexFiles.joinToString()}")
                    appendLine("Root access: ${if (snapshot.rootAvailable) "available" else "not available"}")
                    if (snapshot.notes.isNotEmpty()) {
                        appendLine()
                        appendLine("LIMITATIONS / EVENTS")
                        snapshot.notes.forEach { appendLine("• $it") }
                    }
                }
                status.text = "Scan complete • ${if (snapshot.rootAvailable) "Root diagnostics enabled" else "Non-root diagnostics"}"
            }
        }
    }

    private fun saveLatest() {
        val snapshot = latest ?: run { status.text = "Run a scan first."; return }
        worker.execute {
            val file = engine.saveReport(snapshot)
            runOnUiThread { status.text = "JSON report saved: ${file.name}" }
        }
    }

    private fun uploadLatest() {
        val snapshot = latest ?: run { status.text = "Run a scan first."; return }
        val url = endpoint.text.toString().trim()
        if (url.isBlank()) { status.text = "Enter your HTTPS endpoint first."; return }
        status.text = "Uploading diagnostic report…"
        worker.execute {
            val result = engine.uploadReport(snapshot, url)
            runOnUiThread {
                status.text = result.fold(
                    { "Cloud response: HTTP $it" },
                    { "Upload failed: ${it.message}" }
                )
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
