package com.example.blackbarmask;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private SharedPreferences sp;
    private Switch sw;
    private boolean ignore = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("4:3 视频黑边变白");
        title.setTextSize(24);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("开启后，横屏时屏幕左右两侧会盖上白色遮罩。需要先授予“显示在其他应用上层”权限。\n\n"
                + "如果遮罩没有刚好贴住视频，用下面的滑条微调。");
        tip.setPadding(0, pad / 2, 0, pad);
        root.addView(tip);

        sw = new Switch(this);
        sw.setText("启用遮罩  ");
        sw.setTextSize(18);
        sw.setChecked(MaskService.running);
        sw.setOnCheckedChangeListener((v, on) -> {
            if (ignore) return;
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请先允许“显示在其他应用上层”", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    setChecked(false);
                    return;
                }
                startForegroundService(new Intent(this, MaskService.class));
            } else {
                stopService(new Intent(this, MaskService.class));
            }
        });
        root.addView(sw);

        TextView rt = new TextView(this);
        rt.setText("视频比例");
        rt.setPadding(0, pad, 0, 0);
        root.addView(rt);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"4:3", "5:4", "3:2", "1:1"};
        float[] vals = {4f / 3f, 5f / 4f, 3f / 2f, 1f};
        float cur = sp.getFloat(MaskService.KEY_RATIO, 4f / 3f);
        for (int i = 0; i < names.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[i]);
            rb.setId(1000 + i);
            rg.addView(rb);
            if (Math.abs(vals[i] - cur) < 0.001f) rb.setChecked(true);
        }
        rg.setOnCheckedChangeListener((g, id) -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, vals[id - 1000]).apply();
            refresh();
        });
        root.addView(rg);

        TextView ot = new TextView(this);
        ot.setPadding(0, pad, 0, 0);
        root.addView(ot);

        SeekBar sb = new SeekBar(this);
        sb.setMax(200); // 对应 -100dp ~ +100dp
        int off = sp.getInt(MaskService.KEY_OFFSET, 0);
        sb.setProgress(off + 100);
        ot.setText("宽度微调：" + off + " dp（正数=遮罩更宽）");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                int o = p - 100;
                ot.setText("宽度微调：" + o + " dp（正数=遮罩更宽）");
                sp.edit().putInt(MaskService.KEY_OFFSET, o).apply();
                if (user) refresh();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        root.addView(sb);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setChecked(MaskService.running);
    }

    private void setChecked(boolean c) {
        ignore = true;
        sw.setChecked(c);
        ignore = false;
    }

    private void refresh() {
        if (MaskService.running) {
            startForegroundService(new Intent(this, MaskService.class));
        }
    }
}
