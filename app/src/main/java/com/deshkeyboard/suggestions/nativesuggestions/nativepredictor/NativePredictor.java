package com.deshkeyboard.suggestions.nativesuggestions.nativepredictor;

import android.content.res.AssetManager;

/**
 * ABI-compatible declaration for the Desh native Hindi predictor.
 * The actual implementation lives in libnativepredictor.so.
 *
 * This class intentionally contains declarations only; all use is guarded by
 * DeshHindiPredictor so HeliBoard can fall back cleanly if the native predictor
 * is unavailable on a device/ABI.
 */
public final class NativePredictor {
    private NativePredictor() {}

    public static native long load(String assetName, Object assetManager);
    public static native boolean loadLm(long handle, String assetName, Object assetManager);
    public static native String[] nativeLayoutPrefixSearch(
            long handle, String[] previousWords, String prefix, int maxResults, boolean beginningOfSentence);
    public static native String[] transliterationPrefixSearch(
            long handle, String[] previousWords, String prefix, int maxResults, boolean beginningOfSentence);
    public static native String[] getNextWords(long handle, String[] previousWords, int maxResults);
}
