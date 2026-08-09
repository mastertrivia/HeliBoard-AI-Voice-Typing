# AI Voice Typing runtime engine — implementation handbook

This document defines what happens after the AI toolbar key is tapped. It is separate from settings/UI architecture. The HTML prototype is not an implementation reference; only its separation of recorder from provider and its fallback concept are retained.

## 0. Non-negotiable ownership model

```text
AI toolbar key
  → LatinIME.onEvent(AI_VOICE_TYPING)
  → AiVoiceSessionController (one per LatinIME instance)
  → RecordingSession (one at a time)
      → AndroidAudioRecorder → PCM frames
      → SilenceDetector → ChunkAssembler → disk-backed AudioChunk queue
      → TranscriptionDispatcher → SpeechProvider (Groq first)
      → TranscriptInserter → current InputConnection
      → RotationCoordinator / Diagnostics
  → AiVoiceRuntimeStateHolder → toolbar + settings summary
```

Only `AiVoiceSessionController` changes session state. Only `RotationCoordinator` changes active/pending profile and rotation anchors. Only `TranscriptInserter` accesses `InputConnection`. Only `AiDiagnosticsRepository` retains diagnostic events. A toolbar button is a renderer and an event source—it never owns recording, elapsed time, chunks, or a coroutine scope.

## 1. Files, package layout, and lifecycle wiring

### Objective

Add a self-contained runtime under `helium314.keyboard.latin.aivoice.runtime` with one narrow integration into HeliBoard’s IME service.

### Existing HeliBoard files to inspect/modify

- `app/src/main/java/helium314/keyboard/latin/LatinIME.java` — verified `InputMethodService`; its `onEvent(Event)` is the existing destination for toolbar code events.
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyCode.kt` — add the dedicated `AI_VOICE_TYPING` code from `AI_Toolbar_Button.md`.
- `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt` — renderer/binder only; it must not contain engine logic.
- `app/src/main/AndroidManifest.xml` — inspect before engine implementation; microphone/network permissions and backup rules are a shipping/privacy decision.
- `app/build.gradle.kts` — add only required runtime dependencies (OkHttp and AndroidX Security already specified by the configuration document); do not add an HTTP SDK per provider.

### Files to create

```text
aivoice/runtime/
  AiVoiceSessionController.kt
  RecordingSession.kt
  SessionState.kt
  AudioRecorder.kt
  AndroidAudioRecorder.kt
  SilenceDetector.kt
  ChunkAssembler.kt
  AudioChunk.kt
  TranscriptionDispatcher.kt
  TranscriptInserter.kt
  RotationCoordinator.kt
  RuntimeDependencies.kt
aivoice/provider/
  SpeechProvider.kt
  GroqSpeechProvider.kt
  ProviderFailure.kt
aivoice/diagnostics/
  AiDiagnosticEvent.kt
  AiDiagnosticsRepository.kt
```

### Lifecycle

- **Application-owned:** settings repository, encrypted key store, provider catalog, bounded diagnostics repository. They hold no `LatinIME`, `View`, `InputConnection`, or audio object.
- **`LatinIME`-owned:** `AiVoiceSessionController`. Construct in `LatinIME.onCreate()` after normal service initialization; call `onImeDestroyed()` before `super.onDestroy()`.
- **Session-owned:** recorder, session `SupervisorJob`, chunk queue, dispatcher, and audio files. Created only after permission/profile/preflight succeeds; destroyed on every terminal transition.
- **View-owned:** toolbar binder only. It observes `AiVoiceRuntimeStateHolder`; it is cancelled on `SuggestionStripView` detach.
- **ViewModel-owned:** no engine object. Settings view models only edit configuration and observe state.

### Why this is the correct integration point

`LatinIME` is the verified input-method service and already receives toolbar input via `onEvent`. It owns the active app/editor relationship and is destroyed with the IME. Putting the engine in a settings activity would terminate recording on navigation; putting it in `SuggestionStripView` would leak/restart sessions whenever the input view is rebuilt. Do not modify `InputLogic` or `KeyboardSwitcher` for provider/audio execution.

### ⚠ Ambiguous

The user’s fork is unavailable. Verify the exact `LatinIME.onDestroy()` body and existing permission-launch mechanism before insertion. Android runtime permission cannot safely be assumed to be requestable directly from an IME service. Until a verified host flow exists, the controller must reject start with `PERMISSION_REQUIRED`, remain idle, and log diagnostics.

## 2. Session state machine

### Objective

Make all races explicit. A session has one monotonic state owner and no boolean combination such as `isRecording && isStopping`.

### Production code

`SessionState.kt`:

```kotlin
sealed interface SessionState {
    data object Idle : SessionState
    data class Starting(val sessionId: String, val profileId: String) : SessionState
    data class Recording(
        val sessionId: String,
        val profileId: String,
        val startedAtElapsedRealtime: Long,
    ) : SessionState
    data class Stopping(val sessionId: String, val reason: TerminationReason) : SessionState
}

enum class TerminationReason {
    MANUAL_STOP, SILENCE_TIMEOUT, MAXIMUM_DURATION, INPUT_INTERACTION, IME_DESTROYED,
    START_FAILED, CANCELLED, ERROR, ROTATION,
}
```

`AiVoiceSessionController.kt` public API:

```kotlin
interface AiVoiceSessionController {
    fun onToolbarToggleRequested()
    fun onInputInteraction() // only used when independent mode is off
    fun onSilenceTimeout() // called only by the session-owned silence detector
    fun onMaximumDurationReached() // called only by the session-owned duration timer
    fun onImeDestroyed()
}
```

Use a main-thread actor rather than scattered mutexes:

```kotlin
class DefaultAiVoiceSessionController(
    private val deps: RuntimeDependencies,
    private val mainScope: CoroutineScope,
) : AiVoiceSessionController {
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var state: SessionState = SessionState.Idle
    private var activeSession: RecordingSession? = null

    init { mainScope.launch { for (command in commands) reduce(command) } }

    override fun onToolbarToggleRequested() { commands.trySend(Command.Toggle) }
    override fun onInputInteraction() { commands.trySend(Command.InputInteraction) }
    override fun onImeDestroyed() { commands.trySend(Command.Destroy) }

    private suspend fun reduce(command: Command) {
        when (command) {
            Command.Toggle -> when (state) {
                SessionState.Idle -> start()
                is SessionState.Starting, is SessionState.Recording -> finishSession(TerminationReason.MANUAL_STOP)
                is SessionState.Stopping -> Unit
            }
            Command.InputInteraction -> if (state is SessionState.Recording && activeSession?.policy?.independentMicAndKeyboard == false) {
                finishSession(TerminationReason.INPUT_INTERACTION)
            }
            Command.SilenceTimeout -> finishSession(TerminationReason.SILENCE_TIMEOUT)
            Command.MaximumDuration -> finishSession(TerminationReason.MAXIMUM_DURATION)
            Command.Destroy -> finishSession(TerminationReason.IME_DESTROYED)
        }
    }
}

