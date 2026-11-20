package com.logan.runtimeintelligence

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import brut.androlib.ApkBuilder
import brut.androlib.ApkDecoder
import brut.androlib.android.ApktoolAndroid
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import javax.security.auth.x500.X500Principal

data class UrlFinding(
    val url: String,
    val host: String,
    val file: String,
    val line: Int,
    val category: String,
    val editable: Boolean
)

data class ApkStudioResult(
    val originalApk: File,
    val decodedDirectory: File,
    val packageName: String,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val findings: List<UrlFinding>
)

/**
 * Local APK decode / review / resource-string patch / rebuild / sign pipeline.
 * Intended only for APKs the operator owns or is authorised to modify.
 */
class ApkModificationStudio(private val context: Context) {
    private val root = File(context.filesDir, "apk-modification-studio").apply { mkdirs() }

    fun inspect(
        source: Uri,
        progress: (Int, String) -> Unit
    ): ApkStudioResult {
        progress(2, "Preparing a private workspace")
        val original = File(root, "original.apk")
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Cannot read the selected APK." }.use { stream ->
                original.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 250L * 1024L * 1024L) { "APK exceeds the 250 MB limit." }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        require(original.length() > 0) { "Selected APK is empty." }
        progress(8, "Reading original package metadata")
        val packageInfo = readPackageInfo(original)
            ?: error("Android could not read package metadata from this APK.")
        val appInfo = packageInfo.applicationInfo ?: error("APK application metadata is missing.")
        val packageName = packageInfo.packageName ?: error("APK package name is missing.")
        val version = packageInfo.versionName ?: "unknown"
        val minSdk = appInfo.minSdkVersion
        val targetSdk = appInfo.targetSdkVersion

        progress(12, "Initialising Android-safe Apktool engine")
        val config = ApktoolAndroid.configure(context).apply { isForced = true }
        val decoded = File(root, "decoded").apply {
            deleteRecursively()
            mkdirs()
        }
        progress(18, "Decompiling manifest, resources and DEX to Smali")
        ApkDecoder(original, config).decode(decoded)

