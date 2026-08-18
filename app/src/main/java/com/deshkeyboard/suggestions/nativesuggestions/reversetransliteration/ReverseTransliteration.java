package com.deshkeyboard.suggestions.nativesuggestions.reversetransliteration;

import android.content.res.AssetManager;

/**
 * ABI-compatible declaration for the Desh native reverse-transliteration engine
 * (Devanagari -> Latin). The implementation lives in libplaywright.so, loaded
 * like the original app:
 *
 *   System.loadLibrary("playwright")
 *   loadModelNative("reverse_transliteration.db", assets) -> handle
 *   predictNative(handle, text, 3) -> String[]
 *
 * Mirrors com/deshkeyboard/suggestions/nativesuggestions/reversetransliteration/
 * ReverseTransliteration.smali: same class name, same native method names and
 * signatures, so JNI registration resolves identically.
 *
 * This class intentionally contains declarations only; all use is guarded by
 * DeshReverseTransliteration so HeliBoard can fall back cleanly.
 */
public final class ReverseTransliteration {
    public ReverseTransliteration() {}

    public native long loadModelNative(String assetName, AssetManager assetManager);

    public native String[] predictNative(long handle, String text, int maxResults);
}
