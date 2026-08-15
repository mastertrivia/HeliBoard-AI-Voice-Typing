// Ported from SpeechNotes' c.c.a.b — the complete voice session controller.
// This is the whole engine: SpeechRecognizer session, state machine, partial/stable text
// handling, restart loop, timers, error recovery. Logic identical to the original;
// only names were made readable.
package helium314.keyboard.voice;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** The SpeechNotes voice session controller (was c.c.a.b). */
public class VoiceController implements RecognitionListener {
    /** Error codes that surface to the IME as errors (0,3,9). */
    public static final List<Integer> A = Arrays.asList(0, 3, 9);

    private String recognitionService = "default";
    private ComponentName recognitionComponent = null;
    private Activity activity;
    private Context context;
    private Intent recognitionIntent;
    private VoiceCallback callback;
    private TextTrim textTrim;
    private ConnectToRecognizerRunnable connectRunnable;
    private AudioManager audioManager;
    private String language;
    private boolean normalizePunctuation = true;
    private boolean muteAudio;
    private boolean listening;
    private boolean speechBegan;
    private CountDownTimer noSpeechTimer;
    private CountDownTimer forceRestartTimer;
    private SpeechRecognizer recognizer = null;
    private int musicVolume = -1;
    private int systemVolume = -1;
    private String composingText = "";
    private String committedText = "";
    private String lastCallback = "";
    private int rmsRepeatCount = 0;
    private float lastRms = 0.0f;
    private String pendingTypedChar = "";
    private int uiState = 0;

    /** 4000ms no-speech-error timer. (was b$a) */
    class NoSpeechErrorTimer extends CountDownTimer {
        NoSpeechErrorTimer(long j, long j2) {
            super(j, j2);
        }

        @Override
        public void onFinish() {
            SmartLog.a("mTimerToNoSpeechError", "onFinish");
            VoiceController.this.callback.onAboutToReconnect();
            VoiceController.this.uiState = 1;
            VoiceController.this.forceRestartTimer.start();
        }

        @Override
        public void onTick(long j) {
            SmartLog.a("mTimerToNoSpeechError", "onTick");
        }
    }

    /** 2000ms force-restart timer (API < 23). (was b$b) */
    class ForceRestartTimerLegacy extends CountDownTimer {
        public ForceRestartTimerLegacy() {
            super(2000L, 2000L);
        }

        @Override
        public void onFinish() {
            SmartLog.a("mTimerToForceRestart", "onFinish");
            if (VoiceController.this.speechBegan) {
                SmartLog.a("mTimerToForceRestart", "mHasSpeechBegan true");
            }
            VoiceController voiceController = VoiceController.this;
            voiceController.destroyAndRestart(Boolean.valueOf(voiceController.listening));
        }

        @Override
        public void onTick(long j) {
        }
    }

    /** 900ms force-restart timer (API >= 23). (was b$c) */
    class ForceRestartTimer extends CountDownTimer {
        public ForceRestartTimer() {
            super(900L, 900L);
        }

        @Override
        public void onFinish() {
            SmartLog.a("mTimerToForceRestart", "onFinish");
            if (!VoiceController.this.speechBegan) {
                VoiceController voiceController = VoiceController.this;
                voiceController.restartListening(Boolean.valueOf(voiceController.listening));
            } else {
                SmartLog.a("mTimerToForceRestart", "mHasSpeechBegan true");
                VoiceController voiceController2 = VoiceController.this;
                voiceController2.destroyAndRestart(Boolean.valueOf(voiceController2.listening));
            }
        }

        @Override
        public void onTick(long j) {
        }
    }

    /** Connects to the recognizer: busy-wait for availability, then create + startListening. (was b$d) */
    private class ConnectToRecognizerRunnable implements Runnable {
        RecognitionListener listener;
        Context context;
        long startTime = System.currentTimeMillis();
        long timeout = 5000;

        public ConnectToRecognizerRunnable(Context context, RecognitionListener recognitionListener) {
            this.context = context;
            this.listener = recognitionListener;
        }

