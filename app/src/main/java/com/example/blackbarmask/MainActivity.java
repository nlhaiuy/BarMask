package com.example.blackbarmask;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 白边主页：黑色舞台，开启时屏幕两侧亮起两条纸白色的边。 */
public class MainActivity extends Activity {
    private static final float[] RATIO_VALUES = {16f / 9f, 4f / 3f};
    private static final int[] STRENGTH_LAYERS = {1, 3};

    private SharedPreferences sp;
    private float density;
    private Vibrator vib;

    private View barL, barR, dot;
    private TextView statusTv, banner, appsLink, numTv;
    private PreviewView preview;
    private PowerButton power;
    private Ruler ruler;

    // 权限引导
    private static final String[] PERM_NAMES = {"悬浮窗", "通知", "后台运行", "无障碍"};
    private boolean guiding = false, awaitingReturn = false;
    private int step = -1;

    private int dp(float v) { return Math.round(v * density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        getWindow().setStatusBarColor(Ui.INK);
        getWindow().setNavigationBarColor(Ui.INK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.INK));

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.INK);

        // ---- 主体（两侧各留出“白边”的位置）----
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(32), dp(20), dp(32), dp(112));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildHeader(col);

        banner = new TextView(this);
        banner.setTextSize(13);
        banner.setTextColor(Ui.PAPER);
        banner.setPadding(dp(16), dp(12), dp(16), dp(12));
        banner.setBackground(Ui.round(Ui.PANEL, dp(14)));
        banner.setOnClickListener(v -> startGuide());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        col.addView(banner, blp);

        preview = new PreviewView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(20);
        col.addView(preview, plp);

        buildModeRow(col);
        buildRatioRow(col);
        buildStrengthRow(col);
        buildWidthRow(col);

        // ---- 两条白边 ----
        barL = new View(this);
        barR = new View(this);
        barL.setBackgroundColor(Ui.PAPER);
        barR.setBackgroundColor(Ui.PAPER);
        page.addView(barL, new FrameLayout.LayoutParams(dp(14),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        page.addView(barR, new FrameLayout.LayoutParams(dp(14),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        // ---- 总开关 ----
        power = new PowerButton(this);
        power.setOnClickListener(v -> toggle());
        FrameLayout.LayoutParams pwl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM);
        pwl.setMargins(dp(32), 0, dp(32), dp(24));
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
        head.setGravity(Gravity.BOTTOM);

        TextView title = new TextView(this);
        title.setText("白边");
        title.setTextSize(38);
        title.setTextColor(Ui.PAPER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.06f);
        title.setIncludeFontPadding(false);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        st.addView(dot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        statusTv = new TextView(this);
        statusTv.setTextSize(13);
        statusTv.setPadding(dp(8), 0, 0, 0);
        st.addView(statusTv);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(5);
        head.addView(st, slp);

        col.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Ui.DIM);
        return t;
    }

    private LinearLayout labelRow(String text, TextView right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(text), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) row.addView(right);
        return row;
    }

    /** 每个设置项：上方一条细线，小标题，下面是控件。 */
    private LinearLayout section(LinearLayout col, boolean first) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(0, dp(first ? 18 : 16), 0, dp(16));
        col.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private void rule(LinearLayout col) {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        col.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));
    }

    private void buildModeRow(LinearLayout col) {
        rule(col);
        LinearLayout s = section(col, true);
        appsLink = new TextView(this);
        appsLink.setTextSize(13);
        appsLink.setTextColor(Ui.PAPER);
        appsLink.setPadding(dp(12), dp(6), 0, dp(6));
        appsLink.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        s.addView(labelRow("模式", appsLink));

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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        s.addView(seg, lp);
    }

    private void buildRatioRow(LinearLayout col) {
        rule(col);
        LinearLayout s = section(col, false);
        s.addView(labelRow("视频比例", null));

        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int sel = Math.abs(RATIO_VALUES[1] - cur) < 0.001f ? 1 : 0;
        if (sel == 0) sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[0]).apply();
        Tiles tiles = new Tiles(this, new String[]{"16:9", "4:3"}, RATIO_VALUES, sel);
        tiles.setOnSelect(i -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[i]).apply();
            changed();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        s.addView(tiles, lp);
    }

    private void buildStrengthRow(LinearLayout col) {
        rule(col);
        LinearLayout s = section(col, false);
        s.addView(labelRow("遮盖强度", null));

        int curLayers = sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS);
        Segmented seg = new Segmented(this, new String[]{"标准", "加强"}, curLayers >= 2 ? 1 : 0);
        seg.setOnSelect(i -> {
            sp.edit().putInt(MaskService.KEY_LAYERS, STRENGTH_LAYERS[i]).apply();
            changed();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        s.addView(seg, lp);
    }

    private void buildWidthRow(LinearLayout col) {
        rule(col);
        LinearLayout s = section(col, false);

        TextView reset = new TextView(this);
        reset.setText("归零");
        reset.setTextSize(13);
        reset.setTextColor(Ui.PAPER);
        reset.setPadding(dp(12), dp(6), 0, dp(6));
        s.addView(labelRow("宽度微调", reset));

        // 大号数字：点一下可以用键盘输入
        LinearLayout num = new LinearLayout(this);
        num.setOrientation(LinearLayout.HORIZONTAL);
        num.setGravity(Gravity.CENTER);
        numTv = new TextView(this);
        numTv.setTextSize(52);
        numTv.setTextColor(Ui.PAPER);
        numTv.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        numTv.setIncludeFontPadding(false);
        num.addView(numTv);
        TextView unit = new TextView(this);
        unit.setText("dp");
        unit.setTextSize(14);
        unit.setTextColor(Ui.DIM);
        unit.setPadding(dp(6), 0, 0, dp(8));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.gravity = Gravity.BOTTOM;
        num.addView(unit, ulp);
        num.setOnClickListener(v -> askOffset());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4);
        s.addView(num, nlp);

        ruler = new Ruler(this);
        int off = Math.max(Ruler.MIN, Math.min(Ruler.MAX, sp.getInt(MaskService.KEY_OFFSET, 0)));
        ruler.setValue(off);
        showOffset(off);
        ruler.setOnChange((v, user) -> {
            sp.edit().putInt(MaskService.KEY_OFFSET, v).apply();
            showOffset(v);
            if (user) tick(v);
            changed();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        s.addView(ruler, rlp);

        reset.setOnClickListener(v -> setOffset(0));
    }

    private void showOffset(int v) {
        numTv.setText((v > 0 ? "+" : "") + v);
    }

    private void setOffset(int v) {
        v = Math.max(Ruler.MIN, Math.min(Ruler.MAX, v));
        sp.edit().putInt(MaskService.KEY_OFFSET, v).apply();
        ruler.setValue(v);
        showOffset(v);
        tick(v);
        changed();
    }

    /** 点大号数字，用键盘直接输入。 */
    private void askOffset() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        in.setSingleLine(true);
        in.setText(String.valueOf(ruler.getValue()));
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

    /** 开 → 两侧白边滑入、按钮翻白；关 → 滑出。 */
    private void updateUi(boolean animate) {
        boolean on = AutoMode.isMaster(this);
        power.setOn(on, animate);
        preview.setOn(on);
        statusTv.setText(on ? "已开启" : "已关闭");
        statusTv.setTextColor(on ? Ui.PAPER : Ui.DIM);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (on) {
            g.setColor(Ui.PAPER);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(Math.max(1, dp(1.5f)), Ui.MUTE);
        }
        dot.setBackground(g);

        float hide = dp(14);
        float toL = on ? 0f : -hide, toR = on ? 0f : hide;
        if (animate) {
            barL.animate().translationX(toL).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            barR.animate().translationX(toR).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        } else {
            barL.setTranslationX(toL);
            barR.setTranslationX(toR);
        }
        refreshPerms();
    }

    /** 白名单入口：只在白名单模式显示，位置不变所以界面不跳动。 */
    private void refreshAuto() {
        boolean whitelist = AutoMode.isEnabled(this);
        appsLink.setText("白名单应用 " + installedSelectedCount() + "  ›");
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
            banner.setText("还需授权：" + String.join("、", lack) + "  ›");
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

    /** 设置有变化：让正在显示的白边立刻刷新。 */
    private void changed() {
        preview.invalidate();
        if (MaskService.running) {
            startForegroundService(new Intent(this, MaskService.class));
        }
        AutoMode.evaluate(this);
    }

    /** 拖动卷尺的触感（很轻）：每 1dp 一下，每 10dp 和 0 稍实一点。 */
    private void tick(int o) {
        boolean major = o % 10 == 0;
        if (vib != null && vib.hasVibrator() && Build.VERSION.SDK_INT >= 26
                && vib.hasAmplitudeControl()) {
            vib.vibrate(VibrationEffect.createOneShot(major ? 8 : 5, major ? 50 : 22));
            return;
        }
        if (major && vib != null && Build.VERSION.SDK_INT >= 29) {
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            return;
        }
        ruler.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
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
                .setTitle("无障碍选项是灰色的")
                .setMessage("1. 打开应用信息页\n"
                        + "2. 右上角 ⋮ → 允许受限制的设置\n"
                        + "3. 回到白边，再点一次设置")
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
