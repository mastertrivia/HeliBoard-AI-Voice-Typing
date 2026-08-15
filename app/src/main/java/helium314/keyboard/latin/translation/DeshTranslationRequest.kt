// Ported from Desh Keyboard v17.4.9 — com/deshkeyboard/translation AutoRetryDeshNetworkRequest
// (xk/b.smali). Same behavior: GET, JSON, auto-retry 4 times with 300 ms delay,
// 2.5 s connect timeout / 10 s request timeout, exact Desh User-Agent.
// Transport is OkHttp (HeliBoard already ships it) instead of Desh's Ktor.
package helium314.keyboard.latin.translation

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desh's AutoRetryDeshNetworkRequest. [onSuccess] gets the raw response body,
 * [onError] any failure, [onRetry] the retry count (0-based) before each retry.
 */
class DeshTranslationRequest(
    private val url: String,
    private val onSuccess: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onRetry: (Int) -> Unit,
) {
    /** Desh xk/b.f — max 4 retries. */
    private val maxRetries = 4

    /** Desh xk/b.g — 0x12c ms = 300 ms delay between retries. */
    private val retryDelayMs = 0x12cL

    /** Desh xk/b.h — exact User-Agent from the APK. */
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** Desh xk/b.k — cancellation flag. */
    private val cancelled = AtomicBoolean(false)

    /** All callbacks touch views, so they are posted to the main thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(0x9c4L, TimeUnit.MILLISECONDS)      // 2.5 s — Desh xk/b connect timeout
        .readTimeout(0x2710L, TimeUnit.MILLISECONDS)        // 10 s
        .writeTimeout(0x2710L, TimeUnit.MILLISECONDS)
        .build()

    /** Desh xk/b.a() — start the request (on a background thread). */
    fun start() {
        cancelled.set(false)
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            var attempt = 0
            while (!cancelled.get() && attempt <= maxRetries) {
                if (attempt > 0) mainHandler.post { onRetry(attempt) }
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", userAgent)
                        .get()
                        .build()
                    client.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw java.io.IOException("HTTP ${response.code}")
                        }
                        val body = response.body?.string().orEmpty()
                        if (!cancelled.get()) {
                            mainHandler.post { onSuccess(body) }
                            return@execute
                        }
                    }
                    return@execute
                } catch (t: Throwable) {
                    attempt++
                    if (cancelled.get()) return@execute
                    if (attempt > maxRetries) {
                        mainHandler.post { onError(t) }
                        return@execute
                    }
                    try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) { return@execute }
                }
            }
        }
        executor.shutdown()
    }

    /** Desh xk/b.k = true — cancel the in-flight request. */
    fun cancel() {
        cancelled.set(true)
    }
}