        public ConnectToRecognizerRunnable setTimeout(int i) {
            this.timeout = i;
            return this;
        }

        @Override
        public void run() {
            SmartLog.a("ConnectToRecognizerRunnable", "run");
            while (!SpeechRecognizer.isRecognitionAvailable(this.context) && System.currentTimeMillis() - this.startTime < this.timeout) {
            }
            if (SpeechRecognizer.isRecognitionAvailable(this.context)) {
                SmartLog.a("ConnectToRecognizerRunnable", "isRecognitionAvailable true");
                if (VoiceController.this.recognizer == null) {
                    VoiceController voiceController = VoiceController.this;
                    voiceController.recognizer = SpeechRecognizer.createSpeechRecognizer(this.context, voiceController.recognitionComponent);
                    VoiceController.this.recognizer.setRecognitionListener(this.listener);
                }
                try {
                    VoiceController.this.recognizer.startListening(VoiceController.this.recognitionIntent);
                    return;
                } catch (Exception unused) {
                }
            } else {
                SmartLog.a("ConnectToRecognizerRunnable", "isRecognitionAvailable false");
            }
            VoiceController.this.callback.onError(-2);
        }
    }

    public VoiceController(Context context, VoiceCallback callback, String language, Boolean muteAudio) {
        this.context = context;
        this.language = language;
        this.callback = callback;
        this.connectRunnable = new ConnectToRecognizerRunnable(context, this);
        this.audioManager = (AudioManager) context.getSystemService("audio");
        this.muteAudio = muteAudio.booleanValue();
        SmartLog.b(false);
        Intent intent = new Intent("android.speech.action.RECOGNIZE_SPEECH");
        this.recognitionIntent = intent;
        intent.putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE", true);
        this.recognitionIntent.putExtra("android.speech.extra.LANGUAGE_MODEL", "free_form");
        this.recognitionIntent.putExtra("android.speech.extra.MAX_RESULTS", 1);
        this.recognitionIntent.putExtra("android.speech.extra.PARTIAL_RESULTS", true);
        this.recognitionIntent.putExtra("calling_package", this.context.getPackageName());
        this.recognitionIntent.putExtra("android.speech.extra.DICTATION_MODE", true);
        setLanguage(language);
        String p = resolveRecognitionService();
        this.recognitionService = p;
        if (p.equals("no_google_service") || this.recognitionService.equals("no_service")) {
            this.callback.onError(-1);
        }
        if (!this.recognitionService.equals("default") && !this.recognitionService.equals("no_google_service") && !this.recognitionService.equals("no_service")) {
            recognitionComponent = ComponentName.unflattenFromString(this.recognitionService);
        }
        this.noSpeechTimer = new NoSpeechErrorTimer(4000L, 4000L);
        this.forceRestartTimer = Build.VERSION.SDK_INT >= 23 ? new ForceRestartTimer() : new ForceRestartTimerLegacy();
    }

    /** Cancel both timers. (was A()) */
    private void cancelTimers() {
        CountDownTimer countDownTimer = this.forceRestartTimer;
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        CountDownTimer countDownTimer2 = this.noSpeechTimer;
        if (countDownTimer2 != null) {
            countDownTimer2.cancel();
        }
    }

    /** Destroy the recognizer, then restart listening if the flag is set. (was l(Boolean)) */
    public void destroyAndRestart(Boolean bool) {
        SmartLog.a(VoiceController.class.getName(), "destroyAndRestart");
        destroyRecognizer();
        if (bool.booleanValue()) {
            restartListening(bool);
        }
    }

    /** Process a stable hypothesis and hand it to the IME for commit. (was n(String, float)) */
    private void processStableText(String str, float f) {
        String str2;
        this.composingText = "";
        while (str.startsWith(" ")) {
            str = str.substring(1);
        }
        if (!this.normalizePunctuation) {
            str = normalizePunctuation(str);
        }
        if (this.pendingTypedChar.equals("")) {
            str2 = this.textTrim.formatSpoken(str);
        } else {
            str2 = TextTrim.trimEnd(str) + this.pendingTypedChar;
            this.pendingTypedChar = "";
        }
        this.callback.commitText(str2, f);
    }

