/*
 * RootOps PTY backend.
 *
 * Implements a forkpty()-equivalent without libutil, because bionic does not
 * ship forkpty()/openpty() on all API levels and we want a single code path:
 *
 *   posix_openpt() -> grantpt() -> unlockpt() -> ptsname_r()
 *   -> fork() -> child: setsid()/TIOCSCTTY/dup2/execve()
 *
 * Everything the child needs (argv, envp, candidate shell paths) is built in
 * the parent BEFORE fork(), so the child only executes async-signal-safe
 * syscalls (no malloc, no JNI, no stdio).
 *
 * The parent keeps the master fd in non-blocking mode. A per-fd table maps the
 * master fd to the child pid so waitpid() can be driven from Java.
 */

/* bionic always exposes these POSIX declarations; glibc needs the feature
 * macro, which also lets this file be built on the host for testing. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define RO_PID_TABLE_SIZE 1024
#define RO_READ_CHUNK 16384
#define RO_DEFAULT_COLS 80
#define RO_DEFAULT_ROWS 24
#define RO_DEFAULT_PATH "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:/odm/bin"
#define RO_DEFAULT_TERM "xterm-256color"
#define RO_REAP_POLLS 100 /* bounded WNOHANG reap on close: ~100ms worst case */
#define RO_REAP_SLEEP_US 1000

/* ------------------------------------------------------------------ */
/* fd -> child pid table                                               */
/* ------------------------------------------------------------------ */

static pid_t g_pids[RO_PID_TABLE_SIZE];
static pthread_mutex_t g_pid_lock = PTHREAD_MUTEX_INITIALIZER;

static void pid_table_set(int fd, pid_t pid) {
    if (fd < 0 || fd >= RO_PID_TABLE_SIZE) {
        return;
    }
    pthread_mutex_lock(&g_pid_lock);
    g_pids[fd] = pid;
    pthread_mutex_unlock(&g_pid_lock);
}

static pid_t pid_table_get(int fd) {
    pid_t pid;
    if (fd < 0 || fd >= RO_PID_TABLE_SIZE) {
        return -1;
    }
    pthread_mutex_lock(&g_pid_lock);
    pid = g_pids[fd];
    pthread_mutex_unlock(&g_pid_lock);
    return pid;
}

static pid_t pid_table_take(int fd) {
    pid_t pid;
    if (fd < 0 || fd >= RO_PID_TABLE_SIZE) {
        return -1;
    }
    pthread_mutex_lock(&g_pid_lock);
    pid = g_pids[fd];
    g_pids[fd] = -1;
    pthread_mutex_unlock(&g_pid_lock);
    return pid;
}

/* An fd we never handed out, or one that has already been closed, must not be
 * touched: fcntl(F_GETFD) fails with EBADF for stale descriptors. */
static int fd_is_live(int fd) {
    if (fd < 0 || fd >= RO_PID_TABLE_SIZE) {
        return 0;
    }
    return fcntl(fd, F_GETFD) >= 0;
}

/*
 * Bounded WNOHANG reap: closing the pty master delivers SIGHUP to the
 * foreground process group, so a child normally dies within a few ms. Used on
 * paths that cannot block (close) or that must not leak a zombie (open failure).
 */
static void reap_bounded(pid_t pid) {
    int i;

    if (pid <= 0) {
        return;
    }
    for (i = 0; i < RO_REAP_POLLS; i++) {
        int status = 0;
        pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid || (r < 0 && errno != EINTR)) {
            return;
        }
        usleep(RO_REAP_SLEEP_US);
    }
}

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

static void free_vec(char **vec, int n) {
    int i;
    if (vec == NULL) {
        return;
    }
    for (i = 0; i < n; i++) {
        free(vec[i]);
    }
    free(vec);
}

