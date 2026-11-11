package dev.pingfront.internal.android

import android.content.Context
import androidx.startup.Initializer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.pingfront.PingFront
import dev.pingfront.internal.Engine
import dev.pingfront.internal.RetryScheduler
import dev.pingfront.internal.runtime.Log
import java.util.concurrent.TimeUnit

private val log = Log.of(Log.Category.Outbox)

/** Retries the outbox when the network returns, even if the app was closed in between. */
internal class OutboxRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = outcome(PingFront.instance.engine)

    companion object {
        /**
         * Not configured yet (React Native and Flutter configure from script, which a process WorkManager woke does not
         * run): the queue is still there, so the work runs again with WorkManager's backoff instead of being dropped.
         */
        internal suspend fun outcome(engine: Engine?): ListenableWorker.Result {
            if (engine == null) {
                log.i("The outbox retry waits: the SDK is not configured in this process yet.")
                return ListenableWorker.Result.retry()
            }
            return if (engine.drainForWorker()) ListenableWorker.Result.success() else ListenableWorker.Result.retry()
        }
    }
}

internal class WorkManagerRetryScheduler(private val context: Context) : RetryScheduler {
    private fun manager(): WorkManager? = try {
        WorkManager.getInstance(context)
    } catch (e: IllegalStateException) {
        log.w("WorkManager is not available (${e.javaClass.simpleName}); queued messages send when the app is next open.")
        null
    }

    override fun scheduleOutboxRetry() {
        val request = OneTimeWorkRequestBuilder<OutboxRetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager()?.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        manager()?.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME: String = "dev.pingfront.outbox"
    }
}

/**
 * Zero-line setup: configures the SDK from `<meta-data android:name="pingfront.app_key">` at process
 * start, through androidx.startup. Validates the key offline only; no network.
 */
public class PingFrontInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Always: activities are tracked from process start, also for apps that configure later (React Native, Flutter).
        (context.applicationContext as? android.app.Application)?.let(PingFront.instance::attach)
        val key = PingFront.instance.manifestKey(context) ?: return
        if (!PingFront.instance.isConfigured.value) PingFront.configure(context, key)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(androidx.lifecycle.ProcessLifecycleInitializer::class.java)
}
