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
import android.os.Build;
import android.os.IBinder;
import android.service.quicksettings.TileService;
import android.view.WindowManager;

/**
 * 悬浮窗方式的遮罩宿主（需要悬浮窗权限 + 前台通知）。
 * 白名单模式下遮罩改由无障碍服务直接挂载（见 AutoAccessibilityService），这个服务只在
 * “全局模式且没开无障碍”时才会用到。
 */
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

    /**
     * 拖动微调时的“实时值”：只放在内存里，松手一会儿再写盘，避免每动一格就写一次文件。
     * LIVE_NONE 表示没有实时值，以 SharedPreferences 里的为准。
     */
    public static final int LIVE_NONE = Integer.MIN_VALUE;
    public static volatile int liveOffset = LIVE_NONE;

    /** 当前生效的微调值(dp)：优先取拖动中的实时值。 */
    public static int offset(SharedPreferences sp) {
        int v = liveOffset;
        return v != LIVE_NONE ? v : sp.getInt(KEY_OFFSET, 0);
    }

    private MaskOverlay overlay;

    @Override
    public void onCreate() {
        super.onCreate();
        overlay = new MaskOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "留白", NotificationManager.IMPORTANCE_LOW));
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        running = true;
        instance = this;
        refreshTile();
    }

    /** 通知：点一下回到留白设置页，带一个“关闭”按钮。 */
    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags);
        PendingIntent off = PendingIntent.getActivity(this, 1,
                new Intent(this, ToggleActivity.class)
                        .setAction(ToggleActivity.ACTION_TOGGLE)
                        .putExtra(ToggleActivity.EXTRA_OFF_ONLY, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags);
        Notification.Action close = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_tile), "关闭", off).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("留白已开启")
                .setContentText("点按打开设置")
                .setSmallIcon(R.drawable.ic_tile)
                .setContentIntent(open)
                .addAction(close)
                .setShowWhen(false)
                .setOngoing(true)
                .build();
    }

    /** 设置变了：能原地改就原地改，不拆也不重建，拖动微调时就不会闪。 */
    public static boolean refresh() {
        MaskService s = instance;
        if (s == null) return false;
        s.overlay.update();
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
        overlay.update();
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        overlay.update(); // 旋转屏幕后重新计算
    }

    @Override
    public void onDestroy() {
        overlay.remove(true);
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
