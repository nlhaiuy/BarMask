package com.example.blackbarmask;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private static final int BG = 0xFFF3F4F8;
    private static final int ACCENT = 0xFF4F46E5;
    private static final int ACCENT_SOFT = 0xFFE0E7FF;
    private static final int TEXT = 0xFF111827;
    private static final int SUB = 0xFF6B7280;
    private static final int CHIP = 0xFFEEF0F6;
    private static final int OFF_BTN = 0xFF374151;

    private SharedPreferences sp;
    private float density;
    private TextView btn, appsTv, banner, statusTv;

    // 权限引导
    private static final String[] PERM_NAMES = {"悬浮窗", "通知", "后台运行", "无障碍"};
    private boolean guiding = false, awaitingReturn = false;
    private int step = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        // 顶部：图标 + 标题；中间：大圆形电源键 + 状态；下面：设置卡片
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(16), dp(20), dp(4));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("白边");
        title.setTextSize(22);
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

        // ---- 总开关：大圆键 ----
        btn = new TextView(this);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(20);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setElevation(dp(8));
        btn.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(132), dp(132));
        flp.gravity = Gravity.CENTER_HORIZONTAL;
        flp.topMargin = dp(8);
        root.addView(btn, flp);

        statusTv = new TextView(this);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setTextSize(14);
        statusTv.setTextColor(SUB);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(14);
        slp.bottomMargin = dp(24);
        root.addView(statusTv, slp);

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

        root.addView(modeCard());
        root.addView(displayCard());
        root.addView(offsetCard());

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
        appsTv = new TextView(this);
        appsTv.setTextSize(15);
        appsTv.setTextColor(ACCENT);
        appsTv.setTypeface(Typeface.DEFAULT_BOLD);
        appsTv.setPadding(0, dp(14), 0, 0);
        appsTv.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        c.addView(appsTv);
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

    private LinearLayout offsetCard() {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = label("宽度微调", 0);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(this);
        value.setTextSize(14);
        value.setTextColor(SUB);
        head.addView(value);
        c.addView(head);

        SeekBar sb = new SeekBar(this);
        sb.setMax(200); // 对应 -100dp ~ +100dp
        int off = sp.getInt(MaskService.KEY_OFFSET, 0);
        sb.setProgress(off + 100);
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        value.setText(offsetText(off));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                int o = p - 100;
                value.setText(offsetText(o));
                sp.edit().putInt(MaskService.KEY_OFFSET, o).apply();
                if (user) changed();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        c.addView(sb);
        return c;
    }

    private String offsetText(int o) {
        return (o > 0 ? "+" : "") + o + " dp";
    }

    // ---------------- 状态刷新 ----------------

    /** 总开关按钮：开着显示“关闭白边”，关着显示“开启白边”。 */
    private void updateUi() {
        boolean on = AutoMode.isMaster(this);
        btn.setText(on ? "已开启" : "已关闭");
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? ACCENT : 0xFF9CA3AF);
        btn.setBackground(g);
        statusTv.setText(on
                ? (AutoMode.isEnabled(this) ? "白名单软件横屏时自动盖白边" : "横屏时始终盖白边")
                : "点击圆键开启");
        refreshPerms();
    }

    /** 白名单软件入口（仅白名单模式显示）。 */
    private void refreshAuto() {
        if (appsTv == null) return;
        boolean whitelist = AutoMode.isEnabled(this);
        appsTv.setVisibility(whitelist ? View.VISIBLE : View.GONE);
        appsTv.setText("白名单软件 · 已选 " + installedSelectedCount() + " 个  ›");
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
        c.setBackground(round(Color.WHITE, 16));
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

    /** 一排“药丸”单选按钮，每个等宽。 */
    private void addChips(LinearLayout parent, String[] labels, int perRow, int selected, OnPick cb) {
        final TextView[] chips = new TextView[labels.length];
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < labels.length; i++) {
            TextView t = new TextView(this);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(16);
            t.setPadding(0, dp(12), 0, dp(12));
            chips[i] = t;
            final int idx = i;
            t.setOnClickListener(x -> {
                styleChips(chips, idx);
                cb.pick(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(t, lp);
        }
        styleChips(chips, selected);
    }

    private void styleChips(TextView[] chips, int sel) {
        for (int i = 0; i < chips.length; i++) {
            chips[i].setBackground(round(i == sel ? ACCENT : CHIP, 12));
            chips[i].setTextColor(i == sel ? Color.WHITE : TEXT);
        }
    }
}
