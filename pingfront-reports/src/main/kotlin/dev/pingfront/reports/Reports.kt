package dev.pingfront.reports

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.PixelCopy
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.eventFlow
import dev.pingfront.ModuleContext
import dev.pingfront.PingFrontModule
import dev.pingfront.ReportContext
import dev.pingfront.ReportContextProvider
import dev.pingfront.ReportPresenter
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bug reports: shake to report, a screenshot with freehand markup, the app's recent log lines and
 * device state (screen, battery, network, memory, free disk).
 *
 * ```kotlin
 * PingFront.configure(context, "pk_live_…") { use(Reports) }            // shake on
 * PingFront.configure(context, "pk_live_…") { use(Reports(shake = false)) }
 * ```
 */
public object Reports : PingFrontModule("reports") {
    override fun install(context: ModuleContext): Unit = ReportsModule(shake = true, logTail = true).install(context)

    /** With shake detection and the log tail switched on or off. */
    public operator fun invoke(shake: Boolean = true, logTail: Boolean = true): PingFrontModule = ReportsModule(shake, logTail)
}

internal class ReportsModule(private val shake: Boolean, private val logTail: Boolean) : PingFrontModule("reports") {
    override fun install(context: ModuleContext) {
        val app = context.applicationContext
        context.setReportPresenter(ScreenshotReportPresenter(app, context::currentActivity))
        context.setReportContextProvider(DeviceReportContext(app, context::currentActivity, logTail, context.ioDispatcher))
        if (shake) {
            context.scope.launch {
                ShakeGate.run(ProcessLifecycleOwner.get().lifecycle.eventFlow, Shake.events(app)) {
                    ScreenshotReportPresenter(app, context::currentActivity).presentReport(null)
                }
            }
        }
    }
}

/** Shake detection only while the app is in the foreground. */
internal object ShakeGate {
    /**
     * Collects [shakes] from `ON_START` until `ON_STOP`. Only those two events switch it: `ON_RESUME`
     * always follows `ON_START`, and reacting to it (through `collectLatest`) used to cancel the sensor
     * listener in the same frame, so shaking never worked (review CS-10, CQ-H5).
     */
    suspend fun run(events: Flow<Lifecycle.Event>, shakes: Flow<Unit>, onShake: suspend () -> Unit) {
        events.filter { it == Lifecycle.Event.ON_START || it == Lifecycle.Event.ON_STOP }.collectLatest { event ->
            if (event == Lifecycle.Event.ON_START) shakes.collect { onShake() }
        }
    }
}

/** Captures the current screen, then opens the markup screen. */
internal class ScreenshotReportPresenter(private val context: Context, private val activity: () -> Activity?) : ReportPresenter {
    override fun presentReport(prefill: String?) {
        val host = activity()
        if (host == null || host is ReportActivity) return
        Screenshot.capture(host) { bitmap ->
            ReportActivity.pending = ReportActivity.Pending(bitmap, prefill)
            host.startActivity(Intent(context, ReportActivity::class.java))
        }
    }
}

/** Shake detection: a short burst above 2.7 g. */
internal object Shake {
    private const val THRESHOLD_G = 2.7f
    private const val COOLDOWN_MS = 1_500L

    fun events(context: Context): Flow<Unit> = callbackFlow {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            awaitClose()
            return@callbackFlow
        }
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = event.timestamp / NANOS_PER_MS
                if (g > THRESHOLD_G && now - last > COOLDOWN_MS) {
                    last = now
                    trySend(Unit)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }

    private const val NANOS_PER_MS = 1_000_000L
}

internal object Screenshot {
    /** PixelCopy of the activity's window (API 26+), delivered on the main thread. */
    fun capture(activity: Activity, done: (Bitmap?) -> Unit) {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return done(null)
        val bitmap = createBitmap(view.width, view.height)
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                done(
                    if (result ==
                        PixelCopy.SUCCESS
                    ) {
                        bitmap
                    } else {
                        null
                    },
                )
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            done(null)
        }
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        bitmap.compress(Bitmap.CompressFormat.PNG, FULL_QUALITY, it)
    }.toByteArray()

    private const val FULL_QUALITY = 100
}

/** The log tail (this process only) and device state. */
internal class DeviceReportContext(
    private val context: Context,
    private val activity: () -> Activity?,
    private val logTail: Boolean,
    /** The SDK's injected dispatcher for the blocking `logcat` read (review AN-20). */
    private val io: CoroutineDispatcher,
) : ReportContextProvider {

    override suspend fun reportContext(): ReportContext = ReportContext(
        logTail = if (logTail) withContext(io) { readLog() } else null,
        state = state(),
    )

    private fun readLog(): String? {
        val process = try {
            ProcessBuilder("logcat", "-d", "-t", LOG_LINES.toString(), "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
        } catch (_: IOException) {
            return null
        }
        return try {
            process.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (_: IOException) {
            null
        } finally {
            // The child process is always reaped, never left behind (review CQ-L18).
            process.destroy()
            try {
                process.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun state(): Map<String, String> = buildMap {
        activity()?.let { put("screen", it.javaClass.simpleName) }
        context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf {
            it in
                0..PERCENT
        }?.let { put("battery", "$it%") }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        put(
            "network",
            when {
                capabilities == null -> "offline"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            },
        )
        val runtime = Runtime.getRuntime()
        put("memory", "${(runtime.totalMemory() - runtime.freeMemory()) / MB} MB of ${runtime.maxMemory() / MB} MB")
        put("freeDisk", "${StatFs(context.filesDir.path).availableBytes / MB} MB")
        put("os", "Android ${android.os.Build.VERSION.RELEASE}")
        put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    private companion object {
        const val LOG_LINES = 400
        const val PERCENT = 100
        const val MB = 1024L * 1024L
    }
}
