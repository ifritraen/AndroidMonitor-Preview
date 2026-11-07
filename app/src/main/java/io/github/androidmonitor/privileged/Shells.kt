package io.github.androidmonitor.privileged

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuShell(private val service: IShellService) : PrivilegedShell {
    override suspend fun exec(command: String): String? = withContext(Dispatchers.IO) {
        try {
            service.exec(command)
        } catch (e: Exception) {
            null
        }
    }
}

class RootShell private constructor(
    private var process: Process,
    private val suBinary: String,
) : PrivilegedShell, Closeable {

    private var writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private var reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    private val isClosed = AtomicBoolean(false)
    private val lock = Any()

    init {
        drainStderr(process)
    }

    private fun drainStderr(proc: Process) {
        Thread {
            try {
                val errReader = proc.errorStream.bufferedReader()
                while (!isClosed.get()) {
                    val line = errReader.readLine() ?: break
                }
            } catch (_: Exception) {}
        }.apply {
            isDaemon = true
            name = "RootShell-StderrDrainer"
            start()
        }
    }

    private fun ensureAlive(): Boolean {
        if (isClosed.get()) return false
        if (process.isAlive) return true
        return try {
            val newProc = startProcess(suBinary) ?: return false
            process = newProc
            writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            drainStderr(newProc)
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun exec(command: String): String? = exec(command, EXEC_TIMEOUT_MS)

    suspend fun exec(command: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!ensureAlive()) {
                return@withContext runProcess(suBinary, "-c", command)
            }
            val marker = "__AM_DONE_${UUID.randomUUID().toString().replace("-", "")}__"
            try {
                writer.write(command)
                writer.write("\n")
                writer.write("echo \"$marker \$?\"\n")
                writer.flush()

                val deadline = System.currentTimeMillis() + timeoutMs
                val output = StringBuilder()
                while (System.currentTimeMillis() < deadline) {
                    if (isClosed.get()) return@withContext null
                    if (reader.ready() || process.inputStream.available() > 0) {
                        val line = reader.readLine() ?: break
                        if (line.contains(marker)) {
                            return@withContext output.toString().trimEnd('\n', '\r')
                        }
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(line)
                    } else {
                        if (!process.isAlive && process.inputStream.available() == 0) {
                            break
                        }
                        Thread.sleep(25)
                    }
                }
            } catch (_: Exception) {}
        }
        runProcess(suBinary, "-c", command)
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            synchronized(lock) {
                try {
                    writer.write("exit\n")
                    writer.flush()
                } catch (_: Exception) {}
                try {
                    process.destroy()
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val EXEC_TIMEOUT_MS = 10_000L
        private const val GRANT_TIMEOUT_MS = 30_000L

        private val SU_CANDIDATES = listOf(
            "su --mount-master",
            "su -mm",
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/system/bin/magisk su",
            "magisk su",
            "/sbin/su",
        )

        private fun startProcess(command: String): Process? {
            val parts = command.split(" ").filter { it.isNotEmpty() }
            try {
                return Runtime.getRuntime().exec(parts.toTypedArray())
            } catch (_: Exception) {}

            try {
                val pb = ProcessBuilder(parts)
                return pb.start()
            } catch (_: Exception) {}

            try {
                val pb = ProcessBuilder(parts)
                val env = pb.environment()
                val curPath = env["PATH"] ?: System.getenv("PATH") ?: ""
                env["PATH"] = "$curPath:/system/bin:/system/xbin:/sbin:/data/adb/magisk"
                return pb.start()
            } catch (_: Exception) {}

            return null
        }

        fun isSuBinaryPresent(): Boolean {
            val commonPaths = listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/bin/magisk",
                "/data/adb/magisk",
                "/data/adb/ksu",
                "/data/adb/ap",
            )
            if (commonPaths.any { File(it).exists() }) return true
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
                val text = p.inputStream.bufferedReader().use { it.readText().trim() }
                p.waitFor()
                text.isNotEmpty()
            } catch (_: Exception) {
                true
            }
        }

        /**
         * Opens an interactive root shell and verifies uid=0.
         * Allows up to 30 seconds for the user to grant root in Magisk/KernelSU/APatch.
         */
        suspend fun open(): RootShell? = withContext(Dispatchers.IO) {
            for (cmd in SU_CANDIDATES) {
                val proc = startProcess(cmd) ?: continue
                val shell = RootShell(proc, cmd)
                val result = shell.exec("id", GRANT_TIMEOUT_MS)
                if (result != null && result.contains("uid=0")) {
                    return@withContext shell
                }
                shell.close()
            }

            for (cmd in listOf("su --mount-master", "su", "/system/bin/su", "/system/xbin/su")) {
                val directOutput = runProcess(cmd, "-c", "id")
                if (directOutput != null && directOutput.contains("uid=0")) {
                    val fallbackProc = startProcess(cmd)
                    if (fallbackProc != null) {
                        return@withContext RootShell(fallbackProc, cmd)
                    }
                }
            }
            null
        }

        suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
            val shell = open()
            val available = shell != null
            shell?.close()
            available
        }
    }
}