private sealed interface Command { data object Toggle : Command; data object InputInteraction : Command; data object Destroy : Command }
```

`start()` resolves profile/rotation before creating `RecordingSession`; it publishes `Starting`, then `Recording` only after `AudioRecord.state == STATE_INITIALIZED` and `startRecording()` succeeds. `stop()` changes state to `Stopping` first, cancels/joins the session, publishes idle, and only then returns. Every state update also updates `AiVoiceRuntimeStateHolder` atomically with the recording start anchor.

### Threading / lifecycle

The actor runs on `Dispatchers.Main.immediate` because it changes runtime state and coordinates IME lifecycle. Audio capture and requests never run on it. `onImeDestroyed()` is idempotent; enqueueing it twice must not recreate or stop a second session.

### Engineering review and verification

- **Break/crash:** `AudioRecord` can be uninitialized or throw `IllegalStateException`; safe result is diagnostic + idle, never a toolbar timer.
- **Race:** double-tap, timeout, provider failure, and destroy are serialized by the channel.
- **Persistence:** do not persist `SessionState`; after process death it is idle and orphan temporary audio is cleaned at next startup.
- **UI:** `Starting` renders idle—the toolbar has only requested idle/recording visuals.
- **Test:** feed `Toggle, Toggle, Destroy` concurrently and assert one session starts, one stop executes, state is Idle.
- **Development instrumentation:** debug-only event codes `SESSION_STARTING`, `SESSION_RECORDING`, `SESSION_STOPPING` with session-id prefix only.

## 3. Configuration-to-runtime map (all cards)

| Card/controller | Persisted input | Runtime reader | Effect | Never does |
|---|---|---|---|---|
| Toolbar key | none | `AiVoiceSessionController` | sends Toggle | owns a session |
| Active summary | config + runtime | summary view model | observes | changes state |
| Manual profile selector | `activeProfileId`, `pendingProfileId` | `RotationCoordinator` | resolves next session profile | changes an active recording provider |
| Recording behaviour | `independentMicAndKeyboard` | controller session snapshot | enables/disables input-interaction stop | polls keyboard UI |
  | Silence/auto-send | `autoSendAfterSilence`, `silenceDurationMillis` | `SilenceDetector` → controller | ends the current session gracefully after configured continuous silence | keeps the microphone active |
| Recording timeout | `recordingTimeoutMillis` | `RecordingSession` | requests graceful stop | kills recorder from UI |
| Rotation/fallback | rotation fields/usage | `RotationCoordinator` | chooses next eligible profile at boundaries | interrupts a current session |
| Profile manager | profiles/key store | `ProviderResolver` | selects provider/model/key snapshot | exposes key to UI/logs |
| Diagnostics | no command config | all engine components | receives concise events | controls engine |

At start, the controller reads one immutable `SessionPolicySnapshot` from the repository. Mid-session settings edits affect the next session except manual selection, which is persisted to `pendingProfileId` and applied after the current session ends. This avoids a provider/key/config switch in the middle of an audio chunk.

## 4. Audio capture, VAD, and chunk assembly

### Objective

Capture microphone PCM without blocking the IME/UI and turn accepted speech into an ordered disk-backed final chunk. When the configured silence policy is reached, the session ends; it must not keep the microphone running in the background.

### Proven API choice

Use `android.media.AudioRecord`, `MediaRecorder.AudioSource.VOICE_RECOGNITION`, mono PCM 16-bit, requested 16 kHz. `AudioRecord` is the Android pull API and provides blocking reads; use one blocking reader coroutine on `Dispatchers.IO`, avoiding busy loops. Groq accepts WAV and documents 16 kHz mono preprocessing as optimal. Do not use `MediaRecorder`: it writes a single encoded file and is unsuitable for continuous frame-level silence/chunk control. Do not use `SpeechRecognizer`: it is a system recognition UI/service and cannot provide provider-neutral PCM or Groq requests.

### Audio recorder contract

```kotlin
data class PcmFormat(val sampleRateHz: Int = 16_000, val channels: Int = 1, val bitsPerSample: Int = 16)
data class AudioFrame(val bytes: ByteArray, val capturedAtElapsedRealtime: Long)

