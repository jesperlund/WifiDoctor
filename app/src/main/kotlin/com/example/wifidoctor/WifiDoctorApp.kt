package com.example.wifidoctor

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.wifidoctor.platform.AndroidEnvironmentMonitor
import com.example.wifidoctor.work.ProblemNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WifiDoctorApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var environmentMonitor: AndroidEnvironmentMonitor
    @Inject lateinit var notifier: ProblemNotifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        environmentMonitor.start()
        notifier.ensureChannel()
    }
}
