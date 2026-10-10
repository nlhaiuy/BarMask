package com.example.blackbarmask;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 自动模式的“大脑”。
 * 输入：无障碍服务告诉我当前前台是哪个 App；通知使用权服务告诉我哪些 App 正在播放媒体。
 * 规则：前台 App 在名单里，并且（正在播放 / 刚暂停不久 / 系统读不到它的播放状态）→ 开启白边，否则关闭。
 * 只在状态“变化”时才开关，所以你手动开关后，会一直保持到下一次状态变化。
 * 横屏才显示、竖屏自动隐藏，则由 MaskService 自己负责。
 */
public final class AutoMode {
    public static final String KEY_AUTO = "auto";
    public static final String KEY_APPS = "auto_apps";

    /** 默认勾选的常见视频软件（没安装的会被忽略）。 */
    public static final String[] DEFAULT_APPS = {
            "tv.danmaku.bili",               // 哔哩哔哩
            "com.bilibili.app.in",           // 哔哩哔哩国际版
            "com.qiyi.video",                // 爱奇艺
            "com.tencent.qqlive",            // 腾讯视频
            "com.youku.phone",               // 优酷
            "com.hunantv.imgo.activity",     // 芒果TV
            "com.cmcc.cmvideo",              // 咪咕视频
            "com.ss.android.ugc.aweme",      // 抖音
            "com.ss.android.article.video",  // 西瓜视频
            "com.smile.gifmaker",            // 快手
            "com.meizu.media.video",         // 魅族视频
            "com.google.android.youtube",    // YouTube
            "com.netflix.mediaclient",       // Netflix
            "org.videolan.vlc",              // VLC
            "com.mxtech.videoplayer.ad",     // MX Player
    };

    private static final long GRACE_MS = 60_000; // 暂停后继续保持白边的时间
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static String fg = "";
    private static final Map<String, Boolean> media = new HashMap<>();      // 包名 -> 是否正在播放
    private static final Map<String, Long> lastPlaying = new HashMap<>();   // 包名 -> 最近一次停止播放的时间
    private static boolean lastDesired = false;
    private static Context appCtx;

    private static final Runnable RECHECK = () -> {
        if (appCtx != null) evaluate(appCtx);
    };

    private AutoMode() { }

    // ---------------- 设置 ----------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, false);
    }

    public static boolean hasCustomApps(Context c) {
        return prefs(c).contains(KEY_APPS);
    }

    public static Set<String> selectedApps(Context c) {
        Set<String> s = prefs(c).getStringSet(KEY_APPS, null);
        if (s == null) return new HashSet<>(Arrays.asList(DEFAULT_APPS));
        return new HashSet<>(s);
    }

    public static boolean isAccessibilityEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        ComponentName cn = new ComponentName(c, AutoAccessibilityService.class);
        return s.contains(cn.flattenToString()) || s.contains(cn.flattenToShortString());
    }

    public static boolean isListenerEnabled(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm.isNotificationListenerAccessGranted(new ComponentName(c, MediaListener.class));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- 事件输入 ----------------

    /** 无障碍服务：前台窗口变化。 */
    public static void onForeground(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(c.getPackageName())) return;                     // 在设置白边本身时不算
        if (pkg.equals("com.android.systemui") || pkg.equals("android")) return; // 下拉通知栏等
        if (isIme(c, pkg)) return;                                      // 输入法
        fg = pkg;
        evaluate(c);
    }

    /** 通知使用权服务：当前各 App 的播放状态。 */
    public static void onMedia(Context c, Map<String, Boolean> snapshot) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Boolean> e : media.entrySet()) {
            // 刚从“播放”变成“暂停/消失”：记下时间，用来计算暂停后的保持时间
            if (e.getValue() && !Boolean.TRUE.equals(snapshot.get(e.getKey()))) {
                lastPlaying.put(e.getKey(), now);
            }
        }
        media.clear();
        media.putAll(snapshot);
        evaluate(c);
    }

    // ---------------- 判断 ----------------

    public static void evaluate(Context c) {
        appCtx = c.getApplicationContext();
        H.removeCallbacks(RECHECK);
        if (!isEnabled(appCtx)) {
            lastDesired = false;
            return;
        }
        boolean desired = compute();
        if (desired != lastDesired) {
            lastDesired = desired;
            apply(desired);
        }
    }

    private static boolean compute() {
        if (fg.isEmpty() || !selectedApps(appCtx).contains(fg)) return false;
        Boolean playing = media.get(fg);
        if (playing == null) return true;   // 系统读不到它的播放状态：按“在前台”处理
        if (playing) return true;
        Long t = lastPlaying.get(fg);
        long left = t == null ? 0 : GRACE_MS - (System.currentTimeMillis() - t);
        if (left > 0) {                     // 刚暂停：保持一会儿，到点再复查
            H.postDelayed(RECHECK, left + 300);
            return true;
        }
        return false;
    }

    private static void apply(boolean on) {
        Intent i = new Intent(appCtx, MaskService.class);
        if (on) {
            if (!Settings.canDrawOverlays(appCtx)) return;
            try {
                appCtx.startForegroundService(i);
            } catch (Exception ignored) { }
        } else {
            appCtx.stopService(i);
        }
    }

    private static boolean isIme(Context c, String pkg) {
        try {
            InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                if (info.getPackageName().equals(pkg)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