interface AudioRecorder : Closeable {
    val format: PcmFormat
    suspend fun start(onFrame: suspend (AudioFrame) -> Unit)
    suspend fun stop()
}
```

`AndroidAudioRecorder` constructs `AudioRecord` with `getMinBufferSize()` and buffer size `max(minBufferSize * 2, 3_200)` bytes (100 ms at 16 kHz mono/16-bit). Validate `STATE_INITIALIZED`; read into a reusable buffer with `READ_BLOCKING`; copy exactly the returned byte count before passing the frame to the session. Treat `ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`, negative read, and zero-progress loop as recorder failure. Never retain raw frames beyond the chunk assembler.

### Silence detector decision

Android has no general built-in voice-activity detector. Options are: (1) simple elapsed timer—cannot distinguish speech/noise; reject; (2) RMS/energy with adaptive noise floor—small, offline, deterministic; choose for v1; (3) WebRTC VAD—more robust but adds native dependency/build/ABI and tuning complexity; make it a future `VoiceActivityDetector` implementation after real-device evaluation. The detector interface prevents a rewrite.

```kotlin
interface VoiceActivityDetector {
    fun reset()
    fun isSpeech(frame: AudioFrame, format: PcmFormat): Boolean
}
```

`RmsVoiceActivityDetector` computes RMS from signed 16-bit little-endian PCM, establishes a noise floor from non-speech frames only, and uses hysteresis: enter speech above `max(noiseFloor * 3.0, absoluteFloor)`, leave speech below `max(noiseFloor * 1.8, absoluteFloor)` for the configured silence duration. Do not update the noise floor during speech. The detector must expose no user setting beyond Card 4’s silence duration.

### Chunk rules

- Keep one `ChunkAssembler` per session on the audio coroutine.
- Start a chunk at first speech frame; include a fixed 250 ms pre-roll ring buffer so initial consonants are not clipped.
- With auto-send enabled: after continuous non-speech for `silenceDurationMillis`, provided chunk speech duration is at least 500 ms, request `AiVoiceSessionController.onSilenceTimeout()`. The controller stops the recorder and uses the normal graceful final flush/dispatch path; it never leaves the microphone recording silently.
- With auto-send disabled: append until user/timeout/IME stop, then flush one final chunk.
- Force flush at 45 s of audio or before Groq’s file limit. This bounds memory/latency even when the user does not pause.
- Write each chunk as a valid temporary WAV file on `Dispatchers.IO`; queue metadata, not PCM bytes. Delete it after success, final failure, cancellation, or startup orphan cleanup.
- Never overlap chunks in v1. Overlap improves boundary recognition but requires transcript deduplication and can insert duplicate words; add it only with word timestamp/dedup tests.
- Preserve queue order. Requests may run one at a time for a session; ordering is more important than parallel throughput because transcripts are inserted into an editor.

### RecordingSession skeleton

```kotlin
class RecordingSession(
    val id: String,
    val profile: ApiProfile,
    val policy: SessionPolicySnapshot,
    private val recorder: AudioRecorder,
    private val assembler: ChunkAssembler,
    private val dispatcher: TranscriptionDispatcher,
    private val scope: CoroutineScope,
) {
    private var captureJob: Job? = null

    suspend fun start() {
        captureJob = scope.launch(Dispatchers.IO) {
            recorder.start { frame ->
                assembler.accept(frame)?.let(dispatcher::enqueue)
            }
        }
    }

    suspend fun stop(graceful: Boolean) {
        recorder.stop()
        captureJob?.cancelAndJoin()
        assembler.flushFinal()?.let(dispatcher::enqueue)
        if (graceful) dispatcher.drain() else dispatcher.cancelPending()
        recorder.close()
    }
}
```

### Timeout / pause / cancel

Start a session-owned timeout job using `SystemClock.elapsedRealtime()` semantics. It requests `finishSession(TerminationReason.MAXIMUM_DURATION)` through the controller actor, not direct recorder stop. The session-owned silence detector likewise requests `finishSession(TerminationReason.SILENCE_TIMEOUT)` after the policy threshold. There is no pause gesture in the supplied UI; retain `PAUSED` as a future state only. Cancellation (`IME_DESTROYED` or explicit future cancel) stops capture, deletes unsent chunks, cancels HTTP, and does not insert pending text. Manual stop, maximum duration, silence timeout, future error, and future rotation all use this one graceful terminal pipeline: stop capture, flush recorded audio, drain ordered chunks with a bounded final-drain timeout, then publish Idle.

### Engineering review and verification

- **Break/crash:** `RECORD_AUDIO` denied, mic busy, uninitialized record, `ERROR_DEAD_OBJECT`; log `RECORDER_*`, return Idle.
- **Race:** stop can arrive during blocking read; `AudioRecord.stop()` unblocks it; session job then joins.
- **Lifecycle:** recorder exists only after start and is closed in `finally`.
- **Persistence:** chunks are private cache files, excluded from backup; cleanup stale `ai_voice_chunk_*` on application start.
- **UI:** typing/editing never stops capture when independent mode is true; `InputConnection` activity is unrelated to audio capture.
- **Test:** fake PCM silence/speech sequence verifies exactly one chunk after threshold; test forced 45-s split; test stop while read blocks; test disk cleanup after cancellation.
- **Instrumentation:** debug-only log frame counts/RMS summary once per chunk, never PCM/audio/transcript.

## 5. Provider pipeline — provider-neutral contract and Groq implementation

### Objective

Transcribe ordered WAV chunks through the selected profile without coupling the session/controller to Groq.

### Provider contract

```kotlin
data class TranscriptionRequest(
    val sessionId: String,
    val chunkSequence: Long,
    val wavFile: File,
    val profile: ApiProfile,
    val apiKey: String,
    val languageTag: String?,
)

data class TranscriptionResult(
    val text: String,
    val providerRequestId: String? = null,
)

interface SpeechProvider {
    val providerId: String
    suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult
}

sealed interface ProviderFailure {
    data object Authentication : ProviderFailure
    data object Authorization : ProviderFailure
    data object RateLimited : ProviderFailure
    data object ModelUnavailable : ProviderFailure
    data object InvalidRequest : ProviderFailure
    data object NetworkUnavailable : ProviderFailure
    data object NetworkTimeout : ProviderFailure
    data object Server : ProviderFailure
    data object MalformedResponse : ProviderFailure
    data object Cancelled : ProviderFailure
    data class Unknown(val httpCode: Int?) : ProviderFailure
}
```

`ProviderResolver` reads `profile.providerId`, validates its model through `ProviderCatalog`, retrieves the key from `ApiKeyStore` only immediately before the call, then gives it to the selected provider. No other class reads a key.

### Groq implementation

Use one application-owned `OkHttpClient` with explicit connect/read/write/call timeouts defined in one `NetworkPolicy` (recommended: 10 s / 60 s / 60 s / 75 s; final values must be tested on mobile networks). `GroqSpeechProvider` posts multipart to `https://api.groq.com/openai/v1/audio/transcriptions`, Bearer authorization, WAV `file`, selected `model`, `response_format=json`, and optional ISO-639-1 `language`. Groq’s documented response has `text`; reject absent/non-string text as `MalformedResponse`.

```kotlin
class GroqSpeechProvider(private val client: OkHttpClient) : SpeechProvider {
    override val providerId = "groq"

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResult = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", request.wavFile.name,
                request.wavFile.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", request.profile.modelId)
            .addFormDataPart("response_format", "json")
            .apply { request.languageTag?.let { addFormDataPart("language", it) } }
            .build()
        val httpRequest = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${request.apiKey}")
            .post(body)
            .build()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw ProviderException(classify(response.code), response.code)
            val text = response.body.string().let(::parseGroqText)
            TranscriptionResult(text = text, providerRequestId = response.header("x-request-id"))
        }
    }
}
```

`parseGroqText` must use kotlinx serialization JSON, reject blank/missing `text`, and never include raw response body in an exception/diagnostic. Cancellation must call `Call.cancel()` through `suspendCancellableCoroutine`; do not rely only on coroutine cancellation around blocking `execute()`.

