package com.liubai.app;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 开关逻辑：
 *  - 总开关（底部大按钮）关闭 → 任何地方都不显示留白。
 *  - 总开关开启 + 白名单模式开启 → 只在白名单里的软件中显示（靠无障碍服务识别前台软件，遮罩也由它直接挂载）。
 *  - 总开关开启 + 白名单模式关闭 → 全局模式：横屏时始终显示（有无障碍就用无障碍挂载，没有就用悬浮窗服务）。
 *  - “仅播放时显示”开启 → 还要求当前有媒体在出声（暂停后保留一小段时间，免得暂停一下就闪回黑边）。
 * 横屏才显示、竖屏自动隐藏，由 MaskOverlay 自己负责。
 */
public final class AutoMode {
    public static final String KEY_MASTER = "master"; // 总开关
    public static final String KEY_AUTO = "auto";     // 白名单模式：开 = 只在白名单软件里显示；关 = 全局
    public static final String KEY_APPS = "auto_apps";
    public static final String KEY_PLAY_ONLY = "play_only"; // 仅播放时显示

    /** 离开白名单软件后，遮罩多等一会儿再撤：分享面板、权限弹窗、小窗等短暂窗口不会让它闪一下。 */
    private static final long OFF_DEBOUNCE_MS = 400;
    /** 声音停了（暂停 / 切集 / 缓冲）后，遮罩继续保留的时间。 */
    private static final long PLAY_GRACE_MS = 30_000;

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

    private static final Handler H = new Handler(Looper.getMainLooper());
    /** 延迟复查：既用来做“撤遮罩”的防抖，也用来在“暂停保留期”结束时收尾。 */
    private static final Runnable RECHECK = () -> eval(true);

    private static String fg = "";
    private static Context appCtx;

    // 白名单缓存：无障碍每切一次窗口都要判断，不能每次都从 SharedPreferences 里重新拷一份
    private static Set<String> whitelist;
    // 必须用静态字段强引用住，SharedPreferences 只弱引用监听器
    private static SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    // 输入法包名缓存（跨进程查询，不能每个事件都查）
    private static Set<String> imePkgs;
    private static long imeLoadedAt;

    // 播放状态
    private static AudioManager audio;
    private static AudioManager.AudioPlaybackCallback playCb;
    private static boolean wasPlaying;
    private static long lastPlayingAt;

    private static Runnable stateListener;

    private AutoMode() { }

