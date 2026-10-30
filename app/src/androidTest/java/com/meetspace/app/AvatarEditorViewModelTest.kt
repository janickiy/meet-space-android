package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Bitmap ownership with the retained editor; no production account or image. */
class AvatarEditorViewModelTest {
    @Test fun acknowledgedOriginalAndCropSurviveClosingButCancelledReplacementDoesNotOverwriteThem() = runBlocking {
        val owner = withContext(Dispatchers.Main) { owner() }
        val model = owner.second
        val source = picture(Color.RED)
        val replacement = picture(Color.BLUE)
        try {
            withContext(Dispatchers.Main) { model.bind("account|profile"); model.open("account|profile", "Фото профиля", null) { source } }
            ready(model)
            val expectedCrop = AvatarCrop(2f, .5f, -.25f)
            withContext(Dispatchers.Main) { model.crop("account|profile", expectedCrop) }
            val exported = CompletableDeferred<ByteArray>()
            withContext(Dispatchers.Main) { model.submit("account|profile", 4) { exported.complete(it) } }
            withTimeout(5000) { exported.await() }.fill(0)
            withContext(Dispatchers.Main) {
                model.acknowledge("account|profile", 5, null, "version-1")
                assertNull(model.state.value)
                model.open("account|profile", "Фото профиля", "version-1") { error("Saved original must be reused") }
                assertSame(source, model.state.value!!.source)
                assertEquals(expectedCrop, model.state.value!!.crop)
                model.replace("account|profile") { replacement }
            }
            ready(model)
            assertSame(replacement, model.state.value!!.source)
            withContext(Dispatchers.Main) {
                model.close("account|profile")
                model.open("account|profile", "Фото профиля", "version-1") { error("Cancelled draft must not replace the saved original") }
                assertSame(source, model.state.value!!.source)
                assertEquals(expectedCrop, model.state.value!!.crop)
            }
        } finally { withContext(Dispatchers.Main) { owner.first.clear() }; source.recycle(); replacement.recycle() }
    }

    @Test fun errorsPreserveTheDraftAndAChangedAccountRejectsLateLoadingResults() = runBlocking {
        val owner = withContext(Dispatchers.Main) { owner() }
        val model = owner.second
        val source = picture(Color.RED)
        val late = picture(Color.BLUE)
        try {
            withContext(Dispatchers.Main) { model.bind("a|group"); model.open("a|group", "Фото группы", "v1") { source } }
            ready(model)
            val crop = AvatarCrop(1.5f, .3f, .2f)
            withContext(Dispatchers.Main) { model.crop("a|group", crop); model.replace("a|group") { throw IllegalArgumentException("Не удалось прочитать фото.") } }
            ready(model)
            assertSame(source, model.state.value!!.source)
            assertEquals(crop, model.state.value!!.crop)
            assertNotNull(model.state.value!!.error)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { model.replace("a|group") {
                withContext(NonCancellable) { entered.complete(Unit); release.await(); late }
            } }
            withTimeout(5000) { entered.await() }
            withContext(Dispatchers.Main) { model.bind("b|group"); model.open("b|group", "Фото группы", null) { null } }
            release.complete(Unit)
            ready(model)
            withTimeout(5000) { while (!withContext(Dispatchers.Main) { late.isRecycled }) delay(1) }
            assertEquals("b|group", model.state.value!!.key)
            assertNull(model.state.value!!.source)
        } finally { withContext(Dispatchers.Main) { owner.first.clear() }; source.recycle(); late.recycle() }
    }

    @Test fun failedUploadStaysOpenAndOnlyANewCompletionRevisionCanAcknowledgeIt() = runBlocking {
        val owner = withContext(Dispatchers.Main) { owner() }
        val model = owner.second
        val source = picture(Color.GREEN)
        try {
            withContext(Dispatchers.Main) { model.bind("account|profile"); model.open("account|profile", "Фото профиля", null) { source } }
            ready(model)
            val exported = CompletableDeferred<ByteArray>()
            withContext(Dispatchers.Main) { model.submit("account|profile", 10) { exported.complete(it) } }
            withTimeout(5000) { exported.await() }.fill(0)
            withContext(Dispatchers.Main) {
                model.close("account|profile")
                assertTrue(model.state.value!!.saving)
                model.acknowledge("account|profile", 10, null, "version-1")
                assertTrue(model.state.value!!.saving)
                model.acknowledge("account|profile", 11, "Нет соединения", null)
                assertSame(source, model.state.value!!.source)
                assertFalse(model.state.value!!.saving)
                assertNull(model.state.value!!.pendingRevision)
                assertEquals("Нет соединения", model.state.value!!.error)
                model.close("account|profile")
                assertNull(model.state.value)
                assertEquals(0, model.cachedBytes)
            }
        } finally { withContext(Dispatchers.Main) { owner.first.clear() }; source.recycle() }
    }

    @Test fun cachedOriginalNeverCrossesAResourceVersionAndCacheRemainsBounded() = runBlocking {
        val owner = withContext(Dispatchers.Main) { owner() }
        val model = owner.second
        val pictures = mutableListOf<Bitmap>()
        try {
            for (index in 0..2) {
                val source = picture(Color.RED, 1200).also(pictures::add)
                val key = "account|group-$index"
                withContext(Dispatchers.Main) { model.bind(key); model.open(key, "Фото группы", null) { source } }
                ready(model)
                val output = CompletableDeferred<ByteArray>()
                withContext(Dispatchers.Main) { model.submit(key, index.toLong()) { output.complete(it) } }
                withTimeout(5000) { output.await() }.fill(0)
                withContext(Dispatchers.Main) { model.acknowledge(key, index + 1L, null, "v1") }
                assertTrue(model.cachedBytes <= 8 * 1024 * 1024)
            }
            val reloaded = picture(Color.BLUE).also(pictures::add)
            withContext(Dispatchers.Main) {
                model.bind("account|group-0")
                model.open("account|group-0", "Фото группы", "v1") { reloaded }
            }
            ready(model)
            assertSame("Oldest original must have been evicted to respect 8 MiB", reloaded, model.state.value!!.source)
            val fresh = picture(Color.BLUE).also(pictures::add)
            withContext(Dispatchers.Main) {
                model.bind("account|group-2")
                model.open("account|group-2", "Фото группы", "v2") { fresh }
            }
            ready(model)
            assertSame(fresh, model.state.value!!.source)
        } finally { withContext(Dispatchers.Main) { owner.first.clear() }; pictures.forEach(Bitmap::recycle) }
    }

    private suspend fun ready(model: AvatarEditorViewModel) = withTimeout(5000) {
        model.state.filterNotNull().filter { !it.loading }.first()
    }
    private fun owner(): Pair<ViewModelStore, AvatarEditorViewModel> {
        val model = AvatarEditorViewModel()
        return ViewModelStore().apply { put("editor", model) } to model
    }
    private fun picture(color: Int, edge: Int = 64) = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}