### Retry and error classification

- No automatic retry for 401/403, invalid request, invalid model, or malformed response.
- One retry with bounded exponential backoff only for transient network I/O, 408, 429 (honour valid `Retry-After` within a 10 s cap), and 5xx; cancellation stops backoff.
- Authentication, authorization, rate-limit/quota, unavailable-model, and provider-rejected-request failures queue a failure-rotation trigger only for the next session boundary. Do not retry the same key for authentication/authorization/model failures.
- Network-unavailable/DNS/TLS/timeout/server failures never rotate. A server failure receives its one bounded retry, then ends the affected chunk with an actionable error. Malformed responses and cancellation never rotate.
- Never retry on a different provider/key without `RotationCoordinator` selecting a replacement profile.

### Engineering review and verification

- **Break/crash:** corrupted WAV, key missing, unknown provider/model, response parser mismatch; classify and keep recording/session alive when possible.
- **Race:** a response can arrive after session cancellation; dispatcher checks session job active before insertion.
- **Persistence:** raw audio deletion occurs after terminal chunk outcome; keys never enter request logs.
- **UI:** status changes to API Error only for the current session error; a later success returns Connected.
- **Test:** MockWebServer verifies multipart fields, 401 → auth classification, 429 retry cap, cancellation calls cancel, malformed JSON does not crash.
- **Instrumentation:** log provider id/model, HTTP class, duration, byte count, and redacted request id; never URL query, headers, key, audio, or transcript.

## 6. Ordered transcription dispatcher and text insertion

### Objective

Insert each successful transcript once, in speech order, at the active editor cursor, without holding a stale `InputConnection`.

### Dispatcher rules

`TranscriptionDispatcher` owns `Channel<AudioChunk>` and one worker coroutine. It processes sequence number N before N+1. It calls `RotationCoordinator.withProfileForChunk()` for retries/fallback, provider, then inserter. It does not capture audio and does not manipulate toolbar views.

Empty/whitespace-only result is a normal `EMPTY_TRANSCRIPT` warning: delete the file, do not insert, do not rotate. On a final provider failure, delete the file and continue recording; failure rotation can let the next chunk use the next profile. Do not silently replay failed spoken audio after the user has continued typing—the UX/cost/replay policy is not specified.

### InputConnection boundary

```kotlin
interface TranscriptInserter {
    suspend fun insert(text: String, sessionId: String, chunkSequence: Long): InsertResult
}
sealed interface InsertResult { data object Inserted : InsertResult; data object NoConnection : InsertResult; data object Rejected : InsertResult }
```

`LatinImeTranscriptInserter` receives a *provider lambda* `() -> InputConnection?`, not an `InputConnection` instance. Invoke the lambda on `Dispatchers.Main.immediate` immediately before insertion. Call `beginBatchEdit()`, `commitText(normalizedText, 1)`, and `endBatchEdit()` in `try/finally`; `commitText(..., 1)` places the cursor after inserted text. Never use `setComposingText`, because a delayed provider response must not replace a user’s currently composing keyboard text.

```kotlin
override suspend fun insert(text: String, sessionId: String, chunkSequence: Long): InsertResult =
    withContext(Dispatchers.Main.immediate) {
        val connection = currentConnection() ?: return@withContext InsertResult.NoConnection
        runCatching {
            connection.beginBatchEdit()
            if (connection.commitText(text, 1)) InsertResult.Inserted else InsertResult.Rejected
        }.getOrElse { InsertResult.Rejected }
            .also { connection.endBatchEdit() }
    }
```

Correct the above implementation before copy-paste: `endBatchEdit()` must be in `finally`, not `.also`, so it executes if `commitText` throws. Normalize only transport whitespace (`trim()` and a single configured leading separator when needed); do not alter punctuation/case/transcript content in the engine.

If there is no current connection or `commitText` returns false, log `INSERTION_NO_CONNECTION` / `INSERTION_REJECTED`, drop that transcript, continue recording, and do not crash or retry into a newly focused app. Retrying later could insert private text into the wrong editor. The insertion naturally follows current cursor/selection; it must not restore a captured cursor position. User typing/editing continues independently when Card 3 is enabled.

### Engineering review and verification

- **Break/crash:** app/editor may return null, reject batch edits, or throw binder/runtime exception; safe fallback drops the chunk with diagnostics.
- **Race:** focus can change between provider response and commit; resolving connection at insertion time prevents stale-object use, but intentionally inserts into the current editor. This behaviour requires product approval.
- **Lifecycle:** no connection is stored after the main-thread block.
- **Persistence:** transcript text is not persisted or diagnostics logged.
- **UI:** independent recording continues even if insertion fails.
- **Test:** fake InputConnection verifies `begin → commit → end`; null/rejected case creates no crash; type while provider request in flight and ensure commit happens at then-current selection.
- **Instrumentation:** debug counter of insertion outcome only, no text.

### ⚠ Ambiguous

Whether a transcript begun in app A may be committed to app B after the user changes focus is a product/privacy decision. Recommended safe policy: bind each session to the initial editor package/token and drop chunks if it changes; this needs a verified HeliBoard editor identity source. Until defined, do not infer one.

## 7. Rotation and fallback coordinator

### Objective

Select a profile deterministically, without interrupting a recording session and without conflicting writes.

### Priority and safe-boundary rules

At **new session start**, evaluate in this order: pending manual profile → due failure rotation → due day rotation → due clock rotation → due usage rotation → persisted active profile. Apply at most one sequential advance per decision. If multiple policies are due, record all trigger codes but the highest priority causes the advance; anchors for non-winning policies remain due and will be considered on the next safe boundary. This is deterministic and avoids skipping profiles.

At **current-session completion**, persist accumulated active-recording milliseconds and mark a due usage rotation as pending; never swap the profile for an in-flight chunk. A provider authentication failure can select a fallback profile for subsequent chunks only if the policy explicitly permits same-session fallback; the supplied requirements say failure recovery during recording but do not define whether audio may move providers mid-session.

### ⚠ Ambiguous

The specification says failure rotation occurs “during recording” while manual changes apply only next session; it does not define whether a failure may resend the current queued chunk with another profile. Two valid policies exist:

1. **Recommended:** retry the failed uninserted chunk once with the next profile, keep the rest of the session on its original profile, and make the new profile active for the next session. This recovers speech but mixes provider/account use.
2. **Strict session affinity:** fail the chunk, rotate only next session. This is simpler and clearer but loses spoken text.

