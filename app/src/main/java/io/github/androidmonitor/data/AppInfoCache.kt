package io.github.androidmonitor.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

    fun label(packageName: String): String? = labels.getOrPut(packageName) {
        try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
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
            null
        }
    }

    fun installedApps(): List<InstalledAppInfo> {
        val selfPkg = context.packageName
        return runCatching {
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != selfPkg }
                .map { info ->
                    val lbl = info.loadLabel(packageManager).toString().ifBlank { info.packageName }
                    labels.putIfAbsent(info.packageName, lbl)
                    val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    InstalledAppInfo(
                        packageName = info.packageName,
                        label = lbl,
                        isSystem = isSystem,
                    )
                }
                .sortedWith(compareBy<InstalledAppInfo> { it.isSystem }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .toList()
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val ICON_SIZE_PX = 96
    }
}
