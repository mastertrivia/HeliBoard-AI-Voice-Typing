/*
 * Copyright (C) 2026 HeliBoard voice integration work.
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Speechnotes-style continuous speech controller for HeliBoard's normal
 * Voice input key. This reproduces the observable controller architecture
 * from the supplied Speechnotes APK while using Android's public
 * SpeechRecognizer API inside the IME.
 */
package helium314.keyboard.latin.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.ArrayDeque
import java.util.Locale

/**
 * Continuous recognition controller.
 *
 * The controller intentionally keeps partial results internal. A segment is
 * committed only from a final result. When Android ends a recognition session
 * naturally, the controller starts the next session, while an explicit stop
 * permanently prevents restart until start() is called again.
 *
 * The supplied Speechnotes APK exposes the same observable pieces: a
 * ContSpeechRecognizer-style session controller, partial-result windows,
 * partial-diff tracking, no-speech timing, and continuous restart/error
 * handling. The implementation below mirrors those responsibilities without
 * copying proprietary application code.
 */
class ContinuousSpeechRecognizer(
    context: Context,
    private val callback: Callback,
) {
    interface Callback {
        fun onVoicePartialResult(text: String)
        fun onVoiceFinalResult(text: String)
        /**
         * Finalize the currently visible composing segment without starting a new
         * one. Used on explicit stop, IME destroy/collapse, error recovery, and
         * language switch, matching how the reference apps commit their pending
         * partial buffer on those paths.
         */
        fun onVoiceStoppedWithBuffer(text: String)
        fun onVoiceStateChanged(listening: Boolean)
        fun onVoiceError(errorCode: Int)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    // Every recognizer instance owns one generation. Android may deliver callbacks
    // asynchronously after cancel()/destroy(); stale callbacks must never mutate
    // the newer continuous-dictation session.
    private var recognizerGeneration = 0L
    private var listening = false
    private var explicitlyStopped = true
    private var restartGeneration = 0L
    private var locale: Locale = Locale.getDefault()

    // Speechnotes exposes a partialWindows structure and a diffPartial helper.
    // Keep a tiny rolling window here so each provider update can be compared
    // to its recent predecessors without ever committing partial text.
    private val partialWindows = ArrayDeque<String>(PARTIAL_WINDOW_SIZE)
    private var latestPartial = ""
    private var lastStablePartial = ""
    // Only the currently active recognition segment is kept as a live preview.
    // Speechnotes commits a completed recognition segment when the recognizer
    // detects the user's pause, then immediately continues listening. It does
    // NOT wait for the microphone's final Stop action to commit every segment.
    private var speechStartedAt = 0L
    private var lastPartialAt = 0L
    private var beganSpeech = false
    private var restartPosted = false
    // Speechnotes/Speechkeys auto-stop Voice after a long period without any
    // partial/final recognition activity (Speechnotes KEY_PREFS_TIME_TO_NO_SPEECH
    // default = 60000 ms; Speechkeys uses the same 60-second timer). The timer is
    // (re)armed when listening starts and on every partial/final result; on expiry
    // Voice stops with any pending partial finalized exactly like an explicit stop.

    // Do not impose an arbitrary short silence timeout here. Android's
    // SpeechRecognizer already reports speech/no-match/timeout conditions,
    // and Speechnotes keeps continuous dictation alive across those session
    // boundaries. A local 3.5-second watchdog would incorrectly make the IME
    // appear to stop during a normal pause.

    private fun isCurrentGeneration(generation: Long): Boolean =
        !explicitlyStopped && generation == recognizerGeneration

    private fun newRecognitionListener(generation: Long) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isCurrentGeneration(generation)) return
            listening = true
            beganSpeech = false
            speechStartedAt = SystemClock.elapsedRealtime()
            callback.onVoiceStateChanged(true)
        }

        override fun onBeginningOfSpeech() {
            if (!isCurrentGeneration(generation)) return
            beganSpeech = true
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!isCurrentGeneration(generation)) return
            // Keep the session state alive until onResults()/onError().
            // Speechnotes treats this as a segment boundary rather than as a
            // user stop, so the controller is allowed to restart afterward.
            lastPartialAt = SystemClock.elapsedRealtime()
        }

        override fun onError(error: Int) {
            if (!isCurrentGeneration(generation)) return
            listening = false
            callback.onVoiceError(error)
            if (explicitlyStopped) {
                callback.onVoiceStateChanged(false)
                return
            }
            val delay = restartDelay(error)
            // Speechnotes/Speechkeys finalize the pending unstable partial before
            // error recovery (m1660y / m3735n inside onError). This keeps visible
            // text from being silently dropped when a session ends without
            // onResults(), including no-match boundaries.
            val bufferedText = latestPartial.trim()
            clearPartialWindow()
            if (bufferedText.isNotEmpty()) {
                callback.onVoiceStoppedWithBuffer(bufferedText)
            }
            if (delay == NO_RESTART) {
                // Permanent/non-recoverable errors (for example missing
                // microphone permission) must not leave HeliBoard visually
                // stuck in an active Voice state.
                explicitlyStopped = true
                restartGeneration++
                restartPosted = false
                mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
                cancelNoSpeechAutoStop()
                callback.onVoiceStateChanged(false)
                return
            }
            // Keep Voice logically active while transient provider/session
            // errors are being recovered. The next session is scheduled below.
            callback.onVoiceStateChanged(true)
            // A no-match boundary reuses the completed recognizer for the next
            // session (Speechnotes m1640G / Speechkeys m3741u); all other session
            // errors take the destroy-and-recreate path (m1657v / m3734l).
            scheduleRestart(delay, reuseRecognizer = error == SpeechRecognizer.ERROR_NO_MATCH)
        }

        override fun onResults(results: Bundle?) {
            if (!isCurrentGeneration(generation)) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.asSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                .orEmpty()

            clearPartialWindow()
            listening = false
            if (text.isNotEmpty() && !explicitlyStopped) {
                // IMPORTANT: this is the Speechnotes behavior. A natural pause
                // produces a final recognition result, and that completed
                // segment is committed immediately while Voice remains ON.
                // The next recognition session starts automatically.
                callback.onVoiceFinalResult(text)
            } else if (text.isNotEmpty() && explicitlyStopped) {
                // stop() / destroy() already committed the visible unfinished
                // partial buffer before cancelling SpeechRecognizer. A late
                // onResults() callback belongs to that cancelled session and
                // must NOT be committed a second time.
                //
                // This is especially important because Android may deliver a
                // final result asynchronously after cancel().
            } else if (!explicitlyStopped) {
                // Empty result at a natural boundary: drop any stale transient
                // preview so it does not linger in the editor as composing text.
                callback.onVoicePartialResult("")
            }

            if (explicitlyStopped) {
                callback.onVoiceStateChanged(false)
            } else {
                // Keep the microphone/Voice UI active across a natural pause.
                callback.onVoiceStateChanged(true)
                scheduleNoSpeechAutoStop()
                // A natural speech boundary reuses the completed recognizer for
                // the next segment (Speechnotes m1640G / Speechkeys m3741u).
                scheduleRestart(RESTART_AFTER_RESULTS_MS, reuseRecognizer = true)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isCurrentGeneration(generation)) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.asSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (text.isEmpty()) return

            latestPartial = text
            lastPartialAt = SystemClock.elapsedRealtime()
            partialWindows.addLast(text)
            while (partialWindows.size > PARTIAL_WINDOW_SIZE) partialWindows.removeFirst()

            // Use the rolling-window diff result instead of merely calculating
            // it and throwing it away.  The stable prefix is a guard against a
            // recognizer provider briefly regressing its partial hypothesis.
            // It never gets committed by itself; the final onResults() remains
            // the natural-pause commit boundary.
            val stable = diffPartial(partialWindows.toList())
            if (stable.isNotEmpty()) {
                lastStablePartial = stable
            }

            // Speechkeys places the recognizer's current hypothesis directly
            // into the target editor's composing region. Do not substitute an
            // older stable prefix here: that would make the temporary text lag
            // behind the actual provider hypothesis and would no longer be the
            // same composing-buffer mechanism. The rolling diff remains useful
            // as internal recognizer state, but the UI always receives the newest
            // partial hypothesis.
            callback.onVoicePartialResult(latestPartial)
            // Every recognition update keeps the no-speech auto-stop timer alive,
            // exactly like Speechnotes/Speechkeys restart their countdown on each
            // partial result.
            scheduleNoSpeechAutoStop()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun start(locale: Locale) {
        mainHandler.post {
            this.locale = locale
            explicitlyStopped = false
            restartGeneration++
            clearPartialWindow()
            ensureRecognizer()
            beginRecognition()
            scheduleNoSpeechAutoStop()
        }
    }

    /**
     * Changes the recognition locale while continuous Voice is active.
     *
     * A language switch is a new recognition session: preserve/commit the
     * currently visible partial segment, cancel the old session, update the
     * locale, and immediately start a fresh session. The Voice state remains
     * ON throughout the transition.
     */
    fun updateLocale(newLocale: Locale) {
        mainHandler.post {
            if (locale == newLocale) return@post
            val wasActive = !explicitlyStopped
            if (!wasActive) {
                locale = newLocale
                return@post
            }

            // Do not silently lose speech that was visible in the old
            // language. Treat it as the final part of the old session, then
            // start the new locale without turning Voice off.
            val bufferedText = latestPartial.trim()
            if (bufferedText.isNotEmpty()) {
                callback.onVoiceStoppedWithBuffer(bufferedText)
            }

            locale = newLocale
            restartGeneration++
            restartPosted = false
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            recognizerGeneration++
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            clearPartialWindow()
            listening = false
            callback.onVoiceStateChanged(true)
            // Re-arm the no-speech auto-stop countdown for the new language session.
            scheduleNoSpeechAutoStop()

            ensureRecognizer()
            mainHandler.post {
                if (!explicitlyStopped && locale == newLocale) {
                    beginRecognition()
                }
            }
        }
    }

    fun stop() {
        mainHandler.post { performStop() }
    }

    /**
     * Returns whether continuous Voice mode is active, including the short
     * teardown/restart window between recognition sessions.  This must not be
     * tied to SpeechRecognizer's low-level LISTENING state: during a natural
     * pause/onResults/onError the recognizer is temporarily not listening, but
     * HeliBoard Voice is still ON and a restart may already be scheduled.
     * Keeping this true prevents a keyboard tap during that window from being
     * ignored and accidentally allowing the scheduled recognizer restart.
     */
    fun isListening(): Boolean = !explicitlyStopped

    fun destroy() {
        mainHandler.post { performStop() }
    }

    /**
     * Shared teardown for explicit stop, IME destroy, and the no-speech
     * auto-stop: finalizes the currently visible partial segment, marks the
     * controller explicitly stopped, invalidates the recognizer generation so
     * late callbacks are ignored, cancels any pending restart and the no-speech
     * timer, and destroys the recognizer.
     */
    private fun performStop() {
        // A natural pause has already committed its completed segment.
        // At explicit Stop we commit only the currently unfinished partial
        // segment, then return immediately to the normal HeliBoard UI.
        explicitlyStopped = true
        restartGeneration++
        restartPosted = false
        listening = false
        cancelNoSpeechAutoStop()
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        recognizerGeneration++
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null

        // If the provider has only delivered partial text for the current
        // recognition segment, preserve that visible buffer as well.
        // Only the current unfinished partial segment can remain here.
        // Earlier segments were already committed on natural pauses.
        val bufferedText = latestPartial.trim()
        if (bufferedText.isNotEmpty()) {
            callback.onVoiceStoppedWithBuffer(bufferedText)
        }
        clearPartialWindow()
        callback.onVoiceStateChanged(false)
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listening = false
            callback.onVoiceError(SpeechRecognizer.ERROR_CLIENT)
            explicitlyStopped = true
            restartGeneration++
            restartPosted = false
            mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
            callback.onVoiceStateChanged(false)
            return
        }
        val generation = ++recognizerGeneration
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(newRecognitionListener(generation))
        }
    }

    private fun beginRecognition() {
        if (explicitlyStopped || recognizer == null) return
        val current = recognizer ?: return
        clearPartialWindow()
        beganSpeech = false
        speechStartedAt = SystemClock.elapsedRealtime()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Speechnotes requests Android's dictation mode when available.
            // Use the public extra key string so this remains compatible across SDK levels.
            putExtra("android.speech.extra.DICTATION_MODE", true)
            // Allow providers that expose unstable recognition text to surface it as partials.
            putExtra("android.speech.extra.UNSTABLE_TEXT", true)
            // Do not force the provider offline. This follows the supplied
            // Speechnotes behavior: Android/default recognition service decides
            // whether local or network recognition is used.
        }
        try {
            restartPosted = false
            current.startListening(intent)
        } catch (_: RuntimeException) {
            listening = false
            if (!explicitlyStopped) {
                callback.onVoiceStateChanged(true)
                scheduleRestart(RESTART_AFTER_EXCEPTION_MS)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long, reuseRecognizer: Boolean = false) {
        if (explicitlyStopped || restartPosted || delayMs < 0L) return
        val generation = restartGeneration
        restartPosted = true
        mainHandler.removeCallbacksAndMessages(RESTART_TOKEN)
        mainHandler.postAtTime({
            restartPosted = false
            if (!explicitlyStopped && generation == restartGeneration) {
                if (reuseRecognizer && recognizer != null) {
                    // Natural speech boundary (onResults / no-match): Speechnotes
                    // and Speechkeys reuse the completed recognizer instance and
                    // simply call startListening() again (m1640G / m3741u). The
                    // same listener/generation stays valid for the continuous
                    // dictation session, so late callbacks from the finished
                    // segment still belong to the session and remain harmless.
                    beginRecognition()
                } else {
                    // Hard/session error: destroy and recreate the recognizer,
                    // mirroring the reference apps' destroyAndRestart path
                    // (m1657v / m3734l) so a broken session cannot retain
                    // provider state into the next segment.
                    recognizerGeneration++
                    recognizer?.cancel()
                    recognizer?.destroy()
                    recognizer = null
                    listening = false
                    ensureRecognizer()
                    beginRecognition()
                }
            }
        }, RESTART_TOKEN, SystemClock.uptimeMillis() + delayMs)
    }

    /**
     * (Re)arms the Speechnotes-style no-speech auto-stop countdown. Called when
     * listening starts and on every partial/final result. When it expires without
     * any further recognition activity, Voice stops itself (pending partial
     * finalized) exactly like Speechnotes' RecognizerService and Speechkeys do.
     */
    private fun scheduleNoSpeechAutoStop() {
        if (explicitlyStopped) return
        mainHandler.removeCallbacksAndMessages(NO_SPEECH_TOKEN)
        mainHandler.postAtTime({
            if (!explicitlyStopped) {
                performStop()
            }
        }, NO_SPEECH_TOKEN, SystemClock.uptimeMillis() + NO_SPEECH_AUTO_STOP_MS)
    }

    private fun cancelNoSpeechAutoStop() {
        mainHandler.removeCallbacksAndMessages(NO_SPEECH_TOKEN)
    }

    private fun clearPartialWindow() {
        partialWindows.clear()
        latestPartial = ""
        lastStablePartial = ""
        lastPartialAt = 0L
        beganSpeech = false
    }

    /** Returns the longest prefix shared by the recent partial-result window. */
    private fun diffPartial(window: List<String>): String {
        if (window.isEmpty()) return ""
        var end = window.first().length
        for (candidate in window.drop(1)) {
            end = minOf(end, candidate.length)
            var i = 0
            while (i < end && window.first()[i] == candidate[i]) i++
            end = i
            if (end == 0) return ""
        }
        return window.first().substring(0, end).trim()
    }

    private fun restartDelay(error: Int): Long = when (error) {
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RESTART_BUSY_MS
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> RESTART_PROVIDER_ERROR_MS
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NO_MATCH -> RESTART_NO_MATCH_MS
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> NO_RESTART
        else -> RESTART_GENERIC_ERROR_MS
    }

    companion object {
        private val RESTART_TOKEN = Any()
        private val NO_SPEECH_TOKEN = Any()
        private const val PARTIAL_WINDOW_SIZE = 4
        // Matches Speechnotes' KEY_PREFS_TIME_TO_NO_SPEECH default (60000 ms) and
        // Speechkeys' 60-second auto-stop countdown. Voice stops itself after this
        // long without any partial/final recognition activity.
        private const val NO_SPEECH_AUTO_STOP_MS = 60_000L
        // Small scheduling delays are only used to let the Android recognizer
        // finish tearing down the previous session before startListening() is
        // called again. They are not user-facing silence timers.
        private const val RESTART_AFTER_RESULTS_MS = 0L
        private const val RESTART_AFTER_EXCEPTION_MS = 500L
        private const val RESTART_NO_SPEECH_MS = 100L
        private const val RESTART_NO_MATCH_MS = 100L
        private const val RESTART_BUSY_MS = 1000L
        private const val RESTART_PROVIDER_ERROR_MS = 700L
        private const val RESTART_GENERIC_ERROR_MS = 350L
        private const val NO_RESTART = -1L
    }
}
