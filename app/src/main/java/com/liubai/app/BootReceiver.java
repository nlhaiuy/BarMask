package com.liubai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 升级后：按总开关和模式恢复遮罩（白名单模式下仍由无障碍服务根据前台应用决定）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        AutoMode.evaluate(c);
    }
}
