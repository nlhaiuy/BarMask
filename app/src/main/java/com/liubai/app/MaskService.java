package com.liubai.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Display;
import android.view.WindowManager;

/**
 * 在屏幕左右两侧各盖一块白色悬浮窗，遮住横屏视频两边的黑边。
 *
 * 标准：普通悬浮窗，每侧 1 层。
 * 加强：优先用无障碍服务自己的悬浮窗（Android 12+ 对普通悬浮窗的穿透触摸有 0.8 的透明度上限，
 *       叠 3 层才够实；无障碍窗口是受信任窗口，单层就是纯白）。
 *       无障碍没开 / 没连上时，退回普通悬浮窗叠 3 层。
 */
public class MaskService extends Service {
    public static final String PREFS = "mask_prefs";
    public static final String KEY_RATIO = "ratio";   // 视频宽高比，默认 16/9
    public static final String KEY_OFFSET = "offset"; // 微调(dp)，正数=遮罩变宽
    public static final String KEY_LAYERS = "layers"; // 每侧叠几层：1=标准，3=加强
    public static final float DEFAULT_RATIO = 16f / 9f;
    public static final int DEFAULT_LAYERS = 1;
    public static final int MAX_LAYERS = 6;

    /**
     * 加强模式在无障碍窗口里每侧叠几层。
     * 如果实机上看加强时边缘还隐约透出一点黑，把它改成 2 即可（仍然比 3 层少一个窗口）。
     */
    static final int A11Y_LAYERS = 1;

    private static final String CHANNEL_ID = "mask";
    public static boolean running = false;
    /** 正在运行的服务：设置页拖动微调时直接调它，不用每一格都重新启动服务。 */
    private static MaskService instance;

    private static final int APP = 0, A11Y = 1;

    private WindowManager wm;
    private MaskOverlay appOverlay;
    private MaskOverlay a11yOverlay;
    private AutoAccessibilityService a11yOwner;
    private int active = -1;                           // 现在由哪一组在显示
    private final Runnable[] pendingHide = new Runnable[2];
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 拖动微调时一帧里可能来好几次更新：合并成每帧最多算一次
    private boolean framePending;
    private final Choreographer.FrameCallback frameCb = t -> {
        framePending = false;
        update();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        appOverlay = new MaskOverlay(this, wm, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent off = PendingIntent.getBroadcast(this, 1,
                new Intent(this, MaskActionReceiver.class).setAction(MaskActionReceiver.ACTION_OFF),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_tile),
                        getString(R.string.notif_action_off), off).build())
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
        s.scheduleUpdate();
        return true;
    }

    private void scheduleUpdate() {
        if (framePending) return;
        framePending = true;
        Choreographer.getInstance().postFrameCallback(frameCb);
    }

    private void refreshTile() {
        try {
            TileService.requestListeningState(this,
                    new ComponentName(this, MaskTileService.class));
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // intent 为空 = 进程被系统杀掉后重启的服务。此时记不得前台是哪个软件，
        // 白名单模式下如果直接显示，遮罩会盖到所有软件上；先按当前状态判断一次。
        if (intent == null && !AutoMode.shouldShowNow(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
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
            hideAll(false);
            return;
        }

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        float ratio = sp.getFloat(KEY_RATIO, DEFAULT_RATIO);
        int layers = Math.max(1, Math.min(MAX_LAYERS, sp.getInt(KEY_LAYERS, DEFAULT_LAYERS)));
        int offsetPx = Math.round(sp.getInt(KEY_OFFSET, 0) * m.density);

        int bar = Math.round((w - h * ratio) / 2f) + offsetPx;
        bar = Math.max(0, Math.min(bar, w / 2));
        if (bar == 0) {
            hideAll(false);
            return;
        }

        MaskOverlay a11y = layers >= 2 ? a11yOverlayOrNull() : null;
        if (a11y != null) {
            active = A11Y;
            a11y.show(bar, h, A11Y_LAYERS);
            retire(APP, appOverlay);
        } else {
            active = APP;
            appOverlay.show(bar, h, layers);
            if (a11yOverlay != null) retire(A11Y, a11yOverlay);
        }
    }

    /** 无障碍服务连着时，返回它名下的悬浮窗组；没连着返回 null（并收掉旧的）。 */
    private MaskOverlay a11yOverlayOrNull() {
        AutoAccessibilityService svc = AutoAccessibilityService.get();
        if (svc == null) {
            if (a11yOverlay != null) {
                a11yOverlay.hide(false);
                a11yOverlay = null;
                a11yOwner = null;
            }
            return null;
        }
        if (a11yOverlay == null || svc != a11yOwner) {
            if (a11yOverlay != null) a11yOverlay.hide(false);
            WindowManager awm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            a11yOverlay = new MaskOverlay(svc, awm,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
            a11yOwner = svc;
        }
        return a11yOverlay;
    }

    /**
     * 换了一组窗口：等新的一组淡入完成后再收掉旧的，
     * 这样中间不会有一瞬间两边都没遮实、露出黑边。
     */
    private void retire(final int idx, final MaskOverlay old) {
        if (!old.isShown() || pendingHide[idx] != null) return;
        pendingHide[idx] = () -> {
            pendingHide[idx] = null;
            if (active != idx) old.hide(false);
        };
        handler.postDelayed(pendingHide[idx], MaskOverlay.FADE_IN_MS + 40);
    }

    private void hideAll(boolean animate) {
        active = -1;
        for (int i = 0; i < pendingHide.length; i++) {
            if (pendingHide[i] != null) {
                handler.removeCallbacks(pendingHide[i]);
                pendingHide[i] = null;
            }
        }
        appOverlay.hide(animate);
        if (a11yOverlay != null) a11yOverlay.hide(animate);
    }

    @Override
    public void onDestroy() {
        Choreographer.getInstance().removeFrameCallback(frameCb);
        framePending = false;
        hideAll(true);
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
