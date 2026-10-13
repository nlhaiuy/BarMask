package com.liubai.app;

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
import android.animation.ValueAnimator;
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
    /** 正在运行的服务：设置页拖动微调时直接调它，不用每一格都重新启动服务。 */
    private static MaskService instance;

    private static final long FADE_IN_MS = 180, FADE_OUT_MS = 160;

    private WindowManager wm;
    private final java.util.List<View> bars = new java.util.ArrayList<>();
    private int builtW, builtH, builtLayers;    // 当前这批遮罩是按什么尺寸建的

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "留白", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("留白已开启")
                .setSmallIcon(R.drawable.ic_tile)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        running = true;
        instance = this;
        refreshTile();
    }

    /** 设置变了：能原地改就原地改，不拆也不重建，拖动微调时就不会闪。 */
    public static boolean refresh() {
        MaskService s = instance;
        if (s == null) return false;
        s.update();
        return true;
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
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int w = m.widthPixels, h = m.heightPixels;
        if (w <= h) {            // 竖屏不需要遮罩
            removeViews(false);
            return;
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        float ratio = sp.getFloat(KEY_RATIO, DEFAULT_RATIO);
        int layers = Math.max(1, Math.min(MAX_LAYERS, sp.getInt(KEY_LAYERS, DEFAULT_LAYERS)));
        int offsetPx = Math.round(sp.getInt(KEY_OFFSET, 0) * m.density);

        int bar = Math.round((w - h * ratio) / 2f) + offsetPx;
        bar = Math.max(0, Math.min(bar, w / 2));
        if (bar == 0) {
            removeViews(false);
            return;
        }

        // 层数、屏幕高度都没变：只改宽度，原有的悬浮窗直接调整，不拆不建
        if (bars.size() == layers * 2 && builtH == h && builtLayers == layers) {
            if (builtW != bar) {
                for (View v : bars) {
                    try {
                        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                        lp.width = bar;
                        wm.updateViewLayout(v, lp);
                    } catch (Exception ignored) { }
                }
                builtW = bar;
            }
            return;
        }

        removeViews(false);
        for (int i = 0; i < layers; i++) {
            bars.add(addBar(Gravity.TOP | Gravity.START, bar, h));
            bars.add(addBar(Gravity.TOP | Gravity.END, bar, h));
        }
        builtW = bar;
        builtH = h;
        builtLayers = layers;
        fade(new java.util.ArrayList<>(bars), 0f, 1f, FADE_IN_MS, false);
    }

    /**
     * 整体淡入 / 淡出：用窗口自身的透明度，不改变悬浮窗的像素格式（所以遮得还是一样实）。
     * 每个窗口一帧一帧地更新，总共约 0.2 秒。
     */
    private void fade(final java.util.List<View> views, float from, float to, long ms,
                      final boolean removeAtEnd) {
        final WindowManager manager = wm;
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.addUpdateListener(an -> {
            float alpha = (Float) an.getAnimatedValue();
            for (View v : views) {
                try {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                    if (lp == null) continue;
                    lp.alpha = alpha;
                    manager.updateViewLayout(v, lp);
                } catch (Exception ignored) { }
            }
        });
        if (removeAtEnd) {
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    for (View v : views) {
                        try { manager.removeView(v); } catch (Exception ignored) { }
                    }
                }
            });
        }
        a.start();
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
        lp.alpha = 0f;
        lp.x = 0;
        lp.y = 0;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        wm.addView(v, lp);
        return v;
    }

    /** 去掉全部遮罩；animate 为 true 时先淡出再去掉（只用在整个服务关闭时）。 */
    private void removeViews(boolean animate) {
        if (bars.isEmpty()) return;
        if (animate) {
            fade(new java.util.ArrayList<>(bars), 1f, 0f, FADE_OUT_MS, true);
        } else {
            for (View v : bars) {
                try { wm.removeView(v); } catch (Exception ignored) { }
            }
        }
        bars.clear();
        builtW = builtH = builtLayers = 0;
    }

    @Override
    public void onDestroy() {
        removeViews(true);
        running = false;
        if (instance == this) instance = null;
        refreshTile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
