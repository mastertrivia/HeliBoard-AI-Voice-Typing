package com.deshkeyboard.suggestions.nativesuggestions.transliteration;

import android.content.res.AssetManager;

/**
 * ABI-compatible declaration for the Desh native transliteration engine.
 * The actual implementation lives in libplaywright.so (Desh's FST transliteration
 * model), loaded exactly like the original app does:
 *
 *   System.loadLibrary("playwright")
 *   loadModelNative("transliteration.db", assets)  -> handle
 *   predictNative(handle, text.toLowerCase(), 12)  -> String[]
 *
 * Mirrors com/deshkeyboard/suggestions/nativesuggestions/transliteration/
 * Transliteration.smali: same class name, same native method names and
 * signatures, so JNI registration (name- or symbol-based) resolves identically.
 *
 * This class intentionally contains declarations only; all use is guarded by
 * DeshTransliteration so HeliBoard can fall back cleanly if the engine is
 * unavailable on a device/ABI.
 */
public final class Transliteration {
    public Transliteration() {}

    public native long loadModelNative(String assetName, AssetManager assetManager);

    public native String[] predictNative(long handle, String text, int maxResults);
}
