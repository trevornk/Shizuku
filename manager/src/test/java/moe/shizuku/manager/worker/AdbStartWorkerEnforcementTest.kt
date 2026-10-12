package moe.shizuku.manager.worker

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.work.WorkInfo.Companion.STOP_REASON_CONSTRAINT_CONNECTIVITY
import androidx.work.impl.constraints.ConstraintsState
import androidx.work.impl.constraints.ConstraintsState.ConstraintsMet
import androidx.work.impl.constraints.ConstraintsState.ConstraintsNotMet
import androidx.work.impl.constraints.NetworkRequestConstraintController
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Drives the *real* WorkManager 2.10.4 `NetworkRequestConstraintController` (not a re-implementation)
 * with the constraints `AdbStartWorker.enqueue` builds, to show the Wi-Fi requirement is enforced by
 * the library and not merely stated in the request.
 *
 * On API 30+ WorkManager evaluates `NetworkRequest.canBeSatisfiedBy` against the DEFAULT network,
 * which on a device with a VPN is the VPN network. On API 28/29 the platform matches the request
 * itself (registerNetworkCallback), which Robolectric cannot simulate; that range is covered at
 * request level in [AdbStartWorkerConstraintsTest] only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdbStartWorkerEnforcementTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val cm: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)
    private lateinit var network: Network

    @Before
    fun setUp() {
        shadowOf(cm).setNetworkCallbacksEnabled(true)
        network = cm.activeNetwork!!
        setDefaultNetwork(NetworkFixtures.cellularNotMetered())
    }

    private fun setDefaultNetwork(nc: NetworkCapabilities) =
        shadowOf(cm).setNetworkCapabilities(network, nc)

    private fun defaultNetworkChanged(nc: NetworkCapabilities) {
        setDefaultNetwork(nc)
        shadowOf(cm).networkCallbacks.toList().forEach { it.onCapabilitiesChanged(network, nc) }
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

    private fun trackedStates(block: (CopyOnWriteArrayList<ConstraintsState>) -> Unit) = runBlocking {
        val controller = NetworkRequestConstraintController(cm, timeoutMs = 60_000)
        val states = CopyOnWriteArrayList<ConstraintsState>()
        val job = launch(Dispatchers.Default) {
            controller.track(AdbStartWorker.buildConstraints(wifiRequired = true).also {
                assertNotNull("no NetworkRequest to track", it.requiredNetworkRequest)
            }).collect { states += it }
        }
        try {
            await("initial constraint state") { states.isNotEmpty() }
            block(states)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun unmeteredCellularAsDefaultNetworkDoesNotMeetTheConstraint() {
        trackedStates { states ->
            assertEquals(ConstraintsNotMet(STOP_REASON_CONSTRAINT_CONNECTIVITY), states.last())
        }
    }

    @Test
    fun joiningWifiMeetsTheConstraintAndLosingItAgainUnmeetsIt() {
        trackedStates { states ->
            defaultNetworkChanged(NetworkFixtures.wifiMetered())
            await("constraint met on Wi-Fi") { states.last() == ConstraintsMet }

            defaultNetworkChanged(NetworkFixtures.cellularNotMetered())
            await("constraint unmet on cellular") { states.last() is ConstraintsNotMet }
        }
    }

    @Test
    fun vpnOverWifiMeetsTheConstraintButVpnOverCellularDoesNot() {
        trackedStates { states ->
            defaultNetworkChanged(NetworkFixtures.vpnOverWifi())
            await("constraint met on VPN over Wi-Fi") { states.last() == ConstraintsMet }

            defaultNetworkChanged(NetworkFixtures.vpnOverCellular())
            await("constraint unmet on VPN over cellular") { states.last() is ConstraintsNotMet }
        }
    }
}
