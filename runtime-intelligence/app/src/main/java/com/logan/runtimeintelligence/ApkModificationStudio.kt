package com.logan.runtimeintelligence

import android.content.Context
import android.content.pm.PackageInfo
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import brut.androlib.ApkBuilder
import brut.androlib.ApkDecoder
import brut.androlib.android.ApktoolAndroid
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.w3c.dom.Element
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import javax.security.auth.x500.X500Principal
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class UrlFinding(
    val url: String,
    val host: String,
    val file: String,
    val line: Int,
    val category: String,
    val editable: Boolean,
    val confidence: String = "SAFE",
    val matchedSources: List<String> = emptyList()
)

data class PermissionFinding(
    val name: String,
    val maxSdkVersion: String? = null,
    val isAdvertisingId: Boolean = false
)

data class CodeFinding(
    val indicator: String,
    val file: String,
    val line: Int,
    val category: String,
    val detail: String
)

data class ApkStudioResult(
    val originalApk: File,
    val decodedDirectory: File,
    val packageName: String,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val versionCode: Int,
    val nativeAbis: Set<String>,
    val findings: List<UrlFinding>,
    val permissions: List<PermissionFinding>,
    val hostListsUpdated: Boolean,
    val hostListStatus: String
)

/**
 * APK decode / review / selected manifest and resource edits / rebuild / sign.
 * Only use on apps owned by the operator or explicitly authorised for modification.
 */
class ApkModificationStudio(private val context: Context) {
    private val root = File(context.filesDir, "apk-modification-studio").apply { mkdirs() }
    private val hostListSources = linkedMapOf(
        "AdAway" to "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
        "StevenBlack Unified" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "Yoyo ad servers" to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&mimetype=plaintext&useip=0.0.0.0"
    )

    fun inspect(source: Uri, progress: (Int, String) -> Unit): ApkStudioResult {
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
        val packageInfo = readPackageInfo(original) ?: error("Android could not read package metadata from this APK.")
        val appInfo = packageInfo.applicationInfo ?: error("APK application metadata is missing.")
        val packageName = packageInfo.packageName ?: error("APK package name is missing.")
        val version = packageInfo.versionName ?: "unknown"
        val minSdk = appInfo.minSdkVersion
        val targetSdk = appInfo.targetSdkVersion
        val versionCode = packageInfo.versionCode
        val nativeAbis = readNativeAbis(original)

        progress(12, "Initialising Android-safe Apktool engine")
        val config = ApktoolAndroid.configure(context).apply { isForced = true }
        val decoded = File(root, "decoded").apply { deleteRecursively(); mkdirs() }
        progress(18, "Decompiling manifest, resources and DEX to Smali")
        ApkDecoder(original, config).decode(decoded)

        progress(52, "Downloading current advertising / tracking host lists")
        val (blockedHosts, listStatus) = downloadHostLists(progress)
        progress(68, "Scanning resources and DEX-derived Smali references")
        val findings = scanUrls(decoded, blockedHosts)
        val permissions = readPermissions(File(decoded, "AndroidManifest.xml"))
        progress(78, "URL and permission review prepared")
        return ApkStudioResult(
            original, decoded, packageName, version, minSdk, targetSdk, versionCode,
            nativeAbis, findings, permissions, blockedHosts.isNotEmpty(), listStatus
        )
    }

