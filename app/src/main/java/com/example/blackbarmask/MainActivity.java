package com.example.blackbarmask;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
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
    // 常见视频比例
    private static final String[] RATIO_NAMES =
            {"4:3", "16:9", "16:10", "21:9", "3:2", "5:4", "1:1"};
    private static final float[] RATIO_VALUES =
            {4f / 3f, 16f / 9f, 16f / 10f, 21f / 9f, 3f / 2f, 5f / 4f, 1f};

    private static final int BG = 0xFFF3F4F8;
    private static final int ACCENT = 0xFF4F46E5;
    private static final int TEXT = 0xFF111827;
    private static final int SUB = 0xFF6B7280;
    private static final int CHIP = 0xFFEEF0F6;
    private static final int OFF_BTN = 0xFF374151;

    private SharedPreferences sp;
    private float density;
    private TextView statusTv, btn;
    private PreviewView preview;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;

        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // ---- 状态 + 开关按钮 ----
        LinearLayout statusCard = card();
        statusTv = new TextView(this);
        statusTv.setTextSize(15);
        statusTv.setTextColor(SUB);
        statusTv.setPadding(0, 0, 0, dp(12));
        statusCard.addView(statusTv);
        btn = new TextView(this);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(18);
        btn.setTextColor(Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(0, dp(14), 0, dp(14));
        btn.setOnClickListener(v -> toggle());
        statusCard.addView(btn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusCard);

        // ---- 预览 ----
        LinearLayout previewCard = card();
        previewCard.addView(label("效果预览"));
        preview = new PreviewView(this);
        previewCard.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        root.addView(previewCard);

        // ---- 视频比例 ----
        LinearLayout ratioCard = card();
        ratioCard.addView(label("视频比例"));
        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int selRatio = 0;
        for (int i = 0; i < RATIO_VALUES.length; i++) {
            if (Math.abs(RATIO_VALUES[i] - cur) < 0.001f) selRatio = i;
        }
        addChips(ratioCard, RATIO_NAMES, 4, selRatio, pos -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[pos]).apply();
            changed();
        });
        root.addView(ratioCard);

        // ---- 遮罩层数 ----
        LinearLayout layerCard = card();
        layerCard.addView(label("遮罩层数"));
        TextView layerHint = new TextView(this);
        layerHint.setText("遮罩发灰或半透明时调大（部分系统会把悬浮窗压成半透明）");
        layerHint.setTextSize(12);
        layerHint.setTextColor(SUB);
        layerHint.setPadding(0, 0, 0, dp(10));
        layerCard.addView(layerHint);
        String[] layerNames = new String[MaskService.MAX_LAYERS];
        for (int i = 0; i < layerNames.length; i++) layerNames[i] = String.valueOf(i + 1);
        int curLayers = Math.max(1, Math.min(MaskService.MAX_LAYERS,
                sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS)));
        addChips(layerCard, layerNames, MaskService.MAX_LAYERS, curLayers - 1, pos -> {
            sp.edit().putInt(MaskService.KEY_LAYERS, pos + 1).apply();
            changed();
        });
        root.addView(layerCard);

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
        foot.setText("竖屏时遮罩自动隐藏。可在控制中心添加“白边”磁贴，或长按桌面图标一键开关。");
        foot.setTextSize(12);
        foot.setTextColor(SUB);
        foot.setPadding(dp(4), dp(4), dp(4), 0);
        root.addView(foot);

        setContentView(sv);
        updateUi(MaskService.running);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi(MaskService.running);
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
        statusTv.setText(on ? "● 遮罩已开启（横屏时生效）" : "○ 遮罩未开启");
        statusTv.setTextColor(on ? ACCENT : SUB);
        btn.setText(on ? "关闭遮罩" : "开启遮罩");
        btn.setBackground(round(on ? OFF_BTN : ACCENT, 14));
    }

    /** 设置有变化：刷新预览，并让正在运行的遮罩立即更新。 */
    private void changed() {
        preview.invalidate();
        if (MaskService.running) {
            startForegroundService(new Intent(this, MaskService.class));
        }
    }

    private String offsetText(int o) {
        return "宽度微调：" + (o > 0 ? "+" : "") + o + " dp（正数 = 遮罩更宽）";
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

    /** 一排排的单选“药丸”按钮。 */
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
                    t.setTextSize(15);
                    t.setPadding(0, dp(10), 0, dp(10));
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
            chips[i].setBackground(round(i == sel ? ACCENT : CHIP, 10));
            chips[i].setTextColor(i == sel ? Color.WHITE : TEXT);
        }
    }

    // ---------------- 预览图 ----------------

    /** 画一个横屏手机：深色是视频区，白色是遮罩，随比例/微调实时变化。 */
    private class PreviewView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        PreviewView(Context c) {
            super(c);
        }

        @SuppressWarnings("deprecation")
        @Override
        protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight();

            // 设备真实宽高比（取横屏方向）
            DisplayMetrics m = new DisplayMetrics();
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m);
            float lng = Math.max(m.widthPixels, m.heightPixels);
            float sht = Math.min(m.widthPixels, m.heightPixels);
            float aspect = lng / sht;

            float bezel = dp(6);
            float sh = H - 2 * bezel - dp(4);
            float sw = sh * aspect;
            if (sw > W - 2 * bezel - dp(4)) {
                sw = W - 2 * bezel - dp(4);
                sh = sw / aspect;
            }
            float cx = W / 2f, cy = H / 2f;
            RectF body = new RectF(cx - sw / 2 - bezel, cy - sh / 2 - bezel,
                    cx + sw / 2 + bezel, cy + sh / 2 + bezel);
            RectF screen = new RectF(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2);

            // 机身
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1F2937);
            cv.drawRoundRect(body, dp(14), dp(14), p);

            // 遮罩宽度占整个屏幕宽度的比例
            float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
            float offPx = sp.getInt(MaskService.KEY_OFFSET, 0) * density;
            float barPx = (lng - sht * ratio) / 2f + offPx;
            barPx = Math.max(0, Math.min(barPx, lng / 2f));
            float barW = sw * (barPx / lng);

            // 屏幕内容（裁成圆角）
            Path clip = new Path();
            clip.addRoundRect(screen, dp(8), dp(8), Path.Direction.CW);
            cv.save();
            cv.clipPath(clip);
            p.setColor(0xFF0B1020);
            cv.drawRect(screen, p);
            p.setColor(Color.WHITE);
            cv.drawRect(screen.left, screen.top, screen.left + barW, screen.bottom, p);
            cv.drawRect(screen.right - barW, screen.top, screen.right, screen.bottom, p);

            // 播放三角
            float tx = cx, ty = cy, ts = Math.min(sh * 0.16f, dp(14));
            Path tri = new Path();
            tri.moveTo(tx - ts * 0.6f, ty - ts);
            tri.lineTo(tx + ts * 1.0f, ty);
            tri.lineTo(tx - ts * 0.6f, ty + ts);
            tri.close();
            p.setColor(0xFFFFFFFF);
            cv.drawPath(tri, p);
            cv.restore();
        }
    }
}
