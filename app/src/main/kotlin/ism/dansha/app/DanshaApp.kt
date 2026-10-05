package ism.dansha.app

import android.app.Application
import ism.dansha.app.data.AppDatabase
import ism.dansha.app.data.Repository

class DanshaApp : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(AppDatabase.open(this))
        ism.dansha.app.notify.Notifier.setup(this)
        Shortcuts.setup(this)
    }
}
