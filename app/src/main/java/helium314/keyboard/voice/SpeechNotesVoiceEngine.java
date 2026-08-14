// Ported from SpeechNotes' Speechkeys.java — the IME-side glue of the voice engine.
// Every voice-related behavior from the original IME is recreated here:
//   - b()  -> commitText with boundary processing
//   - f()  -> grey composing text
//   - c()/d()/e() -> status callbacks (no-ops here: HeliBoard's button UI is kept unchanged)
//   - onError() -> log + full stop
//   - Y()/Z() -> start/stop (controller + BT SCO + watchdog + wake lock)
//   - startOrPauseListener() -> the mic toggle
//   - s()/I() -> RECORD_AUDIO + Google service gates
//   - y()/x() -> typed-character routing while listening
//   - r() -> boundary-aware backspace while listening
//   - Speechkeys$a -> 60s/30s watchdog
// Logic identical to the original; only names and the settings-launch target changed.
package helium314.keyboard.voice;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.inputmethod.InputConnection;

import androidx.core.content.ContextCompat;

import java.util.List;

import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.settings.SettingsActivity;

/** The Speech Notes voice engine, hosted by HeliBoard (was the voice parts of Speechkeys.java). */
public class SpeechNotesVoiceEngine implements VoiceCallback {

    private static final long WATCHDOG_MILLIS = 60000L;
    private static final long WATCHDOG_TICK = 30000L;
    private static final int WAKE_LOCK_FLAGS = PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
    private static final String WAKE_LOCK_TAG = "SpeechKeys::MyWakelockTag";

    private final LatinIME ime;
    private final VoiceController controller;
    private final BluetoothScoManager bluetoothSco;
    private final CountDownTimer watchdog;
    private PowerManager.WakeLock wakeLock;

    /** Length of the currently composing region. (was Speechkeys.q) */
    private int composingLength;
    /** Current recognition language. (was Speechkeys.t) */
    private String language = "en-US";
    /** Cached Google-service availability. (was Speechkeys.u) */
    private boolean googleServiceCached;

