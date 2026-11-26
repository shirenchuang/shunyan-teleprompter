package com.shunyan.prompter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

final class ScriptTextView extends TextView {
    private final int minOutlineWidth;
    private final int maxOutlineWidth;
    private ForegroundColorSpan baseStyle;
    private boolean outlined;
    private boolean drawingOutline;

    ScriptTextView(Context context, String body, int size, int color) {
        super(context);
        setScriptSize(size);
        setIncludeFontPadding(false);
        minOutlineWidth = Ui.dp(context, 1);
        maxOutlineWidth = Ui.dp(context, 2);
        setText(body, BufferType.SPANNABLE);
        setScriptColor(color);
    }

    ForegroundColorSpan colorSpan(int color) {
        int edge = Color.luminance(color) > .18f ? 0xff14111b : 0xfffcfaff;
        int outlineColor = (Color.alpha(color) << 24) | (edge & 0x00ffffff);
        return new ForegroundColorSpan(color) {
            @Override public void updateDrawState(TextPaint paint) {
                paint.setColor(drawingOutline ? outlineColor : color);
            }
        };
    }

    void setOutlined(boolean enabled) {
        if (outlined == enabled) return;
        outlined = enabled;
        invalidate();
    }

    void setScriptSize(int size) {
        setTextSize(size);
        setLineSpacing(Ui.dp(getContext(), Math.min(7, size * .27f)), 1.05f);
    }

    void setScriptColor(int color) {
        if (baseStyle != null && baseStyle.getForegroundColor() == color) return;
        setTextColor(color);
        if (!(getText() instanceof Spannable text) || text.length() == 0) return;
        if (baseStyle != null) text.removeSpan(baseStyle);
        baseStyle = colorSpan(color);
        // The read span overrides the base color on both passes.
        text.setSpan(baseStyle, 0, text.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE | (1 << Spannable.SPAN_PRIORITY_SHIFT));
    }

    @Override protected void onDraw(Canvas canvas) {
        if (outlined) {
            TextPaint paint = getPaint();
            drawingOutline = true;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(minOutlineWidth, Math.min(maxOutlineWidth, getTextSize() / 12f)));
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            drawingOutline = false;
        }
        super.onDraw(canvas);
    }
}
