package com.shunyan.prompter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.InsetDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public final class OverlayService extends Service {
    static final String OPEN = "com.shunyan.prompter.OPEN_OVERLAY";
    static final String ENABLE_VOICE = "com.shunyan.prompter.ENABLE_OVERLAY_VOICE";
    private static final String CLOSE = "com.shunyan.prompter.CLOSE_OVERLAY";
    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL = "floating_prompt";
    private static final int AUTO = -1;
    private static final int PANEL_RADIUS = 26;
    private static final int MIN_COLUMNS = 4;
    private static final int AUTO_COLUMNS = 33;
    private static final int WIDTH_GRIP_SIZE = 26;
    private static final int SCRIPT_GUTTER = WIDTH_GRIP_SIZE + 1;
    private static final int READING_MARGIN = 12;
    private static final int READING_GRIP_HEIGHT = 36;
    private static final int PROGRESS_HEIGHT = 18;
    private static final int FOOTER_HEIGHT = 36;
    private static final int SETTINGS_WIDTH = 302;
    private static final int SETTINGS_HEIGHT = 306;
    private static final int SETTINGS_TOUCH_HEIGHT = 48;
    private static final int SETTINGS_SURFACE = 0xff20212b;
    private static final int SETTINGS_INK = 0xfff4f2fb;
    private static final int SETTINGS_MUTED = 0xffb7b6c8;
    private static final int SETTINGS_ACCENT = 0xffd7ccff;
    private static final int SETTINGS_CONTROL = 0xff2a2b37;
    private static final int SETTINGS_SELECTED = 0xff3c3453;
    private static final int SETTINGS_EDGE = 0xff3b3c49;

    private enum ColorTarget {
        BACKGROUND("背景颜色", "backgroundColor"),
        TEXT("文字颜色", "textColor"),
        READ("已读颜色", "readColor");

        final String label;
        final String preference;

        ColorTarget(String label, String preference) {
            this.label = label;
            this.preference = preference;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService speechWorker = Executors.newSingleThreadExecutor();
    private final Runnable pulse = this::tick;
    private final Runnable refreshOrientation = this::refreshWindowOrientation;
    private final Runnable saveAfterScroll = () -> {
        manualScrolling = false;
        savePosition();
    };

    private Context ui;
    private DisplayManager displays;
    private Display display;
    private OrientationEventListener orientationSensor;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) scheduleOrientationRefresh();
        }
    };
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;
    private DraftStore store;
    private DraftStore.Draft draft;
    private RotatingOverlay windowRoot;
    private LinearLayout surface;
    private RotatingOverlay settingsRoot;
    private WindowManager.LayoutParams settingsParams;
    private FrameLayout settingsPane;
    private LinearLayout titleBar;
    private FrameLayout content;
    private LinearLayout footer;
    private TextView draftButton;
    private SeekBar positionBar;
    private SeekBar readingLineSlider;
    private ScrollView scriptScroll;
    private ScriptTextView scriptText;
    private View scriptBackground;
    private TextView readingGrip;
    private View leftWidthGrip;
    private View rightWidthGrip;
    private TextView playButton;
    private TextView settingsButton;
    private TextView status;
    private TextView playbackNotice;
    private ForegroundColorSpan readStyle;
    private final BackgroundColorSpan seekStyle = new BackgroundColorSpan(0x4da57cf1);
    private int[] offsets;
    private FollowMatcher matcher;
    private SpeechEngine.Capture capture;
    private boolean voice, playing, collapsed, settingsOpen, loadingSpeech, adjustingScroll;
    private boolean endReached, manualScrolling, inputSilenced, showSeek, windowGestureActive, scriptGestureActive;
    private boolean alignAfterScriptLayout, playbackSettings, scrubbing;
    private boolean bubbleOnLeft;
    private int cursor, voiceGeneration, font, opacity, speed, delay, readingLine, preferredWidth, preferredHeight;
    private int expandedX, expandedY;
    private int settingsPromptX, settingsPromptY;
    private int backgroundColor, textColor, readColor;
    private float columns;
    private boolean renderedPlayback;
    private int orientationMode = AUTO;
    private int deviceTurns = AUTO;
    private int windowTurns;
    private long countdownUntil, lastFrame, lastSaved, overlaySession;
    private double playhead;
    private boolean loop, mirror, pinyin;

    @Override public void onCreate() {
        super.onCreate();
        displays = getSystemService(DisplayManager.class);
        display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        Context displayContext = createDisplayContext(display);
        Context windowContext = Build.VERSION.SDK_INT >= 30
                ? displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                : displayContext;
        ui = new ContextThemeWrapper(windowContext, R.style.OverlayTheme);
        windows = ui.getSystemService(WindowManager.class);
        prefs = getSharedPreferences("prompter", MODE_PRIVATE);
        store = new DraftStore(this);
        orientationSensor = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int angle) {
                int turns = RotatingOverlay.stableSensorTurns(angle, deviceTurns);
                if (turns != deviceTurns) {
                    deviceTurns = turns;
                    if (orientationMode == AUTO) scheduleOrientationRefresh();
                }
            }
        };
        if (orientationSensor.canDetectOrientation()) orientationSensor.enable();
        displays.registerDisplayListener(displayListener, handler);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "悬浮提词", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || CLOSE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ResultReceiver receiver = intent.getParcelableExtra("receiver");
        if (ENABLE_VOICE.equals(intent.getAction())) {
            if (surface == null || intent.getLongExtra("session", 0L) != overlaySession) {
                report(receiver, false, "悬浮窗已关闭，请重新开启。");
                if (surface == null) stopSelf();
            } else {
                boolean changed = changeMode(true);
                report(receiver, changed, changed ? "" : "系统未能启用 AI 跟读，请重试。");
            }
            return START_NOT_STICKY;
        }
        DraftStore.Draft nextDraft = store.find(intent.getStringExtra("draft"));
        if (nextDraft == null || !Settings.canDrawOverlays(this)) {
            report(receiver, false, "需要有台本并允许系统悬浮窗。");
            if (surface == null) stopSelf();
            return START_NOT_STICKY;
        }
        boolean nextVoice = intent.getBooleanExtra("voice", false);
        try {
            enterForeground(nextVoice);
        } catch (RuntimeException denied) {
            report(receiver, false, "系统限制启动。请返回顺言，从台本页重新开启。");
            if (surface == null) stopSelf();
            return START_NOT_STICKY;
        }

        pause();
        removeWindow();
        voice = nextVoice;
        readPreferences();
        loadDraft(nextDraft);
        collapsed = settingsOpen = windowGestureActive = scriptGestureActive = false;
        try {
            createWindow();
            if (intent.getBooleanExtra("preview", false)) {
                openSettings();
                refreshSettings();
            }
            overlaySession = SystemClock.elapsedRealtimeNanos();
            report(receiver, true, "");
        } catch (RuntimeException e) {
            android.util.Log.w("ShunyanOverlay", "Could not attach overlay", e);
            report(receiver, false, "悬浮窗无法显示，请检查悬浮权限。");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void selectMode(boolean nextVoice) {
        if (voice == nextVoice) return;
        if (nextVoice) {
            try {
                startActivity(new Intent(this, OverlayVoiceActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("session", overlaySession));
            } catch (RuntimeException e) {
                Toast.makeText(this, "系统未能打开麦克风授权，请重试。", Toast.LENGTH_SHORT).show();
            }
        } else if (!changeMode(false)) {
            Toast.makeText(this, "切换匀速失败，请重试。", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean changeMode(boolean nextVoice) {
        if (voice == nextVoice) return true;
        try {
            enterForeground(nextVoice);
        } catch (RuntimeException e) {
            return false;
        }
        pause();
        voice = nextVoice;
        prefs.edit().putBoolean("voice", voice).apply();
        savePosition();
        if (!collapsed) {
            updateTitle();
            if (settingsOpen) refreshSettings();
            else repaintWhenLaidOut();
            updateStatus();
        }
        return true;
    }

    private void loadDraft(DraftStore.Draft nextDraft) {
        draft = nextDraft;
        cursor = Math.min(draft.body().length(), Math.max(0, store.position(draft.id(), voice)));
        offsets = FollowMatcher.readingOffsets(draft.body());
        setPlayhead(cursor);
        endReached = false;
        showSeek = false;
    }

    private void enterForeground(boolean nextVoice) {
        PendingIntent openApp = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent close = PendingIntent.getService(this, 2, new Intent(this, OverlayService.class)
                .setAction(CLOSE), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("顺言悬浮窗正在运行")
                .setContentText(nextVoice ? "离线跟读 · 播放时使用麦克风" : "匀速提词")
                .setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "关闭悬浮窗", close).build()).build();
        int type = nextVoice && Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
        if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        startForeground(NOTIFICATION_ID, notification, type);
    }

    private void readPreferences() {
        readAppearance();
        speed = prefs.getInt("speed", 180);
        delay = prefs.getInt("delay", 3);
        loop = prefs.getBoolean("loop", false);
        mirror = prefs.getBoolean("mirror", false);
        pinyin = prefs.getBoolean("pinyin", true);
        columns = prefs.getFloat("columnWidth", prefs.getInt("columns", 0));
        orientationMode = clamp(prefs.getInt("orientationMode", AUTO), AUTO, 3);
        readingLine = clamp(prefs.getInt("readingLine", 0), 0, 100);
    }

    private void readAppearance() {
        PromptAppearance appearance = PromptAppearance.load(prefs);
        font = appearance.font();
        opacity = appearance.opacity();
        backgroundColor = appearance.background();
        textColor = appearance.text();
        readColor = appearance.read();
    }

    private void createWindow() {
        Point size = screen();
        params = overlayParams();
        preferredWidth = prefs.getInt("width", Math.min(size.x - dp(20), dp(460)));
        preferredHeight = prefs.getInt("height", Math.min(size.y - dp(16), dp(312)));
        params.width = preferredWidth;
        params.height = preferredHeight;
        params.x = prefs.getInt("x", (size.x - params.width) / 2);
        params.y = prefs.getInt("y", dp(24));
        windowTurns = currentTurns();
        sizeExpandedWindow(false);
        constrain();
        windowRoot = new RotatingOverlay(ui);
        surface = Ui.column(ui);
        surface.setPadding(0, 0, 0, dp(3));
        surface.setOnClickListener(view -> {
            if (playbackActive()) pause();
        });
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        windowRoot.addView(surface);
        windowRoot.setTurns(windowTurns);
        bindExpanded();
        windows.addView(windowRoot, params);
        repaintWhenLaidOut();
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams attributes = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        attributes.gravity = Gravity.TOP | Gravity.LEFT;
        return attributes;
    }

    private void bindExpanded() {
        surface.removeAllViews();
        int inset = dp(5);
        titleBar = Ui.row(ui);
        titleBar.setMotionEventSplittingEnabled(false);
        titleBar.setPadding(dp(2), 0, dp(2), 0);
        TextView move = Ui.text(ui, "⠿", 17, panelInk(), true);
        move.setGravity(Gravity.CENTER);
        move.setContentDescription("拖动悬浮窗");
        titleBar.addView(move, new LinearLayout.LayoutParams(dp(33), -1));
        draftButton = Ui.action(ui, "", Color.TRANSPARENT, panelInk(), () -> {
            openSettings();
            showDraftChooser(false);
        });
        draftButton.setSingleLine(true);
        draftButton.setEllipsize(TextUtils.TruncateAt.END);
        draftButton.setTextSize(12);
        draftButton.setGravity(Gravity.CENTER_VERTICAL);
        draftButton.setPadding(dp(2), 0, dp(4), 0);
        titleBar.addView(draftButton, new LinearLayout.LayoutParams(0, -1, 1));
        compactButton(titleBar, "↻", "手动旋转九十度", () -> selectOrientation((windowTurns + 1) & 3));
        settingsButton = compactButton(titleBar, "⚙", "悬浮设置", this::toggleSettings);
        compactButton(titleBar, "−", "收起悬浮窗", this::collapse);
        compactButton(titleBar, "×", "关闭悬浮窗", this::stopSelf);
        WindowGesture titleDrag = new WindowGesture(false, false);
        titleBar.setOnTouchListener(titleDrag);
        for (int i = 0; i < titleBar.getChildCount(); i++) {
            titleBar.getChildAt(i).setOnTouchListener(titleDrag);
        }
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, dp(36));
        titleParams.setMargins(inset, dp(3), inset, 0);
        FrameLayout header = new FrameLayout(ui);
        header.addView(titleBar, new FrameLayout.LayoutParams(-1, -1));
        playbackNotice = Ui.text(ui, "", 12, Color.WHITE, true);
        playbackNotice.setPadding(dp(9), dp(5), dp(9), dp(5));
        playbackNotice.setBackground(Ui.shape(ui, 0xe6252131, 11, Color.TRANSPARENT));
        playbackNotice.setVisibility(View.GONE);
        header.addView(playbackNotice, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        surface.addView(header, titleParams);
        updateTitle();

        content = new FrameLayout(ui);
        content.setMotionEventSplittingEnabled(false);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(-1, 0, 1);
        contentParams.setMargins(inset, 0, inset, 0);
        surface.addView(content, contentParams);
        bindScript();

        positionBar = new SeekBar(ui);
        positionBar.setContentDescription("台本进度");
        positionBar.setMax(offsets.length);
        positionBar.setPadding(dp(18), 0, dp(18), 0);
        positionBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
                scriptGestureActive = true;
                pause();
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                scrubbing = false;
                scriptGestureActive = false;
                seekProgress(bar.getProgress());
                if (currentTurns() != windowTurns) scheduleOrientationRefresh();
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !scrubbing) {
                    pause();
                    seekProgress(progress);
                }
            }
        });
        surface.addView(positionBar, new LinearLayout.LayoutParams(-1, dp(PROGRESS_HEIGHT)));

        footer = Ui.row(ui);
        status = Ui.text(ui, "", 11, panelInk(), false);
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout statusSlot = new FrameLayout(ui);
        statusSlot.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        footer.addView(statusSlot, new LinearLayout.LayoutParams(0, -1, 1));
        compactButton(footer, "⏮", "上一段", () -> skipParagraph(false));
        playButton = Ui.action(ui, "▶ 播放", Color.TRANSPARENT, Color.WHITE, this::togglePlay);
        playButton.setContentDescription("播放");
        playButton.setTextSize(12);
        playButton.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams playPlacement = new LinearLayout.LayoutParams(dp(68), dp(29));
        playPlacement.setMargins(dp(3), 0, dp(3), 0);
        footer.addView(playButton, playPlacement);
        compactButton(footer, "⏭", "下一段", () -> skipParagraph(true));
        TextView resizeButton = compactButton(footer, "◢", "拖动调整窗口大小", () -> {});
        resizeButton.setOnTouchListener(new WindowGesture(true, false));
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, dp(FOOTER_HEIGHT));
        footerParams.setMargins(inset, 0, inset, 0);
        surface.addView(footer, footerParams);
        applyBackground();
        updateStatus();
    }

    private void updateTitle() {
        draftButton.setText(draft.label() + "  ⌄");
        draftButton.setContentDescription("切换台本，" + draft.label());
    }

    private void seekProgress(int progress) {
        seekTo(progress == 0 ? 0 : offsets[progress - 1], false);
    }

    private TextView compactButton(LinearLayout parent, String text, String description, Runnable action) {
        TextView button = Ui.action(ui, text, Color.TRANSPARENT, panelInk(), action);
        button.setContentDescription(description);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setMinWidth(dp(32));
        button.setMinHeight(dp(36));
        parent.addView(button, new LinearLayout.LayoutParams(-2, dp(36)));
        return button;
    }

    private void bindScript() {
        content.removeAllViews();
        alignAfterScriptLayout = false;
        scriptScroll = new ScrollView(ui) {
            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (this == scriptScroll && alignAfterScriptLayout) {
                    alignAfterScriptLayout = false;
                    scrollToReadingOffset();
                }
            }
        };
        scriptScroll.setVerticalScrollBarEnabled(false);
        scriptScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scriptText = new ScriptTextView(ui, draft.body(), font, textColor);
        scriptText.setPadding(dp(SCRIPT_GUTTER), dp(READING_MARGIN), dp(SCRIPT_GUTTER), 0);
        scriptText.setScaleX(mirror ? -1 : 1);
        readStyle = scriptText.colorSpan(resolvedReadColor());
        scriptScroll.addView(scriptText, new ScrollView.LayoutParams(-1, -2));
        scriptBackground = new View(ui);
        content.addView(scriptBackground, new FrameLayout.LayoutParams(-1, -1));
        content.addView(scriptScroll, new FrameLayout.LayoutParams(-1, -1));
        leftWidthGrip = addWidthGrip(true);
        rightWidthGrip = addWidthGrip(false);
        readingGrip = Ui.text(ui, "↕", 16, panelAccent(), true);
        readingGrip.setGravity(Gravity.CENTER);
        readingGrip.setContentDescription("阅读线，向上或向下拖动");
        readingGrip.setOnTouchListener(new ReadingLineGesture());
        content.addView(readingGrip, new FrameLayout.LayoutParams(dp(WIDTH_GRIP_SIZE), dp(READING_GRIP_HEIGHT)));

        scriptScroll.setOnClickListener(view -> {
            if (playbackActive()) pause();
        });
        scriptScroll.setOnTouchListener(new ScriptGesture());
        scriptScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            Layout layout = scriptText.getLayout();
            if (!manualScrolling || adjustingScroll || alignAfterScriptLayout || layout == null
                    || playing || settingsOpen || collapsed) return;
            int line = layout.getLineForVertical(y);
            cursor = layout.getLineStart(line);
            setPlayhead(cursor);
            endReached = false;
            showSeek = false;
            paintRead();
            handler.removeCallbacks(saveAfterScroll);
            handler.postDelayed(saveAfterScroll, 350);
        });
        scriptScroll.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (view != scriptScroll || (b - t == ob - ot && r - l == or - ol)) return;
            layoutScript(r - l, b - t);
        });
    }

    private void layoutScript(int width, int height) {
        if (width <= 0 || height <= 0) return;
        int side = ScriptLayout.columnInset(width, dp(SCRIPT_GUTTER), columns, scriptText.getPaint().measureText("字"));
        int lineHeight = scriptText.getLineHeight();
        int inset = ScriptLayout.readingInset(readingHeight(height), lineHeight, dp(READING_MARGIN), readingLine);
        int bottom = Math.max(dp(8), height - inset - lineHeight);
        if (scriptText.getPaddingLeft() != side || scriptText.getPaddingTop() != inset
                || scriptText.getPaddingBottom() != bottom) {
            alignAfterScriptLayout = true;
            scriptText.setPadding(side, inset, side, bottom);
        }
        int gripMargin = Math.max(0, side - dp(WIDTH_GRIP_SIZE));
        setSideMargins(leftWidthGrip, gripMargin);
        setSideMargins(rightWidthGrip, gripMargin);
        setSideMargins(scriptBackground, Math.max(0, side - dp(10)));
        positionReadingGrip(gripMargin, height, inset + lineHeight / 2);
    }

    private int readingHeight(int height) {
        // Keep the reading line steady when playback hides the progress bar and footer.
        return renderedPlayback || settingsOpen ? Math.max(1, height - dp(PROGRESS_HEIGHT) - dp(FOOTER_HEIGHT)) : height;
    }

    private void positionReadingGrip(int side, int height, int center) {
        FrameLayout.LayoutParams placement = (FrameLayout.LayoutParams) readingGrip.getLayoutParams();
        placement.gravity = Gravity.TOP | (mirror ? Gravity.RIGHT : Gravity.LEFT);
        placement.leftMargin = mirror ? 0 : side;
        placement.rightMargin = mirror ? side : 0;
        int top = clamp(center - placement.height / 2, 0, Math.max(0, height - placement.height));
        placement.topMargin = top;
        readingGrip.setLayoutParams(placement);

        View neighbor = mirror ? rightWidthGrip : leftWidthGrip;
        int gripHeight = neighbor.getLayoutParams().height;
        int middle = (height - gripHeight) / 2;
        int bottom = top + placement.height;
        int gripTop = middle;
        if (middle < bottom && middle + gripHeight > top) {
            gripTop = height - bottom >= top ? Math.min(bottom, height - gripHeight) : Math.max(0, top - gripHeight);
        }
        neighbor.setTranslationY(gripTop - middle);
        (mirror ? leftWidthGrip : rightWidthGrip).setTranslationY(0);
    }

    private void setReadingLine(int percent) {
        if (readingLine == percent) return;
        readingLine = percent;
        layoutScript(scriptScroll.getWidth(), scriptScroll.getHeight());
        if (readingLineSlider != null) readingLineSlider.setProgress(percent);
    }

    private void saveReadingLine() {
        prefs.edit().putInt("readingLine", readingLine).apply();
    }

    private final class ReadingLineGesture implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
        private float startY;
        private int initialLine, initialInset;
        private boolean dragging;

        @Override public boolean onTouch(View view, MotionEvent event) {
            float y = view.getTop() + event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = y;
                    initialLine = readingLine;
                    initialInset = scriptText.getPaddingTop();
                    dragging = false;
                    if (manualScrolling) {
                        manualScrolling = false;
                        savePosition();
                    }
                    scriptGestureActive = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    move(y);
                    break;
                case MotionEvent.ACTION_UP:
                    move(y);
                    if (initialLine != readingLine) saveReadingLine();
                    finish();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    setReadingLine(initialLine);
                    finish();
                    break;
            }
            return true;
        }

        private void move(float y) {
            float delta = y - startY;
            if (!dragging && Math.abs(delta) <= slop) return;
            dragging = true;
            int height = readingHeight(scriptScroll.getHeight());
            int lineHeight = scriptText.getLineHeight();
            int margin = dp(READING_MARGIN);
            if (height - lineHeight <= 2 * margin) return;
            int percent = ScriptLayout.readingPercent(height, lineHeight, margin, initialInset + delta);
            setReadingLine(Math.abs(percent - 50) <= 2 ? 50 : percent);
        }

        private void finish() {
            scriptGestureActive = false;
            if (currentTurns() != windowTurns) scheduleOrientationRefresh();
        }
    }

    private View addWidthGrip(boolean left) {
        View grip = new View(ui);
        grip.setContentDescription(left ? "左侧稿件宽度把手" : "右侧稿件宽度把手");
        grip.setOnTouchListener(new ColumnWidthGesture(left));
        int gravity = Gravity.CENTER_VERTICAL | (left ? Gravity.LEFT : Gravity.RIGHT);
        content.addView(grip, new FrameLayout.LayoutParams(dp(WIDTH_GRIP_SIZE), dp(52), gravity));
        return grip;
    }

    private void setSideMargins(View view, int margin) {
        FrameLayout.LayoutParams bounds = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (bounds.leftMargin == margin && bounds.rightMargin == margin) return;
        bounds.leftMargin = bounds.rightMargin = margin;
        view.setLayoutParams(bounds);
    }

    private final class ColumnWidthGesture implements View.OnTouchListener {
        private final boolean left;
        private final int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
        private int startInset;
        private float startX, startColumns;
        private boolean dragging;

        ColumnWidthGesture(boolean left) {
            this.left = left;
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            // Include the moving handle's position to keep the pointer in the content's coordinates.
            float x = view.getLeft() + event.getX();
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    startX = x;
                    startInset = scriptText.getPaddingLeft();
                    startColumns = columns;
                    dragging = false;
                    if (manualScrolling) {
                        manualScrolling = false;
                        savePosition();
                    }
                    scriptGestureActive = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    adjustWidth(x);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (action == MotionEvent.ACTION_UP) adjustWidth(x);
                    if (columns != startColumns) saveColumns();
                    scriptGestureActive = false;
                    if (currentTurns() != windowTurns) scheduleOrientationRefresh();
                    break;
            }
            return true;
        }

        private void adjustWidth(float x) {
            float delta = x - startX;
            if (!dragging && Math.abs(delta) <= slop) return;
            dragging = true;
            int inset = startInset + Math.round(left ? delta : -delta);
            int width = scriptScroll.getWidth();
            float advance = scriptText.getPaint().measureText("字");
            float next = ScriptLayout.columnsForInset(width, dp(SCRIPT_GUTTER), MIN_COLUMNS, inset, advance);
            if (columns == next) return;
            columns = next;
            layoutScript(width, scriptScroll.getHeight());
        }
    }

    private void saveColumns() {
        prefs.edit().putFloat("columnWidth", columns).apply();
    }

    private final class ScriptGesture implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
        private float downX, downY;
        private boolean moved, playbackTap;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (settingsOpen) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    playbackTap = playbackActive();
                    handler.removeCallbacks(saveAfterScroll);
                    downX = event.getX();
                    downY = event.getY();
                    moved = manualScrolling = false;
                    scriptGestureActive = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) {
                        moved = true;
                        manualScrolling = !playbackTap;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!moved && playbackTap) {
                        view.performClick();
                    } else if (!moved) {
                        int hit = findTappedCharacter(event.getX(), event.getY());
                        if (hit >= 0) {
                            seekTo(hit, true);
                            view.performClick();
                        }
                    }
                    finish();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    finish();
                    break;
            }
            return playbackTap;
        }

        private void finish() {
            scriptGestureActive = false;
            if (manualScrolling) {
                handler.removeCallbacks(saveAfterScroll);
                handler.postDelayed(saveAfterScroll, 350);
            }
            if (currentTurns() != windowTurns) scheduleOrientationRefresh();
        }
    }

    private int findTappedCharacter(float x, float y) {
        Layout layout = scriptText.getLayout();
        if (layout == null) return -1;
        float localX = x + scriptScroll.getScrollX() - scriptText.getLeft();
        if (mirror) localX = scriptText.getWidth() - localX;
        float localY = y + scriptScroll.getScrollY() - scriptText.getTop();
        return ScriptLayout.characterAt(layout, draft.body(), localX - scriptText.getTotalPaddingLeft(),
                localY - scriptText.getTotalPaddingTop());
    }

    private void toggleSettings() {
        if (!settingsOpen) {
            openSettings();
            refreshSettings();
            return;
        }
        closeSettingsBounds();
        settingsButton.setText("⚙");
        settingsButton.setContentDescription("悬浮设置");
        applyBackground();
        repaintWhenLaidOut();
    }

    private void openSettings() {
        if (settingsOpen) return;
        settingsPromptX = params.x;
        settingsPromptY = params.y;
        RotatingOverlay panel = new RotatingOverlay(ui);
        settingsPane = new FrameLayout(ui);
        panel.addView(settingsPane);
        settingsParams = overlayParams();
        settingsOpen = true;
        playbackSettings = false;
        settingsButton.setText("↩");
        settingsButton.setContentDescription("返回提词");
        pause();
        applyBackground();
        placeSettingsWindow();
        panel.setTurns(windowTurns);
        windows.updateViewLayout(windowRoot, params);
        windows.addView(panel, settingsParams);
        settingsRoot = panel;
    }

    private void closeSettingsBounds() {
        if (!settingsOpen) return;
        settingsOpen = false;
        readingLineSlider = null;
        removeSettingsWindow();
        restoreSettingsPrompt();
        updateWindow();
    }

    private void refreshSettings() {
        refreshSettings(0);
    }

    private void refreshSettings(int scrollY) {
        readingLineSlider = null;
        settingsPane.removeAllViews();
        LinearLayout page = Ui.column(ui);
        page.setPadding(dp(8), dp(7), dp(8), dp(6));
        LinearLayout header = Ui.row(ui);
        LinearLayout tabs = Ui.row(ui);
        tabs.setBackground(Ui.shape(ui, SETTINGS_CONTROL, 16, Color.TRANSPARENT));
        addSettingsTab(tabs, "显示设置", false);
        addSettingsTab(tabs, "播放跟读", true);
        header.addView(tabs, new LinearLayout.LayoutParams(0, dp(SETTINGS_TOUCH_HEIGHT), 1));
        TextView done = Ui.pillAction(ui, "完成", Color.TRANSPARENT, SETTINGS_ACCENT, this::toggleSettings);
        done.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams donePlacement = new LinearLayout.LayoutParams(dp(60), dp(SETTINGS_TOUCH_HEIGHT));
        donePlacement.leftMargin = dp(4);
        header.addView(done, donePlacement);
        page.addView(header);

        ScrollView scroll = new ScrollView(ui);
        LinearLayout controls = Ui.column(ui);
        controls.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (playbackSettings) addPlaybackSettings(controls);
        else addDisplaySettings(controls, scroll);
        scroll.addView(controls);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        settingsPane.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (scrollY > 0) scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void addSettingsTab(LinearLayout tabs, String label, boolean playback) {
        boolean selected = playbackSettings == playback;
        Runnable select = () -> {
            if (playbackSettings == playback) return;
            playbackSettings = playback;
            refreshSettings();
        };
        TextView tab = Ui.pillAction(ui, label, selected ? SETTINGS_SELECTED : Color.TRANSPARENT,
                selected ? SETTINGS_INK : SETTINGS_MUTED, select);
        tab.setPadding(dp(4), 0, dp(4), 0);
        tab.setSelected(selected);
        tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(SETTINGS_TOUCH_HEIGHT), 1));
    }

    private void addDisplaySettings(LinearLayout controls, ScrollView scroll) {
        addSlider(controls, "文字字号", PromptAppearance.MIN_FONT, PromptAppearance.MAX_FONT,
                font, value -> value + " sp", value -> {
                    font = value;
                    prefs.edit().putInt("font", value).apply();
                    refreshScriptAppearance();
                });
        addSlider(controls, "背景不透明度", 0, 100, opacity, value -> value + "%", value -> {
            opacity = value;
            prefs.edit().putInt("opacity", value).apply();
            applyScriptBackground();
        });
        readingLineSlider = addSlider(controls, "阅读线位置", 0, 100, readingLine, value -> switch (value) {
            case 0 -> "顶部";
            case 50 -> "居中";
            case 100 -> "底部";
            default -> value + "%";
        }, value -> {
            setReadingLine(value);
            saveReadingLine();
        });
        int autoColumns = Math.max(AUTO_COLUMNS, Math.round(columns) + 1);
        addSlider(controls, "每行字数", MIN_COLUMNS, autoColumns, columns == 0 ? autoColumns : Math.round(columns),
                value -> value == autoColumns ? "自动" : value + " 字", value -> {
                    columns = value == autoColumns ? 0 : value;
                    saveColumns();
                    layoutScript(scriptScroll.getWidth(), scriptScroll.getHeight());
                });
        controls.addView(Ui.text(ui, "旋转方向", 12, panelInk(), true));
        addOrientationControls(controls, () -> refreshSettings(scroll.getScrollY()));
        addColorRow(controls, ColorTarget.BACKGROUND, scroll,
                new int[] {Color.BLACK, PromptAppearance.LAVENDER_GLASS, 0xffd9c7f9, 0xffc5f3fa, Ui.NIGHT},
                new String[] {"纯黑", "紫青渐变", "淡紫", "浅青", "深紫"});
        addColorRow(controls, ColorTarget.TEXT, scroll,
                new int[] {Ui.INK, Color.WHITE, 0xff6a49bb, 0xff124e67},
                new String[] {"深紫文字", "白色文字", "紫色文字", "深青文字"});
        addColorRow(controls, ColorTarget.READ, scroll,
                new int[] {0, 0xff9b76f2, 0xff326c83, 0xffb65a43},
                new String[] {"自动淡化", "紫色已读", "青色已读", "暖橙已读"});
        Ui.space(controls, 8);
        TextView reset = Ui.pillAction(ui, "恢复黑底白字", panelShade(), panelAccent(), () -> {
            PromptAppearance.reset(prefs);
            readAppearance();
            applyBackground();
            refreshScriptAppearance();
            refreshSettings(scroll.getScrollY());
        });
        controls.addView(reset, new LinearLayout.LayoutParams(-1, dp(SETTINGS_TOUCH_HEIGHT)));
    }

    private void addColorRow(LinearLayout controls, ColorTarget target, ScrollView scroll, int[] colors, String[] names) {
        LinearLayout row = Ui.row(ui);
        TextView label = Ui.text(ui, target.label, 12, panelInk(), true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        int selected = target == ColorTarget.READ ? readColor : pickerColor(target);
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            int fill = color == 0 ? panelSelection() : color;
            if (target == ColorTarget.BACKGROUND && color == PromptAppearance.LAVENDER_GLASS) fill = Ui.LAVENDER;
            int ink = Color.luminance(fill) < .4f ? Color.WHITE : Ui.INK;
            TextView swatch = Ui.pillAction(ui, color == selected ? "✓" : "", fill, ink, () -> {
                setColor(target, color);
                refreshSettings(scroll.getScrollY());
            });
            Ui.setPill(swatch, fill, color == selected ? panelAccent() : panelShade());
            swatch.setContentDescription(names[i]);
            swatch.setSelected(color == selected);
            swatch.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(dp(26), dp(29));
            cell.leftMargin = dp(4);
            row.addView(swatch, cell);
        }
        TextView custom = Ui.pillAction(ui, "自定义", Color.TRANSPARENT, panelAccent(), () -> showColorPicker(target));
        custom.setPadding(dp(6), 0, dp(2), 0);
        custom.setTextSize(11);
        custom.setContentDescription("自定义" + target.label);
        row.addView(custom, new LinearLayout.LayoutParams(-2, dp(34)));
        controls.addView(row);
    }

    private void addPlaybackSettings(LinearLayout controls) {
        LinearLayout modes = Ui.row(ui);
        addModeButton(modes, "匀速提词", false);
        addModeButton(modes, "离线跟读", true);
        controls.addView(modes);
        if (!voice) addSlider(controls, "字/分钟", 60, 600, speed, value -> {
            speed = value;
            prefs.edit().putInt("speed", value).apply();
        });
        addSlider(controls, "开始倒计时", 0, 9, delay, value -> value == 0 ? "无" : value + " 秒", value -> {
            delay = value;
            prefs.edit().putInt("delay", value).apply();
        });
        addToggle(controls, "循环播放", "loop", loop, enabled -> loop = enabled);
        addToggle(controls, "镜像显示", "mirror", mirror, enabled -> mirror = enabled);
        if (voice) addToggle(controls, "同音容错", "pinyin", pinyin, enabled -> pinyin = enabled);

        LinearLayout navigation = Ui.row(ui);
        navigation.addView(Ui.pillAction(ui, "回到开头", panelShade(), panelInk(), () -> {
            toggleSettings();
            resetTo(0);
        }), new LinearLayout.LayoutParams(0, dp(38), 1));
        navigation.addView(Ui.pillAction(ui, "切换台本", panelShade(), panelInk(), () -> showDraftChooser(true)),
                new LinearLayout.LayoutParams(0, dp(38), 1));
        controls.addView(navigation);
    }

    private void addToggle(LinearLayout controls, String label, String key, boolean checked, Consumer<Boolean> setter) {
        Switch toggle = new Switch(ui);
        toggle.setText(label);
        toggle.setTextColor(panelInk());
        toggle.setTextSize(13);
        toggle.setChecked(checked);
        toggle.setPadding(dp(2), 0, dp(3), 0);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            setter.accept(enabled);
            prefs.edit().putBoolean(key, enabled).apply();
        });
        controls.addView(toggle, new LinearLayout.LayoutParams(-1, dp(38)));
    }

    private void addModeButton(LinearLayout row, String label, boolean mode) {
        boolean selected = voice == mode;
        TextView button = Ui.pillAction(ui, label, selected ? panelSelection() : Color.TRANSPARENT,
                selected ? panelAccent() : panelInk(), () -> selectMode(mode));
        button.setSelected(selected);
        row.addView(button, new LinearLayout.LayoutParams(0, dp(40), 1));
    }

    private void addOrientationControls(LinearLayout controls, Runnable redraw) {
        LinearLayout row = Ui.row(ui);
        for (int mode = AUTO; mode < 4; mode++) {
            int choice = mode;
            TextView button = Ui.pillAction(ui, mode == AUTO ? "自动" : (mode * 90) + "°",
                    mode == orientationMode ? panelSelection() : Color.TRANSPARENT,
                    mode == orientationMode ? panelAccent() : panelInk(), () -> {
                        selectOrientation(choice);
                        redraw.run();
                    });
            button.setContentDescription(mode == AUTO ? "自动旋转" : "手动旋转 " + (mode * 90) + " 度");
            button.setSelected(mode == orientationMode);
            button.setTextSize(12);
            button.setPadding(dp(3), dp(5), dp(3), dp(5));
            row.addView(button, new LinearLayout.LayoutParams(0, dp(35), 1));
        }
        controls.addView(row);
    }

    private void showColorPicker(ColorTarget target) {
        readingLineSlider = null;
        settingsPane.removeAllViews();
        ScrollView scroll = new ScrollView(ui);
        LinearLayout picker = Ui.column(ui);
        picker.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout heading = Ui.row(ui);
        TextView headingLabel = Ui.text(ui, target.label, 14, panelInk(), true);
        heading.addView(headingLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView back = Ui.action(ui, "返回设置", Color.TRANSPARENT, panelAccent(), this::refreshSettings);
        back.setMinHeight(dp(30));
        back.setPadding(dp(8), 0, dp(8), 0);
        heading.addView(back, new LinearLayout.LayoutParams(-2, dp(32)));
        picker.addView(heading);
        Ui.space(picker, 6);
        TextView preview = Ui.text(ui, "", 14, panelInk(), true);
        preview.setGravity(Gravity.CENTER);
        picker.addView(preview, new LinearLayout.LayoutParams(-1, dp(38)));
        SeekBar[] channels = new SeekBar[3];
        IntConsumer renderPreview = color -> {
            headingLabel.setTextColor(panelInk());
            back.setTextColor(panelAccent());
            for (int i = 0; i < picker.getChildCount(); i++) {
                if (picker.getChildAt(i) instanceof TextView label && label != preview) label.setTextColor(panelInk());
            }
            for (SeekBar channel : channels) {
                if (channel != null) tintSlider(channel);
            }
            preview.setBackground(Ui.shape(ui, backgroundColor, 14, panelSelection()));
            if (target == ColorTarget.READ) {
                SpannableString sample = new SpannableString("已读文字    未读文字");
                sample.setSpan(new ForegroundColorSpan(color), 0, 4, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                preview.setTextColor(textColor);
                preview.setText(sample);
            } else {
                preview.setTextColor(target == ColorTarget.BACKGROUND ? panelInk() : color);
                preview.setText(String.format(Locale.ROOT, "#%06X", color & 0xffffff));
            }
        };
        IntConsumer choose = color -> {
            setColor(target, color);
            renderPreview.accept(pickerColor(target));
        };
        int selected = pickerColor(target);
        renderPreview.accept(selected);
        LinearLayout palette = Ui.row(ui);
        int[] presets = {PromptAppearance.LAVENDER_GLASS, Color.BLACK, Color.WHITE, 0xffffe192, 0xffa8f3cf, 0xff8ebbf8};
        String[] names = {"淡紫", "黑色", "白色", "浅黄", "薄荷", "浅蓝"};
        for (int i = 0; i < presets.length; i++) {
            int color = presets[i];
            TextView swatch = Ui.action(ui, "", color, Color.BLACK, () -> {
                choose.accept(color);
                channels[0].setProgress(Color.red(color));
                channels[1].setProgress(Color.green(color));
                channels[2].setProgress(Color.blue(color));
            });
            swatch.setContentDescription(names[i]);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(38), 1);
            cell.setMargins(dp(2), dp(7), dp(2), dp(3));
            palette.addView(swatch, cell);
        }
        picker.addView(palette, new LinearLayout.LayoutParams(-1, -2));
        if (target == ColorTarget.READ) {
            picker.addView(Ui.action(ui, "自动淡化", Color.TRANSPARENT, panelInk(), () -> {
                choose.accept(0);
                channels[0].setProgress(Color.red(textColor));
                channels[1].setProgress(Color.green(textColor));
                channels[2].setProgress(Color.blue(textColor));
            }), new LinearLayout.LayoutParams(-1, dp(36)));
        }
        String[] namesRGB = {"红", "绿", "蓝"};
        for (int i = 0; i < channels.length; i++) {
            int shift = (2 - i) * 8;
            channels[i] = addSlider(picker, namesRGB[i], 0, 255, (selected >> shift) & 255, value -> {
                int current = pickerColor(target);
                choose.accept(0xff000000 | (current & ~(255 << shift)) | (value << shift));
            });
        }
        scroll.addView(picker);
        settingsPane.addView(scroll);
    }

    private void setColor(ColorTarget target, int color) {
        switch (target) {
            case BACKGROUND -> {
                backgroundColor = color;
                if (!prefs.contains("textColor")) textColor = PromptAppearance.defaultText(color);
                applyBackground();
            }
            case TEXT -> textColor = color;
            case READ -> readColor = color;
        }
        prefs.edit().putInt(target.preference, color).apply();
        refreshScriptAppearance();
    }

    private int pickerColor(ColorTarget target) {
        return switch (target) {
            case BACKGROUND -> backgroundColor;
            case TEXT -> textColor;
            case READ -> resolvedReadColor();
        };
    }

    private int resolvedReadColor() {
        return readColor == 0 ? (0x88000000 | (textColor & 0x00ffffff)) : readColor;
    }

    private void showDraftChooser(boolean fromSettings) {
        readingLineSlider = null;
        settingsPane.removeAllViews();
        ScrollView scroll = new ScrollView(ui);
        LinearLayout choices = Ui.column(ui);
        choices.setPadding(dp(14), dp(4), dp(14), dp(10));
        choices.addView(Ui.text(ui, "切换台本", 15, panelAccent(), true));
        Ui.space(choices, 7);
        choices.addView(Ui.primaryAction(ui, "＋ 新建台本", this::openNewDraft),
                new LinearLayout.LayoutParams(-1, dp(42)));
        Ui.space(choices, 7);
        for (DraftStore.Draft choice : store.all()) {
            TextView button = Ui.pillAction(ui, choice.label(), panelShade(), panelInk(), () -> {
                loadDraft(choice);
                prefs.edit().putString("selected", choice.id()).apply();
                closeSettingsBounds();
                bindExpanded();
                repaintWhenLaidOut();
            });
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            choices.addView(button, new LinearLayout.LayoutParams(-1, -2));
            Ui.space(choices, 5);
        }
        choices.addView(Ui.action(ui, fromSettings ? "返回设置" : "返回提词", Color.TRANSPARENT,
                panelAccent(), fromSettings ? this::refreshSettings : this::toggleSettings),
                new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(choices);
        settingsPane.addView(scroll);
    }

    private void openNewDraft() {
        try {
            startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.NEW_DRAFT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            stopSelf();
        } catch (RuntimeException denied) {
            Toast.makeText(this, "无法打开编辑器，请回到顺言首页新建台本。", Toast.LENGTH_SHORT).show();
        }
    }

    private SeekBar addSlider(LinearLayout parent, String label, int minimum, int maximum, int value, IntConsumer setter) {
        return addSlider(parent, label, minimum, maximum, value, Integer::toString, setter);
    }

    private SeekBar addSlider(LinearLayout parent, String label, int minimum, int maximum, int value,
                              IntFunction<String> format, IntConsumer setter) {
        LinearLayout row = Ui.row(ui);
        TextView name = Ui.text(ui, label, 12, panelInk(), true);
        row.addView(name, new LinearLayout.LayoutParams(dp(92), -2));
        SeekBar slider = new SeekBar(ui);
        slider.setPadding(dp(5), 0, dp(5), 0);
        slider.setMax(maximum - minimum);
        slider.setProgress(value - minimum);
        slider.setContentDescription(label);
        row.addView(slider, new LinearLayout.LayoutParams(0, dp(SETTINGS_TOUCH_HEIGHT), 1));
        TextView amount = Ui.text(ui, format.apply(value), 11, panelInk(), false);
        amount.setGravity(Gravity.CENTER);
        row.addView(amount, new LinearLayout.LayoutParams(dp(49), dp(24)));
        tintSlider(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int adjusted = minimum + progress;
                amount.setText(format.apply(adjusted));
                if (fromUser) setter.accept(adjusted);
            }
        });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return slider;
    }

    private void tintSlider(SeekBar slider) {
        ColorStateList tint = ColorStateList.valueOf(panelAccent());
        slider.setProgressTintList(tint);
        slider.setThumbTintList(tint);
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(panelSelection()));
        if (slider != positionBar && slider.getParent() instanceof LinearLayout row) {
            ((TextView) row.getChildAt(0)).setTextColor(panelInk());
            TextView amount = (TextView) row.getChildAt(2);
            amount.setTextColor(panelInk());
            amount.setBackground(Ui.shape(ui, panelShade(), 13, Color.TRANSPARENT));
        }
    }

    private void collapse() {
        pause();
        closeSettingsBounds();
        expandedX = params.x;
        expandedY = params.y;
        bubbleOnLeft = params.x + params.width / 2 < screen().x / 2;
        collapsed = true;
        surface.removeAllViews();
        clearScriptViews();
        windowRoot.setTurns(0);
        params.width = params.height = dp(53);
        snapBubble();
        params.y = expandedY;
        TextView bubble = Ui.primaryAction(ui, "顺", this::restore);
        bubble.setTextSize(20);
        bubble.setContentDescription("恢复悬浮窗");
        bubble.setOnTouchListener(new WindowGesture(false, true));
        int inset = dp(3);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(-1, -1);
        bubbleParams.setMargins(inset, inset, inset, 0);
        surface.addView(bubble, bubbleParams);
        applyBackground();
        constrain();
        updateWindow();
    }

    private void restore() {
        collapsed = false;
        windowTurns = currentTurns();
        sizeExpandedWindow(false);
        params.x = expandedX;
        params.y = expandedY;
        windowRoot.setTurns(windowTurns);
        bindExpanded();
        constrain();
        updateWindow();
        repaintWhenLaidOut();
    }

    private void togglePlay() {
        if (settingsOpen || collapsed) return;
        if (playing || countdownUntil != 0) {
            pause();
            return;
        }
        if (offsets.length == 0) {
            Toast.makeText(this, "稿件里还没有可朗读的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (endReached || cursor >= offsets[offsets.length - 1]) resetTo(0);
        if (delay > 0) {
            countdownUntil = SystemClock.uptimeMillis() + delay * 1000L;
            tick();
        } else startPlayback();
    }

    private void startPlayback() {
        countdownUntil = 0;
        playing = true;
        showSeek = false;
        paintRead();
        lastFrame = SystemClock.uptimeMillis();
        if (voice) startCapture();
        else handler.post(pulse);
        updateStatus();
    }

    private void tick() {
        long now = SystemClock.uptimeMillis();
        if (countdownUntil > now) {
            updateStatus();
            handler.postDelayed(pulse, Math.min(countdownUntil - now, 100));
        } else if (countdownUntil != 0) {
            startPlayback();
        } else if (playing && !voice) {
            playhead = Math.min(offsets.length, playhead + speed * (now - lastFrame) / 60_000.0);
            lastFrame = now;
            int whole = (int) playhead;
            int advanced = whole == 0 ? 0 : offsets[whole - 1];
            if (advanced != cursor) {
                cursor = advanced;
                paintRead();
            }
            scrollToReadingOffset();
            savePeriodically(now);
            if (whole >= offsets.length) finishPass();
            else handler.postDelayed(pulse, 16L);
        }
    }

    private void startCapture() {
        loadingSpeech = true;
        int generation = ++voiceGeneration;
        matcher = new FollowMatcher(draft.body(), cursor);
        SpeechEngine.Listener listener = new SpeechEngine.Listener() {
            @Override public void onReady() {
                handler.post(() -> {
                    if (!currentVoice(generation)) return;
                    loadingSpeech = false;
                    updateStatus();
                });
            }
            @Override public void onText(String text) {
                handler.post(() -> {
                    if (!currentVoice(generation)) return;
                    int matched = matcher.match(text, pinyin);
                    if (matched < 0) return;
                    cursor = matched;
                    paintRead();
                    scrollToOffset(cursor, true);
                    savePeriodically(SystemClock.uptimeMillis());
                    if (cursor >= offsets[offsets.length - 1]) finishPass();
                });
            }
            @Override public void onSentenceEnd() {
                handler.post(() -> { if (currentVoice(generation)) matcher.sentenceEnd(); });
            }
            @Override public void onInputSilenced(boolean silenced) {
                handler.post(() -> {
                    if (!currentVoice(generation)) return;
                    inputSilenced = silenced;
                    updateStatus();
                });
            }
            @Override public void onError() {
                handler.post(() -> {
                    if (!currentVoice(generation)) return;
                    pause();
                    Toast.makeText(OverlayService.this, "无法启动麦克风或离线模型，请重试。", Toast.LENGTH_LONG).show();
                });
            }
        };
        capture = new SpeechEngine.Capture(getAssets(), listener);
        speechWorker.execute(capture);
    }

    private boolean currentVoice(int generation) {
        return playing && voice && generation == voiceGeneration;
    }

    private void finishPass() {
        if (loop) {
            if (voice) pause();
            resetTo(0);
            if (voice) startPlayback();
            else handler.postDelayed(pulse, 16);
        } else {
            endReached = true;
            paintRead();
            pause();
        }
    }

    private void pause() {
        handler.removeCallbacks(pulse);
        countdownUntil = 0;
        playing = false;
        manualScrolling = false;
        loadingSpeech = false;
        inputSilenced = false;
        voiceGeneration++;
        if (capture != null) {
            capture.stop();
            capture = null;
        }
        savePosition();
        updateStatus();
    }

    private void resetTo(int offset) {
        seekTo(offset, false);
    }

    private void seekTo(int offset, boolean markCharacter) {
        cursor = Math.min(draft.body().length(), Math.max(offset, 0));
        endReached = false;
        showSeek = markCharacter;
        setPlayhead(cursor);
        paintRead();
        scrollToOffset(cursor, voice || markCharacter);
        savePosition();
        updateStatus();
    }

    private void setPlayhead(int offset) {
        int index = Arrays.binarySearch(offsets, offset);
        playhead = index >= 0 ? index + 1 : -index - 1;
    }

    private void skipParagraph(boolean forward) {
        pause();
        String text = draft.body();
        int target = 0;
        if (forward) {
            int end = text.indexOf('\n', cursor);
            target = end < 0 ? text.length() : end + 1;
            while (target < text.length() && text.charAt(target) == '\n') target++;
        } else if (cursor > 0) {
            int currentStart = text.lastIndexOf('\n', cursor - 1) + 1;
            int before = currentStart - 2;
            while (before >= 0 && text.charAt(before) == '\n') before--;
            target = currentStart < cursor ? currentStart : before < 0 ? 0 : text.lastIndexOf('\n', before) + 1;
        }
        resetTo(target);
    }

    private void scrollToOffset(double offset, boolean byLine) {
        if (scriptText == null || collapsed) return;
        Layout layout = scriptText.getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset((int) offset);
        int top = layout.getLineTop(line);
        if (!byLine) {
            int start = layout.getLineStart(line), end = layout.getLineEnd(line);
            if (end > start) top += (int) ((layout.getLineTop(line + 1) - top) * (offset - start) / (end - start));
        }
        adjustingScroll = true;
        scriptScroll.scrollTo(0, top);
        adjustingScroll = false;
    }

    private void scrollToReadingOffset() {
        boolean uniformPlayback = playing && !voice;
        double offset = cursor;
        int whole = (int) playhead;
        if (uniformPlayback && whole < offsets.length) {
            offset += (offsets[whole] - cursor) * (playhead - whole);
        }
        scrollToOffset(offset, voice || showSeek);
    }

    private void paintRead() {
        if (positionBar != null && !scrubbing) positionBar.setProgress((int) playhead);
        if (scriptText == null || !(scriptText.getText() instanceof Spannable text)) return;
        int readEnd = endReached ? text.length() : cursor;
        if (readEnd == 0) text.removeSpan(readStyle);
        else if (text.getSpanEnd(readStyle) != readEnd) {
            text.setSpan(readStyle, 0, readEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (showSeek && cursor < text.length()) {
            int end = Character.offsetByCodePoints(text, cursor, 1);
            text.setSpan(seekStyle, cursor, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else text.removeSpan(seekStyle);
    }

    private void repaintWhenLaidOut() {
        ScrollView view = scriptScroll;
        if (view != null) view.post(() -> {
            if (view != scriptScroll || collapsed) return;
            paintRead();
            scrollToReadingOffset();
        });
    }

    private void savePeriodically(long now) {
        if (now - lastSaved > 2000L) savePosition();
    }

    private void savePosition() {
        handler.removeCallbacks(saveAfterScroll);
        if (draft == null) return;
        DraftStore.Draft current = store.find(draft.id());
        if (current != null && current.body().equals(draft.body())) store.savePosition(draft.id(), voice, cursor);
        lastSaved = SystemClock.uptimeMillis();
    }

    private void updateStatus() {
        if (status == null || playButton == null) return;
        if (renderedPlayback != playbackActive()) applyBackground();
        boolean counting = countdownUntil != 0;
        surface.setKeepScreenOn(playbackActive());
        String label;
        if (counting) label = "准备 " + Math.max(1, (countdownUntil - SystemClock.uptimeMillis() + 999L) / 1000L);
        else if (loadingSpeech) label = "加载中";
        else if (inputSilenced) label = "麦克风不可用";
        else if (playing && voice) label = "正在跟读";
        else if (endReached) label = "已读完";
        else if (!voice) label = speed + " 字/分";
        else label = "已暂停";
        status.setText(label);
        boolean announce = counting || loadingSpeech || inputSilenced;
        playbackNotice.setText(announce ? label : "");
        playbackNotice.setVisibility(announce ? View.VISIBLE : View.GONE);
    }

    private void applyBackground() {
        renderedPlayback = playbackActive();
        surface.setBackground(collapsed || renderedPlayback || settingsOpen ? null
                : PromptAppearance.fill(ui, backgroundColor, 100, PANEL_RADIUS));
        if (collapsed) return;

        surface.setClickable(renderedPlayback);
        titleBar.setVisibility(renderedPlayback ? View.INVISIBLE : View.VISIBLE);
        footer.setVisibility(renderedPlayback || settingsOpen ? View.GONE : View.VISIBLE);
        positionBar.setVisibility(footer.getVisibility());
        int controlInk = panelInk();
        titleBar.setBackground(settingsOpen ? Ui.shape(ui, SETTINGS_SURFACE, 16, SETTINGS_EDGE)
                : Ui.shape(ui, panelShade(), 16, Color.TRANSPARENT));
        for (int i = 0; i < titleBar.getChildCount(); i++) {
            ((TextView) titleBar.getChildAt(i)).setTextColor(controlInk);
        }
        if (settingsOpen) settingsPane.setBackground(Ui.shape(ui, SETTINGS_SURFACE, 20, SETTINGS_EDGE));
        status.setTextColor(controlInk);
        for (int i = 0; i < footer.getChildCount(); i++) {
            if (footer.getChildAt(i) instanceof TextView label && label != playButton) label.setTextColor(controlInk);
        }
        playButton.setTextColor(lightPanel() ? Color.WHITE : Ui.INK);
        Ui.setPill(playButton, panelAccent(), Color.TRANSPARENT);
        tintSlider(positionBar);
        applyScriptBackground();
        scriptScroll.setClickable(renderedPlayback);
        scriptScroll.setContentDescription(renderedPlayback ? "轻点暂停提词" : null);
        readingGrip.setVisibility(renderedPlayback ? View.INVISIBLE : View.VISIBLE);
        readingGrip.setTextColor(settingsOpen ? Ui.NIGHT_INK : panelAccent());
        readingGrip.setShadowLayer(settingsOpen ? dp(2) : 0, 0, 0, Color.BLACK);
        for (View grip : new View[] {leftWidthGrip, rightWidthGrip}) {
            grip.setVisibility(renderedPlayback ? View.INVISIBLE : View.VISIBLE);
            grip.setBackground(new InsetDrawable(Ui.shape(ui, panelAccent(), 3, Color.TRANSPARENT),
                    dp(11), dp(16), dp(11), dp(16)));
        }
    }

    private void applyScriptBackground() {
        boolean usePlaybackAppearance = renderedPlayback || settingsOpen;
        scriptBackground.setBackground(usePlaybackAppearance && opacity > 0
                ? PromptAppearance.fill(ui, backgroundColor, opacity, 12) : null);
        scriptText.setOutlined(usePlaybackAppearance && opacity < 100);
    }

    private void refreshScriptAppearance() {
        scriptText.setScriptSize(font);
        scriptText.setScriptColor(textColor);
        if (scriptText.getText() instanceof Spannable text) text.removeSpan(readStyle);
        readStyle = scriptText.colorSpan(resolvedReadColor());
        layoutScript(scriptScroll.getWidth(), scriptScroll.getHeight());
        paintRead();
        repaintWhenLaidOut();
    }

    private boolean playbackActive() {
        return playing || countdownUntil != 0;
    }

    private int panelInk() {
        if (settingsOpen) return SETTINGS_INK;
        return lightPanel() ? Ui.INK : Ui.NIGHT_INK;
    }

    private int panelAccent() {
        if (settingsOpen) return SETTINGS_ACCENT;
        return lightPanel() ? Ui.ACCENT : Ui.ICE;
    }

    private boolean lightPanel() {
        return Color.luminance(backgroundColor) >= .42f;
    }

    private int panelShade() {
        if (settingsOpen) return SETTINGS_CONTROL;
        return lightPanel() ? 0x166439b4 : 0x16a9d5ff;
    }

    private int panelSelection() {
        if (settingsOpen) return SETTINGS_SELECTED;
        return lightPanel() ? 0x296439b4 : 0x40c4a5ff;
    }

    private Point screen() {
        Point size = new Point();
        display.getSize(size);
        return size;
    }

    private int currentTurns() {
        return orientationMode == AUTO
                ? RotatingOverlay.autoRotation(deviceTurns, display.getRotation()) : orientationMode;
    }

    private void selectOrientation(int mode) {
        orientationMode = mode;
        prefs.edit().putInt("orientationMode", mode).apply();
        refreshWindowOrientation();
    }

    private void scheduleOrientationRefresh() {
        handler.removeCallbacks(refreshOrientation);
        handler.postDelayed(refreshOrientation, 150);
    }

    private void refreshWindowOrientation() {
        if (windowRoot == null) return;
        if (windowGestureActive || scriptGestureActive) {
            scheduleOrientationRefresh();
            return;
        }
        if (collapsed) {
            snapBubble();
        } else {
            if (settingsOpen) restoreSettingsPrompt();
            int next = currentTurns();
            boolean axisChanged = RotatingOverlay.swapsAxes(next) != RotatingOverlay.swapsAxes(windowTurns);
            windowTurns = next;
            windowRoot.setTurns(windowTurns);
            sizeExpandedWindow(axisChanged);
        }
        constrain();
        if (settingsOpen) {
            settingsPromptX = params.x;
            settingsPromptY = params.y;
        }
        updateWindow();
        repaintWhenLaidOut();
    }

    private void sizeExpandedWindow(boolean keepCenter) {
        int centerX = params.x + params.width / 2;
        int centerY = params.y + params.height / 2;
        boolean sideways = RotatingOverlay.swapsAxes(windowTurns);
        params.width = sideways ? preferredHeight : preferredWidth;
        params.height = sideways ? preferredWidth : preferredHeight;
        constrain();
        if (keepCenter) {
            params.x = centerX - params.width / 2;
            params.y = centerY - params.height / 2;
        }
    }

    private void restoreSettingsPrompt() {
        params.x = settingsPromptX;
        params.y = settingsPromptY;
        sizeExpandedWindow(false);
    }

    private void placeSettingsWindow() {
        restoreSettingsPrompt();
        settingsPromptX = params.x;
        settingsPromptY = params.y;
        Point area = settingsArea();
        int promptWidth = Math.min(params.width, area.x);
        int promptHeight = Math.min(params.height, area.y);
        int left = clamp(params.x, 0, area.x - promptWidth);
        int top = clamp(params.y, 0, area.y - promptHeight);
        boolean sideways = RotatingOverlay.swapsAxes(windowTurns);
        int width = sideways ? area.y : area.x;
        int height = sideways ? area.x : area.y;
        Rect prompt = new Rect(left, top, left + promptWidth, top + promptHeight);
        Rect localPrompt = SettingsPlacement.toLocal(prompt, area.x, area.y, windowTurns);
        SettingsPlacement placement = height > width && width < dp(540)
                ? SettingsPlacement.portrait(localPrompt, width, height, dp(320), dp(386), dp(152), dp(10))
                : SettingsPlacement.fit(localPrompt, width, height,
                        dp(SETTINGS_WIDTH), dp(SETTINGS_HEIGHT), dp(270), dp(208), dp(122), dp(6));
        int inverse = (-windowTurns) & 3;
        setWindowBounds(params, SettingsPlacement.toLocal(placement.prompt(), width, height, inverse));
        setWindowBounds(settingsParams, SettingsPlacement.toLocal(placement.settings(), width, height, inverse));
    }

    private Point settingsArea() {
        Point screen = screen();
        Rect area = new Rect(0, 0, screen.x, screen.y);
        Rect visible;
        if (Build.VERSION.SDK_INT >= 30) {
            var metrics = windows.getCurrentWindowMetrics();
            visible = new Rect(metrics.getBounds());
            Insets insets = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            visible.inset(insets.left, insets.top, insets.right, insets.bottom);
        } else {
            visible = new Rect();
            windowRoot.getWindowVisibleDisplayFrame(visible);
        }
        area.intersect(visible);
        // The window manager already applies the parent frame's system bar offsets.
        return new Point(area.width(), area.height());
    }

    private void setWindowBounds(WindowManager.LayoutParams attributes, Rect bounds) {
        attributes.x = bounds.left;
        attributes.y = bounds.top;
        attributes.width = bounds.width();
        attributes.height = bounds.height();
    }

    private void snapBubble() {
        params.x = bubbleOnLeft ? dp(4) : screen().x - params.width - dp(4);
    }

    private void constrain() {
        Point size = screen();
        if (!collapsed) {
            int maxWidth = Math.max(1, size.x - dp(8)), maxHeight = Math.max(1, size.y - dp(8));
            params.width = clamp(params.width, Math.min(minWindowWidth(), maxWidth), maxWidth);
            params.height = clamp(params.height, Math.min(minWindowHeight(), maxHeight), maxHeight);
        }
        params.x = clamp(params.x, 0, Math.max(0, size.x - params.width));
        params.y = clamp(params.y, 0, Math.max(0, size.y - params.height));
    }

    private int minWindowWidth() { return dp(RotatingOverlay.swapsAxes(windowTurns) ? 196 : 252); }
    private int minWindowHeight() { return dp(RotatingOverlay.swapsAxes(windowTurns) ? 252 : 196); }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void updateWindow() {
        if (windowRoot == null || !windowRoot.isAttachedToWindow()) return;
        if (settingsOpen) placeSettingsWindow();
        windows.updateViewLayout(windowRoot, params);
        if (settingsRoot != null) {
            settingsRoot.setTurns(windowTurns);
            windows.updateViewLayout(settingsRoot, settingsParams);
        }
    }

    private final class WindowGesture implements View.OnTouchListener {
        private final boolean resize;
        private final boolean bubble;
        private final int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
        private int x, y, width, height;
        private float startX, startY;
        private boolean moved;

        WindowGesture(boolean resize, boolean bubble) {
            this.resize = resize;
            this.bubble = bubble;
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x = settingsOpen ? settingsPromptX : params.x;
                    y = settingsOpen ? settingsPromptY : params.y;
                    width = params.width;
                    height = params.height;
                    startX = event.getRawX();
                    startY = event.getRawY();
                    moved = false;
                    windowGestureActive = true;
                    if (!resize && view.isClickable()) view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - startX), dy = Math.round(event.getRawY() - startY);
                    moved = moved || Math.abs(dx) > slop || Math.abs(dy) > slop;
                    if (!moved) return true;
                    view.setPressed(false);
                    if (settingsOpen) {
                        settingsPromptX = x + dx;
                        settingsPromptY = y + dy;
                    } else if (resize) {
                        resizeWindow(dx, dy);
                    } else {
                        params.x = x + dx;
                        params.y = y + dy;
                    }
                    if (!settingsOpen) constrain();
                    updateWindow();
                    return true;
                case MotionEvent.ACTION_UP:
                    windowGestureActive = false;
                    view.setPressed(false);
                    if (!moved) {
                        if (!resize && view.isClickable()) view.performClick();
                        return true;
                    }
                    if (bubble) {
                        Point size = screen();
                        bubbleOnLeft = params.x + params.width / 2 < size.x / 2;
                        snapBubble();
                        constrain();
                        updateWindow();
                    } else {
                        if (resize) {
                            boolean sideways = RotatingOverlay.swapsAxes(windowTurns);
                            preferredWidth = sideways ? params.height : params.width;
                            preferredHeight = sideways ? params.width : params.height;
                        }
                        prefs.edit().putInt("x", settingsOpen ? settingsPromptX : params.x)
                                .putInt("y", settingsOpen ? settingsPromptY : params.y)
                                .putInt("width", preferredWidth).putInt("height", preferredHeight).apply();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    windowGestureActive = false;
                    view.setPressed(false);
                    if (resize && moved) refreshWindowOrientation();
                    return true;
                default:
                    return false;
            }
        }

        private void resizeWindow(int dx, int dy) {
            Point size = screen();
            boolean fromLeft = windowTurns == 1 || windowTurns == 2;
            boolean fromTop = windowTurns == 2 || windowTurns == 3;
            int maxWidth = Math.min(size.x - dp(8), fromLeft ? x + width : size.x - x);
            int maxHeight = Math.min(size.y - dp(8), fromTop ? y + height : size.y - y);
            params.width = clamp(width + (fromLeft ? -dx : dx), Math.min(minWindowWidth(), maxWidth), maxWidth);
            params.height = clamp(height + (fromTop ? -dy : dy), Math.min(minWindowHeight(), maxHeight), maxHeight);
            params.x = fromLeft ? x + width - params.width : x;
            params.y = fromTop ? y + height - params.height : y;
        }
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        scheduleOrientationRefresh();
    }

    private void removeWindow() {
        removeSettingsWindow();
        if (windowRoot != null && windowRoot.isAttachedToWindow()) windows.removeViewImmediate(windowRoot);
        windowRoot = null;
        surface = null;
        clearScriptViews();
    }

    private void removeSettingsWindow() {
        if (settingsRoot != null) windows.removeViewImmediate(settingsRoot);
        settingsRoot = null;
        settingsParams = null;
        settingsPane = null;
    }

    private void clearScriptViews() {
        titleBar = null;
        footer = null;
        draftButton = null;
        positionBar = null;
        readingLineSlider = null;
        scrubbing = false;
        scriptScroll = null;
        scriptText = null;
        scriptBackground = null;
        readingGrip = null;
        leftWidthGrip = rightWidthGrip = null;
        playButton = null;
        settingsButton = null;
        status = null;
        playbackNotice = null;
        readStyle = null;
    }

    private void report(ResultReceiver receiver, boolean success, String message) {
        if (receiver == null) return;
        Bundle data = new Bundle();
        data.putString("message", message);
        receiver.send(success ? 1 : 0, data);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        pause();
        orientationSensor.disable();
        displays.unregisterDisplayListener(displayListener);
        handler.removeCallbacksAndMessages(null);
        speechWorker.shutdown();
        removeWindow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private int dp(float value) { return Ui.dp(ui, value); }
}
