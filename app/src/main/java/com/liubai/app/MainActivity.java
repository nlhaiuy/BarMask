package com.liubai.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 留白主页：浅蓝白底，顶部是演示屏，下面一张白卡放全部设置，底部一个大开关。 */
public class MainActivity extends Activity {
    private static final float[] RATIO_VALUES = {16f / 9f, 4f / 3f};
    private static final int[] STRENGTH_LAYERS = {1, 3};

    private SharedPreferences sp;
    private float density;
    private Vibrator vib;

    private View dot;
    private LinearLayout banner;
    private TextView statusTv, bannerTv, appsLink, numTv;
    private PreviewView preview;
    private PowerButton power;
    private Slider slider;

    // 权限引导
    private static final String[] PERM_NAMES = {"悬浮窗", "通知", "后台运行", "无障碍"};
    private boolean guiding = false, awaitingReturn = false;
    private int step = -1;

    private int dp(float v) { return Math.round(v * density); }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        Ui.lightWindow(this);

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.BG);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(128));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildHeader(col);

        preview = new PreviewView(this);
        LinearLayout.LayoutParams plp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(22);
        col.addView(preview, plp);

        buildBanner(col);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(6), dp(18), dp(6));
        card.setBackground(Ui.round(Ui.CARD, dp(24)));
        LinearLayout.LayoutParams clp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        col.addView(card, clp);

        buildModeRow(card);
        buildRatioRow(card);
        buildStrengthRow(card);
        buildWidthRow(card);

        // 底部渐隐，让大开关浮在内容上面
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00F3F6FC, Ui.BG}));
        page.addView(fade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130), Gravity.BOTTOM));

        // 总开关
        power = new PowerButton(this);
        power.setOnClickListener(v -> toggle());
        FrameLayout.LayoutParams pwl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.BOTTOM);
        pwl.setMargins(dp(20), 0, dp(20), dp(22));
        page.addView(power, pwl);

        setContentView(page);
        updateUi(false);
        refreshAuto();

        // 第一次打开：自动带你把需要的权限一次设置完
        if (!sp.getBoolean("guided", false)) {
            sp.edit().putBoolean("guided", true).apply();
            if (anyMissing()) startGuide();
        }
    }

    // ---------------- 页面结构 ----------------

    private void buildHeader(LinearLayout col) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("留白");
        title.setTextSize(34);
        title.setTextColor(Ui.INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.04f);
        title.setIncludeFontPadding(false);
        head.addView(title, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1f;

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackground(Ui.round(Ui.CARD, dp(20)));
        chip.setElevation(dp(2));
        dot = new View(this);
        chip.addView(dot, lp(dp(10), dp(10)));
        statusTv = new TextView(this);
        statusTv.setTextSize(13);
        statusTv.setPadding(dp(7), 0, 0, 0);
        chip.addView(statusTv);
        head.addView(chip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 有没完成的权限时，显示一条可点的提示条；都齐了就整条隐藏。 */
    private void buildBanner(LinearLayout col) {
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(13), dp(16), dp(13));
        banner.setBackground(Ui.round(Ui.WARN_BG, dp(16)));
        banner.setOnClickListener(v -> startGuide());

        bannerTv = new TextView(this);
        bannerTv.setTextSize(13);
        bannerTv.setTextColor(Ui.WARN_TX);
        banner.addView(bannerTv, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) bannerTv.getLayoutParams()).weight = 1f;

        TextView go = new TextView(this);
        go.setText("去设置 ›");
        go.setTextSize(13);
        go.setTextColor(Ui.WARN_TX);
        banner.addView(go);

        LinearLayout.LayoutParams blp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        col.addView(banner, blp);
    }

    private TextView link(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Ui.DIM);
        t.setPadding(dp(12), dp(4), 0, dp(4));
        return t;
    }

    private LinearLayout labelRow(String text, TextView right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Ui.INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
        if (right != null) row.addView(right);
        return row;
    }

    /** 卡片里的一行设置：除第一行外，上方有一条细线。 */
    private LinearLayout section(LinearLayout card, boolean first) {
        if (!first) {
            View v = new View(this);
            v.setBackgroundColor(Ui.LINE);
            card.addView(v, lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));
        }
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(0, dp(16), 0, dp(16));
        card.addView(s, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private LinearLayout.LayoutParams below() {
        LinearLayout.LayoutParams p = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        return p;
    }

    private void buildModeRow(LinearLayout card) {
        LinearLayout s = section(card, true);
        appsLink = link("");
        appsLink.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        s.addView(labelRow("生效范围", appsLink));

        Segmented seg = new Segmented(this, new String[]{"白名单", "全局"},
                AutoMode.isEnabled(this) ? 0 : 1);
        seg.setOnSelect(i -> {
            boolean whitelist = i == 0;
            sp.edit().putBoolean(AutoMode.KEY_AUTO, whitelist).apply();
            AutoMode.evaluate(this);   // 切换后立刻按新模式显示 / 隐藏
            refreshAuto();
            // 白名单模式要靠无障碍识别前台软件
            if (whitelist && AutoMode.isMaster(this) && !AutoMode.isAccessibilityEnabled(this)) startGuide();
        });
        s.addView(seg, below());
    }

    private void buildRatioRow(LinearLayout card) {
        LinearLayout s = section(card, false);
        s.addView(labelRow("视频比例", null));

        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int sel = Math.abs(RATIO_VALUES[1] - cur) < 0.001f ? 1 : 0;
        if (sel == 0) sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[0]).apply();
        Segmented seg = new Segmented(this, new String[]{"16:9", "4:3"}, sel, RATIO_VALUES);
        seg.setOnSelect(i -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[i]).apply();
            changed();
        });
        s.addView(seg, below());
    }

    private void buildStrengthRow(LinearLayout card) {
        LinearLayout s = section(card, false);
        s.addView(labelRow("遮盖强度", null));

        int curLayers = sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS);
        Segmented seg = new Segmented(this, new String[]{"标准", "加强"}, curLayers >= 2 ? 1 : 0);
        seg.setOnSelect(i -> {
            sp.edit().putInt(MaskService.KEY_LAYERS, STRENGTH_LAYERS[i]).apply();
            changed();
        });
        s.addView(seg, below());
    }

    private TextView stepButton(String text, int delta) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(22);
        t.setTextColor(Ui.INK);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.CHIP);
        t.setBackground(g);
        t.setClickable(true);
        t.setOnClickListener(v -> setOffset(slider.getValue() + delta));
        return t;
    }

    private void buildWidthRow(LinearLayout card) {
        LinearLayout s = section(card, false);

        TextView reset = link("归零");
        reset.setOnClickListener(v -> setOffset(0));
        s.addView(labelRow("宽度微调", reset));

        slider = new Slider(this);

        LinearLayout fine = new LinearLayout(this);
        fine.setOrientation(LinearLayout.HORIZONTAL);
        fine.setGravity(Gravity.CENTER_VERTICAL);
        fine.addView(stepButton("−", -1), lp(dp(44), dp(44)));

        // 中间的大号数字：点一下可以用键盘输入
        LinearLayout num = new LinearLayout(this);
        num.setOrientation(LinearLayout.HORIZONTAL);
        num.setGravity(Gravity.CENTER);
        numTv = new TextView(this);
        numTv.setTextSize(40);
        numTv.setTextColor(Ui.INK);
        numTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        numTv.setIncludeFontPadding(false);
        num.addView(numTv);
        TextView unit = new TextView(this);
        unit.setText("dp");
        unit.setTextSize(13);
        unit.setTextColor(Ui.DIM);
        unit.setPadding(dp(4), 0, 0, dp(6));
        LinearLayout.LayoutParams ulp = lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.gravity = Gravity.BOTTOM;
        num.addView(unit, ulp);
        num.setOnClickListener(v -> askOffset());
        LinearLayout.LayoutParams nlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.weight = 1f;
        fine.addView(num, nlp);

        fine.addView(stepButton("+", 1), lp(dp(44), dp(44)));
        s.addView(fine, below());

        int off = Math.max(Slider.MIN, Math.min(Slider.MAX, sp.getInt(MaskService.KEY_OFFSET, 0)));
        slider.setValue(off);
        showOffset(off);
        slider.setOnChange((v, user) -> {
            sp.edit().putInt(MaskService.KEY_OFFSET, v).apply();
            showOffset(v);
            if (user) tick(v);
            changed();
        });
        LinearLayout.LayoutParams slp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        s.addView(slider, slp);
    }

    private void showOffset(int v) {
        numTv.setText((v > 0 ? "+" : "") + v);
    }

    private void setOffset(int v) {
        v = Math.max(Slider.MIN, Math.min(Slider.MAX, v));
        sp.edit().putInt(MaskService.KEY_OFFSET, v).apply();
        slider.setValue(v);
        showOffset(v);
        tick(v);
        changed();
    }

    /** 点大号数字，用键盘直接输入。 */
    private void askOffset() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        in.setSingleLine(true);
        in.setText(String.valueOf(slider.getValue()));
        in.setSelectAllOnFocus(true);
        in.setGravity(Gravity.CENTER);
        in.setTextSize(24);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(in);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("宽度微调（-100 ~ 100 dp）")
                .setView(box)
                .setPositiveButton("确定", (dlg, w) -> {
                    try {
                        setOffset(Integer.parseInt(in.getText().toString().trim()));
                    } catch (NumberFormatException ignored) { }
                })
                .setNegativeButton("取消", null)
                .create();
        d.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    // ---------------- 生命周期 ----------------

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
        updateUi(false);
        refreshAuto();
    }

    // ---------------- 状态刷新 ----------------

    /** 开 → 演示屏两侧变白、按钮翻白；关 → 变回黑边。 */
    private void updateUi(boolean animate) {
        boolean on = AutoMode.isMaster(this);
        power.setOn(on, animate);
        preview.setOn(on);
        statusTv.setText(on ? "已开启" : "已关闭");
        statusTv.setTextColor(on ? Ui.INK : Ui.DIM);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (on) {
            g.setColor(Ui.GREEN);
            g.setStroke(dp(2), 0x552EB67D);
        } else {
            g.setColor(0xFFC9C7C0);
        }
        dot.setBackground(g);
        refreshPerms();
    }

    /** 白名单入口：只在白名单模式显示，位置不变所以界面不跳动。 */
    private void refreshAuto() {
        boolean whitelist = AutoMode.isEnabled(this);
        appsLink.setText("白名单应用 " + installedSelectedCount() + " ›");
        appsLink.setEnabled(whitelist);
        appsLink.setClickable(whitelist);
        appsLink.animate().cancel();
        appsLink.animate().alpha(whitelist ? 1f : 0f).setDuration(180).start();
        updateUi(false);
    }

    private int installedSelectedCount() {
        PackageManager pm = getPackageManager();
        int n = 0;
        for (String pkg : AutoMode.selectedApps(this)) {
            if (pm.getLaunchIntentForPackage(pkg) != null) n++;
        }
        return n;
    }

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
            bannerTv.setText("还需授权：" + String.join("、", lack));
        }
    }

    // ---------------- 总开关 ----------------

    /** 关闭 → 全部关闭；开启 → 按“白名单 / 全局”模式显示。 */
    private void toggle() {
        if (AutoMode.isMaster(this)) {
            AutoMode.setMaster(this, false);
            updateUi(true);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            startGuide();
            return;
        }
        AutoMode.setMaster(this, true);
        updateUi(true);
        if (AutoMode.isEnabled(this) && !AutoMode.isAccessibilityEnabled(this)) startGuide();
    }

    /** 设置有变化：让正在显示的遮罩立刻刷新。 */
    private void changed() {
        preview.invalidate();
        if (MaskService.running) {
            startForegroundService(new Intent(this, MaskService.class));
        }
        AutoMode.evaluate(this);
    }

    /**
     * 拖动微调时的触感：每 1dp 一下很轻的短震，每 10dp 稍实一点，
     * 到 0 或者 ±100 时最明显，让人不用看也知道到了。
     */
    private void tick(int o) {
        boolean edge = o == 0 || Math.abs(o) == Slider.MAX;
        boolean major = o % 10 == 0;
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26 && vib.hasAmplitudeControl()) {
                vib.vibrate(VibrationEffect.createOneShot(
                        edge ? 16 : major ? 9 : 5, edge ? 110 : major ? 60 : 24));
                return;
            }
            if (Build.VERSION.SDK_INT >= 29 && (edge || major)) {
                vib.vibrate(VibrationEffect.createPredefined(
                        edge ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
                return;
            }
        }
        slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
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
                    // 开启成功后，留白会自己跳回前台，不用手动返回
                    sp.edit().putBoolean(AutoAccessibilityService.KEY_RETURN, true).apply();
                    openAccessibility();
            }
        } catch (Exception e) {
            // 实在打不开专用页面，就去应用信息页，让用户手动设置
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    /**
     * 无障碍直达：Android 14+ 直接打开“留白”自己的无障碍详情页，
     * 更低版本打开无障碍列表并把“留白”那一项高亮出来。
     */
    private void openAccessibility() {
        String flat = new ComponentName(this, AutoAccessibilityService.class).flattenToString();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Intent direct = new Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
                direct.putExtra(Intent.EXTRA_COMPONENT_NAME, flat);
                startActivity(direct);
                return;
            } catch (Exception ignored) { /* 个别系统没有这个页面，退回列表 */ }
        }
        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", flat);
        list.putExtra(":settings:fragment_args_key", flat);
        list.putExtra(":settings:show_fragment_args", args);
        startActivity(list);
    }

    /** 无障碍没开成功：最常见的原因是系统对“非商店安装”的软件置灰了该选项。 */
    private void showRestrictedHelp() {
        new AlertDialog.Builder(this)
                .setTitle("无障碍选项是灰色的")
                .setMessage("1. 打开应用信息页\n"
                        + "2. 右上角 ⋮ → 允许受限制的设置\n"
                        + "3. 回到留白，再点一次设置")
                .setPositiveButton("应用信息", (d, w) -> startActivity(new Intent(
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
}
