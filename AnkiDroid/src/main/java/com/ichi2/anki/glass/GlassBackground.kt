// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.deckpicker.BackgroundImage
import com.ichi2.anki.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.max

/**
 * Paints the user's background image behind every activity, blurred.
 *
 * The blur is baked into the bitmap instead of using [android.graphics.RenderEffect] for two
 * reasons: it works below API 31, and it costs nothing per frame - the window background is
 * redrawn on every scroll.
 *
 * The result is cached in memory, because [applyToWindow] runs on every activity creation.
 */
object GlassBackground {
    /**
     * How far the source image is scaled down before blurring.
     *
     * The blurred result is kept at this size rather than scaled back up: it carries no detail
     * anyway, and stretching it costs nothing while saving an order of magnitude of memory.
     */
    private const val SCALE = 6

    /** Radius of the box blur, in pixels *of the downscaled image*. */
    private const val BLUR_RADIUS = 12

    @Volatile
    private var cached: BitmapDrawable? = null

    @Volatile
    private var cachedStamp: Long = -1L

    /**
     * Sets the blurred backdrop as the window background of [activity], if the user enabled it.
     *
     * Decoding and blurring happen off the main thread; until they land - and if they fail - the
     * window keeps the plain theme background colour.
     */
    fun applyToWindow(activity: ComponentActivity) {
        if (!Prefs.isGlassEnabled) return
        val file = BackgroundImage.getImageFile(activity) ?: return
        val stamp = file.lastModified()

        cached?.takeIf { cachedStamp == stamp }?.let {
            activity.window.setBackgroundDrawable(it)
            return
        }

        activity.lifecycleScope.launch {
            val drawable = load(activity, stamp) ?: return@launch
            activity.window.setBackgroundDrawable(drawable)
        }
    }

    /** Drops the cache, e.g. after the user picks a different image. */
    fun invalidate() {
        cached = null
        cachedStamp = -1L
    }

    private suspend fun load(
        activity: ComponentActivity,
        stamp: Long,
    ): BitmapDrawable? =
        withContext(Dispatchers.IO) {
            try {
                val file = BackgroundImage.getImageFile(activity) ?: return@withContext null
                val original =
                    BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null

                val small = downscale(original)
                if (small !== original) original.recycle()

                blurInPlace(small)

                BitmapDrawable(activity.resources, small).apply {
                    // FILL stretches rather than tiles; the bitmap is small on purpose
                    setGravity(Gravity.FILL)
                    isFilterBitmap = true
                }.also {
                    cached = it
                    cachedStamp = stamp
                }
            } catch (e: OutOfMemoryError) {
                Timber.w(e, "Glass background: not enough memory to decode the image")
                null
            } catch (e: Exception) {
                Timber.w(e, "Glass background: failed to build the blurred backdrop")
                null
            }
        }

    private fun downscale(source: Bitmap): Bitmap {
        val width = max(1, source.width / SCALE)
        val height = max(1, source.height / SCALE)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /** Approximates a Gaussian with three box-blur passes, in place. */
    private fun blurInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(3) {
            boxBlurHorizontal(pixels, width, height, BLUR_RADIUS)
            boxBlurVertical(pixels, width, height, BLUR_RADIUS)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun boxBlurHorizontal(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val divisor = radius * 2 + 1
        val row = IntArray(width)
        for (y in 0 until height) {
            val base = y * width
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (i in -radius..radius) {
                val p = pixels[base + i.coerceIn(0, width - 1)]
                alpha += p ushr 24 and 0xFF
                red += p shr 16 and 0xFF
                green += p shr 8 and 0xFF
                blue += p and 0xFF
            }
            for (x in 0 until width) {
                row[x] =
                    ((alpha / divisor) shl 24) or
                    ((red / divisor) shl 16) or
                    ((green / divisor) shl 8) or
                    (blue / divisor)

                val add = pixels[base + (x + radius + 1).coerceAtMost(width - 1)]
                val sub = pixels[base + (x - radius).coerceAtLeast(0)]
                alpha += (add ushr 24 and 0xFF) - (sub ushr 24 and 0xFF)
                red += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                green += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                blue += (add and 0xFF) - (sub and 0xFF)
            }
            System.arraycopy(row, 0, pixels, base, width)
        }
    }

    private fun boxBlurVertical(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val divisor = radius * 2 + 1
        val column = IntArray(height)
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (i in -radius..radius) {
                val p = pixels[i.coerceIn(0, height - 1) * width + x]
                alpha += p ushr 24 and 0xFF
                red += p shr 16 and 0xFF
                green += p shr 8 and 0xFF
                blue += p and 0xFF
            }
            for (y in 0 until height) {
                column[y] =
                    ((alpha / divisor) shl 24) or
                    ((red / divisor) shl 16) or
                    ((green / divisor) shl 8) or
                    (blue / divisor)

                val add = pixels[(y + radius + 1).coerceAtMost(height - 1) * width + x]
                val sub = pixels[(y - radius).coerceAtLeast(0) * width + x]
                alpha += (add ushr 24 and 0xFF) - (sub ushr 24 and 0xFF)
                red += (add shr 16 and 0xFF) - (sub shr 16 and 0xFF)
                green += (add shr 8 and 0xFF) - (sub shr 8 and 0xFF)
                blue += (add and 0xFF) - (sub and 0xFF)
            }
            for (y in 0 until height) {
                pixels[y * width + x] = column[y]
            }
        }
    }
}
