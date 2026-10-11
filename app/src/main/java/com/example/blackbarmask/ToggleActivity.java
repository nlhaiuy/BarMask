package com.example.blackbarmask;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** 没有界面的总开关：开着就关闭，关着就开启，然后立刻退出（桌面长按菜单 / 控制中心磁贴用）。 */
public class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (AutoMode.isMaster(this)) {
            AutoMode.setMaster(this, false);
        } else if (Settings.canDrawOverlays(this)) {
            AutoMode.setMaster(this, true);
        } else {
            Toast.makeText(this, "请先允许“显示在其他应用上层”", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
        finish();
    }
}
