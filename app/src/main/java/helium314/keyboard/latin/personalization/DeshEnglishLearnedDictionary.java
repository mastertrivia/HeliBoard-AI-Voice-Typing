/*
 * Copyright (C) 2026 HeliBorg / HeliBoard modifications
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin.personalization;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import com.android.inputmethod.latin.BinaryDictionary;

import helium314.keyboard.latin.NgramContext;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary;
import helium314.keyboard.latin.makedict.DictionaryHeader;
import helium314.keyboard.latin.common.ComposedData;
import helium314.keyboard.latin.settings.SettingsValuesForSuggestion;

/**
 * Dedicated English learned dictionary used by the Desh-English prediction path.
 * It deliberately uses the same native ExpandableBinaryDictionary mechanism as
 * HeliBoard's history dictionary, but has its own persistent file so English Desh
 * learning is not mixed with the ordinary HeliBoard history dictionary.
 */
public final class DeshEnglishLearnedDictionary extends ExpandableBinaryDictionary {
    private static final String NAME = "DeshEnglishLearnedDictionary";

    DeshEnglishLearnedDictionary(final Context context, final Locale locale) {
        super(context, getDictName(NAME, locale, null), locale, Dictionary.TYPE_USER_HISTORY, null);
        if (mLocale != null && mLocale.toString().length() > 1) {
            reloadDictionaryIfRequired();
        }
    }

    private static Map<String, String> historyHeaders(Map<String, String> base) {
        base.put(DictionaryHeader.USES_FORGETTING_CURVE_KEY,
                DictionaryHeader.ATTRIBUTE_VALUE_TRUE);
        base.put(DictionaryHeader.HAS_HISTORICAL_INFO_KEY,
                DictionaryHeader.ATTRIBUTE_VALUE_TRUE);
        return base;
    }

    @Override
    protected Map<String, String> getHeaderAttributeMap() {
        return historyHeaders(super.getHeaderAttributeMap());
    }

    @Override
    protected void loadInitialContentsLocked() {
        // Learned dictionary starts empty.
    }

    @Override
    public boolean isValidWord(final String word) {
        // Learned entries are prediction candidates, not main spelling words.
        return false;
    }

    public void learn(@NonNull final NgramContext context, @NonNull final String word,
            final int timestamp) {
        if (word.length() == 0 || word.length() > BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH) {
            return;
        }
        updateEntriesForWord(context, word, true, 1, timestamp);
    }

    @Nullable
    public ArrayList<SuggestedWordInfo> suggestions(@NonNull final ComposedData data,
            @NonNull final NgramContext context,
            @NonNull final SettingsValuesForSuggestion settings,
            final int sessionId) {
        return getSuggestions(data, context, 0L, settings, sessionId, 1.0f,
                new float[] { Dictionary.NOT_A_WEIGHT_OF_LANG_MODEL_VS_SPATIAL_MODEL });
    }
}
