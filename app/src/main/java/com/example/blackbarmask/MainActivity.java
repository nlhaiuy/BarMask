package com.example.blackbarmask;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String[] MODE_NAMES = {"白名单", "全局"};
    private static final String[] RATIO_NAMES = {"16:9", "4:3"};
    private static final float[] RATIO_VALUES = {16f / 9f, 4f / 3f};
    private static final String[] STRENGTH_NAMES = {"标准", "加强"};
    private static final int[] STRENGTH_LAYERS = {1, 3};

    // 颜色按系统浅色 / 深色模式取值（Material 3 色调）
    private boolean night;
    private int BG, CARD, ACCENT, ON_ACCENT, ACCENT_SOFT, SEL_TEXT, TEXT, SUB, CHIP, OUTLINE,
            OFF_BTN, ON_OFF_BTN;

    private void initColors() {
        night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            BG = 0xFF121218; CARD = 0xFF1E1E27; ACCENT = 0xFFBDBFFF; ON_ACCENT = 0xFF1E1B6B;
            ACCENT_SOFT = 0xFF3A3C6E; SEL_TEXT = 0xFFE0E0FF; TEXT = 0xFFE5E1E9; SUB = 0xFF9E9DAC;
            CHIP = 0xFF2B2B36; OUTLINE = 0xFF4A4A56; OFF_BTN = 0xFF3A3A47; ON_OFF_BTN = 0xFFE5E1E9;
        } else {
            BG = 0xFFF6F5FB; CARD = 0xFFFFFFFF; ACCENT = 0xFF4F46E5; ON_ACCENT = 0xFFFFFFFF;
            ACCENT_SOFT = 0xFFE0E7FF; SEL_TEXT = 0xFF312E81; TEXT = 0xFF1B1B21; SUB = 0xFF5F6070;
            CHIP = 0xFFEEF0F6; OUTLINE = 0xFFC7C5D0; OFF_BTN = 0xFF3F3F4A; ON_OFF_BTN = 0xFFFFFFFF;
        }
    }

    private SharedPreferences sp;
    private float density;
    private TextView btn, appsTv, banner, statusTv;
    private Preview preview;
    private LinearLayout appsRow, iconBox;

    // 权限引导
    private static final String[] PERM_NAMES = {"悬浮窗", "通知", "后台运行", "无障碍"};
    private boolean guiding = false, awaitingReturn = false;
    private int step = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        initColors();

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(night ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        // 顶部：图标 + 标题；中间：大圆形电源键 + 状态；下面：设置卡片
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(24), dp(20), dp(24), dp(8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = new TextView(this);
        title.setText("白边");
        title.setTextSize(30);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        head.addView(title);
        page.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 缺权限时才出现的提示条 ----
        banner = new TextView(this);
        banner.setTextSize(14);
        banner.setTextColor(ACCENT);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setPadding(dp(16), dp(14), dp(16), dp(14));
        banner.setBackground(round(ACCENT_SOFT, 14));
        banner.setOnClickListener(v -> startGuide());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(12);
        root.addView(banner, blp);

        root.addView(previewCard());
        root.addView(modeCard());
        root.addView(displayCard());
        root.addView(offsetCard());

        // ---- 底部：状态说明 + 通栏总开关（拇指最容易按到）----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(24), dp(4), dp(24), dp(24));
        statusTv = new TextView(this);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setTextSize(12);
        statusTv.setTextColor(SUB);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(10);
        bottom.addView(statusTv, slp);
        btn = new TextView(this);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(18);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setElevation(dp(8));
        btn.setOnClickListener(v -> toggle());
        bottom.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        page.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(page);
        updateUi();
        refreshAuto();

        // 第一次打开：自动带你把需要的权限一次设置完
        if (!sp.getBoolean("guided", false)) {
            sp.edit().putBoolean("guided", true).apply();
            if (anyMissing()) startGuide();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (guiding && step >= 0) awaitingReturn = true; // 去系统设置页了，回来时继续下一步
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (guiding && awaitingReturn) {
            awaitingReturn = false;
            if (required(step) && applicable(step) && missing(step)) {
                int failed = step;
                guiding = false;
                step = -1;
                if (failed == 3) showRestrictedHelp(); // 多半是“无障碍”被系统置灰了
            } else {
                advance();
            }
        }
        updateUi();
        refreshAuto();
    }

    // ---------------- 卡片 ----------------

    private LinearLayout modeCard() {
        LinearLayout c = card();
        c.addView(label("模式", 0));
        addChips(c, MODE_NAMES, 2, AutoMode.isEnabled(this) ? 0 : 1, pos -> {
            boolean whitelist = pos == 0;
            sp.edit().putBoolean(AutoMode.KEY_AUTO, whitelist).apply();
            AutoMode.evaluate(this);   // 切换后立刻按新模式显示 / 隐藏
            refreshAuto();
            // 白名单模式要靠无障碍识别前台软件
            if (whitelist && AutoMode.isMaster(this) && !AutoMode.isAccessibilityEnabled(this)) startGuide();
        });
        appsRow = new LinearLayout(this);
        appsRow.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.setGravity(Gravity.CENTER_VERTICAL);
        appsRow.setPadding(dp(14), 0, dp(14), 0);
        appsRow.setBackground(round(CHIP, 14));
        appsRow.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        iconBox = new LinearLayout(this);
        iconBox.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.addView(iconBox);
        appsTv = new TextView(this);
        appsTv.setTextSize(15);
        appsTv.setTextColor(ACCENT);
        appsTv.setTypeface(Typeface.DEFAULT_BOLD);
        appsRow.addView(appsTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        rlp.topMargin = dp(12);
        c.addView(appsRow, rlp);
        return c;
    }

    private LinearLayout displayCard() {
        LinearLayout c = card();
        c.addView(label("视频比例", 0));
        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int selRatio = -1;
        for (int i = 0; i < RATIO_VALUES.length; i++) {
            if (Math.abs(RATIO_VALUES[i] - cur) < 0.001f) selRatio = i;
        }
        if (selRatio < 0) { // 以前保存过别的比例，重置为默认
            selRatio = 0;
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[0]).apply();
        }
        addChips(c, RATIO_NAMES, 2, selRatio, pos -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[pos]).apply();
            changed();
        });

        c.addView(label("遮盖强度", 16));
        int curLayers = sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS);
        addChips(c, STRENGTH_NAMES, 2, curLayers >= 2 ? 1 : 0, pos -> {
            sp.edit().putInt(MaskService.KEY_LAYERS, STRENGTH_LAYERS[pos]).apply();
            changed();
        });
        return c;
    }

    private boolean offsetBusy = false;

    private LinearLayout offsetCard() {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label("宽度微调", 0);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 可直接用键盘输入数值
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER);
        input.setTextSize(15);
        input.setTextColor(TEXT);
        input.setPadding(dp(8), dp(6), dp(8), dp(6));
        input.setBackground(round(CHIP, 10));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        TextView minus = stepButton("−");
        TextView plus = stepButton("+");
        head.addView(minus);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                dp(60), ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.leftMargin = dp(6);
        ilp.rightMargin = dp(6);
        head.addView(input, ilp);
        head.addView(plus);
        TextView unit = new TextView(this);
        unit.setText(" dp");
        unit.setTextSize(14);
        unit.setTextColor(SUB);
        head.addView(unit);

        TextView reset = new TextView(this);
        reset.setText("重置");
        reset.setTextSize(13);
        reset.setTextColor(ACCENT);
        reset.setTypeface(Typeface.DEFAULT_BOLD);
        reset.setPadding(dp(14), dp(7), dp(14), dp(7));
        reset.setBackground(round(ACCENT_SOFT, 16));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(10);
        head.addView(reset, rlp);
        c.addView(head);

        // 宽条滑块
        SeekBar sb = new SeekBar(this);
        sb.setMax(200); // 对应 -100dp ~ +100dp
        GradientDrawable track = round(CHIP, 9);
        track.setSize(-1, dp(18));
        GradientDrawable fill = round(ACCENT, 9);
        fill.setSize(-1, dp(18));
        LayerDrawable ld = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        sb.setProgressDrawable(ld);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(CARD);
        thumb.setStroke(dp(4), ACCENT);
        thumb.setSize(dp(30), dp(30));
        sb.setThumb(thumb);
        sb.setThumbOffset(0);
        sb.setSplitTrack(false);
        sb.setPadding(dp(15), 0, dp(15), 0);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        slp.topMargin = dp(8);
        c.addView(sb, slp);
        c.addView(new Ticks(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));

        int off = sp.getInt(MaskService.KEY_OFFSET, 0);
        sb.setProgress(off + 100);
        input.setText(String.valueOf(off));

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user || offsetBusy) return;
                int o = p - 100;
                sp.edit().putInt(MaskService.KEY_OFFSET, o).apply();
                offsetBusy = true;
                input.setText(String.valueOf(o));
                offsetBusy = false;
                changed();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence x, int a, int b, int d) { }
            @Override public void afterTextChanged(Editable e) {
                if (offsetBusy) return;
                String t = e.toString().trim();
                if (t.isEmpty() || t.equals("-") || t.equals("+")) return;
                int o;
                try { o = Integer.parseInt(t); } catch (NumberFormatException ex) { return; }
                o = Math.max(-100, Math.min(100, o));
                sp.edit().putInt(MaskService.KEY_OFFSET, o).apply();
                offsetBusy = true;
                sb.setProgress(o + 100);
                offsetBusy = false;
                changed();
            }
        });
        // 输入完成/失焦时，把超出范围或为空的内容规整回有效值
        Runnable normalize = () -> {
            offsetBusy = true;
            input.setText(String.valueOf(sp.getInt(MaskService.KEY_OFFSET, 0)));
            offsetBusy = false;
        };
        input.setOnEditorActionListener((v, id, ev) -> {
            normalize.run();
            input.clearFocus();
            return false;
        });
        input.setOnFocusChangeListener((v, has) -> { if (!has) normalize.run(); });

        java.util.function.IntConsumer setOff = o0 -> {
            int o = Math.max(-100, Math.min(100, o0));
            sp.edit().putInt(MaskService.KEY_OFFSET, o).apply();
            offsetBusy = true;
            sb.setProgress(o + 100);
            input.setText(String.valueOf(o));
            offsetBusy = false;
            input.clearFocus();
            changed();
        };
        reset.setOnClickListener(v -> setOff.accept(0));
        minus.setOnClickListener(v -> setOff.accept(sp.getInt(MaskService.KEY_OFFSET, 0) - 1));
        plus.setOnClickListener(v -> setOff.accept(sp.getInt(MaskService.KEY_OFFSET, 0) + 1));
        return c;
    }

    // ---------------- 状态刷新 ----------------

    /** 总开关按钮：开着显示“关闭白边”，关着显示“开启白边”。 */
    private void updateUi() {
        boolean on = AutoMode.isMaster(this);
        btn.setText(on ? "关闭白边" : "开启白边");
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                on ? new int[]{OFF_BTN, OFF_BTN}
                   : new int[]{ACCENT, night ? 0xFFD6BCFF : 0xFF7C3AED});
        bg.setCornerRadius(dp(30));
        btn.setBackground(bg);
        btn.setTextColor(on ? ON_OFF_BTN : ON_ACCENT);
        if (preview != null) preview.setOn(on);
        statusTv.setText(on
                ? (AutoMode.isEnabled(this) ? "白名单软件横屏时自动盖白边" : "横屏时始终盖白边")
                : "白边已关闭");
        refreshPerms();
    }

    /** 白名单软件入口（仅白名单模式显示）。 */
    private void refreshAuto() {
        if (appsTv == null) return;
        boolean whitelist = AutoMode.isEnabled(this);
        // 位置和高度保持不变，只是文字渐变替换，界面不跳动
        final String text = whitelist
                ? "白名单软件 · 已选 " + installedSelectedCount() + " 个  ›"
                : "全局模式：横屏时始终盖白边";
        appsRow.setClickable(whitelist);
        iconBox.removeAllViews();
        if (whitelist) {
            PackageManager pm = getPackageManager();
            int shown = 0;
            for (String pkg : AutoMode.selectedApps(this)) {
                if (shown >= 4) break;
                try {
                    ImageView iv = new ImageView(this);
                    iv.setImageDrawable(pm.getApplicationIcon(pkg));
                    GradientDrawable ring = round(CHIP, 20);
                    ring.setStroke(dp(2), CHIP);
                    iv.setBackground(ring);
                    iv.setPadding(dp(2), dp(2), dp(2), dp(2));
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
                    if (shown > 0) ilp.leftMargin = -dp(9);
                    iconBox.addView(iv, ilp);
                    shown++;
                } catch (Exception ignored) { }
            }
            if (shown > 0) {
                TextView sp0 = new TextView(this);
                iconBox.addView(sp0, new LinearLayout.LayoutParams(dp(10), 1));
            }
        }
        if (!text.contentEquals(appsTv.getText())) {
            if (appsTv.getText().length() == 0) {
                appsTv.setText(text);
                appsTv.setTextColor(whitelist ? ACCENT : SUB);
            } else {
                appsTv.animate().cancel();
                appsTv.animate().alpha(0f).setDuration(110).withEndAction(() -> {
                    appsTv.setText(text);
                    appsTv.setTextColor(whitelist ? ACCENT : SUB);
                    appsTv.animate().alpha(1f).setDuration(110).start();
                }).start();
            }
        }
        updateUi();
    }

    private int installedSelectedCount() {
        PackageManager pm = getPackageManager();
        int n = 0;
        for (String pkg : AutoMode.selectedApps(this)) {
            if (pm.getLaunchIntentForPackage(pkg) != null) n++;
        }
        return n;
    }

    /** 有没完成的权限时，显示一条可点的提示条；都齐了就整条隐藏。 */
    private void refreshPerms() {
        if (banner == null) return;
        List<String> lack = new ArrayList<>();
        for (int i = 0; i < PERM_NAMES.length; i++) {
            if (applicable(i) && missing(i)) lack.add(PERM_NAMES[i]);
        }
        if (lack.isEmpty()) {
            banner.setVisibility(View.GONE);
        } else {
            banner.setVisibility(View.VISIBLE);
            banner.setText("还差：" + String.join("、", lack) + "  ·  一键设置  ›");
        }
    }

    // ---------------- 总开关 ----------------

    /** 关闭 → 全部关闭；开启 → 按“白名单 / 全局”模式显示。 */
    private void toggle() {
        if (AutoMode.isMaster(this)) {
            AutoMode.setMaster(this, false);
            updateUi();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            startGuide();
            return;
        }
        AutoMode.setMaster(this, true);
        updateUi();
        if (AutoMode.isEnabled(this) && !AutoMode.isAccessibilityEnabled(this)) startGuide();
    }

    /** 设置有变化：让正在显示的白边立刻刷新。 */
    private void changed() {
        if (preview != null) preview.invalidate();
        if (MaskService.running) {
            startForegroundService(new Intent(this, MaskService.class));
        }
        AutoMode.evaluate(this);
    }

    // ---------------- 权限（一键设置） ----------------

    /** 这一项当前是否需要（通知仅 Android 13+，无障碍仅白名单模式）。 */
    private boolean applicable(int i) {
        if (i == 1) return Build.VERSION.SDK_INT >= 33;
        if (i == 3) return AutoMode.isEnabled(this);
        return true;
    }

    private boolean missing(int i) {
        switch (i) {
            case 0:
                return !Settings.canDrawOverlays(this);
            case 1:
                return Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED;
            case 2:
                return !((PowerManager) getSystemService(POWER_SERVICE))
                        .isIgnoringBatteryOptimizations(getPackageName());
            default:
                return !AutoMode.isAccessibilityEnabled(this);
        }
    }

    private boolean required(int i) {
        return i == 0 || i == 3;
    }

    private boolean anyMissing() {
        for (int i = 0; i < PERM_NAMES.length; i++) {
            if (applicable(i) && missing(i)) return true;
        }
        return false;
    }

    private void launch(int i) {
        try {
            switch (i) {
                case 0:
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    break;
                case 1:
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                    break;
                case 2:
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    break;
                default:
                    // 开启成功后，白边会自己跳回前台，不用手动返回
                    sp.edit().putBoolean(AutoAccessibilityService.KEY_RETURN, true).apply();
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        } catch (Exception e) {
            // 实在打不开专用页面，就去应用信息页，让用户手动设置
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    /** 无障碍没开成功：最常见的原因是系统对“非商店安装”的软件置灰了该选项。 */
    private void showRestrictedHelp() {
        new AlertDialog.Builder(this)
                .setTitle("无障碍选项是灰色的？")
                .setMessage("1. 点“打开应用信息页”\n"
                        + "2. 点右上角 ⋮ 菜单\n"
                        + "3. 选“允许受限制的设置”\n"
                        + "4. 返回白边，再点提示条")
                .setPositiveButton("打开应用信息页", (d, w) -> startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("取消", null)
                .show();
    }

    private void startGuide() {
        guiding = true;
        step = -1;
        advance();
    }

    /** 跳到下一个还没授予的权限；都授予了就结束。 */
    private void advance() {
        for (int i = step + 1; i < PERM_NAMES.length; i++) {
            if (applicable(i) && missing(i)) {
                step = i;
                launch(i);
                return;
            }
        }
        guiding = false;
        step = -1;
        refreshPerms();
    }

    // ---------------- UI 小工具 ----------------

    private int dp(int v) {
        return Math.round(v * density);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 20));
        c.setElevation(dp(night ? 0 : 2));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String text, int topPaddingDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(topPaddingDp), 0, dp(10));
        return t;
    }

    private interface OnPick { void pick(int position); }

    /** M3 分段按钮：一条描边圆角容器，里面等宽分段，选中段填充淡色。 */
    private void addChips(LinearLayout parent, String[] labels, int perRow, int selected, OnPick cb) {
        final TextView[] chips = new TextView[labels.length];
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable frame = round(CARD, 22);
        frame.setStroke(dp(1), OUTLINE);
        row.setBackground(frame);
        row.setClipToOutline(true);
        row.setPadding(dp(1), dp(1), dp(1), dp(1));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) {
                View d = new View(this);
                d.setBackgroundColor(OUTLINE);
                row.addView(d, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
            }
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(15);
            chips[i] = t;
            final int idx = i;
            t.setOnClickListener(x -> {
                if (idx == currentSel(chips)) return;
                styleChips(chips, idx);
                cb.pick(idx);
            });
            row.addView(t, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        styleChips(chips, selected);
    }

    private int currentSel(TextView[] chips) {
        for (int i = 0; i < chips.length; i++) {
            if (Boolean.TRUE.equals(chips[i].getTag())) return i;
        }
        return -1;
    }

    /** 切换选中段：背景色 / 文字色渐变。首次设置不播动画。 */
    private void styleChips(TextView[] chips, int sel) {
        for (int i = 0; i < chips.length; i++) {
            final TextView t = chips[i];
            final boolean on = i == sel;
            Object prev = t.getTag();
            final int toBg = on ? ACCENT_SOFT : CARD, toFg = on ? SEL_TEXT : TEXT;
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (prev == null) {
                t.setBackgroundColor(toBg);
                t.setTextColor(toFg);
            } else if ((Boolean) prev != on) {
                final int fromBg = on ? CARD : ACCENT_SOFT, fromFg = on ? TEXT : SEL_TEXT;
                final ArgbEvaluator ev = new ArgbEvaluator();
                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(220);
                va.addUpdateListener(an -> {
                    float f = (Float) an.getAnimatedValue();
                    t.setBackgroundColor((Integer) ev.evaluate(f, fromBg, toBg));
                    t.setTextColor((Integer) ev.evaluate(f, fromFg, toFg));
                });
                va.start();
            }
            t.setTag(on);
        }
    }

    private TextView stepButton(String sym) {
        TextView t = new TextView(this);
        t.setText(sym);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(18);
        t.setTextColor(ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = round(ACCENT_SOFT, 16);
        t.setBackground(g);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        return t;
    }

    /** 滑块下方的刻度尺：每 10dp 一格，0 和两端更长。 */
    private class Ticks extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Ticks(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float pad = dp(15), w = getWidth() - 2 * pad;
            p.setStrokeWidth(Math.max(1f, density * 1.5f));
            for (int i = 0; i <= 20; i++) {
                float x = pad + w * i / 20f;
                boolean major = i == 0 || i == 10 || i == 20;
                p.setColor(major ? SUB : OUTLINE);
                cv.drawLine(x, 0, x, major ? getHeight() : getHeight() * 0.55f, p);
            }
        }
    }

    // ---------------- 效果预览 ----------------

    private LinearLayout previewCard() {
        LinearLayout c = card();
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        preview = new Preview(this);
        c.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(116)));
        return c;
    }

    /** 横屏手机示意：黑色屏幕 + 视频区域，开启后两侧黑边变白。读取当前比例和微调值。 */
    private class Preview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArgbEvaluator ev = new ArgbEvaluator();
        private float amount = AutoMode.isMaster(MainActivity.this) ? 1f : 0f; // 0=黑边 1=白边
        private ValueAnimator anim;

        Preview(Context c) { super(c); }

        void setOn(boolean on) {
            float to = on ? 1f : 0f;
            if (anim != null) anim.cancel();
            if (!isLaidOut()) { amount = to; invalidate(); return; }
            anim = ValueAnimator.ofFloat(amount, to);
            anim.setDuration(260);
            anim.addUpdateListener(a -> { amount = (Float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        @Override protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight();
            DisplayMetrics m = getResources().getDisplayMetrics();
            float longPx = Math.max(m.widthPixels, m.heightPixels);
            float shortPx = Math.min(m.widthPixels, m.heightPixels);
            float aspect = Math.max(16f / 9f, Math.min(2.4f, longPx / shortPx));
            float sw = Math.min(W - dp(8), (H - dp(8)) * aspect), sh = sw / aspect;
            float l = (W - sw) / 2f, t = (H - sh) / 2f, r = l + sw, b = t + sh;
            float scale = sw / (longPx / m.density);

            // 机身
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(night ? 0xFF3A3A47 : 0xFF2B2B33);
            cv.drawRoundRect(new RectF(l - dp(4), t - dp(4), r + dp(4), b + dp(4)), dp(14), dp(14), paint);

            Path clip = new Path();
            clip.addRoundRect(new RectF(l, t, r, b), dp(10), dp(10), Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            paint.setColor(0xFF000000);
            cv.drawRect(l, t, r, b, paint);

            float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
            float vw = Math.min(sw, sh * ratio);
            float vl = l + (sw - vw) / 2f;
            paint.setColor(0xFF4B5563);
            cv.drawRect(vl, t, vl + vw, b, paint);
            // 播放三角
            paint.setColor(0xB3FFFFFF);
            float cx = l + sw / 2f, cy = t + sh / 2f, ts = sh * 0.16f;
            Path tri = new Path();
            tri.moveTo(cx - ts * 0.6f, cy - ts);
            tri.lineTo(cx - ts * 0.6f, cy + ts);
            tri.lineTo(cx + ts, cy);
            tri.close();
            cv.drawPath(tri, paint);

            // 两侧遮罩，宽度算法与真实遮罩一致
            float bar = (sw - sh * ratio) / 2f + sp.getInt(MaskService.KEY_OFFSET, 0) * density * scale;
            bar = Math.max(0f, Math.min(bar, sw / 2f));
            paint.setColor((Integer) ev.evaluate(amount, 0xFF000000, 0xFFFFFFFF));
            cv.drawRect(l, t, l + bar, b, paint);
            cv.drawRect(r - bar, t, r, b, paint);
            cv.restore();
        }
    }
}
