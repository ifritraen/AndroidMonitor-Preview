package io.github.androidmonitor.privileged

import java.io.File

/** Runs shell commands with more rights than a normal app: Shizuku's shell user, or root. */
interface PrivilegedShell {
    /** Returns the command's stdout, or null if it couldn't be run. */
    suspend fun exec(command: String): String?
}

private const val MARKER = "@@"

/** Reads several files in one shell round trip. Missing or unreadable files are left out. */
suspend fun PrivilegedShell.readFiles(paths: Collection<String>): Map<String, String> {
    if (paths.isEmpty()) return emptyMap()
    val script = paths.joinToString("; ") { "printf '\\n$MARKER%s\\n' '$it'; cat '$it' 2>/dev/null" }
    val output = exec(script) ?: return emptyMap()
    return output.split("\n$MARKER").drop(1).mapNotNull { chunk ->
        val path = chunk.substringBefore('\n')
        val value = chunk.substringAfter('\n', "").trim()
        if (value.isEmpty()) null else path to value
    }.toMap()
}

val ANDROID_SHELL_PATH: String = listOf(
    "/sbin",
    "/system/sbin",
    "/system/bin",
    "/system/xbin",
    "/odm/bin",
    "/vendor/bin",
    "/data/adb/magisk",
    "/data/adb/ksu/bin",
    "/data/adb/ap/bin",
).let { custom ->
    val sys = System.getenv("PATH")?.split(':') ?: emptyList()
    (custom + sys).distinct().joinToString(":")
}

/** Runs a process and returns its stdout, or null if it couldn't be started. */
internal fun runProcess(vararg argv: String, timeoutMs: Long = 10_000L): String? = try {
    val parts = if (argv.isNotEmpty() && argv[0].contains(" ")) {
        val split0 = argv[0].split(" ").filter { it.isNotEmpty() }
        (split0 + argv.slice(1 until argv.size)).toTypedArray()
    } else {
        arrayOf(*argv)
    }
    val pb = ProcessBuilder(*parts).apply {
        redirectErrorStream(true)
        environment()["PATH"] = ANDROID_SHELL_PATH
    }
    val process = pb.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val finished = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } else {
        process.waitFor()
        true
    }
    if (finished) output else {
        process.destroy()
        null
    }
} catch (e: Exception) {
    null
}

