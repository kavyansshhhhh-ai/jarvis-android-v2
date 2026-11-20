package app.shilex.android

import android.app.Application
import app.shilex.android.orb.FloatingOrb
import app.shilex.android.tools.InstalledAppsRegistry

/**
 * Starts diagnostics before any screen, service or receiver runs, synchronizes temporary
 * overlay visibility, and indexes installed apps for instant A-Z discovery.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.start(DiagFile(filesDir))
        FloatingOrb.sync(this)
        runCatching { InstalledAppsRegistry.init(this) }
    }
}