    /** Human-readable error message. (was o(int)) */
    public static String errorString(int i) {
        switch (i) {
            case 0:
                return "Language not supported error";
            case 1:
                return "Network timeout";
            case 2:
                return "Network error";
            case 3:
                return "Audio recording error";
            case 4:
                return "error from server";
            case 5:
                return "Client side error";
            case 6:
                return "No mSpeech input";
            case 7:
                return "No match";
            case 8:
                return "RecognitionService busy";
            case 9:
                return "Insufficient permissions";
            default:
                return "Didn't understand, please try again.";
        }
    }

    /** Resolve which RecognitionService to use (Google preferred). (was p()) */
    private String resolveRecognitionService() {
        PackageManager packageManager;
        Intent intent;
        Activity activity = this.activity;
        String string = Settings.Secure.getString(activity != null ? activity.getContentResolver() : this.context.getContentResolver(), "voice_recognition_service");
        if (string != null && string.indexOf("google") != -1) {
            return "default";
        }
        Activity activity2 = this.activity;
        if (activity2 != null) {
            packageManager = activity2.getPackageManager();
            intent = new Intent("android.speech.RecognitionService");
        } else {
            packageManager = this.context.getPackageManager();
            intent = new Intent("android.speech.RecognitionService");
        }
        List<ResolveInfo> queryIntentServices = packageManager.queryIntentServices(intent, 0);
        if (queryIntentServices.size() == 0) {
            return "no_service";
        }
        if (queryIntentServices.size() == 1) {
            if (queryIntentServices.get(0).toString().indexOf("google") == -1) {
                return "no_google_service";
            }
            return queryIntentServices.get(0).serviceInfo.packageName + "/" + queryIntentServices.get(0).serviceInfo.name;
        }
        String str = "";
        for (ResolveInfo resolveInfo : queryIntentServices) {
            if (resolveInfo.toString().indexOf("google") != -1) {
                str = resolveInfo.serviceInfo.packageName + "/" + resolveInfo.serviceInfo.name;
                if ("com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService".equals(str)) {
                    return "com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService";
                }
            }
        }
        return !str.equals("") ? str : "no_google_service";
    }

