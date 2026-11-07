package io.github.androidmonitor.privileged

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        val result = withTimeoutOrNull(timeoutMs) {
            synchronized(lock) {
                if (!ensureAlive()) return@withTimeoutOrNull null
                val marker = "__AM_DONE_${UUID.randomUUID()}__"
                try {
                    writer.write(command)
                    writer.write("\n")
                    writer.write("echo \"$marker \$?\"\n")
                    writer.flush()

                    val output = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.contains(marker)) {
                            return@withTimeoutOrNull output.toString().trimEnd('\n', '\r')
                        }
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(line)
                    }
                    null
                } catch (e: Exception) {
                    null
                }
            }
        }
        // Fallback to single-shot su -c if the interactive shell failed or timed out
        result ?: runProcess("su", "-c", command)
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
            "su --mount-master",
            "su -mm",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
        )

        private fun startProcess(command: String): Process? {
            val parts = command.split(" ").filter { it.isNotEmpty() }

            // Strategy 1: ProcessBuilder with extended PATH
            try {
                val pb = ProcessBuilder(parts).redirectErrorStream(true)
                val env = pb.environment()
                val curPath = env["PATH"] ?: System.getenv("PATH") ?: ""
                env["PATH"] = "$curPath:/system/bin:/system/xbin:/sbin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin"
                val proc = pb.start()
                return proc
            } catch (_: Exception) {}

            // Strategy 2: Direct Runtime.getRuntime().exec
            try {
                return Runtime.getRuntime().exec(parts.toTypedArray())
            } catch (_: Exception) {}

            return null
        }

        fun isSuBinaryPresent(): Boolean = true

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

            // Fallback: check if direct su -c id succeeds
            for (cmd in listOf("su", "/system/bin/su", "/system/xbin/su", "su -mm")) {
                val directOutput = withTimeoutOrNull(GRANT_TIMEOUT_MS) {
                    runProcess(cmd, "-c", "id")
                }
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