        progress(62, "Scanning decoded resources and code for URL references")
        val findings = scan(decoded)
        progress(78, "URL review list prepared")
        return ApkStudioResult(original, decoded, packageName, version, minSdk, targetSdk, findings)
    }

    fun rebuildAndSign(
        result: ApkStudioResult,
        selectedHosts: Set<String>,
        progress: (Int, String) -> Unit
    ): File {
        progress(80, "Applying selected URL removals to editable text/resources")
        val changed = removeSelectedResourceUrls(result.decodedDirectory, selectedHosts)
        progress(84, "Recompiling Android resources and Smali")
        val unsigned = File(root, "rebuilt-unsigned.apk").apply { delete() }
        ApkBuilder(result.decodedDirectory, ApktoolAndroid.configure(context).apply {
            isForced = true
        }).build(unsigned)
        require(unsigned.isFile && unsigned.length() > 0) { "Rebuild did not produce an APK." }

        progress(91, "Signing with your device-protected Runtime Intelligence key")
        val signed = File(root, "rebuilt-signed.apk").apply { delete() }
        sign(unsigned, signed)
        progress(96, "Verifying APK signature and package compatibility metadata")
        val verification = ApkVerifier.Builder(signed).build().verify()
        require(verification.isVerified) {
            "APK signature verification failed: ${verification.errors.joinToString("; ")}"
        }
        val rebuiltInfo = readPackageInfo(signed)
            ?: error("Rebuilt APK metadata could not be read.")
        val rebuiltApp = rebuiltInfo.applicationInfo ?: error("Rebuilt APK application metadata is missing.")
        require(rebuiltInfo.packageName == result.packageName) {
            "Package identity changed during rebuild; output rejected."
        }
        require(rebuiltApp.minSdkVersion == result.minSdk) {
            "Minimum Android version changed during rebuild; output rejected."
        }
        require(rebuiltApp.targetSdkVersion == result.targetSdk) {
            "Target Android version changed during rebuild; output rejected."
        }
        progress(100, "Complete: signed APK verified • $changed URL reference(s) changed")
        return signed
    }

    private fun readPackageInfo(apk: File): android.content.pm.PackageInfo? {
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
    }

    private fun scan(decoded: File): List<UrlFinding> {
        val findings = mutableListOf<UrlFinding>()
        val urlRegex = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        val editableExtensions = setOf("xml", "json", "txt", "properties", "html", "htm", "css", "js", "yaml", "yml")
        val codeExtensions = setOf("smali")
        var bytesScanned = 0L
        Files.walk(decoded.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val file = path.toFile()
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext !in editableExtensions && ext !in codeExtensions) return@forEach
                if (file.length() > 4L * 1024L * 1024L || bytesScanned > 80L * 1024L * 1024L) return@forEach
                val bytes = Files.readAllBytes(path)
                bytesScanned += bytes.size
                val text = bytes.toString(Charsets.UTF_8)
                text.split('\\n').forEachIndexed { index, line ->
                    urlRegex.findAll(line).forEach { match ->
                        val raw = match.value.trimEnd('.', ',', ')', ']', '}')
                        val host = runCatching { java.net.URI(raw).host?.lowercase(Locale.ROOT) }.getOrNull()
                            ?: return@forEach
                        findings.add(
                            UrlFinding(
                                url = raw,
                                host = host,
                                file = decoded.toPath().relativize(path).toString(),
                                line = index + 1,
                                category = classify(host),
                                editable = ext in editableExtensions
                            )
                        )
                    }
                }
            }
        }
        return findings.distinctBy { "${it.file}:${it.line}:${it.url}" }.take(500)
    }

    private fun classify(host: String): String {
        val h = host.lowercase(Locale.ROOT)
        return when {
            listOf("doubleclick", "googlesyndication", "adservice", "adnxs", "adsystem", "adform", "unityads", "applovin", " ironsource").any { h.contains(it.trim()) } -> "Likely advertising"
            listOf("analytics", "app-measurement", "mixpanel", "segment.io", "amplitude", "adjust.com", "branch.io", "crashlytics").any { h.contains(it) } -> "Likely analytics / tracking"
            listOf("phishing", "malware", "malicious").any { h.contains(it) } -> "Suspicious name heuristic"
            else -> "Unclassified URL"
        }
    }

    private fun removeSelectedResourceUrls(decoded: File, selectedHosts: Set<String>): Int {
        if (selectedHosts.isEmpty()) return 0
        val extensions = setOf("xml", "json", "txt", "properties", "html", "htm", "css", "js", "yaml", "yml")
        val urlRegex = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        var replacements = 0
        Files.walk(decoded.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val file = path.toFile()
                if (file.extension.lowercase(Locale.ROOT) !in extensions || file.length() > 4L * 1024L * 1024L) return@forEach
                val original = runCatching { Files.readString(path) }.getOrNull() ?: return@forEach
                val updated = urlRegex.replace(original) { match ->
                    val raw = match.value.trimEnd('.', ',', ')', ']', '}')
                    val host = runCatching { java.net.URI(raw).host?.lowercase(Locale.ROOT) }.getOrNull()
                    if (host != null && selectedHosts.contains(host)) {
                        replacements++
                        ""
                    } else match.value
                }
                if (updated != original) Files.writeString(path, updated)
            }
        }
        return replacements
    }

    private fun sign(unsigned: File, output: File) {
        val alias = "runtime-intelligence-apk-signing-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Runtime Intelligence APK Signing"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365L * 86_400_000L))
                    .build()
            )
            generator.generateKeyPair()
        }
        val privateKey = store.getKey(alias, null) as PrivateKey
        val certificate = store.getCertificate(alias) as X509Certificate
        val signer = ApkSigner.SignerConfig.Builder(
            "Runtime Intelligence",
            privateKey,
            listOf(certificate)
        ).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()
    }
}
