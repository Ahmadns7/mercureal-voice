package com.mercureal.voice

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device LLM ("brain"). llama.cpp (pinned b11838) runs through a small JNI bridge in
 * native/llm_jni.cpp, built for arm64-v8a into jniLibs. The model (GGUF) is downloaded only
 * after the user taps "Download brain" in Home, and it is checked against a SHA-256 before use.
 */
object Llm {
    private const val TAG = "MercurealLLM"
    const val MODEL_NAME = "SmolLM2-135M-Instruct-Q4_K_M.gguf"
    const val MODEL_URL = "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf"
    const val MODEL_SHA256 = "2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d"
    const val MODEL_BYTES = 105_454_432L
    const val MIN_RAM_MB = 3000L      // below this, Smart mode is off and the app stays on Rules
    const val CTX_TOKENS = 2048
    const val MAX_REPLY_TOKENS = 160

    private val nativeOk: Boolean = try {
        System.loadLibrary("mercureal_llm"); true
    } catch (e: Throwable) {
        Log.w(TAG, "native library not available: ${e.message}")
        false
    }
    private val loaded = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    @JvmStatic private external fun nativeLoad(path: String, threads: Int, ctxSize: Int): Boolean
    @JvmStatic private external fun nativeGenerate(roles: Array<String>, contents: Array<String>, maxTokens: Int, sink: TokenSink): Int
    @JvmStatic private external fun nativeCancel()
    @JvmStatic private external fun nativeFree()

    class TokenSink(private val f: (String) -> Unit) {
        @Suppress("unused") fun onToken(s: String) = f(s)
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "brain").apply { mkdirs() }
    fun modelFile(ctx: Context) = File(dir(ctx), MODEL_NAME)

    /** True when the native lib loaded and the model file is present and complete. */
    fun ready(ctx: Context): Boolean = nativeOk && modelFile(ctx).let { it.exists() && it.length() == MODEL_BYTES }

    fun ramMb(ctx: Context): Long {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    /** Why Smart mode can't run here, or null when it can. */
    fun blocker(ctx: Context): String? = when {
        !nativeOk -> "this build has no on-device model library"
        ramMb(ctx) < MIN_RAM_MB -> "this phone has under 3 GB RAM, so it stays on rules"
        !ready(ctx) -> "the brain isn't downloaded yet"
        else -> null
    }

    private val downloading = AtomicBoolean(false)

    /** Snapshot of the system download job. */
    data class DlState(val status: Int, val got: Long, val total: Long, val reason: Int)

    private const val PREFS = "brain_download"
    private const val KEY_ID = "dl_id"
    private const val STAGING_NAME = "$MODEL_NAME.download"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Id of a system download that is still running (or finished but not yet installed), or null. */
    fun pendingDownloadId(ctx: Context): Long? = prefs(ctx).getLong(KEY_ID, -1L).takeIf { it >= 0 }

    /** Where DownloadManager writes the file. App-specific external storage needs no permission. */
    private fun stagingFile(ctx: Context): File? = ctx.getExternalFilesDir(null)?.let { File(it, STAGING_NAME) }

    /** True while a download is being tracked or an imported file is being checked and saved. */
    fun isDownloading(): Boolean = downloading.get()

    /**
     * Queues the model with Android's DownloadManager system service. This app holds no INTERNET
     * permission and makes no network calls: the system downloads the file and we verify it after.
     * Fails when the download service is missing or disabled, so the caller can fall back to
     * downloading in the browser and importing the file.
     */
    fun enqueueSystemDownload(ctx: Context): Result<Long> = runCatching {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: error("this phone's download service is not available")
        val staging = stagingFile(ctx) ?: error("this phone's storage is not available")
        staging.delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("MercuReal brain")
            .setDescription("On-device model, about 105 MB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, STAGING_NAME)
        val id = dm.enqueue(req)
        prefs(ctx).edit().putLong(KEY_ID, id).apply()
        id
    }

    /** Reads the job's state, or null when the job no longer exists (cancelled or removed). */
    fun readState(ctx: Context, id: Long): DlState? {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return null
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            return DlState(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).let { if (it > 0) it else MODEL_BYTES },
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
        return null
    }

    /**
     * Follows the system download until it finishes, reporting [onState] about every half second,
     * then verifies the SHA-256 and installs the model. Runs on the calling (worker) thread.
     * Cancelling removes the system job, which makes this return a "cancelled" failure.
     */
    fun awaitDownload(ctx: Context, id: Long, onState: (DlState) -> Unit): Result<Unit> {
        if (!downloading.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("the brain is already downloading"))
        }
        val result = runCatching {
            while (true) {
                val s = readState(ctx, id) ?: throw IOException("download cancelled")
                when (s.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> break
                    DownloadManager.STATUS_FAILED -> throw IOException("download failed (android code ${s.reason}), try again")
                    else -> onState(s)
                }
                Thread.sleep(500)
            }
            val staged = stagingFile(ctx) ?: error("this phone's storage is not available")
            installFile(ctx, FileInputStream(staged))
        }
        finish(ctx, result)
        return result
    }

    private fun finish(ctx: Context, result: Result<Unit>) {
        // the finished or failed system job is no longer needed either way
        pendingDownloadId(ctx)?.let { id ->
            ctx.getSystemService(DownloadManager::class.java)?.remove(id)
        }
        prefs(ctx).edit().remove(KEY_ID).apply()
        stagingFile(ctx)?.delete()
        downloading.set(false)
    }

    /** Cancels the system download: removes its DownloadManager job, which also deletes the partial file. */
    fun cancelDownload(ctx: Context) {
        val id = pendingDownloadId(ctx) ?: return
        ctx.getSystemService(DownloadManager::class.java)?.remove(id)
        prefs(ctx).edit().remove(KEY_ID).apply()
        stagingFile(ctx)?.delete()
    }

    /**
     * Fallback when the download service can't run: the user downloads the same file in a browser
     * and picks it from device storage. The file is checked against the same SHA-256 before use.
     */
    fun importBrain(ctx: Context, uri: Uri): Result<Unit> {
        if (!downloading.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("the brain is already downloading"))
        }
        return runCatching {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("could not open that file")
            installFile(ctx, input)
        }.also { downloading.set(false) }
    }

