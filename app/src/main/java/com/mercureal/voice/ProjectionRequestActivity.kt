package com.mercureal.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Transparent activity that asks the user to approve screen capture once per session.
 * The result is stored in ScreenshotCapture and reused by later screenshot commands.
 */
class ProjectionRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (e: Throwable) {
            CrashReport.report(this, "screenshot permission", e)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ) {
            ScreenshotCapture.storeGrant(resultCode, data)
            if (resultCode == RESULT_OK) {
                // Promote the already-running service to mediaProjection type before any capture.
                val i = Intent(this, VoiceService::class.java).putExtra(VoiceService.EXTRA_ACTION, VoiceService.ACTION_PROJECTION)
                if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                VoiceService.say(this, "screenshot access granted")
            }
        }
        finish()
    }

    companion object {
        private const val REQ = 4701

        fun launch(ctx: Context) {
            ctx.startActivity(Intent(ctx, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
