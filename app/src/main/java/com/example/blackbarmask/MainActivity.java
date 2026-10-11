package com.example.blackbarmask;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final float[] RATIO_VALUES = {16f / 9f, 4f / 3f};
    private static final int[] STRENGTH_LAYERS = {1, 3};

    private SharedPreferences sp;
    private float density;
    private MaterialButton btn, banner;
    private MaterialButton appsRow;
    private PreviewView preview;
    private Slider slider;
    private TextInputEditText offsetInput;
    private boolean offsetBusy = false;

    // 权限引导
    private static final String[] PERM_NAMES = {"悬浮窗", "通知", "后台运行", "无障碍"};
    private boolean guiding = false, awaitingReturn = false;
    private int step = -1;

    private int color(int attr) {
        return MaterialColors.getColor(this, attr, 0);
    }

    @Override
    protected void onCreate(Bundle b) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;

        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(night ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        btn = findViewById(R.id.btn);
        banner = findViewById(R.id.banner);
        preview = findViewById(R.id.preview);
        appsRow = findViewById(R.id.apps_row);
        slider = findViewById(R.id.slider);
        offsetInput = findViewById(R.id.offset_input);

        btn.setOnClickListener(v -> toggle());
        banner.setOnClickListener(v -> startGuide());
        appsRow.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));

        setupMode();
        setupDisplay();
        setupOffset();

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

    // ---------------- 设置项 ----------------

    private void setupMode() {
        MaterialButtonToggleGroup g = findViewById(R.id.mode_group);
        g.check(AutoMode.isEnabled(this) ? R.id.mode_whitelist : R.id.mode_global);
        g.addOnButtonCheckedListener((grp, id, checked) -> {
            if (!checked) return;
            boolean whitelist = id == R.id.mode_whitelist;
            sp.edit().putBoolean(AutoMode.KEY_AUTO, whitelist).apply();
            AutoMode.evaluate(this);   // 切换后立刻按新模式显示 / 隐藏
            refreshAuto();
            // 白名单模式要靠无障碍识别前台软件
            if (whitelist && AutoMode.isMaster(this) && !AutoMode.isAccessibilityEnabled(this)) startGuide();
        });
    }

    private void setupDisplay() {
        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int selRatio = 0;
        if (Math.abs(RATIO_VALUES[1] - cur) < 0.001f) selRatio = 1;
        else sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[0]).apply();
        MaterialButtonToggleGroup rg = findViewById(R.id.ratio_group);
        rg.check(selRatio == 0 ? R.id.ratio_169 : R.id.ratio_43);
        rg.addOnButtonCheckedListener((grp, id, checked) -> {
            if (!checked) return;
            sp.edit().putFloat(MaskService.KEY_RATIO,
                    RATIO_VALUES[id == R.id.ratio_169 ? 0 : 1]).apply();
            changed();
        });

        int curLayers = sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS);
        MaterialButtonToggleGroup sg = findViewById(R.id.strength_group);
        sg.check(curLayers >= 2 ? R.id.strength_strong : R.id.strength_std);
        sg.addOnButtonCheckedListener((grp, id, checked) -> {
            if (!checked) return;
            sp.edit().putInt(MaskService.KEY_LAYERS,
                    STRENGTH_LAYERS[id == R.id.strength_std ? 0 : 1]).apply();
            changed();
        });
    }

    private void setupOffset() {
        int off = Math.max(-100, Math.min(100, sp.getInt(MaskService.KEY_OFFSET, 0)));
        slider.setValue(off);
        slider.setLabelFormatter(v -> ((int) v) + " dp");
        offsetInput.setText(String.valueOf(off));

        java.util.function.IntConsumer setOff = o0 -> {
            int o = Math.max(-100, Math.min(100, o0));
            sp.edit().putInt(MaskService.KEY_OFFSET, o).apply();
            offsetBusy = true;
            slider.setValue(o);
            offsetInput.setText(String.valueOf(o));
            offsetBusy = false;
            offsetInput.clearFocus();
            changed();
        };

        slider.addOnChangeListener((s, value, fromUser) -> {
            if (!fromUser || offsetBusy) return;
            int o = (int) value;
            sp.edit().putInt(MaskService.KEY_OFFSET, o).apply();
            offsetBusy = true;
            offsetInput.setText(String.valueOf(o));
            offsetBusy = false;
            changed();
        });

        offsetInput.addTextChangedListener(new TextWatcher() {
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
                slider.setValue(o);
                offsetBusy = false;
                changed();
            }
        });
        // 输入完成/失焦时，把超出范围或为空的内容规整回有效值
        Runnable normalize = () -> {
            offsetBusy = true;
            offsetInput.setText(String.valueOf(sp.getInt(MaskService.KEY_OFFSET, 0)));
            offsetBusy = false;
        };
        offsetInput.setOnEditorActionListener((v, id, ev) -> {
            normalize.run();
            offsetInput.clearFocus();
            return false;
        });
        offsetInput.setOnFocusChangeListener((v, has) -> { if (!has) normalize.run(); });

        findViewById(R.id.reset).setOnClickListener(v -> setOff.accept(0));
        findViewById(R.id.minus).setOnClickListener(
                v -> setOff.accept(sp.getInt(MaskService.KEY_OFFSET, 0) - 1));
        findViewById(R.id.plus).setOnClickListener(
                v -> setOff.accept(sp.getInt(MaskService.KEY_OFFSET, 0) + 1));
    }

    // ---------------- 状态刷新 ----------------

    /** 总开关：开着显示“关闭白边”（次要色），关着显示“开启白边”（主色）。 */
    private void updateUi() {
        boolean on = AutoMode.isMaster(this);
        btn.setText(on ? "关闭白边" : "开启白边");
        btn.setBackgroundTintList(ColorStateList.valueOf(color(on
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorPrimary)));
        btn.setTextColor(color(on
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnPrimary));
        preview.setOn(on);
        refreshPerms();
    }

    /** 白名单入口：只在白名单模式显示，淡入淡出，位置不变所以界面不跳动。 */
    private void refreshAuto() {
        boolean whitelist = AutoMode.isEnabled(this);
        appsRow.setText("管理应用 · " + installedSelectedCount());
        appsRow.setEnabled(whitelist);
        appsRow.setClickable(whitelist);
        appsRow.animate().cancel();
        appsRow.animate().alpha(whitelist ? 1f : 0f).setDuration(180).start();
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
            banner.setText("还差：" + String.join("、", lack) + "，点这里一键设置");
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
        preview.invalidate();
        if (MaskService.running) {
            startForegroundService(new Intent(this, MaskService.class));
        }
        AutoMode.evaluate(this);
    }

    private int dp(int v) {
        return Math.round(v * density);
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
}
