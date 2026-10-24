package dev.rootops

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide application entry point; owns the single [AppContainer]. */
class RootOpsApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Probe root eagerly so the very first screen sees the real state instead of "denied".
        appScope.launch { container.shell.refreshRoot() }
        container.scheduler.start()
    }
}
