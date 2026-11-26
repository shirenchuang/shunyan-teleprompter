package com.shunyan.prompter;

import android.text.Layout;

final class ScriptLayout {
    static int columnInset(int viewWidth, int minimumInset, float columns, float fullWidthAdvance) {
        int safeInset = Math.min(minimumInset, Math.max(0, (viewWidth - 1) / 2));
        if (columns == 0) return safeInset;
        int wanted = (int) Math.ceil(columns * fullWidthAdvance);
        return Math.max(safeInset, (viewWidth - wanted) / 2);
    }

    static float columnsForInset(int viewWidth, int minimumInset, int minimumColumns, int inset, float fullWidthAdvance) {
        int widestInset = columnInset(viewWidth, minimumInset, 0, fullWidthAdvance);
        int narrowestInset = columnInset(viewWidth, minimumInset, minimumColumns, fullWidthAdvance);
        if (inset <= widestInset || widestInset == narrowestInset) return 0;
        if (inset >= narrowestInset) return minimumColumns;
        return (viewWidth - 2 * inset) / fullWidthAdvance;
    }

    static int characterAt(Layout layout, CharSequence text, float x, float y) {
        if (y < 0 || y >= layout.getHeight()) return -1;
        int line = layout.getLineForVertical((int) y);
        int start = layout.getLineStart(line);
        int end = layout.getLineVisibleEnd(line);
        if (start >= end || x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return -1;
        int offset = Math.min(end, layout.getOffsetForHorizontal(line, x));
        if (offset > start) {
            // Android returns the nearest caret. The right half of a glyph can return its end.
            int previous = Character.offsetByCodePoints(text, offset, -1);
            float from = layout.getPrimaryHorizontal(previous);
            float to = layout.getPrimaryHorizontal(offset);
            if (offset == end || (x >= Math.min(from, to) && x <= Math.max(from, to))) offset = previous;
        }
        return offset;
    }

}