    private static String lowerFirst(String str) {
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    private static void busyWait(Long l) {
        while (System.currentTimeMillis() < Long.valueOf(System.currentTimeMillis()).longValue() + l.longValue()) {
        }
    }

    /** Punctuation normalization (dead path in this build, l is always true). (was t(String)) */
    public static String normalizePunctuation(String str) {
        if (str != null && str.trim().length() > 2) {
            if (str.endsWith(".") || str.endsWith("?") || str.endsWith("!")) {
                str = str.substring(0, str.length() - 1);
            }
            if (str.length() < 2) {
                return str;
            }
            String[] strArr = {". ", "? ", "! "};
            for (int i = 0; i < 3; i++) {
                String str2 = strArr[i];
                if (str.contains(str2)) {
                    String[] split = str.split(Pattern.quote(str2));
                    for (int i2 = 1; i2 < split.length; i2++) {
                        String str3 = split[i2];
                        while (str3.startsWith(" ")) {
                            str3 = str3.substring(1);
                        }
                        if (!str3.startsWith("I ") && !str3.startsWith("I'") && str3.length() > 1) {
                            str3 = lowerFirst(str3);
                        }
                        split[i2] = str3;
                    }
                    str = TextUtils.join(" ", split);
                }
            }
            String str4 = ", ";
            do {
                str = str.replace(str4, " ");
                str4 = "  ";
            } while (str.contains("  "));
        }
        return str;
    }

    /** Restart listening: reset state, then reconnect + startListening. (was u(Boolean)) */
    public void restartListening(Boolean bool) {
        SmartLog.a(VoiceController.class.getName(), "restartListening");
        this.committedText = "";
        this.composingText = "";
        this.speechBegan = false;
        if (bool.booleanValue()) {
            cancelTimers();
            ConnectToRecognizerRunnable connectToRecognizerRunnable = this.connectRunnable;
            connectToRecognizerRunnable.setTimeout(5000);
            connectToRecognizerRunnable.run();
        }
    }

    /** Destroy the recognizer; flush leftover composing text as a commit first. (was m()) */
    public void destroyRecognizer() {
        SmartLog.a(VoiceController.class.getName(), "destroyRecognizer");
        cancelTimers();
        String str = this.composingText;
        if (str != null && str.length() > 0) {
            processStableText(this.composingText, 0.5f);
            this.composingText = "";
        }
        SpeechRecognizer speechRecognizer = this.recognizer;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Exception unused) {
            }
        }
        this.recognizer = null;
    }

    @Override
    public void onBeginningOfSpeech() {
        SmartLog.a(VoiceController.class.getName(), "onBeginningOfSpeech");
        this.speechBegan = true;
        cancelTimers();
        if (this.listening && this.uiState != 2) {
            this.callback.onListening();
            this.uiState = 2;
        }
        this.lastCallback = "onBeginningOfSpeech";
    }

    @Override
    public void onBufferReceived(byte[] bArr) {
        SmartLog.a(VoiceController.class.getName(), "onBufferReceived");
    }

    @Override
    public void onEndOfSpeech() {
        SmartLog.a(VoiceController.class.getName(), "onEndOfSpeech");
        if (this.lastCallback.equals("onResults")) {
            destroyAndRestart(Boolean.valueOf(this.listening));
        }
        if (this.listening) {
            this.callback.onConnecting();
        }
        this.lastCallback = "onEndOfSpeech";
        this.uiState = 0;
    }

    @Override
    public void onError(int i) {
        String errorString = errorString(i);
        SmartLog.a(VoiceController.class.getName(), "onError: " + errorString);
        if (this.listening) {
            this.callback.onConnecting();
        }
        if (A.indexOf(Integer.valueOf(i)) != -1) {
            this.callback.onError(i);
        }
        if (this.composingText.length() > 0) {
            processStableText(this.composingText, 1.0f);
        }
        if (this.lastCallback.equalsIgnoreCase("onError - network")) {
            busyWait(500L);
        }
        if (i != 7) {
            destroyAndRestart(Boolean.valueOf(this.listening));
        } else {
            restartListening(Boolean.valueOf(this.listening));
        }
        if (i == 2) {
            this.lastCallback = "onError - network";
        } else {
            this.lastCallback = "onError - general";
        }
    }

    @Override
    public void onEvent(int i, Bundle bundle) {
        SmartLog.a(VoiceController.class.getName(), "onEvent");
    }

    @Override
    public void onPartialResults(Bundle bundle) {
        SmartLog.a(VoiceController.class.getName(), "onPartialResults: " + bundle.getStringArrayList("results_recognition").get(0));
        cancelTimers();
        this.speechBegan = true;
        if (this.listening && this.uiState != 2) {
            this.uiState = 2;
            this.callback.onListening();
        }
        this.noSpeechTimer.start();
        String str = bundle.getStringArrayList("results_recognition").get(0);
        if (bundle.containsKey("android.speech.extra.UNSTABLE_TEXT")) {
            if (!this.normalizePunctuation) {
                str = normalizePunctuation(str);
            }
            this.composingText = str;
            this.callback.setComposingText(str);
        } else {
            Float valueOf = Float.valueOf(1.0f);
            if (bundle.containsKey("confidence_scores")) {
                valueOf = Float.valueOf(bundle.getFloatArray("confidence_scores")[0]);
            }
            this.committedText = str;
            processStableText(str, valueOf.floatValue());
        }
        System.currentTimeMillis();
        this.lastCallback = "onPartialResults";
    }

    @Override
    public void onReadyForSpeech(Bundle bundle) {
        SmartLog.a(VoiceController.class.getName(), "onReadyForSpeech");
        this.callback.onListening();
        this.uiState = 2;
        cancelTimers();
        this.noSpeechTimer.start();
        this.lastCallback = "onReadyForSpeech";
        this.lastRms = 0.0f;
    }

    @Override
    public void onResults(Bundle bundle) {
        SmartLog.a(VoiceController.class.getName(), "onResults: " + bundle.getStringArrayList("results_recognition").get(0));
        cancelTimers();
        if (this.composingText.length() > 0) {
            bundle.getStringArrayList("results_recognition");
            bundle.getFloatArray("confidence_scores");
            String str = bundle.getStringArrayList("results_recognition").get(0);
            float f = bundle.getFloatArray("confidence_scores")[0];
            if (this.committedText.length() > 0) {
                String[] split = str.split(this.committedText);
                str = split.length > 0 ? split[split.length - 1] : "";
            }
            processStableText(str, f);
        }
        restartListening(Boolean.valueOf(this.listening));
        this.committedText = "";
        this.lastCallback = "onResults";
    }

    @Override
    public void onRmsChanged(float f) {
        this.callback.onRmsChanged(f);
        if (f >= -2.1d || this.lastRms != f) {
            this.rmsRepeatCount = 0;
        } else {
            this.rmsRepeatCount++;
            this.callback.onRmsChanged(0.0f);
        }
        if (this.rmsRepeatCount > 30) {
            this.rmsRepeatCount = 0;
            destroyAndRestart(Boolean.valueOf(this.listening));
        }
        this.lastRms = f;
    }

    /** Whether the controller is actively listening. (was q()) */
    public Boolean isListening() {
        return Boolean.valueOf(this.listening);
    }

    /** Set mute-audio-during-listening flag. (was v(boolean)) */
    public void setMuteAudio(boolean z) {
        this.muteAudio = z;
    }

    /** Set the recognition language. (was w(String)) */
    public void setLanguage(String str) {
        this.language = str;
        this.recognitionIntent.putExtra("android.speech.extra.LANGUAGE", str);
        this.recognitionIntent.putExtra("android.speech.extra.LANGUAGE_PREFERENCE", this.language);
        this.textTrim = new TextTrim(this.language, Boolean.valueOf(this.normalizePunctuation));
    }

    /** Type a character while listening: buffer it if composing, else commit directly. (was x(String)) */
    public void typeChar(String str) {
        if (this.composingText.length() > 0) {
            this.pendingTypedChar = str;
        } else {
            this.callback.commitText(str, 1.0f);
        }
    }

    /** Start listening. (was y()) */
    public boolean startListening() {
        SmartLog.a(VoiceController.class.getName(), "startListening");
        if (this.recognitionService.equals("no_google_service") || this.recognitionService.equals("no_service")) {
            String p = resolveRecognitionService();
            this.recognitionService = p;
            if (p.equals("no_google_service") || this.recognitionService.equals("no_service")) {
                this.callback.onError(-1);
                return false;
            }
        }
        this.listening = true;
        this.speechBegan = false;
        restartListening(Boolean.TRUE);
        if (AudioStreamHelper.getMusicVolume(this.audioManager) > 0) {
            this.musicVolume = AudioStreamHelper.getMusicVolume(this.audioManager);
        }
        if (AudioStreamHelper.getSystemVolume(this.audioManager) > 0) {
            this.systemVolume = AudioStreamHelper.getSystemVolume(this.audioManager);
        }
        if (this.muteAudio) {
            AudioStreamHelper.muteStreams(this.audioManager);
        }
        this.callback.onConnecting();
        this.uiState = 0;
        return true;
    }

    /** Stop listening (recognizer kept alive for reuse). (was z()) */
    public void stopListening() {
        SmartLog.a(VoiceController.class.getName(), "stopListening");
        this.listening = false;
        cancelTimers();
        SpeechRecognizer speechRecognizer = this.recognizer;
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
        AudioStreamHelper.restoreMusicVolume(this.audioManager, this.musicVolume);
        AudioStreamHelper.restoreSystemVolume(this.audioManager, this.systemVolume);
    }
}
