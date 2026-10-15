package com.liubai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 通知栏里的“关闭遮罩”按钮：不用打开界面，直接把总开关关掉。 */
public class MaskActionReceiver extends BroadcastReceiver {
    static final String ACTION_OFF = "com.liubai.app.ACTION_MASK_OFF";

    @Override
    public void onReceive(Context c, Intent i) {
        if (i != null && ACTION_OFF.equals(i.getAction())) {
            AutoMode.setMaster(c, false);
        }
    }
}
