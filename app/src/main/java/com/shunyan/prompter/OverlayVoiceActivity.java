package com.shunyan.prompter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

/** Gives Android a visible activity when a floating window first enables microphone access. */
public final class OverlayVoiceActivity extends Activity {
    private static final int MICROPHONE = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean requestingPermission;
    private boolean enabling;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestingPermission = state != null && state.getBoolean("requesting");
        TextView message = Ui.text(this, "启用 AI 跟读", 17, Ui.INK, true);
        message.setGravity(Gravity.CENTER);
        message.setPadding(Ui.dp(this, 30), Ui.dp(this, 25), Ui.dp(this, 30), Ui.dp(this, 25));
        message.setBackgroundColor(Ui.CARD);
        setContentView(message);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("requesting", requestingPermission);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) proceed();
    }

    private void proceed() {
        if (requestingPermission || enabling || isFinishing() || !hasWindowFocus()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestingPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
            return;
        }
        enabling = true;
        ResultReceiver receiver = new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int result, Bundle data) {
                if (isDestroyed() || isFinishing()) return;
                if (result == 1) finish();
                else finishWithMessage(data.getString("message"));
            }
        };
        Intent change = new Intent(this, OverlayService.class).setAction(OverlayService.ENABLE_VOICE)
                .putExtra("session", getIntent().getLongExtra("session", 0L)).putExtra("receiver", receiver);
        try {
            startForegroundService(change);
            handler.postDelayed(() -> finishWithMessage("切换 AI 跟读超时，请重试。"), 6500);
        } catch (IllegalStateException | SecurityException e) {
            finishWithMessage("系统未能启用 AI 跟读，请重试。");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != MICROPHONE) return;
        requestingPermission = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) proceed();
        else finishWithMessage("开启麦克风权限后即可使用 AI 跟读。");
    }

    private void finishWithMessage(String message) {
        if (isDestroyed() || isFinishing()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
