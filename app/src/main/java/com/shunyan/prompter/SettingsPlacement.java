package com.shunyan.prompter;

import android.graphics.Rect;

record SettingsPlacement(Rect prompt, Rect settings) {
    static SettingsPlacement portrait(Rect prompt, int width, int height, int minimumPanelHeight,
                                      int maximumPanelHeight, int minimumPreviewHeight, int margin) {
        int availableHeight = height - 2 * margin;
        int previewReserve = Math.min(minimumPreviewHeight, availableHeight / 2);
        int desiredHeight = Math.max(minimumPanelHeight, Math.min(maximumPanelHeight, Math.round(availableHeight * .46f)));
        int panelHeight = Math.min(desiredHeight, availableHeight - previewReserve);
        int previewHeight = Math.min(prompt.height(), availableHeight - panelHeight);
        int previewTop = Math.max(margin, Math.min(prompt.top, height - margin - panelHeight - previewHeight));
        int seam = previewTop + previewHeight;
        return new SettingsPlacement(new Rect(margin, previewTop, width - margin, seam),
                new Rect(margin, seam, width - margin, seam + panelHeight));
    }

    static SettingsPlacement landscape(Rect prompt, int width, int height, int preferredPanelWidth,
                                       int preferredPanelHeight, int margin) {
        int availableWidth = width - 2 * margin;
        int panelWidth = Math.min(preferredPanelWidth, availableWidth / 2);
        int previewWidth = Math.min(prompt.width(), availableWidth - panelWidth);
        int sharedHeight = Math.min(preferredPanelHeight, height - 2 * margin);
        int left = Math.max(margin, Math.min(prompt.left, width - margin - previewWidth - panelWidth));
        int top = Math.max(margin, Math.min(prompt.top, height - margin - sharedHeight));
        int seam = left + previewWidth;
        int bottom = top + sharedHeight;
        return new SettingsPlacement(new Rect(left, top, seam, bottom),
                new Rect(seam, top, seam + panelWidth, bottom));
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