Choose one before implementation; do not let each provider decide.

### Production contract

```kotlin
interface RotationCoordinator {
    suspend fun resolveProfileForNewSession(nowWallMillis: Long, nowElapsedMillis: Long): ApiProfile?
    suspend fun recordActiveDuration(profileId: String, activeMillis: Long)
    suspend fun requestFailureRotation(profileId: String, failure: ProviderFailure): ApiProfile?
    suspend fun completeSession(profileId: String, nowWallMillis: Long, nowElapsedMillis: Long)
}
```

Use repository `update` mutex for every decision/write. Eligible profiles are sorted ascending by stable `serialNumber`; select the next sequential profile, wrapping. Skip only profiles that cannot resolve to a provider/model/key; write `ROTATION_SKIPPED_INELIGIBLE` for each and stop after N attempts. If all are ineligible, do not loop: publish API Error, log `ROTATION_NO_ELIGIBLE_PROFILE`, stop dispatching requests, and leave the session recorder running only if the chosen product policy allows collecting audio with no destination (recommended: graceful stop to avoid privacy/cost surprise).

Anchor storage:

- `dayRotationAnchorEpochMillis`: wall-clock UTC epoch when day rotation last committed. Compare calendar days in the user’s current timezone at evaluation; DST affects wall days by design. Persist it.
- `clockRotationAnchorElapsedRealtime`: **cannot survive reboot**. Add `clockRotationAnchorEpochMillis` to configuration before implementing clock rotation; use elapsed time only in-process, reconstruct/reset from epoch at process start. The existing fields alone are insufficient.
- `accumulatedRecordingMillis`: increment only from `Recording` wall duration using `elapsedRealtime`; flush on pause/stop; never count request/insertion/idle time.
- Usage rotation resets the newly active profile’s usage only if product says limits are per-cycle. Current requirements do not state reset semantics; retain cumulative usage and rotate immediately forever otherwise. This needs a new per-profile `usageCycleStarted...` or `usageMillisSinceLastRotation` field.

Manual selection commits `activeProfileId` when idle or `pendingProfileId` while recording. It resets the rotation starting profile but not implicit anchor/counter policy unless explicitly approved.

### Engineering review and verification

- **Break/crash:** empty list, active deleted, all invalid profiles, negative custom intervals; return null/diagnostic, not modulo-by-zero.
- **Race:** profile save/deletion/manual selection/failure are serialized by repository mutation.
- **Lifecycle:** no timer/worker in settings; evaluate clock/day lazily at session boundaries, so no background service is required.
- **Persistence:** clock elapsed anchor needs augmentation for reboot; all changes commit atomically.
- **UI:** summary reads committed active/pending state; it never guesses a rotation outcome.
- **Test:** 1→N→1 order, simultaneous due policies, process restart, DST/timezone change, active deletion, all failure loop limit, manual selection while recording.
- **Instrumentation:** one diagnostics event per committed rotation with trigger/profile serials; no key or transcript.

## 8. Diagnostics subsystem

### Objective

Produce screenshot/copy-ready AI-only evidence without retaining sensitive data or affecting engine control flow.

### Event schema

```kotlin
enum class DiagnosticLevel { INFO, WARNING, ERROR }
data class AiDiagnosticEvent(
    val epochMillis: Long,
    val elapsedRealtimeMillis: Long,
    val level: DiagnosticLevel,
    val code: String,
    val sessionId: String? = null,
    val chunkSequence: Long? = null,
    val profileSerial: Int? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val message: String,
)
```

Keep the existing bounded in-memory 200-event `StateFlow`. Each component receives a narrow `AiDiagnosticsSink` (`fun emit(event)`) rather than the repository. Copy/export formats timestamp, severity, code, redacted metadata, and message. It never serializes arbitrary exceptions or objects.

Log: session state transitions, recorder setup/read failures, chunk created/discarded (duration/bytes only), request started/finished (provider/model/duration/http class), classified provider failures, insertion result, rotation decision, and cleanup failure.

Never log: API key, Authorization header, full URL if it can contain a key, audio bytes/path, transcript/partial transcript, surrounding editor text, app package unless user explicitly approves diagnostic privacy scope, raw provider JSON, or stack trace in copy export.

### Engineering review and verification

- **Break:** diagnostics must never throw into audio/request path; sink catches its own errors.
- **Memory:** 200 events cap; no raw strings with unbounded server body.
- **Test:** run redaction tests with key-like values and transcript; export contains none.
- **Instrumentation:** debug build may include a short throwable class name, never full stack/response.

## 9. Network, permission, privacy, and process-death policy

### Required manifest/product work

Before enabling real engine behavior, verify and intentionally add `android.permission.RECORD_AUDIO` and `android.permission.INTERNET`; upstream HeliBoard is privacy-oriented and historically avoids internet permission. Add a clear feature consent disclosure: audio is uploaded to the selected provider. Verify Android backup exclusion for encrypted API-key preferences and temporary audio cache. Groq documents retention/privacy options separately; provider choice is a user data-processing decision, not just a model setting.

Do not request audio focus for v1 unless actual device testing proves it is required; audio focus has Android 15 constraints and speech recognition capture does not inherently need playback focus. Never start a microphone foreground service from the background; current Android microphone restrictions make that unsafe. The engine runs only while the user has an active IME interaction; on IME destruction it stops.

On process death: active session ends; temporary chunks are deleted at next application startup; runtime state becomes Idle; no transcript recovery/replay; persisted config/rotation anchors remain. This is safer than replaying speech to an unknown editor.

## 10. Mandatory implementation test gates

1. **Controller:** all state-machine transition tests pass; no direct recorder/provider call from toolbar/view.
2. **Audio:** device test permission denied, mic occupied, route change, 10-minute continuous capture memory profile, silence/forced/max/final chunk boundaries.
3. **Provider:** MockWebServer multipart and classification suite; cancellation and retry tests; Groq real-key smoke test only with a non-production test key.
4. **Insertion:** fake `InputConnection` unit tests plus Chrome/EditText/Compose text field/manual editor switching tests.
5. **Rotation:** deterministic unit matrix for every policy combination, reboot/timezone/DST, deleted/invalid/all-failed profiles.
6. **Lifecycle:** repeatedly show/hide IME, rotate device, switch apps during request, kill process, and ensure no session/timer/job survives.
7. **Regression:** existing HeliBoard voice shortcut, toolbar pinning, typing, suggestions, clipboard, emoji, and keyboard page switching behave identically with AI disabled.

