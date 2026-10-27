package com.meetspace.app

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real SAF pickers and real storage/HTTPS repository. No ActivityResult or document-provider fakes. */
@SdkSuppress(minSdkVersion = 29)
class LiveMessagingSafTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as LiveCallApplication
    private val backend get() = app.liveMessagingBackend
    private val device get() = UiDevice.getInstance(instrumentation)
    private val createdUris = linkedSetOf<Uri>()
    private var exportedName: String? = null
    private var exportSaved = false
    private var model: MessagingViewModel? = null
    private var groupId: String? = null

    @Before fun prepare() {
        assumeTrue("Private stage07 fixture is opt-in", File(context.filesDir, "live-messaging.properties").isFile)
        check(app.repository === backend.primary) { "Restart the test process after installing private fixture inputs" }
        device.pressBack(); device.pressHome(); device.setOrientationNatural()
    }

    @After fun cleanup() {
        if (File(context.filesDir, "live-messaging.properties").isFile) {
            onMain { model?.suspendWork() }
            runBlocking {
                groupId?.let { runCatching { backend.primary.liveMessaging.deleteGroup(it) } }
                backend.finish()
            }
            app.messagingNotifications.clear()
        }
        // Only exact, randomized names created by this test are ever resolved or removed.
        val cleanupFailures = mutableListOf<String>()
        var exportUri: Uri? = null
        exportedName?.let { name ->
            val uri = runCatching { findDownload(name) }.getOrNull()
            exportUri = uri
            if (uri != null) createdUris += uri
        }
        createdUris.forEach { uri ->
            if (runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0) != 1 && uri != exportUri)
                cleanupFailures += "A test-created URI could not be removed"
        }
        // SAF grants access to a document URI, which need not expose the corresponding MediaStore
        // URI to this UID. The shell observer removes only this test's exact UUID export path.
        exportedName?.let { name ->
            val result = runCatching {
                val path = ownExportPath(name)
                // UiAutomation executes argv, not a shell: names are strictly validated above.
                device.executeShellCommand("rm -f $path")
                check(name !in device.executeShellCommand("ls -1 /sdcard/Download").lineSequence().toSet())
            }
            if (result.isFailure) cleanupFailures += "The test's exact export path could not be removed"
        }
        model = null
        device.pressBack(); device.pressHome(); device.setOrientationNatural(); device.unfreezeRotation()
        assertTrue("Cleanup of the test's own document URIs must succeed", cleanupFailures.isEmpty())
    }

    @Test fun actualDocumentPickSendAndCreateDocumentDownloadPreserveBytes() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val sourceName = "stage07-$suffix-source.txt"
        val outputName = "stage07-$suffix-export.txt"
        exportedName = outputName
        File(context.cacheDir, "stage07-saf-owned-files.txt").writeText("source=$sourceName\nexport=$outputName\n")
        val bytes = "MeetSpace stage07 actual Android SAF verification\n$suffix\n".toByteArray(Charsets.UTF_8)
        val expectedHash = hash(bytes)
        val source = createDownload(sourceName, bytes)
        assertEquals(expectedHash, context.contentResolver.openInputStream(source)!!.use { hash(it.readBytes()) })
        val owner = backend.loginPrimary()
        val member = backend.actor(1)
        val group = owner.liveMessaging.createGroup("Android SAF ${suffix.take(8)}", "Disposable document picker verification",
            listOf(member.state.value.session!!.user.id), UUID.randomUUID().toString())
        groupId = group.id
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("authenticated workspace") { compose.onAllNodesWithTag("tab-messages").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tab-messages").performClick()
            var vm: MessagingViewModel? = null
            scenario.onActivity { vm = ViewModelProvider(it)[MessagingViewModel::class.java] }
            val active = vm!!; model = active
            onMain { active.open(group.id) }
            await("SAF group loaded") { active.state.value.detail.data?.id == group.id && active.state.value.messages.data != null }
            // REST completion precedes collection/recomposition in the activity. Start the
            // actual SAF action only when the current thread's composer is in the UI tree.
            await("visible SAF attachment action") {
                compose.onAllNodesWithTag("message-attach").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag("message-draft").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onNodeWithTag("message-attach").performClick()
            awaitDocumentsUi()
            chooseDownloads()
            progress("select source document")
            withFreshDocumentObject("Exact test source file in Downloads", {
                device.wait(Until.findObject(By.text(sourceName)), 3_000)
            }) { it.click() }
            await("real SAF file uploaded", 45_000) { active.state.value.attachments.any { it.filename == sourceName } && !active.state.value.attachmentBusy }
            val uploaded = active.state.value.attachments.single { it.filename == sourceName }
            assertEquals(expectedHash, uploaded.checksum)
            assertEquals(bytes.size.toLong(), uploaded.size)
            compose.onNodeWithTag("message-draft").performTextInput("Real document picker attachment")
            compose.onNodeWithTag("message-send").performClick()
            await("SAF attachment committed") { active.state.value.messages.data.orEmpty().any { message -> message.attachments.any { it.id == uploaded.id } } && !active.state.value.sending }
            val attached = active.state.value.messages.data!!.flatMap { it.attachments }.single { it.id == uploaded.id }
            assertArrayEquals(bytes, member.liveMessaging.downloadAttachment(group.id, attached))

            compose.onNodeWithTag("attachment-${attached.id}").performClick()
            awaitDocumentsUi()
            chooseDownloads()
            progress("create exported document")
            val documentPackage = device.currentPackageName
            withFreshDocumentObject("DocumentsUI filename field", {
                device.findObject(By.res("android", "title").clazz("android.widget.EditText"))
                    ?: device.findObjects(By.clazz("android.widget.EditText")).singleOrNull()
            }) { it.text = outputName }
            assertTrue(device.takeScreenshot(File(context.cacheDir, "stage07-live-saf-create-document.png")))
            withFreshDocumentObject("DocumentsUI Save button", {
                device.findObject(By.res(documentPackage, "button1"))
                    ?: device.findObject(By.res("android", "button1"))
                    ?: device.findObject(By.text("SAVE"))
                    ?: device.findObject(By.text("СОХРАНИТЬ"))
            }) { save ->
                assertTrue("The actual CreateDocument save button must be enabled", save.isEnabled)
                save.click()
            }
            await("SAF download completed", 45_000) { active.state.value.notice == "Файл сохранён в выбранном месте." && !active.state.value.attachmentBusy }
            exportSaved = true
            var exported: Uri? = null
            try {
                await("exported document indexed", 3_000) { exported = findDownload(outputName); exported != null }
            } catch (_: ComposeTimeoutException) {
                // Scoped storage can intentionally hide DocumentsUI-created rows from this UID.
            } catch (_: SecurityException) {
                // The real SAF result grants a document URI, not permission to enumerate Downloads.
            }
            val mediaHash = exported?.let { uri ->
                createdUris += uri
                try { context.contentResolver.openInputStream(uri)?.use { stream -> hash(stream.readBytes()) } }
                catch (_: SecurityException) { null }
                catch (_: FileNotFoundException) { null }
            }
            val actualHash = mediaHash ?: shellExportHash(outputName)
            assertEquals(expectedHash, actualHash)
            File(context.cacheDir, "stage07-live-evidence.txt").appendText("saf:systemOpenDocument=true;systemCreateDocument=true;actualUpload=true;actualDownload=true;sha256Match=true;exportObserver=${if (mediaHash != null) "MediaStore" else "shell"}\n")
        }
        bytes.fill(0)
    }

    private fun createDownload(name: String, bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the test's Downloads document")
        createdUris += uri
        resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun findDownload(name: String): Uri? {
        require(name.startsWith("stage07-") && name.endsWith("-export.txt"))
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return context.contentResolver.query(collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/"), null)?.use { cursor ->
            check(cursor.count <= 1) { "The randomized test export name must be unique" }
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }

    private fun ownExportPath(name: String): String {
        require(name == exportedName && Regex("stage07-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-export\\.txt").matches(name))
        return "/sdcard/Download/$name"
    }

    private fun shellExportHash(name: String): String {
        check(exportSaved)
        progress("verify exact SAF export with shell observer")
        val path = ownExportPath(name)
        val output = device.executeShellCommand("sha256sum $path").trim()
        return Regex("^([0-9a-fA-F]{64})\\s+${Regex.escape(path)}$").matchEntire(output)?.groupValues?.get(1)?.lowercase()
            ?: error("Could not hash the test's exact CreateDocument export")
    }

    private fun awaitDocumentsUi() {
        progress("wait actual system DocumentsUI")
        assertTrue("A system document picker must be foreground", device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000)
            || device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 8_000))
        device.waitForIdle()
    }

    private fun chooseDownloads() {
        progress("choose system Downloads root")
        val documentPackage = device.currentPackageName
        check(documentPackage in setOf("com.google.android.documentsui", "com.android.documentsui"))
        // Anchor the root to the roots list: the content toolbar can also say "Downloads".
        val download = By.text(Pattern.compile("Downloads|Загрузки"))
            .hasAncestor(By.res(documentPackage, "roots_list"))
        if (!device.hasObject(download)) {
            withFreshDocumentObject("DocumentsUI roots drawer button", {
                device.findObject(By.desc("Show roots"))
                    ?: device.findObject(By.desc("Открыть панель навигации"))
                    ?: device.findObject(By.desc("Показать корни"))
            }) { it.click() }
        }
        withFreshDocumentObject("Downloads entry in DocumentsUI roots list", {
            device.wait(Until.findObject(download), 3_000)
        }) { it.click() }
        device.waitForIdle()
    }

    private fun withFreshDocumentObject(label: String, find: () -> UiObject2?, action: (UiObject2) -> Unit) {
        var stale: StaleObjectException? = null
        repeat(3) {
            device.waitForIdle(1_500)
            try {
                val target = find()
                if (target != null) { action(target); return }
            } catch (failure: StaleObjectException) {
                // Drawer/window transitions replace accessibility nodes. Re-query, never reuse one.
                stale = failure
            }
        }
        throw AssertionError("$label was absent or stale after three fresh lookups", stale)
    }
    private fun await(label: String, timeout: Long = 30_000, predicate: () -> Boolean) {
        progress(label); compose.waitUntil(timeoutMillis = timeout, condition = predicate)
    }
    private fun progress(label: String) { File(context.cacheDir, "stage07-saf-progress.txt").writeText("step=$label") }
    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync(block) }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
