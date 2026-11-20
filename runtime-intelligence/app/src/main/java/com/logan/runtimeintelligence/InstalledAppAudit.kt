package com.logan.runtimeintelligence

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import java.util.Locale

/**
 * Read-only inventory of packages visible to this user/profile.
 * Reports metadata and permission declarations; never reads app-private files or process memory.
 */
object InstalledAppAudit {
    @Suppress("DEPRECATION")
    fun createReport(context: Context, maxApps: Int = 250): String {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        val packages = try {
            pm.getInstalledPackages(flags)
        } catch (error: Exception) {
            return "Package inventory unavailable: ${error.message ?: error.javaClass.simpleName}"
        }

        val output = StringBuilder()
        output.append("INSTALLED APP SECURITY INVENTORY\n")
        output.append("Visible packages: ${packages.size}\n")
        output.append("Showing at most $maxApps non-system / user-installed entries.\n")
        output.append("This is manifest metadata, not a runtime or memory inspection.\n\n")

        val candidates = packages
            .filter {
                val appInfo = it.applicationInfo
                appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
            }
            .sortedBy { safeLabel(pm, it) }
            .take(maxApps)

        for (info in candidates) {
            val app = info.applicationInfo ?: continue
            val label = safeLabel(pm, info)
            output.append("▶ $label\n")
            output.append("  Package: ${info.packageName}\n")
            output.append("  Version: ${info.versionName ?: "unknown"}\n")
            val flagsText = mutableListOf<String>()
            if ((app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) flagsText.add("DEBUGGABLE")
            if ((app.flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY) != 0) flagsText.add("TEST_ONLY")
            if (flagsText.isNotEmpty()) output.append("  Build flags: ${flagsText.joinToString(", ")}\n")

            val requested = info.requestedPermissions.orEmpty()
            val requestedFlags = info.requestedPermissionsFlags
            val dangerous = requested.mapIndexedNotNull { index, permission ->
                try {
                    val permissionInfo = pm.getPermissionInfo(permission, 0)
                    val isDangerous = (permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) ==
                        PermissionInfo.PROTECTION_DANGEROUS
                    if (!isDangerous) null
                    else {
                        val granted = requestedFlags != null &&
                            index < requestedFlags.size &&
                            (requestedFlags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                        "${permission.substringAfterLast('.')}=${if (granted) "granted" else "not granted"}"
                    }
                } catch (_: Exception) { null }
            }
            if (dangerous.isNotEmpty()) output.append("  Dangerous permissions: ${dangerous.joinToString(", ")}\n")

            val exported = (info.activities?.count { it.exported } ?: 0) +
                (info.services?.count { it.exported } ?: 0) +
                (info.receivers?.count { it.exported } ?: 0) +
                (info.providers?.count { it.exported } ?: 0)
            output.append("  Exported components: $exported\n\n")
        }
        if (packages.size > candidates.size) {
            output.append("Inventory is capped for readability. System packages are excluded from this first view.\n")
        }
        output.append("\nInterpretation: a declared permission is not proof of misuse. Exported components may be intentionally public and should be checked for required permissions and input validation. DEBUGGABLE builds are useful for test environments but should not normally be shipped as production builds.\n")
        return output.toString()
    }

    @Suppress("DEPRECATION")
    private fun safeLabel(pm: PackageManager, info: PackageInfo): String =
        try {
            val appInfo = info.applicationInfo ?: return info.packageName
            pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        } catch (_: Exception) {
            info.packageName
        }
}
