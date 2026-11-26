package com.shunyan.prompter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public final class MainActivity extends Activity {
    static final String NEW_DRAFT = "com.shunyan.prompter.NEW_DRAFT";
    private static final int MICROPHONE = 30;
    private static final int NOTIFICATIONS = 31;

    private DraftStore store;
    private SharedPreferences preferences;
    private LinearLayout root;
    private String selectedId;
    private String pendingId;
    private boolean pendingPreview;
    private boolean voice;
    private boolean waitingForOverlay;
    private boolean launching;
    private int launchAttempt;
    private DraftStore.Draft editing;
    private EditText titleField;
    private EditText bodyField;
    private TextView progressLabel;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DraftStore(this);
        preferences = getSharedPreferences("prompter", MODE_PRIVATE);
        selectedId = preferences.getString("selected", "");
        voice = preferences.getBoolean("voice", false);
        if (state != null) {
            pendingId = state.getString("pending");
            pendingPreview = state.getBoolean("preview");
            waitingForOverlay = state.getBoolean("waiting");
            String editingId = state.getString("editing");
            if (editingId != null) {
                DraftStore.Draft saved = store.find(editingId);
                if (saved == null) saved = new DraftStore.Draft(editingId, "", "");
                showEditor(saved, state.getString("editorTitle"), state.getString("editorBody"));
                return;
            }
        }
        if (consumeNewDraft(getIntent())) newDraft();
        else showHome();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!consumeNewDraft(intent)) return;
        if (editing == null) newDraft();
        else leaveEditor(this::newDraft);
    }

    private static boolean consumeNewDraft(Intent intent) {
        if (!NEW_DRAFT.equals(intent.getAction())) return false;
        intent.setAction(null);
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pending", pendingId);
        state.putBoolean("preview", pendingPreview);
        state.putBoolean("waiting", waitingForOverlay);
        if (editing != null) {
            state.putString("editing", editing.id());
            state.putString("editorTitle", titleField.getText().toString());
            state.putString("editorBody", bodyField.getText().toString());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForOverlay) {
            waitingForOverlay = false;
            if (Settings.canDrawOverlays(this)) requestLaunchPermissions();
            else toast("已返回。开启悬浮窗时可再次授权。");
        } else if (editing == null && pendingId == null) {
            String active = preferences.getString("selected", selectedId);
            boolean savedVoice = preferences.getBoolean("voice", false);
            if (!active.equals(selectedId) || voice != savedVoice) {
                selectedId = active;
                voice = savedVoice;
                showHome();
            } else if (progressLabel != null) updateReadingProgress(store.find(selectedId));
        }
    }

    private void createPage() {
        progressLabel = null;
        root = Ui.column(this);
        root.setBackground(Ui.atmosphere(this));
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                var ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(root);
        root.requestApplyInsets();
    }

    private void showHome() {
        editing = null;
        createPage();
        List<DraftStore.Draft> drafts = store.all();
        DraftStore.Draft selected = store.find(selectedId);
        if (selected == null && !drafts.isEmpty()) selected = drafts.get(0);
        if (selected != null) selectedId = selected.id();

        addHomeNavigation();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(19), dp(18), dp(19), dp(14));
        scroll.addView(content);
        addHomeHeader(content);
        Ui.space(content, 17);

        if (selected == null) {
            TextView empty = Ui.text(this, "新建台本后即可开始提词", 16, Ui.MUTED, false);
            empty.setPadding(dp(20), dp(24), dp(20), dp(24));
            empty.setBackground(Ui.shape(this, Ui.CARD, 24, Ui.WHITE_EDGE));
            content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            addFeatured(content, selected);
            Ui.space(content, 19);
            LinearLayout heading = Ui.row(this);
            View marker = new View(this);
            marker.setBackground(Ui.shape(this, 0xffae7def, 2, Color.TRANSPARENT));
            heading.addView(marker, new LinearLayout.LayoutParams(dp(3), dp(15)));
            TextView label = Ui.text(this, "历史台本", 18, Ui.INK, true);
            label.setPadding(dp(8), 0, 0, 0);
            heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(Ui.text(this, drafts.size() + " 篇", 12, Ui.MUTED, false));
            content.addView(heading);
            Ui.space(content, 9);
            for (DraftStore.Draft draft : drafts) addDraftRow(content, draft);
        }
    }

    private void addHomeNavigation() {
        LinearLayout header = Ui.row(this);
        header.setPadding(dp(19), dp(10), dp(19), dp(3));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_app);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(logo, new LinearLayout.LayoutParams(dp(37), dp(37)));
        TextView name = Ui.text(this, "顺言", 19, Ui.INK, true);
        name.setPadding(dp(7), 0, 0, 0);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView create = Ui.primaryAction(this, "＋ 新建台本", this::newDraft);
        header.addView(create, new LinearLayout.LayoutParams(-2, dp(46)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addHomeHeader(LinearLayout content) {
        content.addView(Ui.text(this, "从容表达", 38, Ui.INK, true));
        Ui.space(content, 4);
        content.addView(Ui.text(this, "专业提词助手，拍摄演讲更自信", 14, Ui.MUTED, false));
    }

    private void addFeatured(LinearLayout content, DraftStore.Draft draft) {
        LinearLayout card = Ui.column(this);
        card.setPadding(dp(15), dp(12), dp(15), dp(14));
        card.setBackground(Ui.shape(this, Ui.CARD, 29, Ui.WHITE_EDGE));

        LinearLayout heading = Ui.row(this);
        TextView badge = Ui.text(this, "当前台本", 12, Ui.ACCENT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(Ui.shape(this, Ui.LAVENDER, 14, Color.TRANSPARENT));
        heading.addView(badge);
        heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView edit = Ui.pillAction(this, "编辑 ›", Color.TRANSPARENT, Ui.INK, () -> showEditor(draft, null, null));
        heading.addView(edit, new LinearLayout.LayoutParams(-2, dp(38)));
        card.addView(heading);

        TextView title = Ui.text(this, draft.label(), 22, Ui.INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);
        Ui.space(card, 7);
        TextView intro = Ui.text(this, draft.body().trim().replaceAll("[\\r\\n]+", "\n"), 14, Ui.MUTED, false);
        intro.setMaxLines(3);
        intro.setEllipsize(TextUtils.TruncateAt.END);
        intro.setLineSpacing(dp(3), 1);
        card.addView(intro);
        Ui.space(card, 11);

        progressLabel = Ui.text(this, "", 12, Ui.MUTED, true);
        progressLabel.setGravity(Gravity.CENTER);
        progressLabel.setPadding(dp(7), dp(7), dp(7), dp(7));
        progressLabel.setBackground(Ui.shape(this, 0xffede5fa, 18, Ui.WHITE_EDGE));
        card.addView(progressLabel, new LinearLayout.LayoutParams(-1, -2));
        updateReadingProgress(draft);
        Ui.space(card, 10);

        LinearLayout modes = Ui.row(this);
        addModeOption(modes, "匀速提词", "按设定速度滚动", false);
        addModeOption(modes, "离线跟读", "跟随你的朗读", true);
        updateModeOptions(modes);
        card.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        Ui.space(card, 10);

        LinearLayout actions = Ui.row(this);
        TextView preview = Ui.pillAction(this, "预览", Ui.LAVENDER, Ui.ACCENT, () -> beginLaunch(draft, true));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(72), dp(49));
        previewParams.rightMargin = dp(8);
        actions.addView(preview, previewParams);
        TextView begin = Ui.primaryAction(this, "▶  开始提词", () -> beginLaunch(draft, false));
        begin.setTextSize(17);
        actions.addView(begin, new LinearLayout.LayoutParams(0, dp(49), 1));
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addModeOption(LinearLayout modes, String title, String subtitle, boolean nextVoice) {
        LinearLayout option = Ui.row(this);
        option.setPadding(dp(8), dp(6), dp(5), dp(6));
        View icon;
        if (nextVoice) {
            ImageView microphone = new ImageView(this);
            microphone.setImageResource(R.drawable.ic_microphone);
            microphone.setColorFilter(0xff187b88);
            microphone.setPadding(dp(6), dp(6), dp(6), dp(6));
            icon = microphone;
        } else {
            TextView play = Ui.text(this, "▶", 16, Ui.ACCENT, true);
            play.setGravity(Gravity.CENTER);
            icon = play;
        }
        icon.setBackground(Ui.shape(this, nextVoice ? 0xffbcebf2 : 0xffdfcbfc, 17, Color.TRANSPARENT));
        option.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout labels = Ui.column(this);
        labels.setPadding(dp(7), 0, 0, 0);
        labels.addView(Ui.text(this, title, 13, Ui.INK, true));
        Ui.space(labels, 3);
        labels.addView(Ui.text(this, subtitle, 10, Ui.MUTED, false));
        option.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        option.setContentDescription(title + "，" + subtitle);
        option.setFocusable(true);
        option.setOnClickListener(ignored -> {
            voice = nextVoice;
            preferences.edit().putBoolean("voice", voice).apply();
            updateModeOptions(modes);
            updateReadingProgress(store.find(selectedId));
        });
        LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
        if (nextVoice) cell.leftMargin = dp(7);
        modes.addView(option, cell);
        option.setMinimumHeight(dp(54));
    }

    private void updateModeOptions(LinearLayout modes) {
        for (int i = 0; i < modes.getChildCount(); i++) {
            View option = modes.getChildAt(i);
            boolean selected = (i == 1) == voice;
            int color = i == 1 ? Ui.AQUA : Ui.LAVENDER;
            option.setSelected(selected);
            option.setBackground(Ui.shape(this, color, 17, selected ? 0xff9c6ce5 : Ui.WHITE_EDGE));
        }
    }

    private void updateReadingProgress(DraftStore.Draft draft) {
        if (draft == null) return;
        int length = draft.body().length();
        int offset = Math.max(0, Math.min(length, store.position(draft.id(), voice)));
        int percent = length == 0 ? 0 : Math.round(100f * offset / length);
        progressLabel.setText(scriptStats(draft.body()) + (percent > 0 ? "   ·   已读 " + percent + "%" : ""));
    }

    private String scriptStats(CharSequence body) {
        String text = body.toString();
        int characters = text.codePointCount(0, text.length());
        int speed = Math.max(60, preferences.getInt("speed", 180));
        long spokenCharacters = text.codePoints().filter(Character::isLetterOrDigit).count();
        long seconds = (long) Math.ceil(spokenCharacters * 60.0 / speed);
        String duration = seconds < 60 ? seconds + " 秒" : seconds / 60 + " 分 " + seconds % 60 + " 秒";
        return characters + " 字   ·   约 " + duration;
    }

    private void addDraftRow(LinearLayout content, DraftStore.Draft draft) {
        boolean selected = draft.id().equals(selectedId);
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(10), dp(6), dp(6), dp(6));
        row.setBackground(Ui.shape(this, 0xcbfaf9ff, 18, Ui.WHITE_EDGE));
        ImageView icon = new ImageView(this);
        icon.setBackground(Ui.shape(this, selected ? Ui.LAVENDER : Ui.AQUA, 11, Color.TRANSPARENT));
        icon.setImageResource(R.drawable.ic_notification);
        icon.setColorFilter(selected ? Ui.ACCENT : 0xff187b88);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout labels = Ui.column(this);
        labels.setPadding(dp(10), dp(3), 0, dp(3));
        TextView name = Ui.text(this, draft.label(), 14, Ui.INK, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        Ui.space(labels, 3);
        labels.addView(Ui.text(this, scriptStats(draft.body()), 11, Ui.MUTED, false));
        labels.setSelected(selected);
        labels.setFocusable(true);
        labels.setOnClickListener(ignored -> {
            selectedId = draft.id();
            preferences.edit().putString("selected", selectedId).apply();
            showHome();
        });
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(Ui.pillAction(this, "编辑", Color.TRANSPARENT, Ui.ACCENT, () -> showEditor(draft, null, null)));
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, -2);
        placement.bottomMargin = dp(7);
        content.addView(row, placement);
    }

    private void newDraft() {
        showEditor(store.blank(), null, null);
    }

    private void showEditor(DraftStore.Draft draft, String title, String body) {
        editing = draft;
        createPage();
        boolean saved = store.find(draft.id()) != null;
        LinearLayout page = Ui.column(this);
        page.setPadding(dp(16), dp(9), dp(16), dp(10));
        root.addView(page, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout toolbar = Ui.row(this);
        TextView back = Ui.pillAction(this, "‹", Ui.CARD, Ui.INK, this::leaveEditor);
        back.setTextSize(23);
        back.setContentDescription("返回");
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(49), dp(44)));
        TextView heading = Ui.text(this, saved ? "编辑台本" : "新建台本", 18, Ui.INK, true);
        heading.setGravity(Gravity.CENTER);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(Ui.primaryAction(this, "保存", this::saveEditor), new LinearLayout.LayoutParams(-2, dp(43)));
        page.addView(toolbar);
        Ui.space(page, 14);

        ScrollView editorScroll = new ScrollView(this);
        editorScroll.setFillViewport(true);
        page.addView(editorScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout sheet = Ui.column(this);
        sheet.setPadding(dp(15), dp(8), dp(15), dp(9));
        sheet.setBackground(Ui.shape(this, saved ? 0xdffbfbff : 0x70faf9ff, 24, Ui.WHITE_EDGE));
        titleField = editorField(title == null ? draft.title() : title, "标题（选填）", 21);
        titleField.setSingleLine();
        titleField.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        sheet.addView(titleField, new LinearLayout.LayoutParams(-1, dp(48)));
        View divider = new View(this);
        divider.setBackgroundColor(0xffd7cbed);
        sheet.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout bodyTools = Ui.row(this);
        bodyTools.addView(Ui.text(this, "正文", 13, Ui.MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        bodyTools.addView(Ui.pillAction(this, "粘贴正文", Ui.LAVENDER, Ui.ACCENT, this::pasteScript));
        bodyTools.addView(Ui.pillAction(this, "复制", Color.TRANSPARENT, Ui.ACCENT, this::copyScript));
        sheet.addView(bodyTools, new LinearLayout.LayoutParams(-1, -2));
        bodyField = editorField(body == null ? draft.body() : body, "输入或粘贴台本文字", 16);
        bodyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bodyField.setGravity(Gravity.TOP | Gravity.START);
        bodyField.setLineSpacing(dp(5), 1);
        bodyField.setMinHeight(dp(144));
        sheet.addView(bodyField, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView stats = Ui.text(this, scriptStats(bodyField.getText()), 12, Ui.MUTED, false);
        stats.setGravity(Gravity.CENTER);
        stats.setPadding(dp(6), dp(7), dp(6), dp(7));
        stats.setBackground(Ui.shape(this, 0x88eae1f8, 16, Color.TRANSPARENT));
        sheet.addView(stats, new LinearLayout.LayoutParams(-1, -2));
        bodyField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                stats.setText(scriptStats(s));
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        editorScroll.addView(sheet, new ScrollView.LayoutParams(-1, -2));

        if (saved) {
            Ui.space(page, 8);
            page.addView(Ui.pillAction(this, "删除台本", Ui.CARD, 0xffa12d45,
                    () -> new AlertDialog.Builder(this).setTitle("删除这个台本？")
                        .setMessage(draft.label()).setNegativeButton("取消", null)
                        .setPositiveButton("删除", (dialog, which) -> {
                            store.delete(draft.id());
                            hideKeyboard();
                            showHome();
                        }).show()), new LinearLayout.LayoutParams(-1, dp(46)));
        }
        root.requestFocus();
    }

    private EditText editorField(String value, String hint, int size) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(size);
        field.setTextColor(Ui.INK);
        field.setHintTextColor(0xff786b94);
        field.setBackgroundColor(Color.TRANSPARENT);
        field.setPadding(0, dp(11), 0, dp(8));
        return field;
    }

    private void pasteScript() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast("剪贴板没有文字");
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (TextUtils.isEmpty(text)) {
            toast("剪贴板没有文字");
            return;
        }
        int start = bodyField.getSelectionStart(), end = bodyField.getSelectionEnd();
        if (!bodyField.hasFocus() || start < 0 || end < 0) start = end = bodyField.length();
        bodyField.requestFocus();
        bodyField.getText().replace(Math.min(start, end), Math.max(start, end), text.toString());
    }

    private void copyScript() {
        if (bodyField.length() == 0) {
            toast("台本没有正文");
            return;
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("顺言台本", bodyField.getText().toString()));
        if (Build.VERSION.SDK_INT < 33) toast("已复制台本");
    }

    private void saveEditor() {
        saveEditor(this::showHome);
    }

    private void saveEditor(Runnable next) {
        String body = bodyField.getText().toString();
        if (body.trim().isEmpty()) {
            bodyField.requestFocus();
            bodyField.setError("请先填写稿件");
            return;
        }
        selectedId = editing.id();
        store.put(new DraftStore.Draft(selectedId, titleField.getText().toString().trim(), body));
        preferences.edit().putString("selected", selectedId).apply();
        hideKeyboard();
        next.run();
    }

    private void leaveEditor() {
        leaveEditor(this::showHome);
    }

    private void leaveEditor(Runnable next) {
        if (!editing.title().equals(titleField.getText().toString()) || !editing.body().equals(bodyField.getText().toString())) {
            new AlertDialog.Builder(this).setTitle("保存台本修改？")
                    .setNeutralButton("继续编辑", null)
                    .setNegativeButton("放弃修改", (dialog, which) -> { hideKeyboard(); next.run(); })
                    .setPositiveButton("保存", (dialog, which) -> saveEditor(next)).show();
        } else {
            hideKeyboard();
            next.run();
        }
    }

    @Override public void onBackPressed() {
        if (editing != null) leaveEditor();
        else super.onBackPressed();
    }

    private void beginLaunch(DraftStore.Draft draft, boolean preview) {
        if (launching) return;
        pendingId = draft.id();
        pendingPreview = preview;
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("允许显示悬浮窗")
                    .setMessage("在系统设置中找到顺言提词器并启用权限。返回后会打开台本。")
                    .setNegativeButton("暂不开启", null)
                    .setPositiveButton("前往设置", (dialog, which) -> {
                        waitingForOverlay = true;
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        try { startActivity(intent); }
                        catch (ActivityNotFoundException e) {
                            waitingForOverlay = false;
                            toast("无法打开悬浮窗设置");
                        }
                    }).show();
            return;
        }
        requestLaunchPermissions();
    }

    private void requestLaunchPermissions() {
        if (pendingId == null || isFinishing()) return;
        if (voice && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("askedNotification", false)) {
            preferences.edit().putBoolean("askedNotification", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            return;
        }
        launchOverlay();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == MICROPHONE) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) requestLaunchPermissions();
            else toast("离线跟读需要麦克风权限。匀速模式可直接使用。");
        } else if (request == NOTIFICATIONS) {
            launchOverlay();
        }
    }

    private void launchOverlay() {
        if (pendingId == null || launching || isFinishing()) return;
        String id = pendingId;
        boolean preview = pendingPreview;
        pendingId = null;
        pendingPreview = false;
        launching = true;
        int attempt = ++launchAttempt;
        Handler handler = new Handler(Looper.getMainLooper());
        ResultReceiver receiver = new ResultReceiver(handler) {
            @Override protected void onReceiveResult(int result, Bundle data) {
                if (attempt != launchAttempt || isDestroyed()) return;
                launching = false;
                if (result != 1) toast(data.getString("message", "悬浮窗未能开启，请重试。"));
                else if (!preview) moveTaskToBack(true);
            }
        };
        Intent intent = new Intent(this, OverlayService.class)
                .setAction(OverlayService.OPEN).putExtra("draft", id)
                .putExtra("voice", voice).putExtra("preview", preview).putExtra("receiver", receiver);
        try {
            startForegroundService(intent);
            handler.postDelayed(() -> {
                if (launching && attempt == launchAttempt) {
                    launching = false;
                    launchAttempt++;
                    toast("启动悬浮窗超时，请重试。");
                }
            }, 7000);
        } catch (RuntimeException e) {
            launching = false;
            toast("系统未能启动悬浮窗，请重试。");
        }
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private int dp(float value) { return Ui.dp(this, value); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
