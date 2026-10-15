package com.liubai.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.liubai.app.Ui.lp;

/**
 * 留白主页。
 * 顶部固定：标题 + 演示屏（下面是宽度微调），拖动设置时始终看得到遮罩的变化；
 * 下面单独滚动：授权提示、生效范围 / 视频比例 / 遮盖强度；底部一个大开关。
 * 横屏时左边是演示屏，右边是设置。所有权限都由你手动点“开启”，不会自动跳转。
 */
public class MainActivity extends Activity {
    private static final float[] RATIO_VALUES = {16f / 9f, 4f / 3f};
    private static final int[] STRENGTH_LAYERS = {1, 3};

    /** 统计“已选应用里装了几个”要查系统，放到后台做，不卡界面。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private SharedPreferences sp;
    private Vibrator vib;
    private boolean land;
    private long lastTick;                 // 上一次触感的时间，用来节流

    private View dot;
    private PermissionCard perms;
    private TextView statusTv, appsLink, resetBtn;
    private PreviewView preview;
    private PowerButton power;
    private Slider slider;
    private Set<String> launchable;        // 桌面上能打开的应用包名（统计已选数量用）
    private final Handler repeat = new Handler(Looper.getMainLooper());

    private int dp(float v) { return Ui.px(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        Ui.lightWindow(this);
        launchable = queryLaunchable();

        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int rightW = land ? Math.round(screenW * 0.54f) : screenW;

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 固定在顶部（横屏时在左边）：标题 + 演示屏 + 微调 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        if (land) top.setPadding(dp(20), dp(14), dp(8), dp(14));
        else top.setPadding(dp(20), dp(24), dp(20), dp(8));
        buildHeader(top);
        buildStage(top);

        if (land) {
            ScrollView left = new ScrollView(this);
            left.setVerticalScrollBarEnabled(false);
            left.setFillViewport(true);
            left.addView(top, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams llp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT);
            llp.weight = 0.46f;
            root.addView(left, llp);
        } else {
            root.addView(top, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ---- 下面（横屏时在右边）单独滚动：授权 + 设置 ----
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (land) col.setPadding(dp(8), dp(14), dp(20), dp(84));
        else col.setPadding(dp(20), dp(6), dp(20), dp(100));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout svWrap = new FrameLayout(this);
        svWrap.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!land) {
            // 内容滚到演示卡下面时，边缘淡出，不要硬切
            View topFade = new View(this);
            topFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Ui.BG, 0x00F3F6FC}));
            svWrap.addView(topFade, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(14), Gravity.TOP));
        }
        LinearLayout.LayoutParams slp = lp(land ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                land ? ViewGroup.LayoutParams.MATCH_PARENT : 0);
        slp.weight = land ? 0.54f : 1f;
        root.addView(svWrap, slp);

        perms = new PermissionCard(this);
        LinearLayout.LayoutParams plp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(8);
        col.addView(perms, plp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(4), dp(18), dp(4));
        Ui.card(card, 24);
        LinearLayout.LayoutParams clp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        col.addView(card, clp);

        buildModeRow(card);
        buildRatioRow(card);
        buildStrengthRow(card);

        // 底部渐隐，让大开关浮在内容上面
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00F3F6FC, Ui.BG}));
        page.addView(fade, new FrameLayout.LayoutParams(rightW, dp(120),
                land ? (Gravity.BOTTOM | Gravity.END) : Gravity.BOTTOM));

        // 总开关
        power = new PowerButton(this);
        power.setOnClickListener(v -> toggle());
        FrameLayout.LayoutParams pwl;
        if (land) {
            pwl = new FrameLayout.LayoutParams(rightW - dp(28), dp(54), Gravity.BOTTOM | Gravity.END);
            pwl.setMargins(0, 0, dp(20), dp(14));
        } else {
            pwl = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.BOTTOM);
            pwl.setMargins(dp(20), 0, dp(20), dp(22));
        }
        page.addView(power, pwl);

        setContentView(page);
        Ui.noClip(page);
        updateUi(false);
        refreshAuto();
    }

    // ---------------- 页面结构 ----------------

    private void buildHeader(LinearLayout parent) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(land ? 28 : 34);
        title.setTextColor(Ui.TITLE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.04f);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1f;
        head.addView(title, tlp);

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(11), dp(7), dp(13), dp(7));
        chip.setBackground(Ui.round(Ui.CARD, dp(20)));
        Ui.lift(chip, 3, 0x264F7CFF, 0x404F7CFF);
        dot = new View(this);
        chip.addView(dot, lp(dp(10), dp(10)));
        statusTv = new TextView(this);
        statusTv.setTextSize(13);
        statusTv.setPadding(dp(7), 0, 0, 0);
        chip.addView(statusTv);
        head.addView(chip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 演示卡：屏幕在上，下面一行 − 滑条 +；屏幕右上角在微调不为 0 时出现“归零”。 */
    private void buildStage(LinearLayout parent) {
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setPadding(dp(12), dp(12), dp(12), dp(12));
        Ui.card(stage, 28);

        FrameLayout shot = new FrameLayout(this);
        preview = new PreviewView(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 预览控件四周留了画阴影的空间，用负边距抵消，屏幕本身仍和卡片内边距对齐
        plp.setMargins(-dp(PreviewView.PAD_X), -dp(PreviewView.PAD_T),
                -dp(PreviewView.PAD_X), -dp(PreviewView.PAD_B - 10));
        shot.addView(preview, plp);

        resetBtn = new TextView(this);
        resetBtn.setText("↺");
        resetBtn.setTextSize(19);
        resetBtn.setTextColor(0xFFFFFFFF);
        resetBtn.setTypeface(Typeface.DEFAULT_BOLD);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setContentDescription(getString(R.string.reset_desc));
        // 蓝色实心圆：在白边、黑边上都看得清；视觉 36dp，外面再留 4dp，点击范围 44dp
        GradientDrawable rb = Ui.gradient(dp(18));
        rb.setShape(GradientDrawable.OVAL);
        resetBtn.setBackground(new android.graphics.drawable.InsetDrawable(rb, dp(4)));
        Ui.lift(resetBtn, 4, 0x554F7CFF, 0x884F7CFF);
        resetBtn.setAlpha(0f);
        resetBtn.setClickable(false);
        resetBtn.setOnClickListener(v -> setOffset(0));
        Ui.press(resetBtn, 0.9f);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.TOP | Gravity.END);
        rlp.setMargins(0, dp(4), dp(4), 0);
        shot.addView(resetBtn, rlp);
        stage.addView(shot, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout fine = new LinearLayout(this);
        fine.setOrientation(LinearLayout.HORIZONTAL);
        fine.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = stepButton("−", getString(R.string.step_minus_desc), -1);
        TextView plus = stepButton("+", getString(R.string.step_plus_desc), 1);

        slider = new Slider(this);
        slider.setContentDescription(getString(R.string.fine_desc));
        applyRange();
        int off = Math.max(slider.getMin(), Math.min(Slider.MAX, sp.getInt(MaskService.KEY_OFFSET, 0)));
        slider.setValue(off);
        showOffset(off, false);
        slider.setOnChange((v, user) -> {
            sp.edit().putInt(MaskService.KEY_OFFSET, v).apply();
            showOffset(v, true);
            if (user) tick(v);
            changed();
        });

        fine.addView(minus, lp(dp(44), dp(44)));
        LinearLayout.LayoutParams slp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.weight = 1f;
        slp.setMargins(dp(2), 0, dp(2), 0);
        fine.addView(slider, slp);
        fine.addView(plus, lp(dp(44), dp(44)));
        stage.addView(fine, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams sgp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sgp.topMargin = dp(land ? 12 : 18);
        parent.addView(stage, sgp);
    }

    /** −/+：点一下 ±1dp，按住不放则先慢后快地连续调。 */
    private TextView stepButton(String text, String desc, int delta) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(22);
        t.setTextColor(Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(desc);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.CHIP);
        t.setBackground(g);
        t.setClickable(true);
        // 读屏 / 键盘触发的点击
        t.setOnClickListener(v -> setOffset(slider.getValue() + delta));

        final int[] n = {0};
        final Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            setOffset(slider.getValue() + delta);
            n[0]++;
            repeat.postDelayed(loop[0], n[0] < 6 ? 130 : n[0] < 16 ? 75 : 40);
        };
        t.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                    n[0] = 0;
                    repeat.removeCallbacksAndMessages(null);
                    setOffset(slider.getValue() + delta);
                    repeat.postDelayed(loop[0], 380);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    repeat.removeCallbacksAndMessages(null);
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    private TextView link(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Ui.DIM);
        t.setPadding(dp(16), dp(4), dp(2), dp(4));
        return t;
    }

