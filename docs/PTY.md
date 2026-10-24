# RootOps PTY backend

The embedded terminal is a real pseudo terminal: every session forks a shell
attached to a pty pair, so job control, `su`, `top`, `vi` and signal handling
behave exactly as in `adb shell`.

```
Compose terminal view
        │  ByteArray in / out
PtySessionImpl  (dev.rootops.core.pty)   ── Kotlin, coroutines
        │  JNI
librootops_pty.so (app/src/main/cpp/pty.c) ── C, one fork per session
        │
   /dev/ptmx  ⇄  /dev/pts/N  ⇄  shell (pid)
```

## Why no `forkpty()`

bionic does not provide `forkpty()`/`openpty()` (no `libutil`), so `pty.c`
implements the sequence by hand:

1. `posix_openpt(O_RDWR|O_NOCTTY)` → `grantpt()` → `unlockpt()` → `ptsname_r()`
2. open the slave, put it in **raw mode** (the emulator owns echo/line editing),
   apply the initial `TIOCSWINSZ`
3. `fork()`
   * child: `setsid()` → `ioctl(TIOCSCTTY)` → `dup2(slave, 0/1/2)` → `chdir(cwd)`
     → `execve()`. If the shell was given as a bare name it is resolved against
     `PATH` and each candidate is tried in turn.
   * parent: close the slave, set the master **non-blocking** + `FD_CLOEXEC`,
     register `fd → pid`.
4. Failure in the child is reported back through the pty itself
   (`rootops: unable to execute shell`) before `_exit(127)`.

All allocation (argv, envp, candidate paths) happens in the **parent before
`fork()`**, so the child only issues async-signal-safe syscalls — no `malloc`,
no stdio, no JNI in a forked multi-threaded process.

## JNI surface

Class `dev.rootops.core.pty.PtyNative` (Kotlin `object`, `@JvmStatic external fun`):

| Function | Returns |
| --- | --- |
| `openPty(shell, args, cwd, env, cols, rows)` | master fd, or `-1` |
| `readPty(fd, buf, len)` | `>0` bytes, `0` = EAGAIN (idle), `-1` = EOF/error |
| `writePty(fd, buf, len)` | `>0` written, `0` = full (retry), `-1` = error |
| `resizePty(fd, cols, rows)` | `0` / `-1` (`TIOCSWINSZ`) |
| `getChildPid(fd)` | pid, or `-1` |
| `waitChild(fd)` | exit code, `128+signal`, or `-1` |
| `closePty(fd)` | `0` / `-1` |

`JNI_OnLoad` returns `JNI_VERSION_1_6`. The second JNI argument is unused, so the
same symbol serves a static native (`@JvmStatic`, class object) and an instance
native (object instance) — both resolve to `Java_dev_rootops_core_pty_PtyNative_*`
with no `__` signature suffix (no overloads).

Safety rules in C: fds are validated with `fcntl(F_GETFD)` (stale fd → `-1`, never
`close()`-on-garbage), lengths are clamped to both the Java array length and the
16 KB thread-local bounce buffer, `NULL` arrays are rejected, and `EINTR` is retried.

## Kotlin behaviour

* `PtyNative.available` — `System.loadLibrary` in a `try/catch`; a missing `.so`
  degrades to `available == false` instead of crashing at class-load time.
* `PtyEngineImpl.open()` — throws `IllegalStateException("pty unavailable")` when
  the library is missing or `openPty` fails.
* `PtySessionImpl.output` — cold `Flow<ByteArray>`, `flowOn(Dispatchers.IO)`,
  8 KB reads, `emit`s non-empty chunks, sleeps ~8 ms on EAGAIN, ends on EOF.
  A `Mutex` keeps it single-reader: a second collector waits instead of
  splitting the byte stream. Collect it once per session.
* `PtySessionImpl.write` — copies the caller's buffer, serialises through a
  mutex, retries partial writes and EAGAIN.
* `PtySessionImpl.exited` — `StateFlow<Boolean>`, set when the reader hits EOF,
  when the `waitChild` watcher returns, or on `close()`.
* `PtySessionImpl.close()` — idempotent; closes the fd once and cancels the scope.

### Zombies

A watcher coroutine parks in `waitpid(pid, 0)` for the life of the session, so
the child is reaped even if `output` is never collected. `closePty()` also does a
bounded (~100 ms) `WNOHANG` reap, which covers closing the master while the child
is still alive (closing the master delivers `SIGHUP` to the foreground process
group). `close()` therefore returns promptly; a child that ignores `SIGHUP` keeps
its waiting thread until it dies.

### Known trade-offs

* Closing the master while a reader is inside `read(2)` is racy in the classic
  POSIX sense; the fd is `O_NONBLOCK` so the window is a single syscall, and the
  reader re-checks its flags after every read.
* `waitChild` blocks a `Dispatchers.IO` thread per session (elastically sized pool).
* `chdir(cwd)` is best effort: a missing directory falls back to the inherited cwd.

## Build

```bash
scripts/build-native.sh          # all ABIs
scripts/build-native.sh --clean  # remove old .so first
NDK=/path/to/ndk scripts/build-native.sh
```

Produces `app/src/main/jniLibs/<abi>/librootops_pty.so` for
`arm64-v8a`, `armeabi-v7a`, `x86_64` with the NDK r29 clang
(`<triple>26-clang`, `minSdk 26`), `-O2 -Wall -Wextra -Werror`, and
`-Wl,-z,max-page-size=16384` so the library is 16 KB page-size compatible.
No Gradle/CMake step is involved: the `.so` files are packaged as prebuilt
`jniLibs`, matching `abiFilters` in `app/build.gradle.kts`.

Verify:

```bash
file app/src/main/jniLibs/*/librootops_pty.so
nm -D app/src/main/jniLibs/arm64-v8a/librootops_pty.so | grep Java_dev_rootops
```
