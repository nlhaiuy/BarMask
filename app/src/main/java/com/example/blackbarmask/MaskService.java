package com.example.blackbarmask;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** 在屏幕左右两侧各盖一块白色悬浮窗，遮住 4:3 视频两边的黑边。 */
public class MaskService extends Service {
    public static final String PREFS = "mask_prefs";
    public static final String KEY_RATIO = "ratio";   // 视频宽高比，默认 4/3
    public static final String KEY_OFFSET = "offset"; // 微调(dp)，正数=遮罩变宽

    private static final String CHANNEL_ID = "mask";
    public static boolean running = false;

    private WindowManager wm;
    private static final int LAYERS = 4; // 叠几层，对付系统把悬浮窗强制半透明
    private final java.util.List<View> bars = new java.util.ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "黑边遮罩", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("黑边遮罩运行中")
                .setContentText("两侧黑边已变白")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        update();
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        update(); // 旋转屏幕后重新计算
    }

    @SuppressWarnings("deprecation")
    private void update() {
        removeViews();

        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int w = m.widthPixels, h = m.heightPixels;
        if (w <= h) return; // 竖屏不需要遮罩

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        float ratio = sp.getFloat(KEY_RATIO, 4f / 3f);
        int offsetPx = Math.round(sp.getInt(KEY_OFFSET, 0) * m.density);

        int bar = Math.round((w - h * ratio) / 2f) + offsetPx;
        bar = Math.max(0, Math.min(bar, w / 2));
        if (bar == 0) return;

        for (int i = 0; i < LAYERS; i++) {
            bars.add(addBar(Gravity.TOP | Gravity.START, bar, h));
            bars.add(addBar(Gravity.TOP | Gravity.END, bar, h));
        }
    }

    private View addBar(int gravity, int width, int height) {
        View v = new View(this);
        v.setBackgroundColor(0xFFFFFFFF); // 不透明纯白
        v.setAlpha(1f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        lp.alpha = 1f;        // 窗口完全不透明
        lp.dimAmount = 0f;
        lp.gravity = gravity;
        lp.x = 0;
        lp.y = 0;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(v, lp);
        return v;
    }

    private void removeViews() {
        for (View v : bars) {
            try { wm.removeView(v); } catch (Exception ignored) { }
        }
        bars.clear();
    }

    @Override
    public void onDestroy() {
        removeViews();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