/* Converts a Java string array into a NULL-terminated, malloc'ed char*[]. */
static char **jstring_array_to_vec(JNIEnv *env, jobjectArray array, int *out_count) {
    jsize len;
    jsize i;
    char **vec;

    *out_count = 0;
    if (array == NULL) {
        return NULL;
    }
    len = (*env)->GetArrayLength(env, array);
    if (len <= 0) {
        return NULL;
    }
    vec = (char **)calloc((size_t)len + 1, sizeof(char *));
    if (vec == NULL) {
        return NULL;
    }
    for (i = 0; i < len; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        const char *chars;
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            break;
        }
        if (item == NULL) {
            continue;
        }
        chars = (*env)->GetStringUTFChars(env, item, NULL);
        if (chars == NULL) {
            (*env)->DeleteLocalRef(env, item);
            break;
        }
        vec[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, item, chars);
        (*env)->DeleteLocalRef(env, item);
        if (vec[i] == NULL) {
            break;
        }
    }
    *out_count = (int)i;
    return vec;
}

/* strdup() with a fallback for the copy of a Java string. */
static char *jstring_dup(JNIEnv *env, jstring value) {
    const char *chars;
    char *copy;

    if (value == NULL) {
        return NULL;
    }
    chars = (*env)->GetStringUTFChars(env, value, NULL);
    if (chars == NULL) {
        return NULL;
    }
    copy = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, value, chars);
    return copy;
}

static int vec_has_prefix(char **vec, int n, const char *prefix) {
    size_t plen = strlen(prefix);
    int i;
    for (i = 0; i < n; i++) {
        if (vec[i] != NULL && strncmp(vec[i], prefix, plen) == 0) {
            return 1;
        }
    }
    return 0;
}

/* Appends "KEY=VALUE" unless KEY is already present. Returns new count. */
static int vec_ensure(char **vec, int n, int cap, const char *key, const char *value) {
    char *entry;
    size_t klen;
    size_t vlen;

    klen = strlen(key);
    vlen = strlen(value);
    entry = (char *)malloc(klen + vlen + 2);
    if (entry == NULL || n >= cap) {
        free(entry);
        return n;
    }
    memcpy(entry, key, klen);
    entry[klen] = '=';
    memcpy(entry + klen + 1, value, vlen);
    entry[klen + 1 + vlen] = '\0';
    vec[n] = entry;
    return n + 1;
}

/* Builds the list of absolute paths to try for `shell`. */
static char **build_shell_candidates(const char *shell, const char *path_value, int *out_count) {
    char **cands;
    int count = 0;
    int cap;
    const char *scan;
    size_t shell_len;

    *out_count = 0;
    if (shell == NULL || *shell == '\0') {
        return NULL;
    }
    if (strchr(shell, '/') != NULL) {
        cands = (char **)calloc(2, sizeof(char *));
        if (cands == NULL) {
            return NULL;
        }
        cands[0] = strdup(shell);
        if (cands[0] == NULL) {
            free(cands);
            return NULL;
        }
        *out_count = 1;
        return cands;
    }

    shell_len = strlen(shell);
    /* Worst case: one candidate per PATH element. */
    cap = 1;
    for (scan = path_value; *scan != '\0'; scan++) {
        if (*scan == ':') {
            cap++;
        }
    }
    cap++;
    cands = (char **)calloc((size_t)cap + 1, sizeof(char *));
    if (cands == NULL) {
        return NULL;
    }

    scan = path_value;
    while (*scan != '\0' && count < cap) {
        const char *end = strchr(scan, ':');
        size_t dir_len = (end != NULL) ? (size_t)(end - scan) : strlen(scan);
        size_t total = (dir_len == 0 ? 1 : dir_len) + 1 + shell_len + 1;
        char *cand = (char *)malloc(total);
        if (cand != NULL) {
            size_t pos = 0;
            if (dir_len == 0) {
                cand[pos++] = '.';
            } else {
                memcpy(cand, scan, dir_len);
                pos = dir_len;
            }
            cand[pos++] = '/';
            memcpy(cand + pos, shell, shell_len);
            pos += shell_len;
            cand[pos] = '\0';
            cands[count++] = cand;
        }
        if (end == NULL) {
            break;
        }
        scan = end + 1;
    }

    *out_count = count;
    return cands;
}

