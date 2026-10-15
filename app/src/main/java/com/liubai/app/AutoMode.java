package com.liubai.app;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 开关逻辑：
 *  - 总开关（底部大按钮）关闭 → 任何地方都不显示留白。
 *  - 总开关开启 + 白名单模式开启 → 只在白名单里的软件中显示（靠无障碍服务识别前台软件）。
 *  - 总开关开启 + 白名单模式关闭 → 全局模式：横屏时始终显示。
 * 横屏才显示、竖屏自动隐藏，由 MaskService 自己负责。
 */
public final class AutoMode {
    public static final String KEY_MASTER = "master"; // 总开关
    public static final String KEY_AUTO = "auto";     // 白名单模式：开 = 只在白名单软件里显示；关 = 全局
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
            "com.mxtech.videoplayer.pro",    // MX Player 专业版
            // —— 看番 ——
            "me.him188.ani",                 // Animeko
            "app.anich",                     // AniCh
            "com.predidit.kazumi",           // Kazumi
            "tw.com.gamer.android.animad",   // 巴哈姆特动画疯
            // —— 直播 ——
            "com.xycz.simple_live",          // Simple Live
            "com.slotsun.slive",             // SLive
            "air.tv.douyu.android",          // 斗鱼
            "com.duowan.kiwi",               // 虎牙
            "com.netease.cc",                // 网易CC
            "tv.twitch.android.app",         // Twitch
            // —— 更多国内视频 ——
            "com.sohu.sohuvideo",            // 搜狐视频
            "com.baidu.haokan",              // 好看视频
            "com.baidu.video",               // 百度视频
            "com.letv.android.client",       // 乐视视频
            "com.pplive.androidphone",       // PP视频
            "com.cctv.yangshipin.app.androidp", // 央视频
            "com.tencent.weishi",            // 微视
            "com.ss.android.ugc.aweme.lite", // 抖音极速版
            "com.ss.android.ugc.live",       // 抖音火山版
            "com.kuaishou.nebula",           // 快手极速版
            "com.tencent.qqlivepad",         // 腾讯视频 HD
            "com.qiyi.video.pad",            // 爱奇艺 HD
            // —— 海外 / 本地影音 ——
            "com.disney.disneyplus",         // Disney+
            "com.amazon.avod.thirdpartyclient", // Prime Video
            "com.plexapp.android",           // Plex
            "org.xbmc.kodi",                 // Kodi
            "org.jellyfin.mobile",           // Jellyfin
            "com.mb.android",                // Emby
            "org.schabi.newpipe",            // NewPipe
            "app.revanced.android.youtube",  // YouTube（ReVanced）
    };

    private static Set<String> recommended;

    /** 是不是“推荐的视频软件”（即默认白名单里的软件）。 */
    public static boolean isRecommended(String pkg) {
        if (recommended == null) recommended = new HashSet<>(Arrays.asList(DEFAULT_APPS));
        return recommended.contains(pkg);
    }

    private static String fg = "";
    private static Context appCtx;

    private AutoMode() { }

    // ---------------- 设置 ----------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
    }

    /** 总开关是否开启。 */
    public static boolean isMaster(Context c) {
        return prefs(c).getBoolean(KEY_MASTER, false);
    }

    /** 设置总开关，并立刻按当前模式开启 / 关闭留白。 */
    public static void setMaster(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_MASTER, on).apply();
        evaluate(c);
        try {
            TileService.requestListeningState(c.getApplicationContext(),
                    new ComponentName(c, MaskTileService.class));
        } catch (Exception ignored) { }
    }

    /** 白名单模式是否开启（默认开启）。关闭则为全局模式。 */
    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true);
    }

    public static boolean hasCustomApps(Context c) {
        return prefs(c).contains(KEY_APPS);
    }

    /** 缓存的已选名单：窗口每切一次都要判断，不能每次都从 SharedPreferences 里复制一份。 */
    private static volatile Set<String> selectedCache;

    /** 当前勾选的软件（只读；没自定义过就是默认白名单）。 */
    public static Set<String> selectedApps(Context c) {
        Set<String> s = selectedCache;
        if (s == null) {
            Set<String> saved = prefs(c).getStringSet(KEY_APPS, null);
            s = Collections.unmodifiableSet(saved == null
                    ? new HashSet<>(Arrays.asList(DEFAULT_APPS))
                    : new HashSet<>(saved));
            selectedCache = s;
        }
        return s;
    }

    /** 保存勾选的软件，并更新缓存。 */
    public static void setSelectedApps(Context c, Set<String> apps) {
        Set<String> copy = new HashSet<>(apps);
        prefs(c).edit().putStringSet(KEY_APPS, copy).apply();
        selectedCache = Collections.unmodifiableSet(copy);
    }

    public static boolean isAccessibilityEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        ComponentName cn = new ComponentName(c, AutoAccessibilityService.class);
        return s.contains(cn.flattenToString()) || s.contains(cn.flattenToShortString());
    }

    // ---------------- 事件输入 ----------------

    /**
     * 无障碍服务：前台窗口变化。
     * className 是产生事件的窗口类名：真正的应用页面是 Activity（应用自己的类），
     * 而后台软件弹出的悬浮小窗 / 气泡 / 弹层多半是 android.widget.* 之类的框架类，要忽略，
     * 否则会把“在后台播放的软件”误当成回到了前台。
     */
    public static void onForeground(Context c, String pkg, CharSequence className) {
        if (className != null) {
            String cls = className.toString();
            if (cls.startsWith("android.widget.") || cls.startsWith("android.view.")
                    || cls.startsWith("android.inputmethodservice.")) {
                return;
            }
        }
        onForeground(c, pkg);
    }

    /** 无障碍服务：前台窗口变化。 */
    public static void onForeground(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(c.getPackageName())) return;                     // 在设置留白本身时不算
        if (pkg.equals("com.android.systemui") || pkg.equals("android")) return; // 下拉通知栏等
        if (isIme(c, pkg)) return;                                      // 输入法
        fg = pkg;
        evaluate(c);
    }

    // ---------------- 判断 ----------------

    /** 按“总开关 + 模式 + 当前前台软件”算出该不该显示，和实际状态不一致就开 / 关。 */
    public static void evaluate(Context c) {
        appCtx = c.getApplicationContext();
        boolean desired = shouldShow();
        if (desired != MaskService.running) apply(desired);
    }

    /** 现在该不该显示（不会去开 / 关服务）：服务被系统杀掉后重启时用它先判断一次。 */
    public static boolean shouldShowNow(Context c) {
        appCtx = c.getApplicationContext();
        return shouldShow();
    }

    private static boolean shouldShow() {
        if (!isMaster(appCtx)) return false;        // 总开关关：全部关闭
        if (!isEnabled(appCtx)) return true;        // 白名单模式关：全局显示
        return !fg.isEmpty()                        // 白名单模式开：只在白名单软件里
                && selectedApps(appCtx).contains(fg)
                && isScreenActive(appCtx);
    }

    /** 亮屏且未锁屏：息屏、锁屏后台继续播放时，不显示留白。 */
    private static boolean isScreenActive(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return pm.isInteractive() && !km.isKeyguardLocked();
        } catch (Exception e) {
            return true;
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

    private static Set<String> imeCache;
    private static long imeStamp;

    /** 输入法列表要跨进程查询，而窗口每切一次就判断一次：缓存 30 秒，别每次都查。 */
    private static boolean isIme(Context c, String pkg) {
        long now = SystemClock.uptimeMillis();
        if (imeCache == null || now - imeStamp > 30_000L) {
            Set<String> s = new HashSet<>();
            try {
                InputMethodManager imm =
                        (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                    s.add(info.getPackageName());
                }
            } catch (Exception ignored) { }
            imeCache = s;
            imeStamp = now;
        }
        return imeCache.contains(pkg);
    }
}
