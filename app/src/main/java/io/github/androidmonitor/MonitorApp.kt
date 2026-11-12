package io.github.androidmonitor

import android.app.Application
import io.github.androidmonitor.data.SettingsStore
import io.github.androidmonitor.privileged.AccessManager

class MonitorApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var access: AccessManager
        private set
    lateinit var systemSampler: io.github.androidmonitor.data.SystemSampler
        private set
    lateinit var processSampler: io.github.androidmonitor.data.ProcessSampler
        private set
    lateinit var utilityManager: io.github.androidmonitor.data.UtilityAutomationManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        access = AccessManager(this, settings)
        systemSampler = io.github.androidmonitor.data.SystemSampler(this, access)
        processSampler = io.github.androidmonitor.data.ProcessSampler(this, access, settings)
        utilityManager = io.github.androidmonitor.data.UtilityAutomationManager(
            this,
            access,
            settings,
            processSampler.apps,
        )
    }
}
