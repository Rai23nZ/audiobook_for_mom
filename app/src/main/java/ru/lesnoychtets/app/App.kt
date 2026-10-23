package ru.lesnoychtets.app

import android.app.Application
import ru.lesnoychtets.app.data.AppPrefs
import ru.lesnoychtets.app.data.LibraryRepository
import ru.lesnoychtets.app.data.LibrivoxCatalog
import ru.lesnoychtets.app.data.ProgressStore
import ru.lesnoychtets.app.sync.DriveSyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        ProgressStore.init(this)
        LibraryRepository.init(this)
        LibrivoxCatalog.init(this)
        DriveSyncWorker.schedule(this)
    }
}
