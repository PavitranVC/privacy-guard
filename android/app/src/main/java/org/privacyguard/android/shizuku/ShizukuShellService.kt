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

    private val pool = Executors.newCachedThreadPool()

    override fun exec(cmd: Array<out String>?): ShellResult {
        if (cmd == null || cmd.isEmpty()) {
            return ShellResult(-1, "", "Empty command")
        }

        val binary = cmd[0].removePrefix("/system/bin/")
        val subcmd = if (cmd.size > 1) cmd[1] else ""

        val pair = "$binary $subcmd".trim()
        val isAllowed = ALLOWED_COMMAND_PREFIXES.any { pair == it || pair.startsWith("$it ") } ||
                (ALLOWED_BINARIES.contains(binary) && (subcmd == "help" || subcmd == "--help"))

        if (!isAllowed) {
            return ShellResult(-1, "", "Command not in allowlist: $pair")
        }

        // Validate package and component arguments
        for (i in 2 until cmd.size) {
            val arg = cmd[i]
            if (arg.startsWith("-")) continue
            if (arg.contains("/")) {
                if (!COMPONENT_REGEX.matches(arg)) {
                    return ShellResult(-1, "", "Invalid component argument: $arg")
                }
            } else if (!arg.all { it.isDigit() } &&
                       !PKG_REGEX.matches(arg) &&
                       !ALLOWED_KEYWORDS.contains(arg)) {
                return ShellResult(-1, "", "Invalid package or keyword argument: $arg")
            }
        }

        return try {
            val process = Runtime.getRuntime().exec(cmd)
            process.outputStream.close()

            // Drain stdout and stderr concurrently until EOF while capping retained output at 256 KB
            val stdoutFuture = pool.submit<String> { drainStreamBounded(process.inputStream, STREAM_CAP_BYTES) }
            val stderrFuture = pool.submit<String> { drainStreamBounded(process.errorStream, STREAM_CAP_BYTES) }

            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val partialStdout = try { stdoutFuture.get(500, TimeUnit.MILLISECONDS) } catch (_: Exception) { "" }
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
        } catch (_: Throwable) {}
        exitProcess(0)
    }

    private fun drainStreamBounded(stream: InputStream, maxBytes: Int): String {
        return stream.use { input ->
            val buffer = ByteArray(4096)
            val output = ByteArrayOutputStream()
            var totalRead = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break // Keep reading until EOF to prevent SIGPIPE on writer
                val toWrite = minOf(read, maxOf(0, maxBytes - totalRead))
                if (toWrite > 0) {
                    output.write(buffer, 0, toWrite)
                    totalRead += toWrite
                }
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    companion object {
        private const val STREAM_CAP_BYTES = 262_144 // 256 KB cap
        private val PKG_REGEX = Regex("^[a-zA-Z0-9_.]+$")
        private val COMPONENT_REGEX = Regex("^[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+$")

        private val ALLOWED_BINARIES = setOf("cmd", "pm", "am", "dumpsys")

        private val ALLOWED_COMMAND_PREFIXES = setOf(
            "cmd connectivity",
            "cmd appops",
            "cmd package",
            "pm disable-user",
            "pm default-state",
            "pm enable",
            "pm disable",
            "am kill",
            "am set-standby-bucket",
            "dumpsys package",
            "dumpsys activity exit-info",
            "dumpsys activity"
        )

        private val ALLOWED_KEYWORDS = setOf(
            "restricted", "rare", "ignore", "true", "false", "exit-info",
            "set-chain3-enabled", "set-package-networking-enabled",
            "oem_deny_3", "add", "remove", "set", "get", "RUN_ANY_IN_BACKGROUND"
        )
    }
}
