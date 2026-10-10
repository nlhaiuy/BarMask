package com.example.blackbarmask;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    // 视频比例：默认 16:9
    private static final String[] RATIO_NAMES = {"16:9", "4:3"};
    private static final float[] RATIO_VALUES = {16f / 9f, 4f / 3f};

    // 遮盖强度：标准 = 1 层，加强 = 3 层
    private static final String[] STRENGTH_NAMES = {"标准", "加强"};
    private static final int[] STRENGTH_LAYERS = {1, 3};

    private static final int BG = 0xFFF3F4F8;
    private static final int ACCENT = 0xFF4F46E5;
    private static final int TEXT = 0xFF111827;
    private static final int SUB = 0xFF6B7280;
    private static final int CHIP = 0xFFEEF0F6;
    private static final int OFF_BTN = 0xFF374151;

    private SharedPreferences sp;
    private float density;
    private TextView statusTv, btn;
    private TextView accTv, listenTv, appsTv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        // 整体：上面是可滚动的设置，下面固定一个大按钮（拇指最容易按到）
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 顶部：图标 + 标题 ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        header.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextSize(22);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText("把视频两侧的黑边盖成白色");
        subtitle.setTextSize(13);
        subtitle.setTextColor(SUB);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(16);
        root.addView(header, hlp);

        // ---- 视频比例 ----
        LinearLayout ratioCard = card();
        ratioCard.addView(label("视频比例"));
        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int selRatio = -1;
        for (int i = 0; i < RATIO_VALUES.length; i++) {
            if (Math.abs(RATIO_VALUES[i] - cur) < 0.001f) selRatio = i;
        }
        if (selRatio < 0) { // 以前保存过别的比例，重置为默认
            selRatio = 0;
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[0]).apply();
        }
        addChips(ratioCard, RATIO_NAMES, 2, selRatio, pos -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[pos]).apply();
            changed();
        });
        root.addView(ratioCard);

        // ---- 遮盖强度 ----
        LinearLayout strengthCard = card();
        strengthCard.addView(label("遮盖强度"));
        TextView hint = new TextView(this);
        hint.setText("如果白边看起来发灰、不够白，请选“加强”。");
        hint.setTextSize(12);
        hint.setTextColor(SUB);
        hint.setPadding(0, 0, 0, dp(10));
        strengthCard.addView(hint);
        int curLayers = sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS);
        int selStrength = curLayers >= 2 ? 1 : 0;
        addChips(strengthCard, STRENGTH_NAMES, 2, selStrength, pos -> {
            sp.edit().putInt(MaskService.KEY_LAYERS, STRENGTH_LAYERS[pos]).apply();
            changed();
        });
        root.addView(strengthCard);

        root.addView(autoCard());

        // ---- 宽度微调 ----
        LinearLayout offCard = card();
        TextView ot = label("");
        offCard.addView(ot);
        SeekBar sb = new SeekBar(this);
        sb.setMax(200); // 对应 -100dp ~ +100dp
        int off = sp.getInt(MaskService.KEY_OFFSET, 0);
        sb.setProgress(off + 100);
        sb.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sb.setThumbTintList(ColorStateList.valueOf(ACCENT));
        ot.setText(offsetText(off));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                int o = p - 100;
                ot.setText(offsetText(o));
                sp.edit().putInt(MaskService.KEY_OFFSET, o).apply();
                if (user) changed();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        offCard.addView(sb);
        root.addView(offCard);

        TextView foot = new TextView(this);
        foot.setText("竖屏时自动隐藏。也可以在控制中心添加“白边”磁贴，或长按桌面图标一键开关。");
        foot.setTextSize(12);
        foot.setTextColor(SUB);
        foot.setPadding(dp(4), dp(4), dp(4), 0);
        root.addView(foot);

        // ---- 底部固定：状态 + 大按钮 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bbg = new GradientDrawable();
        bbg.setColor(Color.WHITE);
        bbg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        bottom.setBackground(bbg);
        bottom.setElevation(dp(8));
        bottom.setPadding(dp(16), dp(14), dp(16), dp(20));

        statusTv = new TextView(this);
        statusTv.setTextSize(14);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(0, 0, 0, dp(10));
        bottom.addView(statusTv);

        btn = new TextView(this);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(20);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(0, dp(20), 0, dp(20));
        btn.setOnClickListener(v -> toggle());
        bottom.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(page);
        updateUi(MaskService.running);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi(MaskService.running);
        refreshAuto();
    }

    // ---------------- 自动模式 ----------------

    private LinearLayout autoCard() {
        LinearLayout c = card();
        c.addView(label("自动模式"));
        TextView hint = small("打开你选的视频软件、并且正在播放时，自动开启白边；退出或暂停太久后自动关闭。");
        hint.setPadding(0, 0, 0, dp(10));
        c.addView(hint);
        addChips(c, new String[]{"关闭", "开启"}, 2, AutoMode.isEnabled(this) ? 1 : 0, pos -> {
            boolean on = pos == 1;
            sp.edit().putBoolean(AutoMode.KEY_AUTO, on).apply();
            if (on && !AutoMode.isAccessibilityEnabled(this)) {
                Toast.makeText(this, "请在列表里找到“白边”，开启无障碍", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
            refreshAuto();
        });
        accTv = row(c, v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        listenTv = row(c, v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        appsTv = row(c, v -> startActivity(new Intent(this, AppPickerActivity.class)));
        TextView tip = small("如果系统把“无障碍”选项置灰：到 手机设置 → 应用 → 白边 → 右上角菜单，"
                + "选择“允许受限制的设置”，再回来开启。");
        tip.setPadding(0, dp(8), 0, 0);
        c.addView(tip);
        return c;
    }

    private void refreshAuto() {
        if (accTv == null) return;
        boolean acc = AutoMode.isAccessibilityEnabled(this);
        boolean lis = AutoMode.isListenerEnabled(this);
        accTv.setText((acc ? "✓ " : "✗ ") + "无障碍权限（识别当前打开的软件）  ›");
        accTv.setTextColor(acc ? 0xFF15803D : 0xFFB91C1C);
        listenTv.setText((lis ? "✓ " : "○ ") + "通知使用权（判断是否正在播放，可选）  ›");
        listenTv.setTextColor(lis ? 0xFF15803D : SUB);
        appsTv.setText("选择视频软件（"
                + (AutoMode.hasCustomApps(this)
                ? "已选 " + AutoMode.selectedApps(this).size() + " 个"
                : "默认：常见视频软件") + "）  ›");
        appsTv.setTextColor(ACCENT);
    }

    private TextView row(LinearLayout parent, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setTextColor(TEXT);
        t.setPadding(0, dp(12), 0, dp(12));
        t.setOnClickListener(l);
        parent.addView(t);
        return t;
    }

    private TextView small(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(SUB);
        return t;
    }

    // ---------------- 逻辑 ----------------

    private void toggle() {
        Intent svc = new Intent(this, MaskService.class);
        if (MaskService.running) {
            stopService(svc);
            updateUi(false);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许“显示在其他应用上层”", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startForegroundService(svc);
        updateUi(true);
    }

    private void updateUi(boolean on) {
        statusTv.setText(on ? "● 白边已开启（横屏时生效）" : "○ 白边未开启");
        statusTv.setTextColor(on ? ACCENT : SUB);
        btn.setText(on ? "关闭白边" : "开启白边");
        btn.setBackground(round(on ? OFF_BTN : ACCENT, 18));
    }

    /** 设置有变化：让正在运行的遮罩立即更新。 */
    private void changed() {
        if (MaskService.running) {
            startForegroundService(new Intent(this, MaskService.class));
        }
    }

    private String offsetText(int o) {
        return "宽度微调：" + (o > 0 ? "+" : "") + o + " dp（正数 = 白边更宽）";
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

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private interface OnPick { void pick(int position); }

    /** 一排“药丸”单选按钮。 */
    private void addChips(LinearLayout parent, String[] labels, int perRow, int selected, OnPick cb) {
        final TextView[] chips = new TextView[labels.length];
        int rows = (labels.length + perRow - 1) / perRow;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = r == 0 ? 0 : dp(8);
            parent.addView(row, rlp);
            for (int c = 0; c < perRow; c++) {
                int i = r * perRow + c;
                View v;
                if (i < labels.length) {
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
                    v = t;
                } else {
                    v = new View(this); // 占位，保持每个按钮等宽
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) lp.leftMargin = dp(8);
                row.addView(v, lp);
            }
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
