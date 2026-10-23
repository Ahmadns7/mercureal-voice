package com.mercureal.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Holds the MediaProjection grant for this app session. The user approves once
 * (ProjectionRequestActivity); later screenshot commands reuse it until the process dies.
 */
object ScreenshotCapture {
    @Volatile var resultCode: Int = Activity.RESULT_CANCELED
    @Volatile var resultData: Intent? = null
    private var projection: MediaProjection? = null

    fun hasGrant(): Boolean = resultData != null && resultCode == Activity.RESULT_OK

    fun storeGrant(code: Int, data: Intent?) {
        resultCode = code
        resultData = data
        projection = null
    }

    fun clearGrant() {
        projection?.stop()
        projection = null
        resultData = null
        resultCode = Activity.RESULT_CANCELED
    }

    /** Captures one frame and saves it to Pictures/Screenshots. Calls back with the saved name or an error. */
    fun capture(ctx: Context, done: (Boolean, String) -> Unit) {
        val data = resultData
        if (!hasGrant() || data == null) {
            done(false, "screenshot needs permission first")
            return
        }
        val app = ctx.applicationContext
        val mpm = app.getSystemService(MediaProjectionManager::class.java)
        val mp = projection ?: mpm.getMediaProjection(resultCode, data).also {
            // Android 14+ requires a callback before creating a virtual display.
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                    resultData = null
                    resultCode = Activity.RESULT_CANCELED
                }
            }, Handler(Looper.getMainLooper()))
            projection = it
        }
        val dm = app.getSystemService(DisplayManager::class.java)
        val metrics = app.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val main = Handler(Looper.getMainLooper())
        var finished = false
        var vd: VirtualDisplay? = null
        val timeout = Runnable {
            if (!finished) {
                finished = true
                vd?.release()
                reader.close()
                done(false, "screenshot timed out")
            }
        }
        reader.setOnImageAvailableListener({ r ->
            if (finished) return@setOnImageAvailableListener
            val image: Image? = r.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener
            finished = true
            main.removeCallbacks(timeout)
            try {
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * w
                val bmp = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                val name = save(app, cropped)
                done(true, if (name != null) "screenshot saved" else "couldn't save the screenshot")
            } catch (e: Exception) {
                done(false, "screenshot failed")
            } finally {
                image.close()
                vd?.release()
                reader.close()
            }
        }, main)
        try {
            vd = mp.createVirtualDisplay(
                "mercureal-shot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
            )
        } catch (e: SecurityException) {
            // Android 14+: a grant token can only be used for one capture. Drop it and ask again.
            projection = null
            resultData = null
            resultCode = Activity.RESULT_CANCELED
            reader.close()
            done(false, "screenshot access expired, approve again to capture")
            return
        } catch (e: IllegalStateException) {
            projection = null
            reader.close()
            done(false, "screenshot access expired, approve again to capture")
            return
        }
        main.postDelayed(timeout, 4000)
    }

    /** Writes a PNG to Pictures/Screenshots via MediaStore (no storage permission needed on API 29+). */
    private fun save(ctx: Context, bmp: Bitmap): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "MercuReal-$stamp.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
        }
        return name
    }
}
