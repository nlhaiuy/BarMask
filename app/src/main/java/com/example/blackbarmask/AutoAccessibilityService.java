package com.example.blackbarmask;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;

/** 只用来知道“现在前台是哪个 App”，不读取屏幕内容。 */
public class AutoAccessibilityService extends AccessibilityService {
    /** 引导用户开启无障碍时置为 true；一开启成功就自动跳回白边，省得手动返回好几层。 */
    public static final String KEY_RETURN = "return_after_a11y";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        SharedPreferences sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        if (sp.getBoolean(KEY_RETURN, false)) {
            sp.edit().putBoolean(KEY_RETURN, false).apply();
            try {
                startActivity(new Intent(this, MainActivity.class).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception ignored) { }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null) AutoMode.onForeground(this, pkg.toString());
    }

    @Override
    public void onInterrupt() { }
}
