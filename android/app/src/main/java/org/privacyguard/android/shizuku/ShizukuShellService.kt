package org.privacyguard.android.shizuku

import androidx.annotation.Keep
import org.privacyguard.android.service.IShellService
import org.privacyguard.android.service.ShellResult
import java.io.BufferedReader
import kotlin.system.exitProcess

@Keep
class ShizukuShellService : IShellService.Stub() {

    override fun exec(cmd: Array<out String>): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = process.waitFor()
            ShellResult(exitCode, stdout, stderr)
        } catch (e: Throwable) {
            ShellResult(-1, "", e.message ?: e.toString())
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
