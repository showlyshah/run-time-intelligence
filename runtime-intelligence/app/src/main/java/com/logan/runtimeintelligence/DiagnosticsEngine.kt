package com.logan.runtimeintelligence

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.app.usage.UsageStatsManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppTarget(val label: String, val packageName: String, val appInfo: ApplicationInfo) {
    override fun toString(): String = "$label ($packageName)"
}

data class RuntimeSnapshot(
    val packageName: String,
    val capturedAt: Long,
    val foregroundMs24h: Long,
    val lastUsed: Long,
    val pid: Int?,
    val rssKb: Long?,
    val pssKb: Long?,
    val privateDirtyKb: Long?,
    val sharedKb: Long?,
    val processState: String?,
    val apkPath: String,
    val apkBytes: Long,
    val apkSha256: String,
    val dexFiles: List<String>,
    val rootAvailable: Boolean,
    val notes: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("package", packageName)
        put("capturedAt", capturedAt)
        put("foregroundMs24h", foregroundMs24h)
        put("lastUsed", lastUsed)
        put("pid", pid ?: JSONObject.NULL)
        put("rssKb", rssKb ?: JSONObject.NULL)
        put("pssKb", pssKb ?: JSONObject.NULL)
        put("privateDirtyKb", privateDirtyKb ?: JSONObject.NULL)
        put("sharedKb", sharedKb ?: JSONObject.NULL)
        put("processState", processState ?: JSONObject.NULL)
        put("apkPath", apkPath)
        put("apkBytes", apkBytes)
        put("apkSha256", apkSha256)
        put("dexFiles", org.json.JSONArray(dexFiles))
        put("rootAvailable", rootAvailable)
        put("notes", org.json.JSONArray(notes))
    }
}

class DiagnosticsEngine(private val context: Context) {
    fun installedApps(): List<AppTarget> {
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return apps.filter { it.packageName != context.packageName }
            .map { AppTarget(pm.getApplicationLabel(it).toString(), it.packageName, it) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    fun scan(target: AppTarget): RuntimeSnapshot {
        val notes = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val usage = usageFor(target.packageName)
        if (usage == null) notes += "Usage access is not granted; enable Usage access to measure foreground time."
        val apk = File(target.appInfo.sourceDir)
        val hash = runCatching { sha256(apk) }.getOrElse {
            notes += "APK hash unavailable: ${it.message}"
            "unavailable"
        }
        val dexFiles = listOfNotNull(
            target.appInfo.sourceDir,
            target.appInfo.publicSourceDir
        ).distinct().filter { it.endsWith(".apk", true) || it.endsWith(".dex", true) }
        val root = rootAvailable()
        val proc = if (root) rootProcessStats(target.packageName) else null
        if (!root) notes += "Root not available. Android sandbox limits detailed private-process memory inspection; basic app usage and APK integrity scan remain available."
        if (root && proc == null) notes += "Could not read process details. The selected app may not currently be running."
        return RuntimeSnapshot(
            packageName = target.packageName,
            capturedAt = now,
            foregroundMs24h = usage?.first ?: 0L,
            lastUsed = usage?.second ?: 0L,
            pid = proc?.get("pid")?.toIntOrNull(),
            rssKb = proc?.get("VmRSS")?.toLongOrNull(),
            pssKb = proc?.get("VmPSS")?.toLongOrNull(),
            privateDirtyKb = proc?.get("VmData")?.toLongOrNull(),
            sharedKb = proc?.get("RssFile")?.toLongOrNull(),
            processState = proc?.get("State"),
            apkPath = apk.absolutePath,
            apkBytes = apk.length(),
            apkSha256 = hash,
            dexFiles = dexFiles,
            rootAvailable = root,
            notes = notes
        )
    }

    private fun usageFor(packageName: String): Pair<Long, Long>? {
        return runCatching {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val begin = end - TimeUnit.HOURS.toMillis(24)
            val entry = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
                .firstOrNull { it.packageName == packageName } ?: return@runCatching null
            entry.totalTimeInForeground to entry.lastTimeUsed
        }.getOrNull()
    }

    private fun rootAvailable(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = p.inputStream.bufferedReader().readText()
        p.waitFor() == 0 && output.contains("uid=0")
    }.getOrDefault(false)

    private fun rootProcessStats(packageName: String): Map<String, String>? = runCatching {
        val script = "pidof '$packageName' | awk '{print \$1}'"
        val pidResult = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
        val pid = pidResult.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull()
        pidResult.waitFor()
        if (pid.isNullOrBlank()) return null
        val read = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/$pid/status"))
        val text = read.inputStream.bufferedReader().readText()
        read.waitFor()
        val values = mutableMapOf("pid" to pid)
        text.lineSequence().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().substringBefore(" kB").trim()
                if (key in setOf("VmRSS", "VmPSS", "VmData", "RssFile", "State")) values[key] = value
            }
        }
        values
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun saveReport(snapshot: RuntimeSnapshot): File {
        val dir = File(context.getExternalFilesDir(null), "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(snapshot.capturedAt))
        return File(dir, "${snapshot.packageName}-$stamp.json").apply {
            writeText(snapshot.toJson().toString(2))
        }
    }

    fun uploadReport(snapshot: RuntimeSnapshot, endpoint: String): Result<Int> = runCatching {
        require(endpoint.startsWith("https://", true)) { "Cloud endpoint must use HTTPS." }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(snapshot.toJson().toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        connection.disconnect()
        code
    }
}