Only after these gates can the disabled controller be replaced with real microphone/network execution.

# Production-readiness implementation addendum

This addendum is normative. Where it conflicts with an earlier illustrative skeleton, this addendum wins. An implementation agent must not substitute a different owner, coroutine scope, error policy, storage rule, or state transition without an explicit approved change.

## 11. Complete class contract ledger

| Class | Constructed by | Owner | Scope/thread | Destroyed by | Public API | Must not own |
|---|---|---|---|---|---|---|
| `AiVoiceDependencies` | application/lazy singleton | app process | no coroutine scope | process death | repository/catalog/client factories | `LatinIME`, view, connection |
| `DefaultAiVoiceSessionController` | `LatinIME.onCreate()` | one `LatinIME` | `CoroutineScope(SupervisorJob + Main.immediate)` | `LatinIME.onDestroy()` | toolbar/input/destroy commands | audio buffers, HTTP calls, view |
| `RecordingSession` | controller after preflight | controller | child `SupervisorJob` | controller `stopAndJoin` | `start`, `stopGracefully`, `cancel` | `InputConnection`, profile mutations |
| `AndroidAudioRecorder` | session factory | session | capture child on `IO` | session finally | `start`, `stop`, `close` | request queue, UI |
| `ChunkAssembler` | session factory | session/audio child | capture coroutine only | session end | `accept`, `flushFinal`, `discard` | HTTP, connection |
| `TranscriptionDispatcher` | session factory | session | one child worker on `IO` | session stop/cancel | `enqueue`, `drain`, `cancelPending` | recorder, view |
| `SpeechProvider` | provider registry | app process | invoked on `IO` | none/client closes at process death | `transcribe` | state/rotation/UI |
| `LatinImeTranscriptInserter` | controller factory | `LatinIME` | called only on Main | IME destroy | `insert` | retained connection/text |
| `RotationCoordinator` | dependencies | app process | all mutation on `IO` mutex | process death | resolve/record/failure/complete | session/audio/UI |
| `AiDiagnosticsRepository` | dependencies | app process | lock-free StateFlow update on Main or protected mutex | process death | `emit`, `snapshotForExport` | engine decisions, secrets |

### Required controller members and shutdown order

```kotlin
private val controllerJob = SupervisorJob()
private val controllerScope = CoroutineScope(controllerJob + Dispatchers.Main.immediate)
private val commands = Channel<Command>(Channel.UNLIMITED)
private var commandJob: Job = controllerScope.launch { consumeCommands() }
private var session: RecordingSession? = null
private var state: SessionState = SessionState.Idle
private var destroyed = false
```

`onImeDestroyed()` must execute this exact logical order: set `destroyed = true` → close `commands` (no more user actions) → invoke `session?.cancelAndJoin(IME_DESTROYED)` → set `session = null` → publish runtime Idle → cancel/join command job if invoked outside it → `controllerJob.cancel()` → diagnostics `AI-0108`. It must not wait on network on the main thread; joining is a suspend operation performed by the actor before its scope cancellation.

### Required session members

```kotlin
private val sessionJob = SupervisorJob(parentJob)
private val sessionScope = CoroutineScope(sessionJob + Dispatchers.Default)
private val captureJob: Job? = null
private val timeoutJob: Job? = null
private val dispatcher: TranscriptionDispatcher
private val recorder: AudioRecorder
private var closed = false
```

`stopGracefully` is idempotent: first caller transitions closed under a `Mutex`; it stops recorder, joins capture, flushes final chunk, bounds `dispatcher.drain()` with `withTimeout(FINAL_DRAIN_TIMEOUT_MS)`, cancels dispatcher after timeout, deletes all remaining chunk files, closes recorder, and completes `sessionJob`. `cancelAndJoin` skips final flush/drain, calls dispatcher cancellation before deleting files, and then follows the same close path. Each resource close belongs in a `finally` block.

## 12. Coroutine policy — no implicit scopes

| Coroutine name | Parent | Dispatcher | Exception policy | Cancellation/timeout |
|---|---|---|---|---|
| `ai-controller` | `LatinIME` controller job | Main.immediate | catches all expected failures; unexpected → `AI-0109`, Idle | IME destroy closes channel then cancels |
| `ai-session-capture` | session job | IO | recorder failure is reported to controller command channel | recorder stop unblocks blocking read; join required |
| `ai-session-timeout` | session job | Default | emits controller timeout command only | cancelled before resource close |
| `ai-transcription-dispatcher` | session job | IO | per-chunk failures never cancel sibling queue | cancellation cancels active OkHttp Call and deletes queued files |
| `ai-insert` | dispatcher | Main.immediate | insertion failure is result value, not thrown upstream | no retry after return |
| `ai-rotation` | caller coroutine | IO | validation failure returns no profile + diagnostic | bounded to one repository mutation |

Never use `GlobalScope`, `lifecycleScope`, `viewModelScope`, `runBlocking`, `Timer`, or `Handler.postDelayed` for engine operations. The only delayed operation is the session timeout coroutine. Attach a `CoroutineExceptionHandler` to controller/session scopes which emits `AI-0109` with exception class only, then sends a stop command; it must not swallow a cancellation exception.

## 13. Configuration and repository transaction ledger

`SharedPrefsAiVoiceSettingsRepository` is the only persistent configuration writer. It owns one `Mutex`, one JSON document key, and one `MutableStateFlow<AiVoiceConfig>`. All public mutation functions call one private `mutate(reason, transform)` function on `Dispatchers.IO`.

```kotlin
private suspend fun mutate(
    reason: String,
    transform: (AiVoiceConfig) -> AiVoiceConfig,
): AiVoiceConfig = withContext(Dispatchers.IO) {
    mutex.withLock {
        val next = transform(_config.value).normalized(catalog)
        next.requireValid(catalog)
        val payload = json.encodeToString(next)
        check(prefs.edit().putString(KEY_CONFIG_V1, payload).commit()) { "AI settings commit failed" }
        _config.value = next
        diagnostics.emit(info("AI-0201", "Config committed: $reason"))
        next
    }
}
```

