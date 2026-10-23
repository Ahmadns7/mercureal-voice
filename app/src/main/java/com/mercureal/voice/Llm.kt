package com.mercureal.voice

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
    private val cancelRequested = AtomicBoolean(false)

    /** True while a model download is running (one at a time). */
    fun isDownloading(): Boolean = downloading.get()

    /** Asks the running download to stop. It deletes the partial file and returns a failure. */
    fun cancelDownload() { cancelRequested.set(true) }

    /**
     * Downloads the model to a .part file, verifies SHA-256, then renames it into place.
     * [progress] receives (bytesSoFar, totalBytes). Runs on the calling thread.
     * Only one download runs at a time; a failed or cancelled download leaves no .part file.
     */
    fun download(ctx: Context, progress: (Long, Long) -> Unit): Result<Unit> {
        if (!downloading.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("the brain is already downloading"))
        }
        cancelRequested.set(false)
        val out = File(dir(ctx), "$MODEL_NAME.part")
        return runCatching {
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
            }
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            if (cancelRequested.get()) throw java.io.IOException("download cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            total += n
                            progress(total, MODEL_BYTES)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            out.inputStream().use { s ->
                val b = ByteArray(64 * 1024)
                while (true) { val n = s.read(b); if (n < 0) break; digest.update(b, 0, n) }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            check(hex == MODEL_SHA256) { "download failed the checksum, try again" }
            val dest = modelFile(ctx)
            dest.delete()
            check(out.renameTo(dest)) { "could not save the model" }
        }.also { result ->
            downloading.set(false)
            if (result.isFailure) out.delete()
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
