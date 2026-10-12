package com.example.blackbarmask;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
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
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** 在屏幕左右两侧各盖一块白色悬浮窗，遮住横屏视频两边的黑边。 */
public class MaskService extends Service {
    public static final String PREFS = "mask_prefs";
    public static final String KEY_RATIO = "ratio";   // 视频宽高比，默认 16/9
    public static final String KEY_OFFSET = "offset"; // 微调(dp)，正数=遮罩变宽
    public static final String KEY_LAYERS = "layers"; // 每侧叠几层：1=标准，3=加强
    public static final float DEFAULT_RATIO = 16f / 9f;
    public static final int DEFAULT_LAYERS = 1;
    public static final int MAX_LAYERS = 6;

    private static final String CHANNEL_ID = "mask";
    public static boolean running = false;

    private WindowManager wm;
    private final java.util.List<View> bars = new java.util.ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "白边", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("白边已开启")
                .setSmallIcon(R.drawable.ic_tile)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        running = true;
        refreshTile();
    }

    private void refreshTile() {
        try {
            TileService.requestListeningState(this,
                    new ComponentName(this, MaskTileService.class));
        } catch (Exception ignored) { }
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
        float ratio = sp.getFloat(KEY_RATIO, DEFAULT_RATIO);
        int layers = Math.max(1, Math.min(MAX_LAYERS, sp.getInt(KEY_LAYERS, DEFAULT_LAYERS)));
        int offsetPx = Math.round(sp.getInt(KEY_OFFSET, 0) * m.density);

        int bar = Math.round((w - h * ratio) / 2f) + offsetPx;
        bar = Math.max(0, Math.min(bar, w / 2));
        if (bar == 0) return;

        for (int i = 0; i < layers; i++) {
            bars.add(addBar(Gravity.TOP | Gravity.START, bar, h));
            bars.add(addBar(Gravity.TOP | Gravity.END, bar, h));
        }
    }

    private View addBar(int gravity, int width, int height) {
        View v = new View(this);
        v.setBackgroundColor(Color.WHITE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
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
        refreshTile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
