package com.shunyan.prompter;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

final class RotatingOverlay extends FrameLayout {
    private int turns;

    RotatingOverlay(Context context) {
        super(context);
    }

    void setTurns(int turns) {
        if (this.turns == turns) return;
        this.turns = turns;
        getChildAt(0).setRotation(90f * turns);
        requestLayout();
    }

    static boolean swapsAxes(int turns) {
        return (turns & 1) != 0;
    }

    static int stableSensorTurns(int orientation, int previousTurns) {
        if (orientation < 0) return previousTurns;
        if (previousTurns >= 0) {
            int distance = Math.abs(orientation - previousTurns * 90);
            if (Math.min(distance, 360 - distance) < 60) return previousTurns;
        }
        return ((orientation + 45) / 90) & 3;
    }

    static int autoRotation(int physicalTurns, int displayTurns) {
        // The display rotation already compensates for part or all of the device angle.
        return physicalTurns < 0 ? 0 : (-physicalTurns - displayTurns) & 3;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        boolean sideways = swapsAxes(turns);
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(sideways ? height : width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(sideways ? width : height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View child = getChildAt(0);
        int width = child.getMeasuredWidth();
        int height = child.getMeasuredHeight();
        int x = (right - left - width) / 2;
        int y = (bottom - top - height) / 2;
        child.layout(x, y, x + width, y + height);
    }
}
