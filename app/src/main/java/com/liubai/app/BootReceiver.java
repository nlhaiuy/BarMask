package com.liubai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机 / 应用更新后恢复：全局模式下总开关还开着，遮罩要自己回来（白名单模式由无障碍服务重连后接管）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            AutoMode.evaluate(c);
        }
    }
}
