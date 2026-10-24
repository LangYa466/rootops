package dev.rootops.core.pty

/**
 * Thin JNI bridge to `librootops_pty.so` (see `app/src/main/cpp/pty.c`).
 *
 * The library is loaded lazily and defensively: on an ABI we did not ship, or if
 * the `.so` is missing from the APK, [available] stays `false` and the rest of the
 * app can fall back to a non-terminal view instead of crashing.
 *
 * Every function here is fail-safe: invalid fds and out-of-range lengths are
 * rejected in C and reported as negative return values.
 */
object PtyNative {

    /** True when `librootops_pty.so` was loaded and the natives are linkable. */
    val available: Boolean = try {
        System.loadLibrary("rootops_pty")
        true
    } catch (t: Throwable) {
        // UnsatisfiedLinkError (missing ABI), SecurityException, OOM on dlopen...
        false
    }

    /**
     * Forks a shell on a new pseudo terminal.
     *
     * @param shell absolute path (`/system/bin/sh`) or a bare name resolved against PATH.
     * @param args arguments passed after argv[0].
     * @param cwd working directory, or null to inherit the app's cwd.
     * @param env `KEY=VALUE` entries; TERM/PATH/HOME defaults are added when absent.
     * @return master fd (non-blocking) or -1 on failure.
     */
    @JvmStatic
    external fun openPty(
        shell: String,
        args: Array<String>,
        cwd: String?,
        env: Array<String>,
        cols: Int,
        rows: Int,
    ): Int

    /**
     * @return bytes read into [buf] (at most [len]); 0 when the pty is idle
     *         (EAGAIN); -1 on EOF or error.
     */
    @JvmStatic
    external fun readPty(fd: Int, buf: ByteArray, len: Int): Int

    /**
     * @return bytes written; 0 when the pty buffer is full (retry later);
     *         -1 on error.
     */
    @JvmStatic
    external fun writePty(fd: Int, buf: ByteArray, len: Int): Int

    /** Applies TIOCSWINSZ. @return 0 on success, -1 on failure. */
    @JvmStatic
    external fun resizePty(fd: Int, cols: Int, rows: Int): Int

    /** Child pid for a master fd, or -1 when the fd is unknown. */
    @JvmStatic
    external fun getChildPid(fd: Int): Int

    /**
     * Blocks until the child exits and reaps it.
     *
     * @return the exit code, 128 + signal number when killed by a signal,
     *         or -1 when the fd is unknown/already reaped.
     */
    @JvmStatic
    external fun waitChild(fd: Int): Int

    /** Closes the master fd and reaps the child if it is already gone. */
    @JvmStatic
    external fun closePty(fd: Int): Int
}
