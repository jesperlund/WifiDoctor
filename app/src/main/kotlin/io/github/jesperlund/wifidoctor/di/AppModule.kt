package io.github.jesperlund.wifidoctor.di

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.room.Room
import io.github.jesperlund.wifidoctor.core.engine.DiagnosticEngine
import io.github.jesperlund.wifidoctor.core.engine.LinkSource
import io.github.jesperlund.wifidoctor.core.engine.ScanSource
import io.github.jesperlund.wifidoctor.core.engine.WalkTester
import io.github.jesperlund.wifidoctor.core.probe.DefaultDnsTimer
import io.github.jesperlund.wifidoctor.core.probe.FallbackProber
import io.github.jesperlund.wifidoctor.core.probe.HttpThroughputTester
import io.github.jesperlund.wifidoctor.core.probe.NetworkBinding
import io.github.jesperlund.wifidoctor.core.probe.PingProber
import io.github.jesperlund.wifidoctor.core.probe.TcpConnectProber
import io.github.jesperlund.wifidoctor.data.AppSettings
import io.github.jesperlund.wifidoctor.data.DataStoreSettingsRepository
import io.github.jesperlund.wifidoctor.data.RoomRunRepository
import io.github.jesperlund.wifidoctor.data.RunRepository
import io.github.jesperlund.wifidoctor.data.SettingsRepository
import io.github.jesperlund.wifidoctor.data.db.RunDao
import io.github.jesperlund.wifidoctor.data.db.WifiDoctorDatabase
import io.github.jesperlund.wifidoctor.platform.AndroidEnvironmentMonitor
import io.github.jesperlund.wifidoctor.platform.AndroidRegionHintsProvider
import io.github.jesperlund.wifidoctor.platform.RegionHintsProvider
import io.github.jesperlund.wifidoctor.platform.AndroidLinkSource
import io.github.jesperlund.wifidoctor.platform.AndroidScanSource
import io.github.jesperlund.wifidoctor.platform.ContentResolverWriter
import io.github.jesperlund.wifidoctor.platform.DocumentWriter
import io.github.jesperlund.wifidoctor.platform.EnvironmentMonitor
import io.github.jesperlund.wifidoctor.platform.WifiNetworkBinding
import io.github.jesperlund.wifidoctor.work.CheckScheduler
import io.github.jesperlund.wifidoctor.work.WorkManagerCheckScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import io.github.jesperlund.wifidoctor.core.probe.RttProbe
import io.github.jesperlund.wifidoctor.core.probe.StunRealtimeTester
import io.github.jesperlund.wifidoctor.monitor.CallMonitorController
import io.github.jesperlund.wifidoctor.monitor.ForegroundKeeper
import io.github.jesperlund.wifidoctor.monitor.MonitorRunner
import io.github.jesperlund.wifidoctor.monitor.MonitorServiceKeeper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Wall-clock time, injectable for tests. */
fun interface Clock {
    fun now(): Long
}

/** Builds a [DiagnosticEngine] for the current settings (targets, ping fallback). */
fun interface EngineFactory {
    fun create(settings: AppSettings): DiagnosticEngine
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun wifiManager(@ApplicationContext context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Provides
    fun connectivityManager(@ApplicationContext context: Context): ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Provides
    fun locationManager(@ApplicationContext context: Context): LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): WifiDoctorDatabase =
        Room.databaseBuilder(context, WifiDoctorDatabase::class.java, WifiDoctorDatabase.NAME)
            .addMigrations(WifiDoctorDatabase.MIGRATION_1_2, WifiDoctorDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun runDao(db: WifiDoctorDatabase): RunDao = db.runDao()

    @Provides
    @Singleton
    fun engineFactory(
        linkSource: LinkSource,
        scanSource: ScanSource,
        binding: NetworkBinding,
    ): EngineFactory = EngineFactory { settings ->
        val tcp = TcpConnectProber(binding)
        DiagnosticEngine(
            linkSource = linkSource,
            scanSource = scanSource,
            prober = FallbackProber(tcp, if (settings.usePingFallback) PingProber() else null),
            dnsTimer = DefaultDnsTimer(binding),
            throughputTester = HttpThroughputTester(binding),
            realtimeTester = StunRealtimeTester(binding),
            rttProbe = tcp,
        )
    }

    /** Single TCP-handshake samples on the Wi-Fi network, for the call monitor. */
    @Provides
    @Singleton
    fun rttProbe(binding: NetworkBinding): RttProbe = TcpConnectProber(binding)

    @Provides
    fun clock(): Clock = Clock(System::currentTimeMillis)

    /** Lives as long as the process: the call monitor must outlive screens. */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun walkTester(linkSource: LinkSource, binding: NetworkBinding): WalkTester =
        WalkTester(linkSource, TcpConnectProber(binding))
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun linkSource(impl: AndroidLinkSource): LinkSource
    @Binds abstract fun scanSource(impl: AndroidScanSource): ScanSource
    @Binds abstract fun networkBinding(impl: WifiNetworkBinding): NetworkBinding
    @Binds abstract fun environment(impl: AndroidEnvironmentMonitor): EnvironmentMonitor
    @Binds abstract fun runRepository(impl: RoomRunRepository): RunRepository
    @Binds abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun scheduler(impl: WorkManagerCheckScheduler): CheckScheduler
    @Binds abstract fun documentWriter(impl: ContentResolverWriter): DocumentWriter
    @Binds abstract fun regionHints(impl: AndroidRegionHintsProvider): RegionHintsProvider
    @Binds abstract fun monitorRunner(impl: CallMonitorController): MonitorRunner
    @Binds abstract fun foregroundKeeper(impl: MonitorServiceKeeper): ForegroundKeeper
}
