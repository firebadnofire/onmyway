package org.archuser.onmyway

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.archuser.onmyway.data.AppDatabase
import org.archuser.onmyway.data.EventRepository
import org.archuser.onmyway.data.AppSettingsStore
import org.archuser.onmyway.platform.MonitoringCoordinator
import org.archuser.onmyway.platform.NotificationDispatcher
import org.archuser.onmyway.platform.WifiMonitor

class OnMyWayApplication : Application() {
    lateinit var wifiMonitor: WifiMonitor
        private set
    lateinit var repository: EventRepository
        private set
    lateinit var settingsStore: AppSettingsStore
        private set
    lateinit var coordinator: MonitoringCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        repository = EventRepository(AppDatabase.create(this))
        settingsStore = AppSettingsStore(this)
        wifiMonitor = WifiMonitor(this)
        coordinator = MonitoringCoordinator(
            this,
            repository,
            wifiMonitor,
            NotificationDispatcher(this, settingsStore),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        coordinator.start()
    }
}