`normalized` must not silently invent a profile/model. It may only trim display names, remove duplicate provider-option keys impossible in a map, and clamp non-negative usage values. Invalid persisted values cause decode-validation failure → backup corrupt payload under a private timestamped key if storage permits → reset to default → `AI-0202`; do not crash HeliBoard.

| Field | Default | Only writer | Readers | Write transaction / invalid handling |
|---|---|---|---|---|
| `schemaVersion` | `1` | repository migration | repository | migrate sequentially; unknown future version opens read-only/error, never overwrite |
| `nextProfileSerial` | `1` | create profile | profile manager/rotation | increment atomically; positive; never reuse |
| `profiles` | empty | create/save/delete | resolver/rotation/UI | max 40; unique id/serial; reject invalid provider/model |
| `activeProfileId` | null | manual select/rotation | next session summary | same commit as trigger/anchor mutation; null only when no valid active profile |
| `pendingProfileId` | null | manual select while active | controller/rotation | clear atomically when next session accepts it |
| `accumulatedRecordingMillis` | 0 | `recordActiveDuration` | summary/usage rotation | add monotonic delta once at terminal/pause boundary; reject negative/overflow |
| `dayRotationAnchorEpochMillis` | null | committed day rotation | day evaluation | UTC epoch; missing means initialize without rotating |
| `clockRotationAnchorElapsedRealtime` | null | in-process rotation | in-process clock evaluation | never sole persisted reboot source; add epoch anchor before enabling feature |
| `usageRotationLimitMillis` | null | UI settings | rotation | null disables; must be positive |
| API key | absent | `ApiKeyStore.write` | provider resolver | encrypted separate store; blank input preserves existing key; no JSON/backup/export |

### Required migration rule

Before enabling clock/usage rotation, add `clockRotationAnchorEpochMillis: Long?` and `usageCycleRecordingMillis: Long` (or product-approved equivalent) in schema version 2. Migration v1→v2 sets both null/zero and does not rotate. Without these fields the legacy document cannot meet reboot/cycle semantics; do not approximate.

### Profile deletion rule

Reject deletion while profile id equals active or pending and a session is not Idle (`AI-0210`). When idle, deleting active profile requires selecting another explicit profile first; no automatic cost-bearing selection. Delete configuration first, then secret; failure deleting secret schedules best-effort cleanup and emits `AI-0211`.

## 14. Exact audio and WAV contract

| Property | Required v1 value |
|---|---|
| Source | `MediaRecorder.AudioSource.VOICE_RECOGNITION` |
| Encoding | `AudioFormat.ENCODING_PCM_16BIT` |
| Channels | `CHANNEL_IN_MONO` |
| Sample rate | 16,000 Hz requested; fail start if device cannot initialize this exact format |
| Byte order | little-endian signed PCM |
| Frame size | 3,200 bytes / 100 ms |
| AudioRecord buffer | `max(getMinBufferSize(...) * 2, 6,400)` bytes |
| Pre-roll | 250 ms / 8,000 bytes ring buffer |
| Minimum accepted speech chunk | 500 ms / 16,000 bytes PCM |
| Silence terminal threshold | card setting: 2/5/10 s continuous non-speech |
| Maximum chunk | 45 s / 1,440,000 bytes PCM; force flush |
| Queue capacity | 8 chunks maximum; on full, graceful-stop with `AI-0412`, never grow memory |
| Storage | app cache `cacheDir/ai_voice_chunks/<sessionId>/<sequence>.wav` |

WAV writer writes a 44-byte RIFF header at file creation with placeholder lengths, appends PCM, and seeks back in `close()` to write `riffSize = 36 + dataBytes` and `dataSize = dataBytes`. It must reject `dataBytes > Int.MAX_VALUE - 36`, incomplete close, and zero data. Every file is opened/closed on `Dispatchers.IO`; no complete chunk is held in RAM.

The 45-second raw WAV is roughly 1.44 MB and below Groq’s documented upload limits. The provider’s minimum billed duration may cost more for short silence chunks; this is a documented cost consequence of the configured auto-send behavior, not a hidden engine throttle. Do not change the user-selected silence threshold to hide billing.

## 15. Diagnostic code registry

Codes are stable API. Do not use arbitrary English strings as logic keys.

| Code | Severity | Emit location | Safe behavior |
|---|---|---|---|
| `AI-0101` | INFO | toolbar event accepted | controller evaluates state |
| `AI-0102` | WARNING | start rejected/no profile | idle |
| `AI-0103` | WARNING | permission required/denied | idle |
| `AI-0104` | INFO | session recording | toolbar recording |
| `AI-0105` | INFO | graceful stop requested | stopping |
| `AI-0106` | INFO | session stopped | idle |
| `AI-0108` | INFO | IME destruction cleanup | idle |
| `AI-0109` | ERROR | uncaught engine exception | cancel session + idle |
| `AI-0201` | INFO | config committed | continue |
| `AI-0202` | ERROR | config decode/migration failed | default safe config |
| `AI-0203` | INFO | manual profile activation or pending selection committed | continue |
| `AI-0204` | WARNING/ERROR | manual profile selection rejected or not persisted | unchanged |
| `AI-0205` | ERROR | configuration update failed | unchanged |
| `AI-0206` | WARNING | legacy configuration migrated to current policy | continue |
| `AI-0210` | WARNING | active/pending delete rejected | unchanged |
| `AI-0211` | WARNING | secret cleanup failed | config removed; retry later |
| `AI-0301` | ERROR | recorder permission/mic init failure | idle |
| `AI-0302` | ERROR | AudioRecord read/dead object | stop gracefully |
| `AI-0401` | INFO | chunk queued | dispatch |
| `AI-0402` | WARNING | short/noise chunk discarded | continue recording |
| `AI-0412` | ERROR | chunk queue full | graceful-stop |
| `AI-0501` | INFO | provider request started | dispatch |
| `AI-0502` | WARNING | transient retry | bounded retry |
| `AI-0503` | ERROR | auth/authorization failure | rotation policy decision |
| `AI-0504` | ERROR | rate limit/server/network failure | policy decision |
| `AI-0505` | ERROR | malformed response/model invalid | final chunk failure |
| `AI-0601` | WARNING | empty transcript | drop chunk |
| `AI-0602` | INFO | transcript inserted | continue |
| `AI-0603` | WARNING | no/rejected InputConnection | drop chunk |
| `AI-0701` | WARNING | eligible failure queued | next session boundary |
| `AI-0703` | ERROR/WARNING | no eligible profile/alternate | retain current profile |
| `AI-0704` | INFO | first eligible profile selected | continue |
| `AI-0705` | INFO | pending manual profile activated | continue |
| `AI-0706` | INFO | simultaneous trigger deferred | retain trigger |
| `AI-0707` | INFO | profile rotated | next safe profile |
| `AI-0709` | INFO | non-eligible provider failure | retain current profile |

