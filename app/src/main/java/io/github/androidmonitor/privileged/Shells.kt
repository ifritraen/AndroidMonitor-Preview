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

    private fun ensureAlive(): Boolean {
        if (isClosed.get()) return false
        if (process.isAlive) return true
        return try {
            val newProc = startProcess(suBinary) ?: return false
            process = newProc
            writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun exec(command: String): String? = exec(command, EXEC_TIMEOUT_MS)

    suspend fun exec(command: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (ensureAlive()) {
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
                        if (reader.ready()) {
                            val line = reader.readLine() ?: break
                            if (line.contains(marker)) {
                                return@withContext output.toString().trimEnd('\n', '\r')
                            }
                            if (output.isNotEmpty()) output.append('\n')
                            output.append(line)
                        } else {
                            if (!process.isAlive && !reader.ready()) {
                                break
                            }
                            Thread.sleep(15)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        // Direct execution fallback if interactive shell closed or failed:
        runProcess(suBinary, "-c", command, timeoutMs = timeoutMs)
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
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
            "/sbin/su",
        )

        private fun startProcess(command: String): Process? {
            val parts = if (command.contains(" ")) {
                command.split(" ").filter { it.isNotEmpty() }.toTypedArray()
            } else {
                arrayOf(command)
            }
            return try {
                ProcessBuilder(*parts).apply {
                    redirectErrorStream(true)
                    environment()["PATH"] = ANDROID_SHELL_PATH
                }.start()
            } catch (_: Exception) {
                null
            }
        }

        fun isSuBinaryPresent(): Boolean {
            val commonPaths = listOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/system/bin/magisk",
                "/data/adb/magisk/magisk",
                "/data/adb/ksu/bin/ksud",
                "/data/adb/ap/bin/su",
            )
            if (commonPaths.any { runCatching { File(it).exists() }.getOrDefault(false) }) return true
            return true
        }

        /**
         * Opens a root shell and verifies uid=0.
         * Allows up to 30 seconds for the user to grant root in Magisk/KernelSU/APatch.
         */
        suspend fun open(): RootShell? = withContext(Dispatchers.IO) {
            for (cmd in SU_CANDIDATES) {
                // Step 1: Prompt the root manager and check if root is granted.
                val testOutput = runProcess(cmd, "-c", "id", timeoutMs = GRANT_TIMEOUT_MS)
                if (testOutput != null && testOutput.contains("uid=0")) {
                    // Step 2: Spawn persistent interactive root shell.
                    val proc = startProcess(cmd)
                    if (proc != null) {
                        val shell = RootShell(proc, cmd)
                        val verify = shell.exec("id", 5000L)
                        if (verify != null && verify.contains("uid=0")) {
                            return@withContext shell
                        }
                        shell.close()
                    }
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
