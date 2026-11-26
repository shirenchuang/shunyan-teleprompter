package com.shunyan.prompter;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

record PromptAppearance(int font, int opacity, int background, int text, int read) {
    static final int MIN_FONT = 8;
    static final int MAX_FONT = 64;
    static final int DEFAULT_OPACITY = 100;
    static final int DEFAULT_BACKGROUND = Color.BLACK;
    static final int DEFAULT_TEXT = Color.WHITE;
    static final int LAVENDER_GLASS = 0xffebe1fa;

    static PromptAppearance load(SharedPreferences preferences) {
        int fallback = preferences.contains("textColor") ? LAVENDER_GLASS : DEFAULT_BACKGROUND;
        int background = preferences.getInt("backgroundColor", fallback);
        return new PromptAppearance(
                Math.max(MIN_FONT, Math.min(MAX_FONT, preferences.getInt("font", 26))),
                Math.max(0, Math.min(100, preferences.getInt("opacity", DEFAULT_OPACITY))),
                background, preferences.getInt("textColor", defaultText(background)),
                preferences.getInt("readColor", 0));
    }

    static void reset(SharedPreferences preferences) {
        preferences.edit().putInt("backgroundColor", DEFAULT_BACKGROUND).putInt("textColor", DEFAULT_TEXT)
                .putInt("opacity", DEFAULT_OPACITY).remove("readColor").apply();
    }

    static int defaultText(int background) {
        return Color.luminance(background) >= .42f ? Ui.INK : DEFAULT_TEXT;
    }

    static GradientDrawable fill(Context context, int background, int opacity, int radius) {
        int alpha = Math.round(opacity * 255f / 100);
        if (background == LAVENDER_GLASS) return Ui.glass(context, alpha, radius);
        return Ui.shape(context, (alpha << 24) | (background & 0x00ffffff), radius, Color.TRANSPARENT);
    }
}