    /** Copies [input] to a .part file while hashing it, accepts it only when the SHA-256 matches, then moves it into place. */
    private fun installFile(ctx: Context, input: InputStream) {
        val tmp = File(dir(ctx), "$MODEL_NAME.part")
        tmp.delete()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(input, digest).use { src ->
                FileOutputStream(tmp).use { fos -> src.copyTo(fos, 64 * 1024) }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                check(hex == MODEL_SHA256) { "that file is not the brain (checksum didn't match), nothing was saved" }
            }
            val dest = modelFile(ctx)
            dest.delete()
            check(tmp.renameTo(dest)) { "could not save the model" }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    fun deleteModel(ctx: Context) {
        unload()
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Loads the model into native memory. Call off the main thread. */
    @Synchronized
    fun ensureLoaded(ctx: Context): Boolean {
        if (loaded.get()) return true
        if (blocker(ctx) != null) return false
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val ok = nativeLoad(modelFile(ctx).absolutePath, threads, CTX_TOKENS)
        loaded.set(ok)
        return ok
    }

    @Synchronized
    fun unload() {
        if (loaded.getAndSet(false)) nativeFree()
    }

    fun cancel() { if (busy.get()) nativeCancel() }

    /**
     * Streams a reply. roles/contents is the chat history ending with the user turn.
     * Call off the main thread. Returns the full text, or null when the model is busy or failed.
     */
    fun generate(roles: List<String>, contents: List<String>, onToken: (String) -> Unit): String? {
        if (!loaded.get() || !busy.compareAndSet(false, true)) return null
        try {
            val acc = StringBuilder()
            val sink = TokenSink { piece -> acc.append(piece); onToken(piece) }
            val n = nativeGenerate(roles.toTypedArray(), contents.toTypedArray(), MAX_REPLY_TOKENS, sink)
            return if (n < 0) null else acc.toString()
        } finally {
            busy.set(false)
        }
    }
}