    private LinearLayout labelRow(int textRes, TextView right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextSize(14);
        t.setTextColor(Ui.INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1f;
        row.addView(t, tlp);
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
        s.setPadding(0, dp(16), 0, dp(12));   // 分段选择器自带 4dp 上下留白，下面少 4dp 才看着对称
        card.addView(s, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private LinearLayout.LayoutParams below() {
        LinearLayout.LayoutParams p = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(14);
        return p;
    }

    private void buildModeRow(LinearLayout card) {
        LinearLayout s = section(card, true);
        appsLink = link("");
        appsLink.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        Ui.press(appsLink, 0.94f);
        // 这一行至少 44dp 高，比文字大一圈都能点到；用负边距抵消，版面不变
        LinearLayout.LayoutParams lrp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = -dp(10);
        lrp.bottomMargin = -dp(10);
        LinearLayout lr = labelRow(R.string.row_scope, appsLink);
        lr.setMinimumHeight(dp(44));
        s.addView(lr, lrp);

        Segmented seg = new Segmented(this,
                new String[]{getString(R.string.seg_whitelist), getString(R.string.seg_global)},
                AutoMode.isEnabled(this) ? 0 : 1);
        seg.setOnSelect(i -> {
            sp.edit().putBoolean(AutoMode.KEY_AUTO, i == 0).apply();
            AutoMode.evaluate(this);   // 切换后立刻按新模式显示 / 隐藏
            refreshAuto();
        });
        Ui.press(seg, 0.985f);
        s.addView(seg, below());
    }

    private void buildRatioRow(LinearLayout card) {
        LinearLayout s = section(card, false);
        s.addView(labelRow(R.string.row_ratio, null));

        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int sel = Math.abs(RATIO_VALUES[1] - cur) < 0.001f ? 1 : 0;
        if (sel == 0) sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[0]).apply();
        Segmented seg = new Segmented(this, new String[]{"16:9", "4:3"}, sel, RATIO_VALUES);
        seg.setOnSelect(i -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[i]).apply();
            preview.ratioChanged();
            applyRange();
            int cur2 = sp.getInt(MaskService.KEY_OFFSET, 0);
            if (cur2 < slider.getMin()) {      // 换画幅后黑边变窄，微调跟着收回到新的下限
                sp.edit().putInt(MaskService.KEY_OFFSET, slider.getMin()).apply();
                slider.setValue(slider.getMin());
                showOffset(slider.getMin(), false);
            }
            slider.invalidate();
            changed();
        });
        Ui.press(seg, 0.985f);
        s.addView(seg, below());
    }

