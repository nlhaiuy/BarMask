package com.example.blackbarmask;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 自动模式（白名单）。
 * 无障碍服务告诉我当前前台是哪个 App：在白名单里 → 开启白边，不在 → 关闭。
 * 只在状态“变化”时才开关，所以你手动开关后，会一直保持到下一次前台软件变化。
 * 横屏才显示、竖屏自动隐藏，由 MaskService 自己负责。
 */
public final class AutoMode {
    public static final String KEY_AUTO = "auto";
    public static final String KEY_APPS = "auto_apps";

    /** 默认白名单：常见视频 / 番剧 / 直播软件（没安装的会被忽略，可在“白名单”页增删）。 */
    public static final String[] DEFAULT_APPS = {
            // —— 综合视频 ——
            "tv.danmaku.bili",               // 哔哩哔哩
            "com.bilibili.app.in",           // 哔哩哔哩国际版
            "com.bilibili.app.blue",         // 哔哩哔哩概念版
            "tv.danmaku.bilibilihd",         // 哔哩哔哩 HD
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
            // —— 本地播放器 ——
            "org.videolan.vlc",              // VLC
            "com.mxtech.videoplayer.ad",     // MX Player
            // —— 看番 ——
            "me.him188.ani",                 // Animeko
            "cx.xs.open",                    // AniCh
            "com.predidit.kazumi",           // Kazumi
            "tw.com.gamer.android.animad",   // 巴哈姆特动画疯
            // —— 直播 ——
            "com.xycz.simple_live",          // Simple Live
            "com.slotsun.slive",             // SLive
            "air.tv.douyu.android",          // 斗鱼
            "com.duowan.kiwi",               // 虎牙
            "com.netease.cc",                // 网易CC
            "tv.twitch.android.app",         // Twitch
    };

    private static String fg = "";
    private static boolean lastDesired = false;
    private static Context appCtx;

    private AutoMode() { }

    // ---------------- 设置 ----------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true); // 默认开启白名单模式
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

    // ---------------- 判断 ----------------

    public static void evaluate(Context c) {
        appCtx = c.getApplicationContext();
        if (!isEnabled(appCtx)) {
            lastDesired = false;
            return;
        }
        boolean desired = !fg.isEmpty() && selectedApps(appCtx).contains(fg);
        if (desired != lastDesired) {
            lastDesired = desired;
            apply(desired);
        }
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
