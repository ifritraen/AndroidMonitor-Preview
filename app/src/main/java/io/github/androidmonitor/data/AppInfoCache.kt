package io.github.androidmonitor.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/** Caches app names and icons for the process list and utility pickers. */
class AppInfoCache(private val context: Context) {
    private val packageManager = context.packageManager

    // An empty string means "not an installed package".
    private val labels = ConcurrentHashMap<String, String>()
    private val icons = LruCache<String, ImageBitmap>(256)
    private val profileApkPaths = ConcurrentHashMap<String, String>()

    fun registerApkPaths(paths: Map<String, String>) {
        if (paths.isEmpty()) return
        for ((pkg, path) in paths) {
            profileApkPaths[pkg] = path
            // Clear negative ("") cache so secondary-profile apps resolve on next lookup.
            if (labels[pkg] == "") {
                labels.remove(pkg)
            }
        }
    }

    fun label(packageName: String): String? = labels.getOrPut(packageName) {
        try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            loadArchiveLabel(packageName) ?: ""
        }
    }.ifEmpty { null }

    fun icon(packageName: String): ImageBitmap? {
        icons.get(packageName)?.let { return it }
        return try {
            packageManager.getApplicationIcon(packageName)
                .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                .asImageBitmap()
                .also { icons.put(packageName, it) }
        } catch (e: PackageManager.NameNotFoundException) {
            loadArchiveIcon(packageName)?.also { icons.put(packageName, it) }
        }
    }

    private fun getArchiveAppInfo(packageName: String): ApplicationInfo? {
        val apkPath = profileApkPaths[packageName] ?: return null
        return runCatching {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkPath, 0)
            }
            pkgInfo?.applicationInfo?.apply {
                sourceDir = apkPath
                publicSourceDir = apkPath
            }
        }.getOrNull()
    }

    private fun loadArchiveLabel(packageName: String): String? {
        val appInfo = getArchiveAppInfo(packageName) ?: return null
        return runCatching {
            appInfo.loadLabel(packageManager).toString().ifBlank { null }
        }.getOrNull()
    }

    private fun loadArchiveIcon(packageName: String): ImageBitmap? {
        val appInfo = getArchiveAppInfo(packageName) ?: return null
        return runCatching {
            appInfo.loadIcon(packageManager)
                .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                .asImageBitmap()
        }.getOrNull()
    }

    fun installedApps(): List<InstalledAppInfo> {
        val selfPkg = context.packageName
        return runCatching {
            val byPkg = LinkedHashMap<String, InstalledAppInfo>()
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != selfPkg }
                .forEach { info ->
                    val lbl = info.loadLabel(packageManager).toString().ifBlank { info.packageName }
                    labels.putIfAbsent(info.packageName, lbl)
                    val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    byPkg[info.packageName] = InstalledAppInfo(
                        packageName = info.packageName,
                        label = lbl,
                        isSystem = isSystem,
                    )
                }
            for ((pkg, apkPath) in profileApkPaths) {
                if (pkg == selfPkg || byPkg.containsKey(pkg)) continue
                val appInfo = getArchiveAppInfo(pkg)
                val lbl = appInfo?.let { runCatching { it.loadLabel(packageManager).toString() }.getOrNull() }
                    ?.ifBlank { null } ?: pkg
                val isSystem = apkPath.startsWith("/system/") || apkPath.startsWith("/product/") || apkPath.startsWith("/vendor/")
                byPkg[pkg] = InstalledAppInfo(
                    packageName = pkg,
                    label = lbl,
                    isSystem = isSystem,
                )
            }
            byPkg.values
                .sortedWith(compareBy<InstalledAppInfo> { it.isSystem }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toList()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val ICON_SIZE_PX = 96
    }
}
