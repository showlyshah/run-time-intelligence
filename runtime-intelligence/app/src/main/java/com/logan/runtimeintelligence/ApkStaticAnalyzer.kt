package com.logan.runtimeintelligence

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

/**
 * Performs bounded, static inspection of a user-selected APK.
 * It never installs or executes the APK and never prints candidate secret values.
 */
object ApkStaticAnalyzer {
    fun analyze(context: Context, uri: Uri): String {
        val apk = File.createTempFile("selected-apk-", ".apk", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open the selected APK." }
                apk.outputStream().use { output -> input.copyTo(output) }
            }
            require(apk.length() in 1..150L * 1024L * 1024L) {
                "APK is empty or exceeds the 150 MB analysis limit."
            }

            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS
            )

            val output = StringBuilder()
            output.append("STATIC APK INSPECTION — NOT EXECUTED\n")
            if (info == null) {
                output.append("Android could not parse this file as an APK.\n")
                return output.toString()
            }

            output.append("Package: ${info.packageName}\n")
            output.append("Version: ${info.versionName ?: "unknown"}\n")
            output.append("Requested permissions: ${info.requestedPermissions?.size ?: 0}\n")
            info.requestedPermissions?.take(40)?.forEach { output.append(" • $it\n") }

            fun exportedCount(items: Array<out android.content.pm.ComponentInfo>?): Int =
                items?.count { it.exported } ?: 0

            output.append("\nExported components declared in manifest:\n")
            output.append("Activities: ${exportedCount(info.activities)}\n")
            output.append("Services: ${exportedCount(info.services)}\n")
            output.append("Receivers: ${exportedCount(info.receivers)}\n")
            output.append("Providers: ${exportedCount(info.providers)}\n")

            val urlHosts = linkedSetOf<String>()
            var ipMatches = 0
            var secretIndicators = 0
            var dexFiles = 0
            var scannedBytes = 0L
            val urlRegex = Regex("https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]{4,}", RegexOption.IGNORE_CASE)
            val ipRegex = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
            val secretRegex = Regex(
                "(?i)(api[_-]?key|client[_-]?secret|access[_-]?token|private[_-]?key|password|passwd)\\s*[:=]\\s*[\"']?[A-Za-z0-9_./+=-]{8,}"
            )
            val awsRegex = Regex("AKIA[0-9A-Z]{16}")

            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements() && scannedBytes < 100L * 1024L * 1024L) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val isDex = entry.name.matches(Regex("classes([0-9]+)?\\.dex"))
                    val isConfig = entry.name.endsWith(".xml", true) ||
                        entry.name.endsWith(".json", true) ||
                        entry.name.endsWith(".properties", true) ||
                        entry.name.endsWith(".txt", true)
                    if (!isDex && !isConfig) continue
                    if (isDex) dexFiles++
                    val remaining = (100L * 1024L * 1024L - scannedBytes).coerceAtMost(30L * 1024L * 1024L)
                    val bytes = zip.getInputStream(entry).use { it.readNBytes(remaining.toInt()) }
                    scannedBytes += bytes.size
                    val text = bytes.toString(Charsets.ISO_8859_1)

                    urlRegex.findAll(text).forEach { match ->
                        try {
                            URI(match.value.trimEnd('.', ')', ']', '}')).host?.let { urlHosts.add(it.lowercase()) }
                        } catch (_: Exception) { }
                    }
                    ipMatches += ipRegex.findAll(text).count()
                    secretIndicators += secretRegex.findAll(text).count()
                    secretIndicators += awsRegex.findAll(text).count()
                    if (secretIndicators > 500) secretIndicators = 500
                }
            }

            output.append("\nCODE / NETWORK INDICATORS (static string scan)\n")
            output.append("DEX files scanned: $dexFiles\n")
            output.append("Archive bytes scanned: $scannedBytes\n")
            output.append("Distinct URL hostnames: ${urlHosts.size}\n")
            urlHosts.take(40).forEach { output.append(" • $it\n") }
            output.append("IPv4-like string occurrences: $ipMatches\n")
            output.append("Possible hard-coded secret patterns: $secretIndicators (values intentionally hidden)\n")
            output.append("\nLimitations: string scanning can miss obfuscated, encrypted, dynamically generated, or native-code values and can produce false positives. It does not reveal runtime keys, decrypt HTTPS, or prove that an endpoint is contacted.\n")
            output.toString()
        } finally {
            apk.delete()
        }
    }
}