    private void buildStrengthRow(LinearLayout card) {
        LinearLayout s = section(card, false);
        s.addView(labelRow(R.string.row_strength, null));

        int curLayers = sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS);
        Segmented seg = new Segmented(this,
                new String[]{getString(R.string.seg_standard), getString(R.string.seg_strong)},
                curLayers >= 2 ? 1 : 0);
        seg.setOnSelect(i -> {
            sp.edit().putInt(MaskService.KEY_LAYERS, STRENGTH_LAYERS[i]).apply();
            changed();
        });
        Ui.press(seg, 0.985f);
        s.addView(seg, below());
    }

    // ---------------- 微调 ----------------

    private void showOffset(int v, boolean animate) {
        if (resetBtn == null) return;
        boolean show = v != 0;
        resetBtn.setClickable(show);
        resetBtn.setEnabled(show);
        float a = show ? 1f : 0f, s = show ? 1f : 0.8f;
        if (animate) {
            resetBtn.animate().alpha(a).scaleX(s).scaleY(s).setDuration(180).start();
        } else {
            resetBtn.setAlpha(a);
            resetBtn.setScaleX(s);
            resetBtn.setScaleY(s);
        }
    }

    /** 微调下限 = 当前画幅下一侧黑边的宽度：再往负，遮罩已经是 0 了，没有意义。 */
    private void applyRange() {
        float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        slider.setMin(-Ui.baseBarDp(this, ratio));
    }

    private void setOffset(int v) {
        v = Math.max(slider.getMin(), Math.min(Slider.MAX, v));
        if (v == slider.getValue() && sp.getInt(MaskService.KEY_OFFSET, 0) == v) return;
        sp.edit().putInt(MaskService.KEY_OFFSET, v).apply();
        slider.setValue(v);
        showOffset(v, true);
        tick(v);
        changed();
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onPause() {
        super.onPause();
        repeat.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从无障碍设置页回来还没开：多半是“无障碍”被系统置灰了
        if (perms.consumeAwaitingA11y() && perms.accessibilityMissing()) perms.showRestrictedHelp();
        updateUi(false);
        refreshAuto();
        refreshLaunchableAsync();
    }

    // ---------------- 状态刷新 ----------------

    /** 开 → 演示屏两侧变白、按钮翻白；关 → 变回黑边。 */
    private void updateUi(boolean animate) {
        boolean on = AutoMode.isMaster(this);
        power.setOn(on, animate);
        preview.setOn(on);
        statusTv.setText(on ? R.string.status_on : R.string.status_off);
        statusTv.setTextColor(on ? Ui.INK : Ui.DIM);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (on) {
            g.setColor(Ui.GREEN);
            g.setStroke(dp(2), 0x552EB67D);
        } else {
            g.setColor(0xFFC9CCD6);
        }
        dot.setBackground(g);
        perms.refresh();
    }

    /** 白名单入口：只在白名单模式显示，位置不变所以界面不跳动。 */
    private void refreshAuto() {
        boolean whitelist = AutoMode.isEnabled(this);
        int n = installedSelectedCount();
        if (n == 0) {                     // 白名单模式下一个都没选 = 哪里都不显示，用橙色提醒
            SpannableString s = new SpannableString(getString(R.string.apps_none));
            s.setSpan(new ForegroundColorSpan(0xFFD97706), 0, s.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            appsLink.setText(s);
        } else {
            String num = String.valueOf(n);
            appsLink.setText(Ui.highlightNumber(getString(R.string.apps_selected, num), num, true));
        }
        appsLink.setEnabled(whitelist);
        appsLink.setClickable(whitelist);
        appsLink.animate().cancel();
        appsLink.animate().alpha(whitelist ? 1f : 0f).setDuration(180).start();
        updateUi(false);
    }

    private int installedSelectedCount() {
        Set<String> have = launchable;
        int n = 0;
        for (String pkg : AutoMode.selectedApps(this)) {
            if (have.contains(pkg)) n++;
        }
        return n;
    }

    /** 桌面上能打开的应用：一次查询拿到全部，比逐个包名去问快得多。 */
    private Set<String> queryLaunchable() {
        Set<String> s = new HashSet<>();
        try {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                s.add(ri.activityInfo.packageName);
            }
        } catch (Exception ignored) { }
        return s;
    }

    /** 回到页面时在后台重新查一次（比如刚装 / 卸载了应用），有变化再刷新数字。 */
    private void refreshLaunchableAsync() {
        IO.execute(() -> {
            Set<String> s = queryLaunchable();
            runOnUiThread(() -> {
                if (isDestroyed() || s.equals(launchable)) return;
                launchable = s;
                refreshAuto();
            });
        });
    }

    // ---------------- 总开关 ----------------

    /** 关闭 → 全部关闭；开启 → 按“白名单 / 全局”模式显示。没有悬浮窗权限时只提醒，不会自己跳转。 */
    private void toggle() {
        if (AutoMode.isMaster(this)) {
            AutoMode.setMaster(this, false);
            updateUi(true);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            nudgePerms(getString(R.string.toast_need_overlay));
            return;
        }
        AutoMode.setMaster(this, true);
        updateUi(true);
    }

    /** 提醒：授权卡抖一下 + 一句提示 + 一下短震。 */
    private void nudgePerms(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        perms.shake();
        if (vib != null && vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 8, 40, 8}, -1));
        }
    }

    /**
     * 设置有变化：让正在显示的遮罩立刻刷新。
     * 宽度 / 画幅 / 强度都不影响“该不该显示”，所以这里不用重新判断白名单。
     */
    private void changed() {
        preview.refresh();
        // 服务正在运行时直接让它原地更新（不重启、不重建），拖动微调才不会闪
        if (MaskService.running && !MaskService.refresh()) {
            startForegroundService(new Intent(this, MaskService.class));
        }
    }

    /**
     * 拖动微调时的触感：用系统的触感反馈（跟随系统的“触摸反馈”开关），
     * 每 10dp 稍实一点，到 0 或者 ±100 时最明显，让人不用看也知道到了。
     * 快速拖动时按时间节流，不会每一格都震。
     */
    private void tick(int o) {
        boolean edge = o == 0 || o == Slider.MAX || o == slider.getMin();
        boolean major = o % 10 == 0;
        long now = SystemClock.uptimeMillis();
        if (!edge && now - lastTick < (major ? 16 : 32)) return;
        lastTick = now;
        int fb;
        if (edge) {
            fb = HapticFeedbackConstants.CONTEXT_CLICK;
        } else if (Build.VERSION.SDK_INT >= 34) {
            fb = major ? HapticFeedbackConstants.SEGMENT_TICK
                    : HapticFeedbackConstants.SEGMENT_FREQUENT_TICK;
        } else {
            fb = major ? HapticFeedbackConstants.CONTEXT_CLICK : HapticFeedbackConstants.CLOCK_TICK;
        }
        slider.performHapticFeedback(fb);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] results, int[] grants) {
        super.onRequestPermissionsResult(code, results, grants);
        perms.refresh();
    }
}