    fun deepScan(result: ApkStudioResult, progress: (Int, String) -> Unit): List<CodeFinding> {
        progress(2, "Fast deep scan: indexing Smali files")
        val indicators = listOf(
            "AdvertisingIdClient" to "Advertising ID API", "getAdvertisingIdInfo" to "Advertising ID retrieval method",
            "com/google/android/gms/ads/" to "Google Mobile Ads SDK", "com/applovin/" to "AppLovin advertising SDK",
            "com/unity3d/ads/" to "Unity Ads SDK", "com/ironsource/" to "ironSource advertising SDK",
            "com/facebook/ads/" to "Meta Audience Network SDK", "com/mbridge/msdk/" to "Mintegral advertising SDK",
            "com/vungle/" to "Vungle advertising SDK", "com/chartboost/" to "Chartboost advertising SDK",
            "com/startapp/" to "StartApp advertising SDK", "com/inmobi/" to "InMobi advertising SDK",
            "google_ad_id" to "Advertising ID key string", "advertising_id" to "Advertising ID key string",
            "doubleclick" to "Ad network reference"
        )
        val base = result.decodedDirectory.toPath()
        val files = Files.walk(base).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".smali", true) }.limit(500).toList() }
        val output = mutableListOf<CodeFinding>()
        var bytesRead = 0L
        var scanned = 0
        for (path in files) {
            if (output.size >= 250 || bytesRead >= 24L * 1024L * 1024L) break
            val file = path.toFile()
            if (file.length() <= 0L || file.length() > 2L * 1024L * 1024L) continue
            try {
                file.bufferedReader(Charsets.UTF_8).use { reader ->
                    var lineNumber = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        lineNumber++
                        bytesRead += line.length.toLong() + 1L
                        if (bytesRead >= 24L * 1024L * 1024L || output.size >= 250) break
                        for ((needle, label) in indicators) {
                            if (line.contains(needle, ignoreCase = true)) {
                                output.add(CodeFinding(needle, base.relativize(path).toString(), lineNumber, label, "Known ad / identifier indicator. Review this method and its callers before patching."))
                                if (output.size >= 250) break
                            }
                        }
                    }
                }
            } catch (_: Exception) { /* Skip unreadable individual files. */ }
            scanned++
            if (scanned % 20 == 0) progress((scanned * 95 / files.size.coerceAtLeast(1)).coerceIn(5, 95), "Fast scan: $scanned / ${files.size} files • ${output.size} matches")
        }
        progress(100, "Fast scan complete • $scanned files • ${output.size} indicators")
        return output.distinctBy { "${it.file}:${it.line}:${it.indicator}" }
    }

    fun listSmaliFiles(result: ApkStudioResult, maxFiles: Int = 300): List<String> {
        val base = result.decodedDirectory.toPath()
        return Files.walk(base).use { paths -> paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".smali", true) }.limit(maxFiles.toLong()).map { base.relativize(it).toString() }.toList() }
    }

    fun saveSmaliPatch(result: ApkStudioResult, relativePath: String, updatedText: String): String {
        require(relativePath.endsWith(".smali", true)) { "Only Smali files can be patched." }
        val base = result.decodedDirectory.canonicalFile
        val target = File(base, relativePath).canonicalFile
        require(target.toPath().startsWith(base.toPath())) { "Invalid Smali path." }
        require(target.isFile && target.length() <= 2L * 1024L * 1024L) { "Smali file is missing or exceeds 2 MB." }
        require(updatedText.length <= 2 * 1024 * 1024) { "Edited Smali file exceeds 2 MB." }
        require(Regex("(?m)^\\s*\\.class\\s+").containsMatchIn(updatedText)) { "Patch rejected: .class declaration is missing." }
        require(Regex("(?m)^\\s*\\.super\\s+").containsMatchIn(updatedText)) { "Patch rejected: .super declaration is missing." }
        val backup = File(target.parentFile, target.name + ".runtime-intelligence.bak")
        if (!backup.exists()) target.copyTo(backup)
        val temp = File(target.parentFile, target.name + ".patch.tmp")
        temp.writeText(updatedText, Charsets.UTF_8)
        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
        return backup.relativeTo(base).path
    }
    fun rebuildAndSign(
        result: ApkStudioResult,
        selectedUrls: Set<String>,
        selectedPermissions: Set<String>,
        progress: (Int, String) -> Unit
    ): File {
        progress(80, "Applying only individually selected URL removals")
        val urlChanges = removeSelectedUrls(result.decodedDirectory, selectedUrls)
        progress(83, "Removing only user-selected manifest permissions")
        val permissionChanges = removeSelectedPermissions(
            File(result.decodedDirectory, "AndroidManifest.xml"), selectedPermissions
        )
        progress(86, "Recompiling Android resources and Smali")
        val unsigned = File(root, "rebuilt-unsigned.apk").apply { delete() }
        ApkBuilder(result.decodedDirectory, ApktoolAndroid.configure(context).apply { isForced = true }).build(unsigned)
        require(unsigned.isFile && unsigned.length() > 0) { "Rebuild did not produce an APK." }

        progress(92, "Signing with your device-protected Runtime Intelligence key")
        val signed = File(root, "rebuilt-signed.apk").apply { delete() }
        sign(unsigned, signed)
        progress(96, "Verifying signature and compatibility metadata")
        val verification = ApkVerifier.Builder(signed).build().verify()
        require(verification.isVerified) { "APK signature verification failed: ${verification.errors.joinToString("; ")}" }
        val rebuiltInfo = readPackageInfo(signed) ?: error("Rebuilt APK metadata could not be read.")
        val rebuiltApp = rebuiltInfo.applicationInfo ?: error("Rebuilt APK application metadata is missing.")
        require(rebuiltInfo.packageName == result.packageName) { "Package identity changed during rebuild; output rejected." }
        require(rebuiltApp.minSdkVersion == result.minSdk) { "Minimum Android version changed during rebuild; output rejected." }
        require(rebuiltApp.targetSdkVersion == result.targetSdk) { "Target Android version changed during rebuild; output rejected." }
        require(readNativeAbis(signed) == result.nativeAbis) { "Native ABI set changed during rebuild; output rejected." }
        progress(100, "Complete • ${urlChanges} URL(s) and ${permissionChanges} permission(s) changed; signed APK verified")
        return signed
    }

    private fun downloadHostLists(progress: (Int, String) -> Unit): Pair<Map<String, Set<String>>, String> {
        val all = mutableMapOf<String, MutableSet<String>>()
        val successful = mutableListOf<String>()
        hostListSources.forEach { (name, address) ->
            runCatching {
                val connection = URL(address).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", "RuntimeIntelligence/1.0 (ad-host-review)")
                connection.instanceFollowRedirects = true
                try {
                    require(connection.responseCode in 200..299) { "$name returned HTTP ${connection.responseCode}" }
                    connection.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val clean = line.substringBefore("#").trim()
                            val host = when {
                                clean.isEmpty() -> ""
                                clean.startsWith("||") -> clean.removePrefix("||").substringBefore('^').substringBefore('/').trim('.')
                                clean.contains(' ') -> clean.split(Regex("\\s+")).lastOrNull().orEmpty()
                                clean.matches(Regex("[A-Za-z0-9.-]+")) -> clean
                                else -> ""
                            }.lowercase(Locale.ROOT)
                            if (host.contains('.') && !host.startsWith("www.") && host.length < 254 &&
                                host.matches(Regex("[a-z0-9.-]+")) && !host.startsWith("-") && !host.endsWith("-")) {
                                all.getOrPut(host) { mutableSetOf() }.add(name)
                            }
                        }
                    }
                    successful.add(name)
                } finally {
                    connection.disconnect()
                }
            }.onFailure { progress(58, "Host list $name unavailable • using remaining sources and heuristics") }
        }
        val status = if (successful.isEmpty()) "Online host lists unavailable; heuristic-only classification"
            else "Updated from ${successful.joinToString(" + ")} • ${all.size} unique blocked domains"
        return all.mapValues { it.value.toSet() } to status
    }

    private fun scanUrls(decoded: File, blockedHosts: Map<String, Set<String>>): List<UrlFinding> {
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
                bytes.toString(Charsets.UTF_8).split('\n').forEachIndexed { index, line ->
                    urlRegex.findAll(line).forEach inner@{ match ->
                        val raw = match.value.trimEnd('.', ',', ')', ']', '}')
                        val host = runCatching { java.net.URI(raw).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return@inner
                        val matches = matchingBlockedHosts(host, blockedHosts)
                        val matchedSources = matches.flatMap { blockedHosts[it].orEmpty() }.distinct()
                        val confidence = when {
                            matchedSources.any { it == "AdAway" || it == "Yoyo ad servers" } -> "CONFIRMED"
                            matchedSources.isNotEmpty() || isAdHeuristic(host) -> "POSSIBLE"
                            else -> "SAFE"
                        }
                        findings.add(
                            UrlFinding(raw, host, decoded.toPath().relativize(path).toString(), index + 1,
                                when (confidence) {
                                    "CONFIRMED" -> "Ad-server list match"
                                    "POSSIBLE" -> "Possible ad / tracker or broad blocklist match"
                                    else -> "No ad-list match"
                                }, ext in editableExtensions, confidence, matchedSources)
                        )
                    }
                }
            }
        }
        return findings.distinctBy { "${it.file}:${it.line}:${it.url}" }.take(1500)
    }

    private fun matchingBlockedHosts(host: String, blockedHosts: Map<String, Set<String>>): List<String> {
        val matches = mutableListOf<String>()
        var candidate = host
        while (candidate.contains('.')) {
            if (candidate in blockedHosts) matches.add(candidate)
            candidate = candidate.substringAfter('.')
        }
        return matches.take(3)
    }

    private fun isAdHeuristic(host: String): Boolean {
        val tokens = listOf("doubleclick", "googlesyndication", "adservice", "adnxs", "adsystem",
            "adform", "unityads", "applovin", "ironsource", "analytics", "app-measurement",
            "mixpanel", "segment.io", "amplitude", "adjust.com", "branch.io", "crashlytics",
            "adserver", "advertising", "tracking", "tracker")
        return tokens.any { host.contains(it) }
    }

    private fun readPermissions(manifest: File): List<PermissionFinding> {
        if (!manifest.isFile) return emptyList()
        val factory = secureDocumentFactory()
        val document = secureDocumentBuilder(factory).parse(manifest)
        val nodes = document.getElementsByTagName("*")
        val output = mutableListOf<PermissionFinding>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.tagName !in setOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")) continue
            val name = element.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                .ifBlank { element.getAttribute("android:name") }
            if (name.isNotBlank()) {
                val maxSdk = element.getAttributeNS("http://schemas.android.com/apk/res/android", "maxSdkVersion").ifBlank { null }
                output.add(PermissionFinding(name, maxSdk, name == "com.google.android.gms.permission.AD_ID"))
            }
        }
        return output.distinctBy { it.name }
    }

    private fun removeSelectedPermissions(manifest: File, selected: Set<String>): Int {
        if (selected.isEmpty() || !manifest.isFile) return 0
        val document = secureDocumentBuilder(secureDocumentFactory()).parse(manifest)
        val nodes = document.getElementsByTagName("*")
        val remove = mutableListOf<org.w3c.dom.Node>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.tagName !in setOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")) continue
            val name = element.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                .ifBlank { element.getAttribute("android:name") }
            if (name in selected) remove.add(element)
        }
        remove.forEach { it.parentNode.removeChild(it) }
        if (remove.isNotEmpty()) {
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                setOutputProperty(OutputKeys.INDENT, "yes")
            }
            transformer.transform(DOMSource(document), StreamResult(manifest))
        }
        return remove.size
    }

    /**
     * Android ships different XML parser implementations across API levels and OEM builds.
     * Treat hardening features as best-effort: an unsupported optional feature must not abort
     * APK decoding. The builder's resolver still prevents external entity retrieval.
     */
    private fun secureDocumentFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { isXIncludeAware = false }
        runCatching { isExpandEntityReferences = false }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    }

    private fun secureDocumentBuilder(factory: DocumentBuilderFactory) =
        factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }

    private fun removeSelectedUrls(decoded: File, selectedUrls: Set<String>): Int {
        if (selectedUrls.isEmpty()) return 0
        val extensions = setOf("xml", "json", "txt", "properties", "html", "htm", "css", "js", "yaml", "yml")
        var replacements = 0
        Files.walk(decoded.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val file = path.toFile()
                if (file.extension.lowercase(Locale.ROOT) !in extensions || file.length() > 4L * 1024L * 1024L) return@forEach
                val original = runCatching { Files.readAllBytes(path).toString(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                var updated = original
                selectedUrls.forEach { url ->
                    if (updated.contains(url)) {
                        updated = updated.replace(url, "")
                        replacements++
                    }
                }
                if (updated != original) Files.write(path, updated.toByteArray(Charsets.UTF_8))
            }
        }
        return replacements
    }

    private fun readPackageInfo(apk: File): PackageInfo? {
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
    }

    private fun readNativeAbis(apk: File): Set<String> = ZipFile(apk).use { zip ->
        val entries = zip.entries()
        buildSet {
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("lib/")) name.split("/").getOrNull(1)?.let { add(it) }
            }
        }
    }

    private fun sign(unsigned: File, output: File) {
        val alias = "runtime-intelligence-apk-signing-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
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
        val signer = ApkSigner.SignerConfig.Builder("Runtime Intelligence", privateKey, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(output)
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
            .setV4SigningEnabled(false).build().sign()
    }
}
