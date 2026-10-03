package app.rise.clockin

import android.app.Application
import app.rise.clockin.alarm.AlarmService
import app.rise.clockin.data.Store

class RiseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        AlarmService.ensureChannel(this)
    }
}
