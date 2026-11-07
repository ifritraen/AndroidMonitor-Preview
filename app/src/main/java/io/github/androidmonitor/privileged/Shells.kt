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
import kotlin.concurrent.thread

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
    private val suPath: String,
) : PrivilegedShell, Closeable {

    private var writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private var reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    private val isClosed = AtomicBoolean(false)
    private val lock = Any()

    init {
        startStderrDrainer(process)
    }

    private fun startStderrDrainer(proc: Process) {
        thread(isDaemon = true, name = "RootShell-StderrDrainer") {
            try {
                proc.errorStream.bufferedReader().forEachLine { /* drain to prevent pipe blocking */ }
            } catch (_: Exception) {}
        }
    }

    private fun ensureAlive(): Boolean {
        if (isClosed.get()) return false
        if (process.isAlive) return true
        return try {
            val newProc = startProcess(suPath) ?: return false
            process = newProc
            writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            startStderrDrainer(process)
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun exec(command: String): String? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            synchronized(lock) {
                if (!ensureAlive()) return@withTimeoutOrNull null
                val marker = "__CMD_DONE_${UUID.randomUUID()}__"
                try {
                    writer.write(command)
                    writer.newLine()
                    writer.write("printf '\\n%s %s\\n' '$marker' \"$?\"")
                    writer.newLine()
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
        private const val GRANT_TIMEOUT_MS = 20_000L

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/magisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "su",
        )

        fun isSuBinaryPresent(): Boolean {
            return SU_PATHS.any { path ->
                if (path == "su") {
                    val pathEnv = System.getenv("PATH") ?: return@any false
                    pathEnv.split(':').any { dir ->
                        val f = File(dir, "su")
                        f.exists() && f.canExecute()
                    }
                } else {
                    val f = File(path)
                    f.exists() && f.canExecute()
                }
            }
        }

        private fun findSuPath(): String? {
            for (path in SU_PATHS) {
                if (path == "su") {
                    val pathEnv = System.getenv("PATH") ?: continue
                    for (dir in pathEnv.split(':')) {
                        val f = File(dir, "su")
                        if (f.exists() && f.canExecute()) return "su"
                    }
                } else {
                    val f = File(path)
                    if (f.exists() && f.canExecute()) return path
                }
            }
            return "su"
        }

        private fun startProcess(suPath: String): Process? = try {
            ProcessBuilder(suPath, "--mount-master")
                .redirectErrorStream(false)
                .start()
        } catch (_: Exception) {
            try {
                ProcessBuilder(suPath)
                    .redirectErrorStream(false)
                    .start()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Opens an interactive root shell and verifies uid=0.
         * Allows up to 20 seconds for the user to grant root in Magisk/KernelSU/APatch.
         */
        suspend fun open(): RootShell? = withContext(Dispatchers.IO) {
            val suPath = findSuPath() ?: return@withContext null
            val proc = startProcess(suPath) ?: return@withContext null
            val shell = RootShell(proc, suPath)
            val result = withTimeoutOrNull(GRANT_TIMEOUT_MS) {
                shell.exec("id")
            }
            if (result?.contains("uid=0") == true) {
                shell
            } else {
                shell.close()
                null
            }
        }

        suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
            val shell = open()
            val available = shell != null
            shell?.close()
            available
        }
    }
}
