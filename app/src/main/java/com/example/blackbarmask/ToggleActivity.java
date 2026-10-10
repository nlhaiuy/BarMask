package com.example.blackbarmask;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** 没有界面的开关：运行中就关闭，没运行就开启，然后立刻退出。 */
public class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent svc = new Intent(this, MaskService.class);
        if (MaskService.running) {
            stopService(svc);
        } else if (Settings.canDrawOverlays(this)) {
            startForegroundService(svc);
        } else {
            Toast.makeText(this, "请先允许“显示在其他应用上层”", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        }
        finish();
    }
}
