package com.liubai.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** 没有界面的总开关：开着就关闭，关着就开启，然后立刻退出（桌面长按菜单 / 控制中心磁贴 / 通知“关闭”按钮用）。 */
public class ToggleActivity extends Activity {
    public static final String ACTION_TOGGLE = "com.liubai.app.TOGGLE";
    /** 只关不开：通知上的“关闭”按钮用，避免误把已经关掉的又打开。 */
    public static final String EXTRA_OFF_ONLY = "off_only";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean offOnly = getIntent() != null && getIntent().getBooleanExtra(EXTRA_OFF_ONLY, false);
        if (AutoMode.isMaster(this)) {
            AutoMode.setMaster(this, false);
        } else if (offOnly) {
            // 已经是关闭状态，什么也不做
        } else if (AutoMode.canShow(this)) {
            AutoMode.setMaster(this, true);
        } else if (AutoMode.isEnabled(this)) {
            Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
        } else {
            Toast.makeText(this, "请先允许悬浮窗权限", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
        finish();
    }
}
