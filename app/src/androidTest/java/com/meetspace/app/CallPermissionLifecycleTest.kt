package com.meetspace.app

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.meetspace.app.ui.CallHost
import com.meetspace.core.data.CallRepository
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.designsystem.MeetSpaceTheme
import com.meetspace.core.media.CallEngine
import com.meetspace.core.media.CallPhase
import com.meetspace.core.model.Meeting
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual CallHost launchers/lifecycle, controlled permission results, no hardware capture or backend. */
class CallPermissionLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var engine: CallEngine? = null
    private val registry = object : ActivityResultRegistry() {
        var request: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(request == null) { "One permission result must not be rebound to another request" }
            request = requestCode
        }
        fun result(granted: Boolean) {
            val code = checkNotNull(request)
            request = null
            assertTrue(dispatchResult(code, granted))
        }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }

    @Before fun prepare() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Owned emulator opt-in only", (args.getString("auditOffline") == "true" || args.getString("quality09") == "true") &&
            (Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("sdk", ignoreCase = true)))
        // The UI context below requests a controlled result. The engine's real permission
        // check remains enabled, providing a positive control without creating a PeerConnection.
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
    }

    @After fun release() {
        engine?.let { current ->
            current.leave(updateMembership = false)
            try { barrier(current) }
            finally {
                field<CoroutineScope>(current, "scope").cancel()
                field<ExecutorCoroutineDispatcher>(current, "dispatcher").close()
            }
        }
        engine = null
    }

    @Test fun ordinaryPausedPermissionDialogReturnCanEnableTheRequestedMicrophone() {
        val current = render()
        request("call-device-mic")
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.onActivity { registry.result(true) }
        barrier(current)
        assertTrue("A normal dialog pause must not suppress the user's permission action", current.state.value.microphone)
        assertNull("Prejoin microphone toggling must not open native capture", field<Any?>(current, "factory"))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test fun permissionReturnAfterOnStopCannotEnableTheMicrophoneOnResume() {
        val current = render()
        request("call-device-mic")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { registry.result(true) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        barrier(current)
        assertFalse(current.state.value.microphone)
        assertNull(field<Any?>(current, "factory"))
    }

    @Test fun permissionForAnOldMeetingCannotOpenTheCameraInAnotherMeeting() {
        val current = render()
        request("call-device-camera")
        current.leave(updateMembership = false); barrier(current)
        current.prepare(meeting.copy(id = "99999999-9999-4999-8999-999999999999")); barrier(current)
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { registry.result(true) }
        barrier(current)
        assertFalse(current.state.value.camera)
        assertEquals("99999999-9999-4999-8999-999999999999", current.state.value.conferenceId)
        assertNull("Rejecting the old result must happen before camera factory/capture creation", field<Any?>(current, "factory"))
    }

    private fun render(): CallEngine {
        val current = CallEngine(context, unavailable(CallRepository::class.java), unavailable(MeetingsRepository::class.java))
        engine = current
        current.prepare(meeting); barrier(current)
        val permissions = object : ContextWrapper(compose.activity) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission in setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        }
        compose.setContent {
            val state by current.state.collectAsState()
            CompositionLocalProvider(LocalContext provides permissions, LocalActivityResultRegistryOwner provides owner) {
                MeetSpaceTheme { CallHost(current, state, authScope = "offline-server|account") }
            }
        }
        compose.onNodeWithTag("call-devices").performScrollTo().performClick()
        return current
    }

    private fun request(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.runOnIdle { assertNotNull("The actual CallHost must launch its permission contract", registry.request) }
    }
    private fun barrier(current: CallEngine) = runBlocking {
        val done = CompletableDeferred<Unit>()
        CallEngine::class.java.declaredMethods.single { it.name == "enqueue" }.apply { isAccessible = true }
            .invoke(current, suspend { done.complete(Unit); Unit })
        withTimeout(10_000) { done.await() }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(current: CallEngine, name: String): T = CallEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.get(current) as T
    @Suppress("UNCHECKED_CAST")
    private fun <T> unavailable(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        throw IllegalStateException("Offline permission test does not provide ${method.name}")
    } as T
    private companion object {
        val meeting = Meeting("88888888-8888-4888-8888-888888888888", "owner", "Offline permission", "", "", "active", Instant.EPOCH, Instant.EPOCH)
    }
}
