package com.example.wifidoctor.di

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.room.Room
import com.example.wifidoctor.core.engine.DiagnosticEngine
import com.example.wifidoctor.core.engine.LinkSource
import com.example.wifidoctor.core.engine.ScanSource
import com.example.wifidoctor.core.engine.WalkTester
import com.example.wifidoctor.core.probe.DefaultDnsTimer
import com.example.wifidoctor.core.probe.FallbackProber
import com.example.wifidoctor.core.probe.HttpThroughputTester
import com.example.wifidoctor.core.probe.NetworkBinding
import com.example.wifidoctor.core.probe.PingProber
import com.example.wifidoctor.core.probe.TcpConnectProber
import com.example.wifidoctor.data.AppSettings
import com.example.wifidoctor.data.DataStoreSettingsRepository
import com.example.wifidoctor.data.RoomRunRepository
import com.example.wifidoctor.data.RunRepository
import com.example.wifidoctor.data.SettingsRepository
import com.example.wifidoctor.data.db.RunDao
import com.example.wifidoctor.data.db.WifiDoctorDatabase
import com.example.wifidoctor.platform.AndroidEnvironmentMonitor
import com.example.wifidoctor.platform.AndroidRegionHintsProvider
import com.example.wifidoctor.platform.RegionHintsProvider
import com.example.wifidoctor.platform.AndroidLinkSource
import com.example.wifidoctor.platform.AndroidScanSource
import com.example.wifidoctor.platform.ContentResolverWriter
import com.example.wifidoctor.platform.DocumentWriter
import com.example.wifidoctor.platform.EnvironmentMonitor
import com.example.wifidoctor.platform.WifiNetworkBinding
import com.example.wifidoctor.work.CheckScheduler
import com.example.wifidoctor.work.WorkManagerCheckScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
            .addMigrations(WifiDoctorDatabase.MIGRATION_1_2)
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
        )
    }

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
}
