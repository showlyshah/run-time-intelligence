package com.logan.runtimeintelligence

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var report: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 40, 36, 36)
        }

        content.addView(TextView(this).apply {
            text = "Runtime Intelligence"
            textSize = 27f
        })
        status = TextView(this).apply {
            text = "Local diagnostics • Monitoring is off until you request a refresh"
            textSize = 14f
            setPadding(0, 12, 0, 20)
        }
        content.addView(status)

        content.addView(sectionTitle("Memory intelligence"))
        content.addView(TextView(this).apply {
            text = "Inspects this app's own process only. Android does not allow ordinary apps to read another app's private heap or secret keys."
            textSize = 14f
            setPadding(0, 4, 0, 12)
        })

        val usageAccess = Button(this).apply {
            text = "Grant usage access for app statistics"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        content.addView(usageAccess)

        val refresh = Button(this).apply {
            text = "Refresh diagnostics"
            setOnClickListener { refreshDiagnostics() }
        }
        content.addView(refresh)

        report = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 24)
        }
        content.addView(report)

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        refreshDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        if (::report.isInitialized) refreshDiagnostics()
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 20f
        setPadding(0, 18, 0, 6)
    }

    private fun refreshDiagnostics() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val device = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(device)

        val processMemory = Debug.MemoryInfo()
        Debug.getMemoryInfo(processMemory)
        val runtime = Runtime.getRuntime()
        val appHeapUsed = runtime.totalMemory() - runtime.freeMemory()

        val output = StringBuilder()
        output.append("DEVICE MEMORY\n")
        output.append(String.format(
            Locale.getDefault(),
            "Total RAM: %.0f MB\nAvailable RAM: %.0f MB\nLow-memory state: %s\n\n",
            device.totalMem / MB,
            device.availMem / MB,
            if (device.lowMemory) "Yes" else "No"
        ))

        output.append("THIS APP'S PROCESS\n")
        output.append(String.format(
            Locale.getDefault(),
            "Java heap used: %.1f MB\nJava heap limit: %.1f MB\nProcess PSS: %.1f MB\nNative heap allocated: %.1f MB\n\n",
            appHeapUsed / MB,
            runtime.maxMemory() / MB,
            processMemory.totalPss / 1024.0,
            Debug.getNativeHeapAllocatedSize() / MB
        ))

        output.append("INSTALLED-APP NETWORK USAGE (LAST 24 HOURS)\n")
        if (!hasUsageAccess()) {
            output.append("Usage access is not enabled. Tap the access button above, enable Runtime Intelligence, then return and refresh.\n")
            status.text = "Waiting for your explicit Usage Access permission"
        } else {
            output.append(readLauncherAppNetworkUsage())
            status.text = "Updated just now • On-demand local statistics"
        }

        report.text = output.toString()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun readLauncherAppNetworkUsage(): String {
        val manager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = end - 24L * 60L * 60L * 1000L
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launchIntent, 0)
            .mapNotNull { resolve ->
                val info = resolve.activityInfo.applicationInfo
                info.packageName to info.uid
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        if (apps.isEmpty()) return "No launcher apps were returned by Android.\n"

        val lines = mutableListOf<Triple<String, Long, Long>>()
        for ((appPackage, uid) in apps) {
            var received = 0L
            var sent = 0L
            for (networkType in intArrayOf(
                ConnectivityManager.TYPE_WIFI,
                ConnectivityManager.TYPE_MOBILE
            )) {
                var stats: NetworkStats? = null
                try {
                    stats = manager.querySummaryForUid(networkType, null, start, end, uid)
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        if (stats.getNextBucket(bucket)) {
                            received += bucket.rxBytes
                            sent += bucket.txBytes
                        }
                    }
                } catch (_: Exception) {
                    // Some devices/carriers do not expose a bucket for this network type.
                } finally {
                    stats?.close()
                }
            }
            if (received > 0 || sent > 0) {
                val label = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                } catch (_: Exception) {
                    appPackage
                }
                lines.add(Triple(label, received, sent))
            }
        }

        if (lines.isEmpty()) {
            return "Android returned no per-app traffic buckets for this period.\n"
        }
        return lines.sortedByDescending { it.second + it.third }
            .joinToString("\n") { (label, rx, tx) ->
                "$label\n  Downloaded: ${formatBytes(rx)} • Uploaded: ${formatBytes(tx)}"
            } + "\n\nNote: Android usage statistics show aggregate bytes, not packet contents, destination IPs, or decrypted HTTPS messages. Attribution and availability vary by device."
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val MB = 1024.0 * 1024.0
    }
}
