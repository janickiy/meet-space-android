package com.meetspace.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import com.meetspace.core.model.ChatMessageInput
import com.meetspace.core.model.MeetingInput
import com.meetspace.core.model.MeetingTransition
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual MainActivity → MessagingViewModel → production repository → HTTPS/WSS backend/MinIO. */
class LiveMessagingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as LiveCallApplication
    private val backend get() = app.liveMessagingBackend
    private val device get() = UiDevice.getInstance(instrumentation)
    private var model: MessagingViewModel? = null

    @Before fun prepare() {
        assumeTrue("Private stage07 fixture is opt-in", File(context.filesDir, "live-messaging.properties").isFile)
        check(app.repository === backend.primary) { "Restart the test process after installing private fixture inputs" }
        device.pressBack(); device.pressHome(); device.setOrientationNatural()
    }
    @After fun cleanup() {
        if (File(context.filesDir, "live-messaging.properties").isFile) {
            onMain { model?.suspendWork() }
            runBlocking { backend.finish() }
            app.messagingNotifications.clear()
        }
        model = null
        device.pressBack(); device.pressHome(); device.setOrientationNatural(); device.unfreezeRotation()
    }

    @Test fun realDirectMessagesReplyEditDeleteAndRotation() = runBlocking {
        val owner = backend.loginPrimary(); val member = backend.actor(1)
        val direct = owner.liveMessaging.createDirect(member.state.value.session!!.user.id)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = openWorkspace(scenario)
            onMain { vm.open(direct.id) }
            await("direct loaded") { vm.state.value.detail.data?.id == direct.id && vm.state.value.messages.data != null }
            awaitComposer()
            val sentText = "Android UI ${UUID.randomUUID().toString().take(8)}"
            compose.onNodeWithTag("message-draft").performTextInput(sentText)
            compose.onNodeWithTag("message-send").performClick()
            await("actual sent message") { vm.state.value.messages.data.orEmpty().any { it.text == sentText } && !vm.state.value.sending }
            val sent = vm.state.value.messages.data!!.single { it.text == sentText }
            assertTrue(member.liveMessaging.messages(direct.id).items.any { it.id == sent.id })
            compose.onNodeWithTag("message-menu-${sent.id}").performClick()
            compose.onNodeWithTag("message-reply-${sent.id}").performClick()
            compose.onNodeWithTag("message-draft").performTextInput("UI reply")
            compose.onNodeWithTag("message-send").performClick()
            await("reply persisted") { vm.state.value.messages.data.orEmpty().any { it.replyTo == sent.id && it.text == "UI reply" } }
            val reply = vm.state.value.messages.data!!.single { it.replyTo == sent.id && it.text == "UI reply" }
            assertEquals(sent.id, member.liveMessaging.messages(direct.id).items.first { it.id == reply.id }.replyPreview!!.id)
            // The first row may move outside a compact viewport after a reply; use actual VM action
            // to select its edit operation, then the visible composer submits through real UI.
            onMain { vm.edit(sent) }
            await("edit composer is visible") { tagged("message-draft") && vm.state.value.editing?.id == sent.id }
            compose.onNodeWithTag("message-draft").performTextReplacement("UI edited")
            compose.onNodeWithTag("message-send").performClick()
            await("edit acknowledged") { vm.state.value.messages.data.orEmpty().any { it.id == sent.id && it.text == "UI edited" && it.version > sent.version } }
            onMain { vm.draft("Transient draft survives activity recreation") }
            scenario.recreate()
            var retained = false
            scenario.onActivity { retained = ViewModelProvider(it)[MessagingViewModel::class.java] === vm }
            assertTrue(retained)
            compose.onNodeWithTag("message-draft").assertTextContains("Transient draft survives activity recreation")
            val initialOrientation = context.resources.configuration.orientation
            device.setOrientationLeft()
            await("rotated compose workspace") { context.resources.configuration.orientation != initialOrientation && compose.onAllNodesWithTag("message-draft").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Transient draft survives activity recreation", vm.state.value.draft)
            screenshot("stage07-live-direct.png")
            device.setOrientationNatural()
            onMain { vm.cancelCompose(); vm.delete(vm.state.value.messages.data!!.first { it.id == sent.id }) }
            await("delete tombstone reconciled") { vm.state.value.messages.data.orEmpty().any { it.id == sent.id && it.deletedAt != null && it.text.isEmpty() } }
            assertNotNull(member.liveMessaging.messages(direct.id).items.first { it.id == sent.id }.deletedAt)
            evidence("direct", "send=true;reply=true;edit=true;delete=true;activityRecreation=true;rotation=true")
        }
    }

    @Test fun realGroupFoldersMinioFilesAndNotificationCenter() = runBlocking {
        val owner = backend.loginPrimary(); val member = backend.actor(1)
        var groupId: String? = null; var folderId: String? = null
        val meeting = owner.liveMeetings.createMeeting(MeetingInput("Android live folder meeting"))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val vm = openWorkspace(scenario)
                val name = "Android group ${UUID.randomUUID().toString().take(6)}"
                onMain { vm.createGroup(name, "Disposable real integration", listOf(member.state.value.session!!.user.id)) }
                await("real group created") { vm.state.value.detail.data?.name == name && !vm.state.value.busy && vm.state.value.messages.data != null }
                awaitComposer()
                groupId = vm.state.value.selectedId!!
                val gid = groupId!!
                onMain { vm.loadMembers() }
                await("real group roster") { vm.state.value.members.data?.size == 2 }
                assertTrue(member.liveMessaging.groupMembers(gid).any { it.id == owner.state.value.session!!.user.id && it.role == "owner" })
                val payload = "Actual Android private MinIO attachment ${UUID.randomUUID()}\n".toByteArray()
                val expectedHash = hash(payload)
                onMain { vm.upload("android-verification.txt", "text/plain") { payload.copyOf() } }
                await("MinIO upload finalized") { vm.state.value.attachments.size == 1 && !vm.state.value.attachmentBusy }
                val pending = vm.state.value.attachments.single()
                assertEquals(expectedHash, pending.checksum)
                compose.onNodeWithTag("message-draft").performTextInput("Android file proof")
                compose.onNodeWithTag("message-send").performClick()
                await("attachment committed to message") { vm.state.value.messages.data.orEmpty().any { it.attachments.any { a -> a.id == pending.id } } && !vm.state.value.sending }
                val attached = vm.state.value.messages.data!!.flatMap { it.attachments }.single { it.id == pending.id }
                val exportedHash = AtomicReference<String?>()
                onMain { vm.export(attached) { bytes -> exportedHash.set(hash(bytes)) } }
                await("actual authenticated group file downloaded") { !vm.state.value.attachmentBusy && exportedHash.get() != null }
                assertEquals(expectedHash, exportedHash.get())
                assertArrayEquals(payload, member.liveMessaging.downloadAttachment(gid, attached))
                payload.fill(0)
                onMain { vm.createFolder("Android live ${UUID.randomUUID().toString().take(6)}") }
                await("personal folder created") { vm.state.value.selectedFolderId != null && !vm.state.value.busy }
                folderId = vm.state.value.selectedFolderId!!
                val fid = folderId!!
                onMain { vm.folderMembership(fid, "conversation", gid, true) }
                await("group included in folder") { !vm.state.value.busy && vm.state.value.folderEntries.data.orEmpty().any { it.id == gid } }
                onMain { vm.folderMembership(fid, "conference", meeting.id, true) }
                await("mixed folder displayed") { !vm.state.value.busy && vm.state.value.folderEntries.data.orEmpty().any { it.id == meeting.id } }
                assertEquals(2, owner.liveMessaging.folderItems(fid).items.size)
                screenshot("stage07-live-group-folder.png")
                val direct = member.liveMessaging.createDirect(owner.state.value.session!!.user.id)
                member.liveMessaging.sendMessage(direct.id, ChatMessageInput(UUID.randomUUID().toString(), "Live Android inbox proof"))
                onMain { vm.loadNotifications() }
                await("real inbox notification loaded") { vm.state.value.notifications.data.orEmpty().any { it.conversationId == direct.id } }
                // A phone thread owns the whole viewport; return through its visible toolbar
                // before opening the current, shared notification center from the list header.
                if (tagged("conversation-back")) compose.onNodeWithTag("conversation-back").performClick()
                await("chat list notification bell") { tagged("notification-bell") }
                compose.onNodeWithTag("notification-bell").performClick()
                await("notification center") { tagged("notification-center") && tagged("notification-events") }
                screenshot("stage07-live-notifications.png")
                device.pressBack()
                val notification = vm.state.value.notifications.data!!.first { it.conversationId == direct.id }
                onMain { vm.readNotification(notification.id) }
                await("notification read persisted") { vm.state.value.notifications.data.orEmpty().any { it.id == notification.id && it.readAt != null } }
                assertNotNull(owner.liveMessaging.notifications().items.first { it.id == notification.id }.readAt)
                evidence("group-files-folders", "group=true;members=2;upload=true;download=true;sha256Match=true;mixedFolderItems=2;notifications=true")
            }
        } finally {
            folderId?.let { runCatching { owner.liveMessaging.deleteFolder(it) } }
            groupId?.let { runCatching { owner.liveMessaging.deleteGroup(it) } }
            owner.liveMeetings.transitionMeeting(meeting.id, MeetingTransition.CANCEL)
        }
    }

    @Test fun realOfflineHistoryReconnectAndLogoutCleanup() = runBlocking {
        val owner = backend.loginPrimary(); val member = backend.actor(1)
        val direct = owner.liveMessaging.createDirect(member.state.value.session!!.user.id)
        val marker = member.liveMessaging.sendMessage(direct.id, ChatMessageInput(UUID.randomUUID().toString(), "Retained offline history ${UUID.randomUUID().toString().take(6)}"))
        val wifi = device.executeShellCommand("settings get global wifi_on").trim() == "1"
        val data = device.executeShellCommand("settings get global mobile_data").trim() == "1"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val vm = openWorkspace(scenario)
            onMain { vm.open(direct.id) }
            await("online history loaded") { vm.state.value.messages.data.orEmpty().any { it.id == marker.id } }
            awaitComposer()
            try {
                device.executeShellCommand("svc data disable"); device.executeShellCommand("svc wifi disable")
                await("actual user WSS disconnected", 35_000) { vm.state.value.realtimeLabel.contains("Нет связи") || vm.state.value.realtimeLabel.contains("Восстанавливаем") }
                onMain { vm.actions.refreshMessages() }
                await("offline refresh failure preserves cache", 25_000) { !vm.state.value.messages.loading && vm.state.value.messages.error != null }
                assertTrue(vm.state.value.messages.data.orEmpty().any { it.id == marker.id })
                screenshot("stage07-live-offline.png")
            } finally {
                device.executeShellCommand("svc wifi ${if (wifi) "enable" else "disable"}")
                device.executeShellCommand("svc data ${if (data) "enable" else "disable"}")
            }
            await("actual user WSS reconnected", 50_000) { vm.state.value.realtimeLabel == "Обновления подключены" && vm.state.value.messages.error == null }
            // Keep transport available but stop this device's stream; another real repository
            // sends while it is disconnected, then REST reconciliation restores the missing row.
            onMain { vm.foreground(false) }
            val missed = member.liveMessaging.sendMessage(direct.id, ChatMessageInput(UUID.randomUUID().toString(), "Persisted while user stream was stopped"))
            assertFalse(vm.state.value.messages.data.orEmpty().any { it.id == missed.id })
            onMain { vm.foreground(true) }
            await("missed history recovered by REST") { vm.state.value.realtimeLabel == "Обновления подключены" && vm.state.value.messages.data.orEmpty().any { it.id == missed.id } }
            onMain { vm.draft("Private unsent content") }
            owner.logout()
            await("logout clears all messaging memory") {
                val s = vm.state.value
                s.selfId.isEmpty() && s.selectedId == null && s.messages.data == null && s.draft.isEmpty() && s.attachments.isEmpty() && s.conversations.data == null
            }
            assertNull(owner.state.value.session)
            evidence("offline-reconnect-logout", "radioLoss=true;cachedHistoryPreserved=true;freshWss=true;restResync=true;logoutMemoryCleared=true")
        }
    }

    @Test fun realForegroundWssNotificationOpensInboxAndClearsOnLogout() = runBlocking {
        val hadPermission = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java)
        fun posted() = manager.activeNotifications.firstOrNull { it.tag == "meetspace-messaging" && it.id == 707 }
        try {
            if (!hadPermission && Build.VERSION.SDK_INT >= 33)
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            app.messagingNotifications.clear()
            val owner = backend.loginPrimary(); val member = backend.actor(1)
            val direct = owner.liveMessaging.createDirect(member.state.value.session!!.user.id)
            owner.liveMessaging.setConversationNotifications(direct.id, true)
            // The notification changes MainActivity's Intent, so ActivityScenario's
            // original launch-intent filter cannot own this activity's later teardown.
            val activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            try {
                val vm = openWorkspace {
                    var value: MessagingViewModel? = null
                    onMain { value = ViewModelProvider(activity)[MessagingViewModel::class.java] }
                    checkNotNull(value)
                }
                // Keep the real user WSS foreground listener active while another app tab is visible.
                compose.onNodeWithTag("tab-calls").performClick()
                await("meetings before notification") { compose.onAllNodesWithTag("messaging-workspace").fetchSemanticsNodes().isEmpty() }
                app.messagingNotifications.clear()
                val sentAt = System.currentTimeMillis()
                val rawBody = "PRIVATE_RAW_FIXTURE_MESSAGE_${UUID.randomUUID()}"
                val message = member.liveMessaging.sendMessage(direct.id, ChatMessageInput(UUID.randomUUID().toString(), rawBody))
                await("actual WSS notification reaches Android manager", 25_000) {
                    vm.state.value.notifications.data.orEmpty().any { it.messageId == message.id } &&
                        posted()?.postTime?.let { it >= sentAt } == true
                }
                val notification = posted()!!.notification
                assertEquals("MeetSpace", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertEquals("Новое событие в чате", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                assertFalse(notification.extras.keySet().any { key -> notification.extras.get(key)?.toString()?.contains(rawBody) == true })
                assertNotNull(notification.contentIntent)
                onMain { notification.contentIntent.send() }
                await("notification intent opens real inbox") {
                    // OPEN_INBOX now opens the shared panel over the current destination.
                    // Its contents must contain the event received through the real WSS.
                    tagged("notification-center") && tagged("notification-events") &&
                        tagged("notification-event-${vm.state.value.notifications.data.orEmpty().firstOrNull { it.messageId == message.id }?.id}")
                }
                screenshot("stage07-live-system-notification-inbox.png")
                // PendingIntent.send does not emulate a shade click: auto-cancel has not
                // removed the posted notification, so this explicitly tests logout cleanup.
                assertNotNull(posted())
                owner.logout()
                await("logout clears posted notification") { posted() == null && owner.state.value.session == null }
                evidence("foreground-os-notification", "actualWss=true;osNotification707=true;genericContent=true;rawBodyExcluded=true;inboxIntent=true;logoutCleared=true")
            } finally {
                finishMessagingActivities()
            }
        } finally {
            app.messagingNotifications.clear()
            // Revoking a runtime permission kills this package and the instrumentation
            // process. The isolated test host restores permissions after the runner exits
            // (or removes its disposable AVD); never revoke from in-process cleanup.
        }
    }

    private fun openWorkspace(scenario: ActivityScenario<MainActivity>): MessagingViewModel = openWorkspace {
        var value: MessagingViewModel? = null
        scenario.onActivity { value = ViewModelProvider(it)[MessagingViewModel::class.java] }
        checkNotNull(value)
    }
    private fun openWorkspace(readModel: () -> MessagingViewModel): MessagingViewModel {
        await("authenticated workspace", 30_000) { compose.onAllNodesWithTag("tab-messages").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-messages").performClick()
        await("messaging workspace") { compose.onAllNodesWithTag("messaging-workspace").fetchSemanticsNodes().isNotEmpty() }
        val vm = readModel()
        model = vm
        await("actual WSS and initial list") { vm.state.value.realtimeLabel == "Обновления подключены" && vm.state.value.conversations.data != null }
        return vm
    }
    private fun finishMessagingActivities() {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        fun liveActivities() = Stage.values().filter { it != Stage.DESTROYED }
            .flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>().distinct()
        onMain { liveActivities().forEach { it.finish() } }
        instrumentation.waitForIdleSync()
        await("notification activity teardown", 10_000) {
            var empty = false
            onMain { empty = liveActivities().isEmpty() }
            empty
        }
    }
    private fun await(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        File(context.cacheDir, "stage07-progress.txt").writeText("step=$label")
        try { compose.waitUntil(timeoutMillis = timeout) { predicate() } }
        catch (failure: ComposeTimeoutException) {
            // Record only shape/lifecycle facts: never fixture IDs, credentials or message text.
            val state = model?.state?.value
            val facts = buildString {
                append("step=$label;authenticated=${app.repository.state.value.session != null}")
                state?.let {
                    append(";selfPresent=${it.selfId.isNotEmpty()};selected=${it.selectedId != null};busy=${it.busy}")
                    append(";detail=${it.detail.data != null};detailLoading=${it.detail.loading};detailError=${it.detail.error != null}")
                    append(";messages=${it.messages.data != null};messagesLoading=${it.messages.loading};messagesError=${it.messages.error != null}")
                    append(";error=${it.error != null};notificationError=${it.notifications.error != null}")
                }
                listOf("workspace", "messaging-workspace", "conversation-detail", "message-draft", "notification-center").forEach {
                    append(";$it=${tagged(it)}")
                }
            }
            File(context.cacheDir, "stage07-timeout-shape.txt").writeText(facts)
            throw AssertionError("Live messaging wait failed: $facts", failure)
        }
    }
    private fun tagged(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun awaitComposer() = await("visible conversation composer") { tagged("conversation-detail") && tagged("message-draft") && tagged("message-send") }
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync(block) }
    private fun screenshot(name: String) { compose.waitForIdle(); assertTrue(device.takeScreenshot(File(context.cacheDir, name))) }
    private fun evidence(case: String, facts: String) { File(context.cacheDir, "stage07-live-evidence.txt").appendText("$case:$facts\n") }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