/* Raw mode: the terminal emulator on the Java side owns all editing/echo. */
static int set_raw_mode(int fd) {
    struct termios tio;

    if (tcgetattr(fd, &tio) != 0) {
        return -1;
    }
    tio.c_iflag &= (tcflag_t) ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON | IXOFF);
    tio.c_oflag &= (tcflag_t) ~OPOST;
    tio.c_lflag &= (tcflag_t) ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
    tio.c_cflag &= (tcflag_t) ~(CSIZE | PARENB);
    tio.c_cflag |= (tcflag_t)(CS8 | CREAD | CLOCAL);
    tio.c_cc[VMIN] = 1;
    tio.c_cc[VTIME] = 0;
    return tcsetattr(fd, TCSANOW, &tio);
}

/*
 * Child side. Only async-signal-safe syscalls from here on: all memory was
 * prepared by the parent before fork().
 */
static void child_exec(int master, int slave, const char *cwd, char *const *argv,
                       char *const *envp, char *const *cands, int ncand) {
    int i;

    setsid();
    (void)ioctl(slave, TIOCSCTTY, 0);

    if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 ||
        dup2(slave, STDERR_FILENO) < 0) {
        _exit(126);
    }
    if (slave > STDERR_FILENO) {
        close(slave);
    }
    close(master);

    if (cwd != NULL && *cwd != '\0') {
        (void)chdir(cwd); /* best effort: fall back to the inherited cwd */
    }

    for (i = 0; i < ncand; i++) {
        execve(cands[i], argv, envp);
        if (errno != ENOENT && errno != ENOTDIR) {
            break;
        }
    }

    {
        static const char msg[] = "rootops: unable to execute shell\r\n";
        ssize_t ignored = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        (void)ignored;
    }
    _exit(127);
}

/* ------------------------------------------------------------------ */
/* JNI exports                                                         */
/* ------------------------------------------------------------------ */

/*
 * jclass vs jobject: for `@JvmStatic external fun` in a Kotlin object the
 * second argument is the class object, for a plain `external fun` it is the
 * instance. The symbol name is identical either way and we never touch it, so
 * the parameter is declared as jclass and ignored.
 */
