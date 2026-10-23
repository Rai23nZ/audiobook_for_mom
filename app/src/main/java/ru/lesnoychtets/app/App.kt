package ru.lesnoychtets.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.lesnoychtets.app.data.AppPrefs
import ru.lesnoychtets.app.data.LibraryRepository
import ru.lesnoychtets.app.data.LibrivoxCatalog
import ru.lesnoychtets.app.data.ProgressStore
import ru.lesnoychtets.app.sync.CleanupWorker
import ru.lesnoychtets.app.sync.DriveSyncWorker

class App : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        ProgressStore.init(this)
        LibraryRepository.init(this)
        LibrivoxCatalog.init(this)
        DriveSyncWorker.schedule(this)
        CleanupWorker.schedule(this)
        scope.launch {
            LibraryRepository.rescan(this@App)
            LibraryRepository.removeListened(this@App)
        }
    }
}
