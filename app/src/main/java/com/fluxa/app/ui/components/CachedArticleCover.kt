package com.fluxa.app.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val coverDecoders = Semaphore(2)

/** Only embedded bytes are considered; this component never resolves a URL. */
@Composable
internal fun CachedArticleCover(contentHtml: String, modifier: Modifier = Modifier, thumbnail: Boolean = false) {
    // Read/star changes are deliberately not keys. No HTML parsing occurs on the UI thread.
    val bitmap by produceState<Bitmap?>(null, contentHtml) {
        value = withContext(Dispatchers.Default) {
            coverDecoders.withPermit { decodeCachedCover(contentHtml) }
        }
    }
    val image = bitmap
    if (image != null) {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        Image(
            bitmap = remember(image) { image.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = (if (thumbnail) modifier else modifier.fillMaxWidth().padding(top = 10.dp)
                .height(if (landscape) 96.dp else 156.dp)).clip(RoundedCornerShape(if (thumbnail) 4.dp else 16.dp))
        )
    }
}

/** Bounds apply before base64 allocation, then again before pixel allocation. */
internal object CachedCoverLimits {
    const val MAX_HTML_CHARS = 2_000_000
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_BASE64_CHARS = ((MAX_BYTES + 2) / 3) * 4
    private val images = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val attributes = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val header = Regex("data:image/(?:jpeg|png|gif|webp);base64,", RegexOption.IGNORE_CASE)

    fun embeddedBytes(html: String): Sequence<ByteArray> {
        if (html.length > MAX_HTML_CHARS) return emptySequence()
        return images.findAll(html).take(80).mapNotNull { tag ->
            val src = attributes.findAll(tag.value).firstOrNull { it.groupValues[1].equals("src", true) }
                ?.let { match -> match.groupValues.drop(2).firstOrNull { it.isNotEmpty() } }
                ?: return@mapNotNull null
            val prefix = header.find(src)?.takeIf { it.range.first == 0 } ?: return@mapNotNull null
            val count = src.length - prefix.value.length
            if (count !in 4..MAX_BASE64_CHARS) return@mapNotNull null
            try {
                Base64.getDecoder().decode(src.substring(prefix.value.length)).takeIf { it.size in 1..MAX_BYTES }
            } catch (_: IllegalArgumentException) { null }
        }
    }

    fun sampleSize(width: Int, height: Int): Int? {
        if (width !in 1..16384 || height !in 1..16384 || width.toLong() * height > 32_000_000L) return null
        var sample = 1
        while ((width + sample - 1) / sample > 1024 || (height + sample - 1) / sample > 1024) sample *= 2
        return sample
    }
}

internal fun decodeCachedCover(html: String): Bitmap? {
    try {
        for (bytes in CachedCoverLimits.embeddedBytes(html)) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = CachedCoverLimits.sampleSize(bounds.outWidth, bounds.outHeight) ?: continue
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: continue
                if (bitmap.width in 1..1024 && bitmap.height in 1..1024 && bitmap.allocationByteCount <= 4 * 1024 * 1024) return bitmap
                bitmap.recycle()
            } catch (_: IllegalArgumentException) { /* Try the next embedded raster. */ }
        }
    } catch (_: OutOfMemoryError) { /* A decorative cover must not prevent reading. */ }
    return null
}