## 16. Required sequence flows

### Start

```text
Toolbar → SuggestionStripView → LatinIME.onEvent
 → Controller actor: Idle → Starting
 → RotationCoordinator.resolveProfileForNewSession
 → ProviderResolver validates profile/key presence
 → AudioRecord factory initialize + permission preflight
 → RecordingSession.start (capture + timeout + dispatcher)
 → RuntimeStateHolder.publish(Recording, monotonicStart)
 → toolbar binder renders recording timer
```

### Silence chunk and insertion

```text
AudioRecord IO read → RMS detector → ChunkAssembler
 → silence threshold → WAV close/queue(sequence N)
 → Dispatcher N → provider request → parse non-empty text
 → Main: obtain current InputConnection → batch commitText(text,1) → end batch
 → delete WAV → diagnostics result
```

### Graceful user stop / timeout

```text
toolbar toggle or timeout → controller actor → Stopping
 → recorder.stop → capture join → final WAV flush
 → dispatcher drains ordered queue (bounded timeout)
 → close recorder/delete files → persist usage + complete rotation evaluation
 → RuntimeStateHolder Idle → toolbar idle
```

### Provider failure

```text
provider response/IO failure → classify → bounded transient retry when applicable
 → eligible profile-specific failure queues RotationCoordinator.requestFailureRotation
 → current request ends without changing its session profile
 → next session start arbitrates rotation → diagnostic
```

### Permission denied / IME destroy

```text
preflight denied → AI-0103 → Runtime Idle (no AudioRecord)
LatinIME.onDestroy → controller closes command channel → cancel session jobs/HTTP
 → delete cache chunks → runtime Idle → controller scope cancel
```

## 17. Implementation verification gates

Before proceeding from each subsystem, all relevant boxes must be true. If one fails: **stop, fix it, rerun the gate; do not start the next subsystem.**

### Repository/configuration

- [ ] Every field in the ledger has exactly one writer and documented transaction.
- [ ] `commit()` failure leaves StateFlow unchanged.
- [ ] JSON corruption, future schema, migration, and 40-profile tests pass.
- [ ] Secret/key never appears in JSON, backup, diagnostics, exception, or export.

### Controller/lifecycle

- [ ] State transition table tests cover every command in every state.
- [ ] No engine `GlobalScope`/view-model/activity scope exists.
- [ ] Destroy during start/read/request/drain produces Idle and joins children.
- [ ] Existing HeliBoard voice shortcut is unchanged.

### Recorder/chunking

- [ ] Recorder uses exact format/buffer contract and fails closed if unavailable.
- [ ] Silence, forced, final, short-noise, and full-queue paths are tested with deterministic PCM fixtures.
- [ ] WAV header and data length validate with an independent WAV parser.
- [ ] Every terminal path deletes cache audio.

### Provider/dispatcher

- [ ] MockWebServer asserts Groq multipart/auth/model fields and no key logging.
- [ ] HTTP 401/403/429/5xx/timeout/cancel/malformed JSON each maps to one code/policy.
- [ ] Queue preserves N-before-N+1 insertion despite response timing.
- [ ] No HTTP request survives session cancellation.

### Insertion

- [ ] All `InputConnection` calls happen only on Main.immediate and use `try/finally` batch closing.
- [ ] Null/rejected/throwing connection drops, logs, and never retries into another app.
- [ ] Current user cursor/selection behavior is tested in EditText and Compose editor.

### Rotation/diagnostics

- [ ] Precedence, manual pending, all-ineligible, DST/timezone, reboot-anchor, and deletion tests pass.
- [ ] Every listed diagnostic code is emitted at its source and copy/export redaction tests pass.
- [ ] Toolbar and summary state follow runtime/repository flows without owning logic.

## 18. Cross-reference and final consistency review

Companion document: [architecture.md](architecture.md).

| UI/controller section | Runtime owner in this document |
|---|---|
| Card 1 Active Status/Summary | `AiVoiceRuntimeStateHolder`, sections 2/8 |
| Card 2 Manual Active API Selector | repository + `RotationCoordinator`, sections 3/7/13 |
| Card 3 Recording Behaviour | `DefaultAiVoiceSessionController`, section 2 |
| Card 4 Silence Detection/Auto Send | `VoiceActivityDetector`/`ChunkAssembler`, section 4 |
| Card 5 Automatic Recording Stop | `RecordingSession` timeout child, sections 4/12 |
| Card 6 Rotation/Fallback | `RotationCoordinator`, section 7 |
| Card 7 API Profile Manager | repository/`ProviderResolver`, sections 5/13 |
| Card 8 Diagnostics Console | `AiDiagnosticsRepository`, section 8/15 |
| AI toolbar button | controller + runtime state, sections 2/16; see `AI_Toolbar_Button.md` |

Final release review must confirm:

- [ ] Every UI control has exactly one runtime owner.
- [ ] Every runtime component is controlled by a documented UI control or marked internal.
- [ ] No persisted configuration field is orphaned.
- [ ] No runtime component is unreachable from `LatinIME` or retained by a view.
- [ ] There is no duplicate owner for profile selection, session state, audio, insertion, or diagnostics.
- [ ] `architecture.md`, `AI_Toolbar_Button.md`, and this document use the same names, fields, error codes, and lifecycle boundaries.

## Verified external implementation constraints

- Android `AudioRecord` owns microphone resources and supports blocking or non-blocking reads; this design uses blocking reads on an I/O coroutine. [Android AudioRecord reference](https://developer.android.com/reference/android/media/AudioRecord)
- `InputConnection.commitText(text, 1)` is the standard IME API for committing text and moving the cursor after the committed content. [Android InputConnection reference](https://developer.android.com/reference/android/view/inputmethod/InputConnection)
- Groq documents `POST /openai/v1/audio/transcriptions`, multipart `file` and `model`, the `whisper-large-v3` / `whisper-large-v3-turbo` models, and JSON `text` responses used by the initial provider implementation. [Groq speech-to-text documentation](https://console.groq.com/docs/speech-to-text)
