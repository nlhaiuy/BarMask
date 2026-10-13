package com.liubai.app;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 两件事：
 *  1. 知道“现在前台是哪个 App”（只读取包名，不读取屏幕内容）。
 *  2. 直接把遮罩挂在自己身上（TYPE_ACCESSIBILITY_OVERLAY）：不需要悬浮窗权限、不需要常驻通知，
 *     也不会遇到“后台启动前台服务被拒绝”。
 */
public class AutoAccessibilityService extends AccessibilityService {
    /** 引导用户开启无障碍时置为 true；一开启成功就自动跳回留白，省得手动返回好几层。 */
    public static final String KEY_RETURN = "return_after_a11y";

    /** 已连接的服务实例；为 null 表示无障碍没开（或还没连上）。 */
    static volatile AutoAccessibilityService instance;

    private MaskOverlay overlay;
    private boolean overlayOn = false;

    /** 亮屏 / 息屏 / 解锁时重新判断：息屏、锁屏后台播放时不显示留白，解锁回到视频软件后自动恢复。 */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            AutoMode.evaluate(c);
        }
    };
    private boolean receiverRegistered = false;

    // ---------------- 遮罩 ----------------

    boolean isOverlayOn() { return overlayOn; }

    /** 开始显示遮罩。失败（比如窗口加不上）返回 false，调用方会改走悬浮窗服务。 */
    boolean showOverlay() {
        try {
            if (overlay == null) {
                overlay = new MaskOverlay(this,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, true);
            }
            overlayOn = true;
            overlay.update();
            return true;
        } catch (RuntimeException e) {
            android.util.Log.w("Liubai", "accessibility overlay failed", e);
            overlayOn = false;
            return false;
        }
    }

    void hideOverlay(boolean animate) {
        overlayOn = false;
        if (overlay != null) overlay.remove(animate);
    }

    void refreshOverlay() {
        if (overlayOn && overlay != null) overlay.update();
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

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
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshOverlay();   // 旋转屏幕后重新计算
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg != null) AutoMode.onForeground(this, pkg.toString(), e.getClassName());
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        boolean wasOn = overlayOn;
        if (instance == this) instance = null;
        hideOverlay(false);
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) { }
            receiverRegistered = false;
        }
        // 无障碍没了：重新判断一次（全局模式下会改走悬浮窗服务；白名单模式下会关掉）
        if (wasOn) AutoMode.evaluate(getApplicationContext());
    }
}
