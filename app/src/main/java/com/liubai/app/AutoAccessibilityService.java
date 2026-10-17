package com.liubai.app;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;

/** 只用来知道“现在前台是哪个 App”，不读取屏幕内容。 */
public class AutoAccessibilityService extends AccessibilityService {
    /** 引导用户开启无障碍时置为 true；一开启成功就自动跳回留白，省得手动返回好几层。 */
    public static final String KEY_RETURN = "return_after_a11y";

    /** 亮屏 / 息屏 / 解锁时重新判断：息屏、锁屏后台播放时不显示留白，解锁回到视频软件后自动恢复。 */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            AutoMode.evaluate(c);
        }
    };
    private boolean receiverRegistered = false;

    /** 服务是否真的连上了（设置里开关是开的，不代表系统已经把服务连起来）。 */
    public static volatile boolean connected = false;
    /** 连上 / 断开时通知界面刷新。 */
    public static volatile Runnable onStateChanged;

    private static void notifyState() {
        Runnable r = onStateChanged;
        if (r != null) r.run();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        notifyState();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f);
        receiverRegistered = true;
        AutoMode.evaluate(this);

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
        if (e == null) return;
        int t = e.getEventType();
        CharSequence pkg = e.getPackageName();
        if (pkg == null) return;
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AutoMode.onForeground(this, pkg.toString(), e.getClassName());
        } else if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !AutoMode.hasForeground()) {
            // 重启 / 更新后服务刚连上，还没等到窗口切换：用界面内容变化的事件先认出当前前台软件
            AutoMode.onForeground(this, pkg.toString());
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        notifyState();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        notifyState();
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
