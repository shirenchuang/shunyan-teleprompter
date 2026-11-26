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
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private static final int DEFAULT_BACKGROUND = 0xffebe1fa;
    private static final int PANEL_RADIUS = 26;
    private static final int MIN_COLUMNS = 4;
    private static final int AUTO_COLUMNS = 33;
    private static final int WIDTH_GRIP_SIZE = 26;
    private static final int SCRIPT_GUTTER = WIDTH_GRIP_SIZE + 1;

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
    private LinearLayout titleBar;
    private TextView title;
    private FrameLayout content;
    private LinearLayout draftBar;
    private LinearLayout footer;
    private TextView draftButton;
    private SeekBar positionBar;
    private ScrollView scriptScroll;
    private TextView scriptText;
    private View scriptMarker;
    private View leftWidthGrip;
    private View rightWidthGrip;
    private TextView playButton;
    private TextView settingsButton;
    private TextView status;
    private ForegroundColorSpan readStyle;
    private final BackgroundColorSpan seekStyle = new BackgroundColorSpan(0x4da57cf1);
    private int[] offsets;
    private FollowMatcher matcher;
    private SpeechEngine.Capture capture;
    private boolean voice, playing, collapsed, settingsOpen, loadingSpeech, adjustingScroll;
    private boolean endReached, manualScrolling, inputSilenced, showSeek, windowGestureActive, scriptGestureActive;
    private boolean alignAfterScriptLayout, playbackSettings, scrubbing;
    private boolean bubbleOnLeft;
    private int cursor, voiceGeneration, font, opacity, speed, delay, anchor, preferredWidth, preferredHeight;
    private int expandedX, expandedY;
    private int settingsOriginX, settingsOriginY, settingsOriginTurns;
    private int backgroundColor, textColor, readColor;
    private float columns;
    private int renderedPanelColor;
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
        font = prefs.getInt("font", 26);
        opacity = prefs.getInt("opacity", 91);
        speed = prefs.getInt("speed", 180);
        delay = prefs.getInt("delay", 3);
        loop = prefs.getBoolean("loop", false);
        mirror = prefs.getBoolean("mirror", false);
        pinyin = prefs.getBoolean("pinyin", true);
        backgroundColor = prefs.getInt("backgroundColor", DEFAULT_BACKGROUND);
        textColor = prefs.getInt("textColor", panelInk());
        readColor = prefs.getInt("readColor", 0);
        columns = prefs.getFloat("columnWidth", prefs.getInt("columns", 0));
        orientationMode = clamp(prefs.getInt("orientationMode", AUTO), AUTO, 3);
        anchor = dp(12);
    }

    private void createWindow() {
        Point size = screen();
        params = new WindowManager.LayoutParams(
                Math.min(size.x - dp(20), dp(460)),
                Math.min(size.y - dp(16), dp(312)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        preferredWidth = prefs.getInt("width", params.width);
        preferredHeight = prefs.getInt("height", params.height);
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
        windowRoot.addView(surface);
        windowRoot.setTurns(windowTurns);
        bindExpanded();
        windows.addView(windowRoot, params);
        repaintWhenLaidOut();
    }

    private void bindExpanded() {
        surface.removeAllViews();
        int inset = dp(5);
        titleBar = Ui.row(ui);
        titleBar.setPadding(dp(12), dp(2), dp(8), 0);
        ImageView logo = new ImageView(ui);
        logo.setImageResource(R.drawable.ic_app);
        logo.setContentDescription("拖动悬浮窗");
        logo.setOnTouchListener(new WindowGesture(false, false));
        titleBar.addView(logo, new LinearLayout.LayoutParams(dp(22), dp(22)));
        title = Ui.text(ui, "顺言提词", 13, panelInk(), true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(6), 0, dp(4), 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setContentDescription("拖动悬浮窗");
        title.setOnTouchListener(new WindowGesture(false, false));
        titleBar.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
        compactButton(titleBar, "↻", "手动旋转九十度", () -> selectOrientation((windowTurns + 1) & 3));
        settingsButton = compactButton(titleBar, "⚙", "悬浮设置", this::toggleSettings);
        compactButton(titleBar, "−", "收起悬浮窗", this::collapse);
        compactButton(titleBar, "×", "关闭悬浮窗", this::stopSelf);
        surface.addView(titleBar);

        draftBar = Ui.row(ui);
        draftBar.setPadding(dp(12), dp(2), dp(12), dp(3));
        draftButton = Ui.pillAction(ui, "", panelSelection(), panelInk(), () -> {
            openSettings();
            showDraftChooser(false);
        });
        draftButton.setSingleLine(true);
        draftButton.setEllipsize(TextUtils.TruncateAt.END);
        draftButton.setTextSize(12);
        draftButton.setGravity(Gravity.CENTER_VERTICAL);
        draftButton.setPadding(dp(10), 0, dp(8), 0);
        draftBar.addView(draftButton, new LinearLayout.LayoutParams(0, dp(27), 1));
        status = Ui.text(ui, "", 11, panelInk(), false);
        status.setPadding(dp(9), 0, 0, 0);
        status.setSingleLine(true);
        draftBar.addView(status, new LinearLayout.LayoutParams(-2, -2));
        surface.addView(draftBar);
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
        surface.addView(positionBar, new LinearLayout.LayoutParams(-1, dp(19)));

        footer = Ui.row(ui);
        footer.setPadding(dp(8), 0, dp(5), 0);
        footer.addView(new View(ui), new LinearLayout.LayoutParams(0, 1, 1));
        compactButton(footer, "⏮", "上一段", () -> skipParagraph(false));
        playButton = Ui.primaryAction(ui, "▶", this::togglePlay);
        playButton.setTextSize(17);
        playButton.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams playPlacement = new LinearLayout.LayoutParams(dp(46), dp(40));
        playPlacement.setMargins(dp(9), 0, dp(9), 0);
        footer.addView(playButton, playPlacement);
        compactButton(footer, "⏭", "下一段", () -> skipParagraph(true));
        footer.addView(new View(ui), new LinearLayout.LayoutParams(0, 1, 1));
        TextView grip = compactButton(footer, "◢", "拖动调整窗口大小", () -> {});
        grip.setOnTouchListener(new WindowGesture(true, false));
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, dp(46));
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
        button.setMinWidth(dp(34));
        button.setMinHeight(dp(38));
        parent.addView(button, new LinearLayout.LayoutParams(-2, dp(39)));
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
        scriptText = Ui.text(ui, "", font, textColor, false);
        scriptText.setLineSpacing(dp(7), 1.05f);
        scriptText.setPadding(dp(SCRIPT_GUTTER), anchor, dp(SCRIPT_GUTTER), 0);
        scriptText.setScaleX(mirror ? -1 : 1);
        scriptText.setText(new SpannableString(draft.body()), TextView.BufferType.SPANNABLE);
        readStyle = new ForegroundColorSpan(resolvedReadColor());
        scriptScroll.addView(scriptText, new ScrollView.LayoutParams(-1, -2));
        content.addView(scriptScroll, new FrameLayout.LayoutParams(-1, -1));
        scriptMarker = new View(ui);
        scriptMarker.setBackground(Ui.shape(ui, textColor, 2, Color.TRANSPARENT));
        content.addView(scriptMarker, new FrameLayout.LayoutParams(dp(3), dp(19)));
        leftWidthGrip = addWidthGrip(true);
        rightWidthGrip = addWidthGrip(false);

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
        int side = ScriptLayout.columnInset(width, dp(SCRIPT_GUTTER), columns, scriptText.getPaint().measureText("字"));
        int bottom = Math.max(dp(8), height - anchor - scriptText.getLineHeight());
        if (scriptText.getPaddingLeft() != side || scriptText.getPaddingBottom() != bottom) {
            alignAfterScriptLayout = true;
            scriptText.setPadding(side, anchor, side, bottom);
        }
        int gripMargin = Math.max(0, side - dp(WIDTH_GRIP_SIZE));
        positionWidthGrip(leftWidthGrip, gripMargin);
        positionWidthGrip(rightWidthGrip, gripMargin);
        FrameLayout.LayoutParams marker = (FrameLayout.LayoutParams) scriptMarker.getLayoutParams();
        marker.gravity = Gravity.TOP | (mirror ? Gravity.RIGHT : Gravity.LEFT);
        marker.leftMargin = mirror ? 0 : Math.max(dp(4), side - dp(10));
        marker.rightMargin = mirror ? Math.max(dp(4), side - dp(10)) : 0;
        marker.topMargin = anchor;
        scriptMarker.setLayoutParams(marker);
    }

    private View addWidthGrip(boolean left) {
        TextView grip = Ui.text(ui, "↔", 15, panelAccent(), true);
        grip.setGravity(Gravity.CENTER);
        grip.setBackground(new InsetDrawable(Ui.shape(ui, lightPanel() ? 0xdff7f0ff : 0xe044375b, 10, Color.TRANSPARENT),
                dp(3), dp(7), dp(3), dp(7)));
        grip.setContentDescription(left ? "左侧稿件宽度把手" : "右侧稿件宽度把手");
        grip.setOnTouchListener(new ColumnWidthGesture(left));
        int gravity = Gravity.CENTER_VERTICAL | (left ? Gravity.LEFT : Gravity.RIGHT);
        content.addView(grip, new FrameLayout.LayoutParams(dp(WIDTH_GRIP_SIZE), dp(52), gravity));
        return grip;
    }

    private void positionWidthGrip(View grip, int margin) {
        FrameLayout.LayoutParams bounds = (FrameLayout.LayoutParams) grip.getLayoutParams();
        if (bounds.leftMargin == margin && bounds.rightMargin == margin) return;
        bounds.leftMargin = bounds.rightMargin = margin;
        grip.setLayoutParams(bounds);
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
        private boolean moved;

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (playing || countdownUntil != 0) pause();
                    handler.removeCallbacks(saveAfterScroll);
                    downX = event.getX();
                    downY = event.getY();
                    moved = manualScrolling = false;
                    scriptGestureActive = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop) {
                        moved = manualScrolling = true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
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
            return false;
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
        positionBar.setVisibility(View.VISIBLE);
        footer.setVisibility(View.VISIBLE);
        bindScript();
        repaintWhenLaidOut();
    }

    private void openSettings() {
        settingsOriginX = params.x;
        settingsOriginY = params.y;
        settingsOriginTurns = windowTurns;
        settingsOpen = true;
        playbackSettings = false;
        settingsButton.setText("返回提词");
        settingsButton.setContentDescription("返回提词");
        pause();
        positionBar.setVisibility(View.GONE);
        footer.setVisibility(View.GONE);
        sizeExpandedWindow(false);
        updateWindow();
    }

    private void closeSettingsBounds() {
        if (!settingsOpen) return;
        settingsOpen = false;
        if (windowTurns == settingsOriginTurns) {
            params.x = settingsOriginX;
            params.y = settingsOriginY;
        }
        sizeExpandedWindow(false);
        updateWindow();
    }

    private void refreshSettings() {
        refreshSettings(0);
    }

    private void refreshSettings(int scrollY) {
        content.removeAllViews();
        LinearLayout page = Ui.column(ui);
        page.setPadding(dp(9), 0, dp(9), dp(5));
        LinearLayout tabs = Ui.row(ui);
        tabs.setBackground(Ui.shape(ui, panelShade(), 20, Color.TRANSPARENT));
        addSettingsTab(tabs, "显示设置", false);
        addSettingsTab(tabs, "播放跟读", true);
        page.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(ui);
        LinearLayout controls = Ui.column(ui);
        controls.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (playbackSettings) addPlaybackSettings(controls);
        else addDisplaySettings(controls, scroll);
        scroll.addView(controls);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView done = Ui.primaryAction(ui, "完成", this::toggleSettings);
        LinearLayout.LayoutParams donePlacement = new LinearLayout.LayoutParams(-1, dp(33));
        donePlacement.setMargins(dp(43), dp(2), dp(43), 0);
        page.addView(done, donePlacement);
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (scrollY > 0) scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    private void addSettingsTab(LinearLayout tabs, String label, boolean playback) {
        boolean selected = playbackSettings == playback;
        Runnable select = () -> {
            if (playbackSettings == playback) return;
            playbackSettings = playback;
            refreshSettings();
        };
        TextView tab = selected ? Ui.primaryAction(ui, label, select)
                : Ui.pillAction(ui, label, Color.TRANSPARENT, panelInk(), select);
        tab.setSelected(selected);
        tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(35), 1));
    }

    private void addDisplaySettings(LinearLayout controls, ScrollView scroll) {
        controls.addView(Ui.text(ui, "旋转方向", 12, panelInk(), true));
        addOrientationControls(controls, () -> refreshSettings(scroll.getScrollY()));
        addColorRow(controls, ColorTarget.BACKGROUND, scroll,
                new int[] {DEFAULT_BACKGROUND, 0xffd9c7f9, 0xffc5f3fa, Ui.NIGHT},
                new String[] {"紫青渐变", "淡紫", "浅青", "深紫"});
        addColorRow(controls, ColorTarget.TEXT, scroll,
                new int[] {Ui.INK, Color.WHITE, 0xff6a49bb, 0xff124e67},
                new String[] {"深紫文字", "白色文字", "紫色文字", "深青文字"});
        addColorRow(controls, ColorTarget.READ, scroll,
                new int[] {0, 0xff9b76f2, 0xff326c83, 0xffb65a43},
                new String[] {"自动淡化", "紫色已读", "青色已读", "暖橙已读"});
        int autoColumns = Math.max(AUTO_COLUMNS, Math.round(columns) + 1);
        addSlider(controls, "每行字数", MIN_COLUMNS, autoColumns, columns == 0 ? autoColumns : Math.round(columns),
                value -> value == autoColumns ? "自动" : value + " 字", value -> {
                    columns = value == autoColumns ? 0 : value;
                    saveColumns();
                });
        addSlider(controls, "文字字号", 16, 64, font, value -> value + " sp", value -> {
            font = value;
            prefs.edit().putInt("font", value).apply();
        });
        addSlider(controls, "播放背景透明度", 15, 100, opacity, value -> value + "%", value -> {
            opacity = value;
            prefs.edit().putInt("opacity", value).apply();
        });
    }

    private void addColorRow(LinearLayout controls, ColorTarget target, ScrollView scroll, int[] colors, String[] names) {
        LinearLayout row = Ui.row(ui);
        TextView label = Ui.text(ui, target.label, 12, panelInk(), true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        int selected = target == ColorTarget.READ ? readColor : pickerColor(target);
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            int fill = color == 0 ? panelSelection() : color;
            if (target == ColorTarget.BACKGROUND && color == DEFAULT_BACKGROUND) fill = Ui.LAVENDER;
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
        content.removeAllViews();
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
        int[] presets = {DEFAULT_BACKGROUND, Color.BLACK, Color.WHITE, 0xffffe192, 0xffa8f3cf, 0xff8ebbf8};
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
        content.addView(scroll);
    }

    private void setColor(ColorTarget target, int color) {
        switch (target) {
            case BACKGROUND -> {
                backgroundColor = color;
                if (!prefs.contains("textColor")) textColor = panelInk();
                applyBackground();
            }
            case TEXT -> textColor = color;
            case READ -> readColor = color;
        }
        prefs.edit().putInt(target.preference, color).apply();
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
        content.removeAllViews();
        ScrollView scroll = new ScrollView(ui);
        LinearLayout choices = Ui.column(ui);
        choices.setPadding(dp(14), dp(4), dp(14), dp(10));
        choices.addView(Ui.text(ui, "切换台本", 15, panelAccent(), true));
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
        content.addView(scroll);
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
        row.addView(slider, new LinearLayout.LayoutParams(0, dp(34), 1));
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
        if (scriptText == null || collapsed || settingsOpen) return;
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
            if (view != scriptScroll || collapsed || settingsOpen) return;
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
        if (renderedPanelColor != panelColor()) applyBackground();
        boolean counting = countdownUntil != 0;
        surface.setKeepScreenOn(playing || counting);
        String label;
        if (counting) label = "准备 " + Math.max(1, (countdownUntil - SystemClock.uptimeMillis() + 999L) / 1000L);
        else if (loadingSpeech) label = "加载中";
        else if (inputSilenced) label = "麦克风不可用";
        else if (playing && voice) label = "正在跟读";
        else if (endReached) label = "已读完";
        else if (!voice) label = speed + " 字/分";
        else label = "已暂停";
        status.setText(label);
        playButton.setText(playing || counting ? "Ⅱ" : "▶");
        playButton.setContentDescription(playing || counting ? "暂停" : "播放");
    }

    private void applyBackground() {
        renderedPanelColor = panelColor();
        boolean glass = backgroundColor == DEFAULT_BACKGROUND && !collapsed;
        GradientDrawable background = glass ? Ui.glass(ui, Color.alpha(renderedPanelColor), PANEL_RADIUS)
                : Ui.shape(ui, renderedPanelColor, PANEL_RADIUS, Color.TRANSPARENT);
        if (glass) background.setStroke(dp(1.5f), (Color.alpha(renderedPanelColor) << 24) | 0xffffff);
        surface.setBackground(background);
        if (collapsed) return;

        GradientDrawable titleBackground = glass ? Ui.glass(ui, 255, 0)
                : Ui.shape(ui, 0xff000000 | backgroundColor, 0, Color.TRANSPARENT);
        float radius = dp(PANEL_RADIUS);
        titleBackground.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
        titleBar.setBackground(titleBackground);
        updatePanelRow(titleBar);
        updatePanelRow(draftBar);
        updatePanelRow(footer);
        Ui.setPill(draftButton, panelSelection(), Color.TRANSPARENT);
        tintSlider(positionBar);
        if (scriptText != null) {
            int halo = lightPanel() ? 0xaaffffff : 0xbb000000;
            scriptText.setShadowLayer(panelOpacity() < 60 ? dp(2) : 0, 0, 0, halo);
        }
    }

    private int panelOpacity() {
        return playing || countdownUntil != 0 ? opacity : 100;
    }

    private int panelColor() {
        if (collapsed) return Color.TRANSPARENT;
        return (Math.round(panelOpacity() * 255f / 100) << 24) | (backgroundColor & 0x00ffffff);
    }

    private int panelInk() {
        return lightPanel() ? Ui.INK : Ui.NIGHT_INK;
    }

    private int panelAccent() {
        return lightPanel() ? Ui.ACCENT : Ui.ICE;
    }

    private boolean lightPanel() {
        return Color.luminance(backgroundColor) >= .42f;
    }

    private int panelShade() {
        return lightPanel() ? 0x166439b4 : 0x16a9d5ff;
    }

    private int panelSelection() {
        return lightPanel() ? 0x296439b4 : 0x40c4a5ff;
    }

    private void updatePanelRow(LinearLayout row) {
        int ink = panelInk();
        int shadow = lightPanel() ? 0x99ffffff : 0xaa000000;
        int shadowRadius = row == footer && panelOpacity() < 65 ? dp(1) : 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof TextView label && label != playButton) {
                label.setTextColor(label == title || label == settingsButton ? panelAccent() : ink);
                label.setShadowLayer(shadowRadius, 0, 0, shadow);
                if (row == titleBar && label != title) Ui.setPill(label, panelShade(), Color.TRANSPARENT);
            }
        }
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
            int next = currentTurns();
            boolean axisChanged = RotatingOverlay.swapsAxes(next) != RotatingOverlay.swapsAxes(windowTurns);
            windowTurns = next;
            windowRoot.setTurns(windowTurns);
            sizeExpandedWindow(axisChanged);
        }
        constrain();
        updateWindow();
        repaintWhenLaidOut();
    }

    private void sizeExpandedWindow(boolean keepCenter) {
        int centerX = params.x + params.width / 2;
        int centerY = params.y + params.height / 2;
        boolean sideways = RotatingOverlay.swapsAxes(windowTurns);
        int height = settingsOpen ? Math.max(preferredHeight, dp(405)) : preferredHeight;
        params.width = sideways ? height : preferredWidth;
        params.height = sideways ? preferredWidth : height;
        constrain();
        if (keepCenter) {
            params.x = centerX - params.width / 2;
            params.y = centerY - params.height / 2;
        }
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
        if (windowRoot != null && windowRoot.isAttachedToWindow()) windows.updateViewLayout(windowRoot, params);
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
                    x = params.x;
                    y = params.y;
                    width = params.width;
                    height = params.height;
                    startX = event.getRawX();
                    startY = event.getRawY();
                    moved = false;
                    windowGestureActive = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - startX), dy = Math.round(event.getRawY() - startY);
                    moved = moved || Math.abs(dx) > slop || Math.abs(dy) > slop;
                    if (!moved) return true;
                    if (resize) {
                        resizeWindow(dx, dy);
                    } else {
                        params.x = x + dx;
                        params.y = y + dy;
                    }
                    constrain();
                    updateWindow();
                    return true;
                case MotionEvent.ACTION_UP:
                    windowGestureActive = false;
                    if (bubble && !moved) return view.performClick();
                    if (bubble) {
                        Point size = screen();
                        bubbleOnLeft = params.x + params.width / 2 < size.x / 2;
                        snapBubble();
                        constrain();
                        updateWindow();
                    } else if (moved) {
                        if (resize) {
                            boolean sideways = RotatingOverlay.swapsAxes(windowTurns);
                            preferredWidth = sideways ? params.height : params.width;
                            preferredHeight = sideways ? params.width : params.height;
                        }
                        if (settingsOpen) {
                            settingsOriginX = params.x;
                            settingsOriginY = params.y;
                            settingsOriginTurns = windowTurns;
                        }
                        prefs.edit().putInt("x", params.x).putInt("y", params.y)
                                .putInt("width", preferredWidth).putInt("height", preferredHeight).apply();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    windowGestureActive = false;
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
        if (windowRoot != null && windowRoot.isAttachedToWindow()) windows.removeViewImmediate(windowRoot);
        windowRoot = null;
        surface = null;
        clearScriptViews();
    }

    private void clearScriptViews() {
        titleBar = null;
        title = null;
        footer = null;
        draftBar = null;
        draftButton = null;
        positionBar = null;
        scrubbing = false;
        scriptScroll = null;
        scriptText = null;
        scriptMarker = null;
        leftWidthGrip = rightWidthGrip = null;
        playButton = null;
        settingsButton = null;
        status = null;
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