    public SpeechNotesVoiceEngine(LatinIME ime) {
        this.ime = ime;
        this.controller = new VoiceController(ime, this, language, Boolean.TRUE);
        this.bluetoothSco = new BluetoothScoManager(ime) {
            @Override
            public void onHeadsetConnected() {
            }

            @Override
            public void onHeadsetDisconnected() {
            }

            @Override
            public void onScoConnected() {
                start();
            }

            @Override
            public void onScoDisconnected() {
                stop();
            }
        };
        this.watchdog = new CountDownTimer(WATCHDOG_MILLIS, WATCHDOG_TICK) { // was Speechkeys$a
            @Override
            public void onTick(long millisUntilFinished) {
            }

            @Override
            public void onFinish() {
                if (controller.isListening()) {
                    stop();
                }
            }
        };
        PowerManager powerManager = (PowerManager) ime.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            this.wakeLock = powerManager.newWakeLock(WAKE_LOCK_FLAGS, WAKE_LOCK_TAG);
        }
        this.googleServiceCached = googleServiceAvailable();
    }

    /** The mic toggle. (was Speechkeys.startOrPauseListener, minus the billing gate) */
    public void startOrPause() {
        if (controller.isListening()) {
            stop();
            return;
        }
        if (!hasRecordAudioPermission() || !googleServiceCached) {
            openSetup();
        } else {
            start();
        }
    }

    /** Full start: controller + BT SCO + watchdog + wake lock. (was Speechkeys.Y) */
    public void start() {
        controller.startListening();
        bluetoothSco.start();
        watchdog.start();
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    /** Full stop: BT SCO + controller + watchdog + wake lock. (was Speechkeys.Z) */
    public void stop() {
        bluetoothSco.stop();
        if (controller.isListening()) {
            controller.stopListening();
        }
        watchdog.cancel();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        ime.onVoiceEngineListeningStateChanged(false);
    }

    /** Stop if currently listening. (was the check inside Speechkeys.onFinishInput) */
    public void stopIfListening() {
        if (controller.isListening()) {
            stop();
        }
    }

    public boolean isListening() {
        return controller.isListening();
    }

    /** Set the recognition language (both controller and text processing). (was Speechkeys.R) */
    public void setLanguage(String lang) {
        this.language = lang;
        controller.setLanguage(lang);
    }

    /** Route a typed string: buffer it in the engine while composing, else commit. (was Speechkeys.y/x) */
    public void typeText(String str) {
        if (controller.isListening()) {
            controller.typeChar(str);
        } else {
            commitText(str, 1.0f);
        }
    }

    /** Boundary-aware backspace while listening. (was Speechkeys.r) */
    public void deleteChar() {
        InputConnection currentInputConnection = ime.getCurrentInputConnection();
        if (currentInputConnection == null) {
            return;
        }
        CharSequence selectedText = currentInputConnection.getSelectedText(0);
        if (selectedText != null && selectedText.length() >= 1) {
            sendDeleteKeyEvent();
            return;
        }
        if (isEmojiAtStart(currentInputConnection.getTextBeforeCursor(2, 1).toString())) {
            currentInputConnection.deleteSurroundingText(2, 0);
            return;
        }
        int e2 = TextBoundary.deleteBoundaryLength(currentInputConnection.getTextBeforeCursor(10, 0).toString());
        if (e2 <= 0 || e2 > 10) {
            return;
        }
        currentInputConnection.deleteSurroundingText(e2, 0);
    }

    // -- VoiceCallback implementation (was the Speechkeys implements c.c.a.a methods) --

    /** Commit stable/final text with boundary processing. (was Speechkeys.b) */
    @Override
    public void commitText(String str, float f) {
        InputConnection currentInputConnection = ime.getCurrentInputConnection();
        if (currentInputConnection == null) {
            return;
        }
        try {
            String before = currentInputConnection.getTextBeforeCursor(this.composingLength + 10, 0).toString();
            String ctxB = TextBoundary.sliceBeforeComposing(before, -(this.composingLength + 10), -this.composingLength);
            String after = currentInputConnection.getTextAfterCursor(1, 0).toString();
            String out = TextBoundary.process(str, ctxB, after, this.language);
            currentInputConnection.commitText(out, 1);
            this.composingLength = 0;
            if (this.watchdog != null) {
                this.watchdog.cancel();
            }
            this.watchdog.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Show unstable text as grey composing text. (was Speechkeys.f) */
    @Override
    public void setComposingText(String str) {
        InputConnection currentInputConnection;
        if (str == null || str.length() < 1 || (currentInputConnection = ime.getCurrentInputConnection()) == null) {
            return;
        }
        try {
            String before = currentInputConnection.getTextBeforeCursor(this.composingLength + 10, 0).toString();
            String ctxB = TextBoundary.sliceBeforeComposing(before, -(this.composingLength + 10), -this.composingLength);
            String after = currentInputConnection.getTextAfterCursor(1, 0).toString();
            String out = TextBoundary.process(str, ctxB, after, this.language);
            SpannableString spannableString = new SpannableString(out);
            spannableString.setSpan(new ForegroundColorSpan(0xFF888888), 0, out.length(), 0);
            currentInputConnection.setComposingText(spannableString, 1);
            this.composingLength = out.length();
            if (this.watchdog != null) {
                this.watchdog.cancel();
            }
            this.watchdog.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** "About to reconnect" — the session is still active, so keep the listening indicator on. (was c() -> W()) */
    @Override
    public void onAboutToReconnect() {
        ime.onVoiceEngineListeningStateChanged(true);
    }

    /** "Wait, connecting" — recognition is (re)starting, keep the listening indicator on. (was d() -> U()) */
    @Override
    public void onConnecting() {
        ime.onVoiceEngineListeningStateChanged(true);
    }

    /** "Listening..." — drive the mic button's animated three-bar indicator. (was e() -> V()) */
    @Override
    public void onListening() {
        ime.onVoiceEngineListeningStateChanged(true);
    }

    /** Error: log and do a full stop. (was Speechkeys.onError) */
    @Override
    public void onError(int i) {
        VoiceController.errorString(i);
        stop();
    }

    /** RMS meter — unused in HeliBoard. (was Speechkeys.onRmsChanged) */
    @Override
    public void onRmsChanged(float f) {
    }

    // -- helpers (ported from Speechkeys) --

    /** RECORD_AUDIO permission check. (was Speechkeys.s) */
    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(ime, "android.permission.RECORD_AUDIO")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Google recognition service availability. (was Speechkeys.I) */
    private boolean googleServiceAvailable() {
        String string = Settings.Secure.getString(ime.getContentResolver(), "voice_recognition_service");
        if (string != null && string.indexOf("google") != -1) {
            return true;
        }
        List<ResolveInfo> queryIntentServices = ime.getPackageManager().queryIntentServices(new Intent("android.speech.RecognitionService"), 0);
        if (queryIntentServices.size() == 0) {
            return false;
        }
        if (queryIntentServices.size() == 1) {
            if (queryIntentServices.get(0).toString().indexOf("google") == -1) {
                return false;
            }
            return true;
        }
        String str2 = "";
        for (ResolveInfo resolveInfo : queryIntentServices) {
            if (resolveInfo.toString().indexOf("google") != -1) {
                str2 = resolveInfo.serviceInfo.packageName + "/" + resolveInfo.serviceInfo.name;
                if ("com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService".equals(str2)) {
                    return true;
                }
            }
        }
        return !str2.equals("");
    }

    /** Open setup when permission/service missing. (was Speechkeys.z -> LauncherActivity) */
    private void openSetup() {
        Intent intent = new Intent(ime, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ime.startActivity(intent);
    }

    /** Send a single backspace key event. (was Speechkeys.K(67)) */
    private void sendDeleteKeyEvent() {
        InputConnection currentInputConnection = ime.getCurrentInputConnection();
        if (currentInputConnection == null) {
            return;
        }
        android.view.KeyEvent down = new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL);
        android.view.KeyEvent up = new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DEL);
        currentInputConnection.sendKeyEvent(down);
        currentInputConnection.sendKeyEvent(up);
    }

    /** Emoji surrogate-pair check. (was com.speechlogger.customprototypes.a.c) */
    private static boolean isEmojiAtStart(String str) {
        return str != null && str.length() == 2 && isEmojiCodePoint(Character.codePointAt(str, 0));
    }

    private static boolean isEmojiCodePoint(int i) {
        return i >= 127744 && i <= 129433;
    }
}
