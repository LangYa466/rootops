package dev.rootops

import android.content.Context
import dev.rootops.core.api.ArchiveManager
import dev.rootops.core.api.FileOps
import dev.rootops.core.api.FileRepository
import dev.rootops.core.api.LogRepository
import dev.rootops.core.api.MountRepository
import dev.rootops.core.api.NetworkTools
import dev.rootops.core.api.PackageRepository
import dev.rootops.core.api.ProcessRepository
import dev.rootops.core.api.Scheduler
import dev.rootops.core.api.ScriptStore
import dev.rootops.core.api.SettingsStore
import dev.rootops.core.api.SftpManager
import dev.rootops.core.api.ShellRunner
import dev.rootops.core.api.SshManager
import dev.rootops.core.api.TerminalEngine
import dev.rootops.core.fs.ArchiveManagerImpl
import dev.rootops.core.fs.FileOpsImpl
import dev.rootops.core.fs.FileRepositoryImpl
import dev.rootops.core.mount.MountRepositoryImpl
import dev.rootops.core.net.NetworkToolsImpl
import dev.rootops.core.pkg.PackageRepositoryImpl
import dev.rootops.core.prefs.SettingsStoreImpl
import dev.rootops.core.pty.PtyEngineImpl
import dev.rootops.core.scripts.SchedulerImpl
import dev.rootops.core.scripts.ScriptStoreImpl
import dev.rootops.core.shell.ShellRunnerImpl
import dev.rootops.core.ssh.SftpManagerImpl
import dev.rootops.core.ssh.SshManagerImpl
import dev.rootops.core.sys.LogRepositoryImpl
import dev.rootops.core.sys.ProcessRepositoryImpl

/**
 * Process-wide dependency graph. Everything is created lazily on first use and kept for the
 * lifetime of the process; the application context is retained, never an Activity.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settings: SettingsStore by lazy { SettingsStoreImpl(appContext) }

    val shell: ShellRunner by lazy { ShellRunnerImpl(appContext) }

    val files: FileRepository by lazy { FileRepositoryImpl(shell) }

    val fileOps: FileOps by lazy { FileOpsImpl(shell, files) }

    val archives: ArchiveManager by lazy { ArchiveManagerImpl(shell) }

    val terminal: TerminalEngine by lazy { PtyEngineImpl() }

    val processes: ProcessRepository by lazy { ProcessRepositoryImpl(shell) }

    val logs: LogRepository by lazy { LogRepositoryImpl(shell) }

    val network: NetworkTools by lazy { NetworkToolsImpl(appContext, shell) }

    val scripts: ScriptStore by lazy { ScriptStoreImpl(appContext, shell) }

    val scheduler: Scheduler by lazy { SchedulerImpl(appContext, scripts, shell) }

    val mounts: MountRepository by lazy { MountRepositoryImpl(shell) }

    val packages: PackageRepository by lazy { PackageRepositoryImpl(appContext, shell) }

    val ssh: SshManager by lazy { SshManagerImpl(appContext) }

    val sftp: SftpManager by lazy { SftpManagerImpl(appContext) }
}
