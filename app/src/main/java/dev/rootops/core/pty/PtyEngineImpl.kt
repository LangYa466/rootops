package dev.rootops.core.pty

import dev.rootops.core.api.PtySession
import dev.rootops.core.api.TerminalEngine

/**
 * [TerminalEngine] backed by the forked pty implementation in `librootops_pty.so`.
 *
 * Each [open] call forks a real shell process attached to its own pseudo
 * terminal, which is what lets `su`, `top`, `vi` and friends behave normally.
 */
class PtyEngineImpl : TerminalEngine {

    override fun open(
        shell: String,
        args: List<String>,
        cwd: String?,
        env: Map<String, String>,
        cols: Int,
        rows: Int,
    ): PtySession {
        if (!PtyNative.available) {
            throw IllegalStateException("pty unavailable")
        }

        val envArray = env.map { (key, value) -> "$key=$value" }.toTypedArray()
        val masterFd = PtyNative.openPty(
            shell = shell,
            args = args.toTypedArray(),
            cwd = cwd,
            env = envArray,
            cols = cols,
            rows = rows,
        )
        if (masterFd < 0) {
            throw IllegalStateException("pty unavailable")
        }

        val childPid = PtyNative.getChildPid(masterFd)
        if (childPid <= 0) {
            PtyNative.closePty(masterFd)
            throw IllegalStateException("pty unavailable")
        }

        return PtySessionImpl(masterFd, childPid)
    }
}
