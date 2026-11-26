package com.shunyan.prompter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int PAGE = 0xfff1ebfa;
    static final int CARD = 0xbffaf9ff;
    static final int INK = 0xff251c44;
    static final int MUTED = 0xff60577d;
    static final int ACCENT = 0xff6439b4;
    static final int LAVENDER = 0xffece1ff;
    static final int AQUA = 0xffdef7fb;
    static final int WHITE_EDGE = 0xe6ffffff;
    static final int NIGHT = 0xff25213d;
    static final int NIGHT_INK = 0xfff4f8ff;
    static final int ICE = 0xffd7c8ff;

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, int radius, int outline) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (outline != Color.TRANSPARENT) shape.setStroke(dp(context, 1), outline);
        return shape;
    }

    static LayerDrawable atmosphere(Context context) {
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xffecd5fa, PAGE, 0xffd7f6fc});
        GradientDrawable aqua = glow(context, .98f, .06f, 250, 0xc0a8f3f4);
        GradientDrawable lilac = glow(context, .05f, .96f, 190, 0x8ee5c4fa);
        return new LayerDrawable(new GradientDrawable[] {sky, aqua, lilac});
    }

    static GradientDrawable glass(Context context, int opacity, int radius) {
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(opacity << 24) | 0xf1dcfb, (opacity << 24) | 0xece7fa, (opacity << 24) | 0xc7effb});
        fill.setCornerRadius(dp(context, radius));
        return fill;
    }

    private static GradientDrawable glow(Context context, float x, float y, int radius, int color) {
        GradientDrawable spot = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {color, color & 0x00ffffff});
        spot.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        spot.setGradientRadius(dp(context, radius));
        spot.setGradientCenter(x, y);
        return spot;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static TextView text(Context context, CharSequence value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static TextView action(Context context, String label, int background, int foreground, Runnable task) {
        TextView view = text(context, label, 14, foreground, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 46));
        view.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x336439b4),
                shape(context, background, 12, Color.TRANSPARENT),
                shape(context, Color.WHITE, 12, Color.TRANSPARENT)));
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        view.setOnClickListener(ignored -> task.run());
        return view;
    }

    static TextView pillAction(Context context, String label, int background, int foreground, Runnable task) {
        TextView view = action(context, label, Color.TRANSPARENT, foreground, task);
        setPill(view, background, Color.TRANSPARENT);
        return view;
    }

    static void setPill(TextView view, int background, int edge) {
        Context context = view.getContext();
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x306439b4),
                shape(context, background, 24, edge), shape(context, Color.WHITE, 24, Color.TRANSPARENT)));
    }

    static TextView primaryAction(Context context, String label, Runnable task) {
        TextView view = action(context, label, Color.TRANSPARENT, Color.WHITE, task);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xff824ac6, 0xff7450ce, 0xff277da2});
        fill.setCornerRadius(dp(context, 24));
        fill.setStroke(dp(context, 1), 0xbaf4efff);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x45ffffff),
                fill, shape(context, Color.WHITE, 24, Color.TRANSPARENT)));
        return view;
    }

    static void space(LinearLayout parent, int height) {
        parent.addView(new View(parent.getContext()), new LinearLayout.LayoutParams(1, dp(parent.getContext(), height)));
    }
}