JNIEXPORT jint JNICALL
Java_dev_rootops_core_pty_PtyNative_openPty(JNIEnv *env, jclass clazz, jstring shell, jobjectArray args,
                                            jstring cwd, jobjectArray env_array, jint cols, jint rows) {
    char *shell_copy = NULL;
    char *cwd_copy = NULL;
    char **args_vec = NULL;
    char **env_vec = NULL;
    char **cands = NULL;
    char **argv = NULL;
    int nargs = 0;
    int nenv = 0;
    int ncand = 0;
    int master = -1;
    int slave = -1;
    int status = -1;
    int i;
    char slave_name[128];
    struct winsize ws;
    pid_t pid;

    (void)clazz;

    shell_copy = jstring_dup(env, shell);
    if (shell_copy == NULL || *shell_copy == '\0') {
        if ((*env)->ExceptionCheck(env)) {
            goto done;
        }
        free(shell_copy);
        shell_copy = strdup("/system/bin/sh");
        if (shell_copy == NULL) {
            goto done;
        }
    }
    if ((*env)->ExceptionCheck(env)) {
        goto done;
    }
    cwd_copy = jstring_dup(env, cwd);
    if ((*env)->ExceptionCheck(env)) {
        goto done;
    }

    args_vec = jstring_array_to_vec(env, args, &nargs);
    if ((*env)->ExceptionCheck(env)) {
        goto done;
    }
    env_vec = jstring_array_to_vec(env, env_array, &nenv);
    if ((*env)->ExceptionCheck(env)) {
        goto done;
    }

    /* argv = [shell, args..., NULL] */
    argv = (char **)calloc((size_t)nargs + 2, sizeof(char *));
    if (argv == NULL) {
        goto done;
    }
    argv[0] = strdup(shell_copy);
    if (argv[0] == NULL) {
        goto done;
    }
    for (i = 0; i < nargs; i++) {
        argv[i + 1] = args_vec[i];
        args_vec[i] = NULL; /* ownership moved */
    }
    argv[nargs + 1] = NULL;

    if (env_vec == NULL) {
        env_vec = (char **)calloc(4, sizeof(char *));
        if (env_vec == NULL) {
            goto done;
        }
    } else {
        /* The vector from Java has exactly nenv + 1 slots; the defaults below
         * need up to three more. Copy into a roomier array. */
        char **roomier = (char **)calloc((size_t)nenv + 4, sizeof(char *));
        if (roomier == NULL) {
            goto done;
        }
        for (i = 0; i < nenv; i++) {
            roomier[i] = env_vec[i];
        }
        free(env_vec);
        env_vec = roomier;
    }
    {
        const int env_cap = nenv + 3;
        if (!vec_has_prefix(env_vec, nenv, "TERM=")) {
            nenv = vec_ensure(env_vec, nenv, env_cap, "TERM", RO_DEFAULT_TERM);
        }
        if (!vec_has_prefix(env_vec, nenv, "PATH=")) {
            nenv = vec_ensure(env_vec, nenv, env_cap, "PATH", RO_DEFAULT_PATH);
        }
        if (!vec_has_prefix(env_vec, nenv, "HOME=")) {
            nenv = vec_ensure(env_vec, nenv, env_cap, "HOME", "/");
        }
    }
    env_vec[nenv] = NULL;

    {
        const char *path_value = RO_DEFAULT_PATH;
        for (i = 0; i < nenv; i++) {
            if (env_vec[i] != NULL && strncmp(env_vec[i], "PATH=", 5) == 0) {
                path_value = env_vec[i] + 5;
                break;
            }
        }
        cands = build_shell_candidates(shell_copy, path_value, &ncand);
        if (cands == NULL || ncand == 0) {
            goto done;
        }
    }

    master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        goto done;
    }
    if (master >= RO_PID_TABLE_SIZE) {
        /* Table is fd-indexed; keep the fd number inside the table. */
        int moved = fcntl(master, F_DUPFD, 3);
        if (moved >= 0 && moved < RO_PID_TABLE_SIZE) {
            close(master);
            master = moved;
        }
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        goto done;
    }
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        goto done;
    }

    slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        goto done;
    }
    (void)set_raw_mode(slave);

    ws.ws_col = (unsigned short)(cols > 0 ? cols : RO_DEFAULT_COLS);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : RO_DEFAULT_ROWS);
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    (void)ioctl(slave, TIOCSWINSZ, &ws);

    pid = fork();
    if (pid < 0) {
        goto done;
    }
    if (pid == 0) {
        child_exec(master, slave, cwd_copy, argv, env_vec, cands, ncand);
        _exit(127); /* not reached */
    }

    /* Parent. */
    close(slave);
    slave = -1;

    {
        int flags = fcntl(master, F_GETFL, 0);
        if (flags < 0 || fcntl(master, F_SETFL, flags | O_NONBLOCK) < 0) {
            close(master);
            master = -1;
            reap_bounded(pid);
            goto done;
        }
    }
    {
        int fdflags = fcntl(master, F_GETFD, 0);
        if (fdflags >= 0) {
            (void)fcntl(master, F_SETFD, fdflags | FD_CLOEXEC);
        }
    }

    pid_table_set(master, pid);
    status = master;
    master = -1;

done:
    if (slave >= 0) {
        close(slave);
    }
    if (master >= 0) {
        close(master);
    }
    free_vec(cands, ncand);
    free_vec(argv, nargs + 1);
    free_vec(args_vec, nargs);
    free_vec(env_vec, nenv);
    free(shell_copy);
    free(cwd_copy);
    return status;
}