    private static synchronized void init(Context c) {
        if (appCtx != null) return;
        appCtx = c.getApplicationContext();
        prefListener = (sp, key) -> {
            if (key == null || KEY_APPS.equals(key)) whitelist = null;
        };
        prefs(appCtx).registerOnSharedPreferenceChangeListener(prefListener);
        try {
            audio = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            playCb = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    boolean now = anyMedia(configs);
                    if (now || wasPlaying) lastPlayingAt = SystemClock.elapsedRealtime();
                    wasPlaying = now;
                    if (isPlayOnly(appCtx)) {
                        eval(false);
                        notifyState();
                    }
                }
            };
            audio.registerAudioPlaybackCallback(playCb, H);
        } catch (Exception ignored) { }
    }

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
        notifyState();
        try {
            TileService.requestListeningState(c.getApplicationContext(),
                    new ComponentName(c, MaskTileService.class));
        } catch (Exception ignored) { }
    }

    /** 白名单模式是否开启（默认开启）。关闭则为全局模式。 */
    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true);
    }

    /** 是否只在播放时显示（默认关闭：横屏就显示）。 */
    public static boolean isPlayOnly(Context c) {
        return prefs(c).getBoolean(KEY_PLAY_ONLY, false);
    }

    public static boolean hasCustomApps(Context c) {
        return prefs(c).contains(KEY_APPS);
    }

    /** 当前白名单（只读，已缓存）。 */
    public static Set<String> selectedApps(Context c) {
        init(c);
        Set<String> s = whitelist;
        if (s == null) {
            Set<String> saved = prefs(c).getStringSet(KEY_APPS, null);
            s = Collections.unmodifiableSet(saved == null
                    ? new HashSet<>(Arrays.asList(DEFAULT_APPS))
                    : new HashSet<>(saved));
            whitelist = s;
        }
        return s;
    }

    public static boolean isAccessibilityEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        ComponentName cn = new ComponentName(c, AutoAccessibilityService.class);
        return s.contains(cn.flattenToString()) || s.contains(cn.flattenToShortString());
    }

    /** 现在有没有办法画出遮罩：无障碍已连接，或者有悬浮窗权限。 */
    public static boolean canShow(Context c) {
        return AutoAccessibilityService.instance != null || Settings.canDrawOverlays(c);
    }

    // ---------------- 状态（给界面用） ----------------

    /** 遮罩宿主是否在运行（不代表此刻一定画出来了：竖屏时宿主在，但不画）。 */
    public static boolean isActive() {
        AutoAccessibilityService a = AutoAccessibilityService.instance;
        return MaskService.running || (a != null && a.isOverlayOn());
    }

    /** 界面注册一个回调，状态变化时在主线程调用（界面在前台时才注册，离开时传 null）。 */
    public static void setStateListener(Runnable r) {
        stateListener = r;
    }

    private static void notifyState() {
        Runnable r = stateListener;
        if (r != null) H.post(r);
    }

    /** 一句话说明现在是什么状态、为什么还没显示。 */
    public static String statusHint(Context c) {
        init(c);
        if (!isMaster(c)) return "点下方开关启用";
        if (isActive()) return "遮罩已生效，横屏时显示";
        if (isEnabled(c)) {
            if (AutoAccessibilityService.instance == null) return "需开启无障碍，才能识别视频软件";
            if (fg.isEmpty() || !selectedApps(c).contains(fg)) return "进入已选的视频软件后自动生效";
        }
        if (isPlayOnly(c) && !playbackOk()) return "开始播放后自动生效";
        return "正在待命";
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

    /** 无障碍服务：前台窗口变化。先做最便宜的判断，输入法查询放在最后。 */
    public static void onForeground(Context c, String pkg) {
        init(c);
        if (pkg == null || pkg.isEmpty()) return;
        if (pkg.equals(c.getPackageName())) return;                     // 在设置留白本身时不算
        if (pkg.equals("com.android.systemui") || pkg.equals("android")) return; // 下拉通知栏等
        if (pkg.equals(fg)) {                                           // 还是同一个软件：不用再查输入法
            eval(false);
            return;
        }
        if (isIme(c, pkg)) return;                                      // 输入法
        fg = pkg;
        eval(false);
        notifyState();
    }

    // ---------------- 判断 ----------------

    /** 按“总开关 + 模式 + 当前前台软件 + 播放状态”算出该不该显示，和实际状态不一致就开 / 关。 */
    public static void evaluate(Context c) {
        init(c);
        eval(false);
    }

    private static void eval(boolean immediate) {
        H.removeCallbacks(RECHECK);
        boolean showing = isActive();
        boolean desired = shouldShow();
        if (desired == showing) {
            scheduleGraceEnd(desired);
            return;
        }
        if (!desired && !immediate && !hardOff()) {
            // 只是换到了别的窗口：先等一小会儿，要是马上又回来了就不用撤
            H.postDelayed(RECHECK, OFF_DEBOUNCE_MS);
            return;
        }
        apply(desired);
        scheduleGraceEnd(desired);
    }

    /** 不需要等的情形：总开关关了、息屏 / 锁屏、无障碍没了（白名单模式识别不了前台）。 */
    private static boolean hardOff() {
        if (!isMaster(appCtx)) return true;
        if (isEnabled(appCtx)) {
            return AutoAccessibilityService.instance == null || !isScreenActive(appCtx);
        }
        return false;
    }

    private static boolean shouldShow() {
        if (!isMaster(appCtx)) return false;        // 总开关关：全部关闭
        if (isEnabled(appCtx)) {                    // 白名单模式开：只在白名单软件里
            if (AutoAccessibilityService.instance == null) return false;  // 没有无障碍就识别不了前台
            if (fg.isEmpty() || !selectedApps(appCtx).contains(fg)) return false;
            if (!isScreenActive(appCtx)) return false;
        }
        return playbackOk();                        // 全局模式或白名单命中：再看是否要求“正在播放”
    }

    // ---------------- 播放检测 ----------------

    private static boolean anyMedia(List<AudioPlaybackConfiguration> configs) {
        if (configs == null) return false;
        for (AudioPlaybackConfiguration cfg : configs) {
            int u = cfg.getAudioAttributes().getUsage();
            if (u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME
                    || u == AudioAttributes.USAGE_UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    private static boolean mediaPlaying() {
        try {
            return audio != null && anyMedia(audio.getActivePlaybackConfigurations());
        } catch (Exception e) {
            return false;
        }
    }

    /** 没开“仅播放时显示”就总是通过；开了则要求正在出声，或者刚停不久（暂停保留期内）。 */
    private static boolean playbackOk() {
        if (!isPlayOnly(appCtx)) return true;
        if (mediaPlaying()) {
            wasPlaying = true;
            lastPlayingAt = SystemClock.elapsedRealtime();
            return true;
        }
        return graceLeft() > 0;
    }

    private static long graceLeft() {
        if (!isPlayOnly(appCtx) || lastPlayingAt == 0) return 0;
        long left = PLAY_GRACE_MS - (SystemClock.elapsedRealtime() - lastPlayingAt);
        return Math.max(0, left);
    }

    /** 正处在保留期内：到期时自己再判断一次，不用等别的事件来触发。 */
    private static void scheduleGraceEnd(boolean showing) {
        if (!showing || !isPlayOnly(appCtx) || mediaPlaying()) return;
        long left = graceLeft();
        if (left > 0) H.postDelayed(RECHECK, left + 50);
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

    // ---------------- 执行 ----------------

    private static void apply(boolean on) {
        AutoAccessibilityService a = AutoAccessibilityService.instance;
        if (on) {
            // 优先让无障碍服务直接挂遮罩：不要悬浮窗权限、不要前台通知
            if (a != null && a.showOverlay()) {
                if (MaskService.running) appCtx.stopService(new Intent(appCtx, MaskService.class));
                notifyState();
                return;
            }
            if (!Settings.canDrawOverlays(appCtx)) return;
            try {
                appCtx.startForegroundService(new Intent(appCtx, MaskService.class));
            } catch (Exception e) {
                android.util.Log.w("Liubai", "start MaskService failed", e);
            }
        } else {
            if (a != null && a.isOverlayOn()) a.hideOverlay(true);
            if (MaskService.running) appCtx.stopService(new Intent(appCtx, MaskService.class));
        }
        notifyState();
    }

    /** 设置变了（比如拖动微调）：让正在显示的遮罩立刻刷新。 */
    public static void refreshMask(Context c) {
        init(c);
        AutoAccessibilityService a = AutoAccessibilityService.instance;
        if (a != null && a.isOverlayOn()) a.refreshOverlay();
        if (MaskService.running && !MaskService.refresh()) {
            try {
                appCtx.startForegroundService(new Intent(appCtx, MaskService.class));
            } catch (Exception ignored) { }
        }
    }

    private static boolean isIme(Context c, String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (imePkgs == null || now - imeLoadedAt > 60_000) {
            Set<String> s = new HashSet<>();
            try {
                InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                for (InputMethodInfo info : imm.getEnabledInputMethodList()) s.add(info.getPackageName());
            } catch (Exception ignored) { }
            imePkgs = s;
            imeLoadedAt = now;
        }
        return imePkgs.contains(pkg);
    }
}
