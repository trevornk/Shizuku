package moe.shizuku.manager.worker

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class NoopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}

/**
 * End to end through a real, initialised WorkManager: work enqueued with `AdbStartWorker`'s Wi-Fi
 * constraints must not run on unmetered cellular and must run once Wi-Fi is the default network.
 *
 * Its own class and SDK level (35) on purpose: WorkManager's SharedNetworkCallback is process-wide
 * state, and Robolectric shares one sandbox per SDK, so this must not share a sandbox with
 * [AdbStartWorkerEnforcementTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AdbStartWorkerWorkManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val cm: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)
    private lateinit var network: Network

    @Before
    fun setUp() {
        shadowOf(cm).setNetworkCallbacksEnabled(true)
        network = cm.activeNetwork!!
        shadowOf(cm).setNetworkCapabilities(network, NetworkFixtures.cellularNotMetered())
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        WorkManagerImpl.setDelegate(null)
    }

    private fun await(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) {
                org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag.startsWith("WM-") }
                    .forEach { println("WMLOG ${it.tag}: ${it.msg}") }
                fail("Timed out waiting for: $message")
            }
            Thread.sleep(20)
        }
    }

    /** The JobScheduler WorkManager actually scheduled with (its API 34+ namespaced instance). */
    private fun workManagerJobScheduler(): JobScheduler {
        val scheduler = WorkManagerImpl.getInstance(context).schedulers
            .first { it.javaClass.simpleName == "SystemJobScheduler" }
        val field = scheduler.javaClass.declaredFields.first { JobScheduler::class.java.isAssignableFrom(it.type) }
        field.isAccessible = true
        return field.get(scheduler) as JobScheduler
    }

    @Test
    fun workWaitsOnCellularAndRunsOnceWifiIsTheDefaultNetwork() {
        WorkManager.initialize(
            context,
            Configuration.Builder()
                .setExecutor(Executors.newSingleThreadExecutor())
                .setDefaultProcessName(Application.getProcessName())
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build(),
        )
        val wm = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<NoopWorker>()
            .setConstraints(AdbStartWorker.buildConstraints(wifiRequired = true))
            .build()
        wm.enqueue(request).result.get(10, TimeUnit.SECONDS)
        fun state() = wm.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)?.state

        // JobScheduler (the persistent backstop) was handed the same Wi-Fi request.
        val jobs = workManagerJobScheduler()
        await("SystemJobScheduler to hand the job to JobScheduler") { jobs.allPendingJobs.isNotEmpty() }
        val jobRequest = jobs.allPendingJobs.single().requiredNetwork!!
        assertTrue(jobRequest.canBeSatisfiedBy(NetworkFixtures.wifiMetered()))
        assertTrue(jobRequest.canBeSatisfiedBy(NetworkFixtures.vpnOverWifi()))
        assertFalse(jobRequest.canBeSatisfiedBy(NetworkFixtures.cellularNotMetered()))
        assertFalse(jobRequest.canBeSatisfiedBy(NetworkFixtures.vpnOverCellular()))

        // GreedyScheduler tracks the constraint in-process and must not run the work on cellular.
        await("GreedyScheduler to register its default network callback") {
            shadowOf(cm).networkCallbacks.isNotEmpty()
        }
        repeat(25) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(WorkInfo.State.ENQUEUED, state())

        val wifi = NetworkFixtures.wifiMetered()
        shadowOf(cm).setNetworkCapabilities(network, wifi)
        shadowOf(cm).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, wifi) }
        await("work to succeed once Wi-Fi is the default network") { state() == WorkInfo.State.SUCCEEDED }
    }
}
