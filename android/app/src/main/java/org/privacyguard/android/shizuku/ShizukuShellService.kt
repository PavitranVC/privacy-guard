package org.privacyguard.android.shizuku

import androidx.annotation.Keep
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.service.ShellResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Keep
class ShizukuShellService : IShellService.Stub() {

    private val pool = Executors.newFixedThreadPool(2)

    override fun exec(cmd: Array<out String>?): ShellResult {
        if (cmd == null || cmd.isEmpty()) {
            return ShellResult(-1, "", "Empty command")
        }

        val binary = cmd[0]
        if (!ALLOWED_BINARIES.contains(binary)) {
            return ShellResult(-1, "", "Binary not in allowlist: $binary")
        }

        return try {
            val process = Runtime.getRuntime().exec(cmd)
            // Immediately close stdin to avoid hanging processes waiting for input
            process.outputStream.close()

            // Drain stdout and stderr concurrently with a strict 256 KB cap
            val stdoutFuture = pool.submit<String> { drainStreamBounded(process.inputStream, STREAM_CAP_BYTES) }
            val stderrFuture = pool.submit<String> { drainStreamBounded(process.errorStream, STREAM_CAP_BYTES) }

            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val partialStdout = try { stdoutFuture.get(500, TimeUnit.MILLISECONDS) } catch (e: Exception) { "" }
                return ShellResult(-124, partialStdout, "TIMEOUT (10s exceeded)")
            }

            val stdout = stdoutFuture.get()
            val stderr = stderrFuture.get()
            val exitCode = process.exitValue()

            ShellResult(exitCode, stdout, stderr)
        } catch (e: Throwable) {
            ShellResult(-1, "", e.message ?: e.toString())
        }
    }

    override fun destroy() {
        try {
            pool.shutdownNow()
        } catch (ignored: Throwable) {}
        exitProcess(0)
    }

    private fun drainStreamBounded(stream: InputStream, maxBytes: Int): String {
        return stream.use { input ->
            val buffer = ByteArray(4096)
            val output = ByteArrayOutputStream()
            var totalRead = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                val toWrite = minOf(read, maxBytes - totalRead)
                if (toWrite > 0) {
                    output.write(buffer, 0, toWrite)
                    totalRead += toWrite
                }
                if (totalRead >= maxBytes) {
                    break
                }
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        private const val STREAM_CAP_BYTES = 262_144 // 256 KB max payload to prevent TransactionTooLargeException
        private val ALLOWED_BINARIES = setOf(
            "/system/bin/cmd", "cmd",
            "/system/bin/pm", "pm",
            "/system/bin/am", "am",
            "/system/bin/dumpsys", "dumpsys"
        )
    }
}
