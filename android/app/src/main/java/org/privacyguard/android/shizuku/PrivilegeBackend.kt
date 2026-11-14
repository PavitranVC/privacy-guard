package org.privacyguard.android.shizuku

import org.privacyguard.android.service.IShellService
import org.privacyguard.android.service.ShellResult

/**
 * Common interface for executing privileged shell commands.
 *
 * Every backend must enforce the same command allowlist as ShizukuShellService.validateCommand before executing.
 */
interface PrivilegeBackend {
    val id: String
    fun exec(cmd: Array<String>): ShellResult
}

class ShizukuBackend(private val shellService: IShellService) : PrivilegeBackend {
    override val id: String = "shizuku"

    override fun exec(cmd: Array<String>): ShellResult {
        return shellService.exec(cmd)
    }
}
