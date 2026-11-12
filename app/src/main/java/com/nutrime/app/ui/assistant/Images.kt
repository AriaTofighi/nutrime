package com.nutrime.app.ui.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.nutrime.app.data.remote.ChatImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo attached to the next message: what's sent, and a preview. */
data class PendingImage(val image: ChatImage, val preview: ImageBitmap)

/** Longest side sent to the model. Enough to read a nutrition label, small enough to upload quickly. */
private const val MAX_SIDE = 1280

/** Decode, orient, downscale and JPEG-encode a picked or captured photo. */
suspend fun encodeImage(context: Context, uri: Uri): PendingImage? = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = MAX_SIDE.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
            if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true) else decoded
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        PendingImage(ChatImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP)), bitmap.asImageBitmap())
    }.getOrNull()
}

/** Decode a base64 image from the conversation history. */
fun decodeBase64Image(data: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(data, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** A content:// URI the camera app can write a new photo to. */
fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "photos").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
    val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
