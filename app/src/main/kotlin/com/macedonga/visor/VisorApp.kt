package com.macedonga.visor

import android.app.Application
import com.macedonga.visor.core.session.DataXConnection
import com.macedonga.visor.core.transport.FileTrace
import com.macedonga.visor.log.LogFeed
import com.macedonga.visor.outside.VdkHost
import com.macedonga.visor.update.Updates
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

/** Starts the log feed, so the event log records from the first moment, the update check, and the VDK. */
@HiltAndroidApp
class VisorApp : Application() {

    @Inject
    lateinit var logFeed: LogFeed

    @Inject
    lateinit var updates: Updates

    @Inject
    lateinit var vdk: VdkHost

    override fun onCreate() {
        super.onCreate()
        // Debug builds keep every DataX message in files/datax.log.
        if (BuildConfig.DEBUG) DataXConnection.trace = FileTrace(File(filesDir, "datax.log"))
        updates.start()
        vdk.start()
    }
}
