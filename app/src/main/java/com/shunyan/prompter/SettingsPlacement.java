package com.shunyan.prompter;

import android.graphics.Rect;

record SettingsPlacement(Rect prompt, Rect settings) {
    static SettingsPlacement portrait(Rect prompt, int width, int height, int minimumPanelHeight,
                                      int minimumPreviewHeight, int margin) {
        int panelBottom = height - margin;
        int previewReserve = Math.min(minimumPreviewHeight, (panelBottom - margin) / 2);
        int desiredHeight = Math.max(minimumPanelHeight, Math.round(panelBottom * .57f));
        int panelHeight = Math.min(desiredHeight, panelBottom - previewReserve - margin);
        int panelTop = panelBottom - panelHeight;

        int previewBottom = panelTop - margin;
        int previewHeight = Math.min(prompt.height(), previewBottom);
        int previewTop = Math.min(prompt.top, previewBottom - previewHeight);
        Rect preview = new Rect(prompt.left, previewTop, prompt.right, previewTop + previewHeight);
        return new SettingsPlacement(preview, new Rect(margin, panelTop, width - margin, panelBottom));
    }

    static SettingsPlacement fit(Rect prompt, int width, int height, int preferredWidth, int preferredHeight,
                                 int minimumSideWidth, int minimumHeight, int gap) {
        int panelWidth = Math.min(preferredWidth, width);
        int panelHeight = Math.min(preferredHeight, height);
        int right = width - prompt.right - gap;
        int left = prompt.left - gap;
        int panelTop = Math.min(prompt.top, height - panelHeight);
        if (right >= minimumSideWidth && (right >= panelWidth || right >= left)) {
            int panelLeft = prompt.right + gap;
            return new SettingsPlacement(prompt, new Rect(panelLeft, panelTop,
                    panelLeft + Math.min(panelWidth, right), panelTop + panelHeight));
        }
        if (left >= minimumSideWidth) {
            int panelRight = prompt.left - gap;
            return new SettingsPlacement(prompt, new Rect(panelRight - Math.min(panelWidth, left), panelTop,
                    panelRight, panelTop + panelHeight));
        }

        int above = prompt.top - gap;
        int below = height - prompt.bottom - gap;
        boolean dockBelow = below >= above;
        if (Math.max(above, below) < minimumHeight) {
            // Share the height on crowded screens so the manuscript can still reach either side.
            int reserved = Math.min(minimumHeight, Math.max(0, (height - gap) / 2));
            int promptHeight = Math.min(prompt.height(), Math.max(1, height - reserved - gap));
            int top = dockBelow ? Math.min(prompt.top, Math.max(0, height - reserved - gap - promptHeight))
                    : Math.max(reserved + gap, Math.min(prompt.top, height - promptHeight));
            prompt = new Rect(prompt.left, top, prompt.right, top + promptHeight);
        }

        int panelLeft = Math.max(0, Math.min(prompt.left, width - panelWidth));
        if (dockBelow) {
            int top = Math.min(height - 1, prompt.bottom + gap);
            return new SettingsPlacement(prompt, new Rect(panelLeft, top, panelLeft + panelWidth,
                    Math.min(height, top + panelHeight)));
        }
        int bottom = Math.max(1, prompt.top - gap);
        return new SettingsPlacement(prompt, new Rect(panelLeft, Math.max(0, bottom - panelHeight),
                panelLeft + panelWidth, bottom));
    }

    static Rect toLocal(Rect bounds, int width, int height, int turns) {
        return switch (turns) {
            case 1 -> new Rect(bounds.top, width - bounds.right, bounds.bottom, width - bounds.left);
            case 2 -> new Rect(width - bounds.right, height - bounds.bottom, width - bounds.left, height - bounds.top);
            case 3 -> new Rect(height - bounds.bottom, bounds.left, height - bounds.top, bounds.right);
            default -> bounds;
        };
    }
}
