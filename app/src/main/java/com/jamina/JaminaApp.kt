package com.jamina

import android.app.Application
import com.jamina.core.Repository
import com.jamina.core.db.AppDatabase
import com.jamina.core.settings.SettingsRepository
import com.jamina.diagnostics.LanTestServer
import com.jamina.wifi.ConnectionMonitor
import com.jamina.wifi.WifiScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Simple manual dependency container (no DI framework needed for a single-module app). */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(app)
    val repo = Repository(app, db)
    val dao = repo.dao
    val settings = SettingsRepository(app, scope)
    val scanner = WifiScanner(app, scope)
    val connection = ConnectionMonitor(app, scope, scanner)
    val lanServer = LanTestServer(scope)

    init {
        // Persist every scan batch Android delivers so measurement history is preserved.
        scope.launch {
            scanner.state.map { it.latest }.filterNotNull().distinctUntilChangedBy { it.id }.collect { batch ->
                if (batch.results.isNotEmpty()) runCatching { repo.saveBatch(batch) }
            }
        }
    }
}

class JaminaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
