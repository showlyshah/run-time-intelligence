package com.logan.runtimeintelligence

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
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
            text = "Local security diagnostics • User-controlled"
            textSize = 14f
            setPadding(0, 12, 0, 20)
        }
        content.addView(status)

        content.addView(sectionTitle("Sandbox & app inspection"))
        content.addView(infoText("Inspect APK files without installing them. Audit installed apps' declared permissions, debug flags and exported components. This app does not read other apps' private memory, files, passwords or cryptographic keys."))
        content.addView(actionButton("Import APK for static analysis") {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.android.package-archive"
            }
            startActivityForResult(picker, APK_PICK_REQUEST)
        })
        content.addView(actionButton("Audit installed user apps") {
            status.text = "Building local package inventory…"
            Thread {
                val result = InstalledAppAudit.createReport(this)
                runOnUiThread {
                    report.text = result
                    status.text = "Installed-app audit complete • metadata only"
                }
            }.start()
        })

        content.addView(sectionTitle("Special access & administration"))
        content.addView(infoText("Grant only the access you need. Android requires you to approve special access in Settings. Device Owner and Work Profile ownership require managed-device provisioning; this app cannot grant itself administrator status."))
        content.addView(actionButton("Open Usage Access settings") {
            openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        content.addView(actionButton("Work Profile setup guide") { showWorkProfileGuide() })
        content.addView(actionButton("Open this app's system settings") {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        content.addView(sectionTitle("Memory & network diagnostics"))
        content.addView(infoText("Memory figures below belong to Runtime Intelligence itself. Per-app network figures are Android's aggregate UID counters; they do not expose packet contents or remote endpoints."))
        content.addView(actionButton("Refresh device and network diagnostics") { refreshDiagnostics() })

        report = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, 16, 0, 24)
        }
        content.addView(actionButton("Export current report (JSON)") { exportReport() })
        content.addView(report)

        setContentView(ScrollView(this).apply { addView(content) })
        refreshDiagnostics()
    }

    private fun infoText(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setPadding(0, 4, 0, 12)
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 20f
        setPadding(0, 20, 0, 6)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            status.text = "This Settings page is not available on this device."
        }
    }

    @Deprecated("Deprecated in Android API; retained for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(JSONObject()
                        .put("app", "Runtime Intelligence")
                        .put("exportedAtEpochMs", System.currentTimeMillis())
                        .put("report", report.text.toString())
                        .toString(2).toByteArray(Charsets.UTF_8))
                } ?: error("Unable to open destination")
                status.text = "Report exported to the selected location"
            } catch (error: Exception) {
                status.text = "Export failed: ${error.message ?: error.javaClass.simpleName}"
            }
            return
        }
        if (requestCode == APK_PICK_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            status.text = "Analysing selected APK locally…"
            Thread {
                val result = try {
                    ApkStaticAnalyzer.analyze(this, uri)
                } catch (error: Exception) {
                    "APK analysis failed: ${error.message ?: error.javaClass.simpleName}"
                }
                runOnUiThread {
                    report.text = result
                    status.text = "Static APK inspection finished • APK was not executed"
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::report.isInitialized) refreshDiagnostics()
    }

    private fun exportReport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "runtime-intelligence-report.json")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    private fun showWorkProfileGuide() {
        report.text = """WORK PROFILE SETUP GUIDE

1. Back up important personal data first.
2. If your organisation provides Android Enterprise enrollment, follow its official QR-code, enrollment-token or invitation flow.
3. On a personally managed device, check Android Settings > Passwords & accounts (or Accounts) > Add work account, if your device and account support it.
4. Complete the prompts from your organisation's management provider. Android creates the Work Profile and its policy controller becomes Profile Owner.
5. Install Runtime Intelligence separately inside the Work Profile from an approved source.
6. Open this app inside that profile and refresh diagnostics. Inventory is scoped to the current Android user/profile.

This app cannot create or appoint itself Profile Owner. Menu names and availability vary by device and organisation. Removing a Work Profile may delete managed apps and data; follow your administrator's instructions.

A Work Profile is not root access. Android still protects each app's private memory, files and credentials from other apps."""
        status.text = "Work Profile guidance displayed"
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

        output.append("AVAILABLE PRIVILEGES / MANAGEMENT STATE\n")
        output.append("Usage Access: ${if (hasUsageAccess()) "GRANTED" else "not granted"}\n")
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        output.append("Device Owner: ${if (dpm.isDeviceOwnerApp(packageName)) "YES" else "no"}\n")
        output.append("Profile Owner: ${if (dpm.isProfileOwnerApp(packageName)) "YES" else "no"}\n")
        val userManager = getSystemService(USER_SERVICE) as UserManager
        output.append("Running inside managed Work Profile: ${if (userManager.isManagedProfile) "YES" else "no"}\n")
        output.append("Note: administrator / profile-owner privileges require explicit enterprise provisioning.\n\n")

        output.append("ACTIVE NETWORK\n")
        output.append(readNetworkState())
        output.append("\n")

        output.append("INSTALLED-APP NETWORK USAGE (LAST 24 HOURS)\n")
        if (!hasUsageAccess()) {
            output.append("Enable Usage Access above, return here, then refresh.\n")
            status.text = "Some diagnostics need additional user-approved access"
        } else {
            output.append(readLauncherAppNetworkUsage())
            status.text = "Diagnostics refreshed • local collection only"
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

    private fun readNetworkState(): String {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "No active network reported.\n"
        val caps = cm.getNetworkCapabilities(network) ?: return "Network capabilities unavailable.\n"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
        val link = cm.getLinkProperties(network)
        return "Transport: $kind\nInterface: ${link?.interfaceName ?: "unknown"}\nDNS servers: ${link?.dnsServers?.joinToString { it.hostAddress ?: "?" } ?: "not available"}\n"
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
            for (networkType in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
                try {
                    val stats = manager.queryDetailsForUid(networkType, null, start, end, uid)
                    try {
                        val bucket = NetworkStats.Bucket()
                        while (stats.hasNextBucket()) {
                            if (stats.getNextBucket(bucket)) {
                                received += bucket.rxBytes
                                sent += bucket.txBytes
                            }
                        }
                    } finally {
                        stats.close()
                    }
                } catch (_: Exception) {
                    // Network type may not be available on this device.
                }
            }
            if (received > 0 || sent > 0) {
                val label = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(appPackage, 0)).toString()
                } catch (_: Exception) { appPackage }
                lines.add(Triple(label, received, sent))
            }
        }

        if (lines.isEmpty()) return "No per-app traffic buckets returned for this period.\n"
        return lines.sortedByDescending { it.second + it.third }
            .joinToString("\n") { (label, rx, tx) ->
                "$label\n  Downloaded: ${formatBytes(rx)} • Uploaded: ${formatBytes(tx)}"
            } + "\n\nAggregate bytes only; not destination IPs or packet payloads. Device support and UID attribution vary."
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val MB = 1024.0 * 1024.0
        private const val APK_PICK_REQUEST = 4102
        private const val EXPORT_REQUEST = 4103
    }
}
