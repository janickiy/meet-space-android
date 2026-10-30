package com.meetspace.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal val LocalAvatarImages = staticCompositionLocalOf<AvatarImageLoader?> { null }

/** An instance belongs to one origin/account and holds only bounded, private RAM thumbnails. */
internal class AvatarImageLoader(
    private val user: suspend (String) -> ByteArray,
    private val group: suspend (String) -> ByteArray,
    private val isCurrent: () -> Boolean,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private val permits = Semaphore(2)
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    internal val cachedBytes get() = synchronized(lock) { cache.size() }

    /** The crop editor needs the original server image, rather than its list thumbnail. */
    suspend fun loadSource(id: String, isGroup: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        if (synchronized(lock) { closed || !isCurrent() }) return@withContext null
        val bytes = (if (isGroup) group else user)(id)
        var decoded: Bitmap? = null
        try {
            decoded = decodeAvatarCropSource(bytes)
            currentCoroutineContext().ensureActive()
            if (synchronized(lock) { closed || !isCurrent() }) return@withContext null
            decoded.also { decoded = null }
        } finally { bytes.fill(0); decoded?.recycle() }
    }

    suspend fun load(id: String, version: String, isGroup: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${if (isGroup) "group" else "user"}|$id|$version"
        permits.withPermit {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (closed || !isCurrent()) return@withPermit null
                cache.get(key)?.let { return@withPermit it }
            }
            var bitmap: Bitmap? = null
            var published = false
            try {
                val bytes = (if (isGroup) group else user)(id)
                bitmap = try { decodeAvatarThumbnail(bytes) } finally { bytes.fill(0) }
                currentCoroutineContext().ensureActive()
                val decoded = bitmap
                if (decoded != null) synchronized(lock) {
                    if (closed || !isCurrent()) return@withPermit null
                    cache.put(key, decoded)
                    published = true
                }
                decoded
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            finally { if (!published) bitmap?.recycle() }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        // Compose may still be drawing a departing frame: release references without recycling it.
        cache.evictAll()
    }
}

@Composable
internal fun AvatarImage(
    name: String,
    id: String? = null,
    version: String? = null,
    group: Boolean = false,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
    shape: Shape = if (group) RoundedCornerShape(14.dp) else CircleShape,
) {
    val loader = LocalAvatarImages.current
    val bitmap by produceState<Bitmap?>(null, loader, id, version, group) {
        value = null
        if (loader != null && !id.isNullOrBlank() && !version.isNullOrBlank()) value = loader.load(id, version, group)
    }
    val label = if (name.isBlank()) "Аватар" else "Аватар $name"
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val photo = bitmap
        if (photo != null) Image(photo.asImageBitmap(), contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else {
            val initials = name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull(Char::isLetterOrDigit)?.uppercaseChar() }.take(2).joinToString("")
            Text(initials.ifBlank { "?" }, fontSize = (size.value * .34f).coerceIn(11f, 26f).sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

internal data class AvatarEditTarget(val id: String, val version: String?, val group: Boolean = false)

/** Retained draft editor; the platform picker never uploads until the user confirms the crop. */
@Composable
internal fun rememberAvatarPicker(
    identityKey: String,
    enabled: Boolean,
    onPicked: (String, ByteArray) -> Unit,
    @Suppress("UNUSED_PARAMETER") onError: (String) -> Unit,
    existingAvatar: AvatarEditTarget? = null,
    title: String = "Фото профиля",
    operationRevision: Long = 0L,
    operationError: String? = null,
): () -> Unit {
    val context = LocalContext.current
    val resolver = context.applicationContext.contentResolver
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .take(16).filterIsInstance<android.app.Activity>().firstOrNull()
    }
    val owner: AvatarEditorViewModel = viewModel()
    val editor by owner.state.collectAsStateWithLifecycle()
    val loader = LocalAvatarImages.current
    val currentKey by rememberUpdatedState(identityKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPicked by rememberUpdatedState(onPicked)
    val currentTarget by rememberUpdatedState(existingAvatar)
    val currentTitle by rememberUpdatedState(title)
    val revision by rememberUpdatedState(operationRevision)
    var launchedFor by rememberSaveable { mutableStateOf<String?>(null) }
    SideEffect { owner.bind(identityKey) }
    DisposableEffect(owner, identityKey) {
        onDispose { if (activity?.isChangingConfigurations != true) owner.deactivate(identityKey) }
    }
    LaunchedEffect(identityKey, operationRevision, operationError, existingAvatar?.version) {
        owner.acknowledge(identityKey, operationRevision, operationError, existingAvatar?.version)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = launchedFor
        launchedFor = null
        if (uri != null && target != null && target == currentKey && currentEnabled && owner.state.value?.key == target) {
            owner.replace(target) {
                resolver.openInputStream(uri)?.use { input ->
                    val encoded = readAvatarInput(input)
                    try { decodeAvatarCropSource(encoded) } finally { encoded.fill(0) }
                } ?: throw IllegalArgumentException("Не удалось открыть изображение.")
            }
        }
    }
    val choose = {
        if (currentEnabled && launchedFor == null && owner.state.value?.saving != true && owner.state.value?.loading != true) {
            launchedFor = currentKey
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    editor?.takeIf { it.key == identityKey }?.let { draft ->
        AvatarCropDialog(draft.title, draft.source, draft.crop, draft.loading, draft.saving, draft.error,
            onCrop = { owner.crop(identityKey, it) }, onChoose = choose,
            onSave = {
                if (currentEnabled) owner.submit(identityKey, revision) { bytes -> currentPicked("image/jpeg", bytes) }
            }, onDismiss = { owner.close(identityKey) })
    }
    return {
        if (currentEnabled) {
            val key = currentKey
            val existing = currentTarget
            owner.open(key, currentTitle, existing?.version) {
                if (existing?.version != null) loader?.loadSource(existing.id, existing.group)
                    ?: throw IllegalArgumentException("Не удалось открыть фото. Выберите другое изображение.")
                else null
            }
        }
    }
}

private const val MAX_AVATAR_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_AVATAR_UPLOAD_BYTES = 2 * 1024 * 1024

internal suspend fun readAvatarInput(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count == -1) break
        require(count <= MAX_AVATAR_INPUT_BYTES - output.size()) { "Выберите изображение размером до 8 МБ." }
        output.write(buffer, 0, count)
    }
    require(output.size() > 0) { "Выбранный файл пуст." }
    return output.toByteArray()
}

/** Decode with subsampling, honour camera EXIF orientation, and encode a small square JPEG. */
internal fun normalizeAvatarImage(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty() && bytes.size <= MAX_AVATAR_INPUT_BYTES) { "Выберите изображение размером до 8 МБ." }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth; val height = bounds.outHeight
    require(width in 1..32_768 && height in 1..32_768 && width.toLong() * height <= 100_000_000L) { "Не удалось прочитать изображение или его разрешение слишком велико." }
    var sample = 1
    while (maxOf(width, height) / sample > 1024) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: throw IllegalArgumentException("Не удалось прочитать изображение.")
    val bitmaps = linkedSetOf(decoded)
    try {
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also(bitmaps::add)
        val edge = minOf(oriented.width, oriented.height)
        val square = Bitmap.createBitmap(oriented, (oriented.width - edge) / 2, (oriented.height - edge) / 2, edge, edge).also(bitmaps::add)
        val scaled = if (edge > 512) Bitmap.createScaledBitmap(square, 512, 512, true).also(bitmaps::add) else square
        val output = ByteArrayOutputStream()
        require(scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "Не удалось подготовить изображение." }
        require(output.size() in 1..MAX_AVATAR_UPLOAD_BYTES) { "Не удалось уменьшить изображение до 2 МБ." }
        return output.toByteArray()
    } finally { bitmaps.forEach(Bitmap::recycle) }
}

private fun decodeAvatarThumbnail(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty() || bytes.size > 256 * 1024) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 || bounds.outWidth.toLong() * bounds.outHeight > 4_000_000) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
}