/* Thread-local bounce buffer: avoids a malloc per read/write call and keeps
 * concurrent sessions on different threads independent. */
static _Thread_local unsigned char g_io_buf[RO_READ_CHUNK];

JNIEXPORT jint JNICALL
Java_dev_rootops_core_pty_PtyNative_readPty(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint len) {
    jsize cap;
    ssize_t n;

    (void)clazz;
    if (buf == NULL || len <= 0 || !fd_is_live(fd)) {
        return -1;
    }
    cap = (*env)->GetArrayLength(env, buf);
    if (cap <= 0) {
        return 0;
    }
    if (len > cap) {
        len = cap;
    }
    if (len > RO_READ_CHUNK) {
        len = RO_READ_CHUNK;
    }

    do {
        n = read(fd, g_io_buf, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buf, 0, (jsize)n, (const jbyte *)g_io_buf);
        if ((*env)->ExceptionCheck(env)) {
            return -1;
        }
        return (jint)n;
    }
    if (n == 0) {
        return -1; /* EOF: child side closed */
    }
    if (errno == EAGAIN || errno == EWOULDBLOCK) {
        return 0; /* idle */
    }
    return -1; /* EIO and friends: pty gone */
}

JNIEXPORT jint JNICALL
Java_dev_rootops_core_pty_PtyNative_writePty(JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint len) {
    jsize cap;
    ssize_t n;

    (void)clazz;
    if (buf == NULL || len <= 0 || !fd_is_live(fd)) {
        return -1;
    }
    cap = (*env)->GetArrayLength(env, buf);
    if (cap <= 0) {
        return 0;
    }
    if (len > cap) {
        len = cap;
    }
    if (len > RO_READ_CHUNK) {
        len = RO_READ_CHUNK;
    }

    (*env)->GetByteArrayRegion(env, buf, 0, len, (jbyte *)g_io_buf);
    if ((*env)->ExceptionCheck(env)) {
        return -1;
    }

    do {
        n = write(fd, g_io_buf, (size_t)len);
    } while (n < 0 && errno == EINTR);

    if (n >= 0) {
        return (jint)n;
    }
    if (errno == EAGAIN || errno == EWOULDBLOCK) {
        return 0; /* retry later */
    }
    return -1;
}

JNIEXPORT jint JNICALL
Java_dev_rootops_core_pty_PtyNative_resizePty(JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows) {
    struct winsize ws;

    (void)env;
    (void)clazz;
    if (!fd_is_live(fd)) {
        return -1;
    }
    ws.ws_col = (unsigned short)(cols > 0 ? cols : RO_DEFAULT_COLS);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : RO_DEFAULT_ROWS);
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    return ioctl(fd, TIOCSWINSZ, &ws) == 0 ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_dev_rootops_core_pty_PtyNative_getChildPid(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    return (jint)pid_table_get(fd);
}

JNIEXPORT jint JNICALL
Java_dev_rootops_core_pty_PtyNative_waitChild(JNIEnv *env, jclass clazz, jint fd) {
    pid_t pid;
    pid_t r;
    int status = 0;

    (void)env;
    (void)clazz;
    pid = pid_table_get(fd);
    if (pid <= 0) {
        return -1;
    }
    do {
        r = waitpid(pid, &status, 0);
    } while (r < 0 && errno == EINTR);

    if (r < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return (jint)WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return (jint)(128 + WTERMSIG(status));
    }
    return -1;
}

JNIEXPORT jint JNICALL
Java_dev_rootops_core_pty_PtyNative_closePty(JNIEnv *env, jclass clazz, jint fd) {
    pid_t pid;
    int rc;

    (void)env;
    (void)clazz;
    pid = pid_table_take(fd);
    rc = close(fd);

    /* Reap the child if it is already gone; a live child is reaped later by
     * waitChild() on the session watcher. */
    reap_bounded(pid);
    return rc == 0 ? 0 : -1;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    return JNI_VERSION_1_6;
}
