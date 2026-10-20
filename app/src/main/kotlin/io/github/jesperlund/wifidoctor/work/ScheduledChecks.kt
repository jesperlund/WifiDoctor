package io.github.jesperlund.wifidoctor.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jesperlund.wifidoctor.MainActivity
import io.github.jesperlund.wifidoctor.R
import io.github.jesperlund.wifidoctor.core.engine.EngineEvent
import io.github.jesperlund.wifidoctor.core.history.RunRecord
import io.github.jesperlund.wifidoctor.core.model.Diagnosis
import io.github.jesperlund.wifidoctor.core.model.EnvironmentIssue
import io.github.jesperlund.wifidoctor.core.model.Severity
import io.github.jesperlund.wifidoctor.data.RunRepository
import io.github.jesperlund.wifidoctor.data.SettingsRepository
import io.github.jesperlund.wifidoctor.di.EngineFactory
import io.github.jesperlund.wifidoctor.platform.EnvironmentMonitor
import io.github.jesperlund.wifidoctor.platform.PermissionChecker
import io.github.jesperlund.wifidoctor.platform.RegionHintsProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Enables or disables the periodic background check. */
interface CheckScheduler {
    fun apply(enabled: Boolean, intervalMinutes: Long)
}

@Singleton
class WorkManagerCheckScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : CheckScheduler {
    override fun apply(enabled: Boolean, intervalMinutes: Long) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(DiagnosticWorker.NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<DiagnosticWorker>(intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(DiagnosticWorker.NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/** Runs a diagnosis in the background, stores it and notifies about problems. */
@HiltWorker
class DiagnosticWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engineFactory: EngineFactory,
    private val repository: RunRepository,
    private val settingsRepository: SettingsRepository,
    private val environment: EnvironmentMonitor,
    private val notifier: ProblemNotifier,
    private val regionHints: RegionHintsProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val env = environment.refresh()
        // Wi-Fi deliberately switched off is not a problem worth recording.
        if (EnvironmentIssue.WIFI_OFF in env.issues) return Result.success()
        val settings = settingsRepository.settings.first()
        val engine = engineFactory.create(settings)
        val finished = withTimeoutOrNull(TIMEOUT_MS) {
            engine.run(settings.toRunConfig(metered = env.wifiMetered, background = true, regionHints.hints(settings.countryOverride)))
                .filterIsInstance<EngineEvent.Finished>()
                .first()
        } ?: return Result.retry()
        val id = repository.save(RunRecord.from(finished.measurements, finished.diagnosis))
        if (finished.diagnosis.severity != Severity.OK || !settings.notifyOnlyOnProblems) {
            notifier.notify(finished.diagnosis, id)
        }
        return Result.success()
    }

    companion object {
        const val NAME = "scheduled_wifi_check"
        private const val TIMEOUT_MS = 3 * 60_000L
    }
}

@Singleton
class ProblemNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionChecker,
) {
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun notify(diagnosis: Diagnosis, runId: Long) {
        if (!permissions.canNotify()) return
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val prefix = when (diagnosis.severity) {
            Severity.OK -> "OK"
            Severity.WARN -> "Warning"
            Severity.FAIL -> "Problem"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$prefix: ${diagnosis.verdict}")
            .setContentText(diagnosis.fixes.firstOrNull() ?: diagnosis.explanation)
            .setStyle(NotificationCompat.BigTextStyle().bigText(diagnosis.explanation))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(runId.toInt(), notification) }
    }

    companion object {
        const val CHANNEL_ID = "wifi_problems"
    }
}
