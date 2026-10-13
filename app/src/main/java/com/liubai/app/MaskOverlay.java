package com.liubai.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 在屏幕左右两侧各盖一块白色悬浮窗，遮住横屏视频两边的黑边。
 * 这个类只管“画”，不关心挂在哪里：
 *  - 挂在 MaskService 上：TYPE_APPLICATION_OVERLAY，需要悬浮窗权限 + 前台通知；
 *  - 挂在无障碍服务上：TYPE_ACCESSIBILITY_OVERLAY，不要悬浮窗权限、不要通知，也不受后台启动限制。
 */
final class MaskOverlay {
    private static final long FADE_IN_MS = 180, FADE_OUT_MS = 160;
    /** 用窗口透明度淡入淡出时，把整个过程切成几档：每档才跨进程更新一次，而不是每一帧。 */
    private static final int FADE_STEPS = 5;

    private final Context ctx;
    private final WindowManager wm;
    private final int windowType;
    /**
     * true：窗口是半透明格式，淡入淡出直接动 View 自己的透明度（动画在本进程里跑，零跨进程调用）。
     * false：窗口是不透明格式，只能用窗口自身的透明度淡入淡出（分档更新，见 FADE_STEPS）。
     */
    private final boolean viewFade;

    private final List<View> bars = new ArrayList<>();
    private int builtW, builtH, builtLayers;   // 当前这批遮罩是按什么尺寸建的
    private ValueAnimator fadeAnim;

    MaskOverlay(Context ctx, int windowType, boolean viewFade) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.windowType = windowType;
        this.viewFade = viewFade;
    }

    /** 按当前设置和屏幕尺寸刷新：能原地改宽度就原地改，不拆不建，拖动微调时就不会闪。 */
    @SuppressWarnings("deprecation")
    void update() {
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        int w = m.widthPixels, h = m.heightPixels;
        if (w <= h) {            // 竖屏不需要遮罩
            remove(false);
            return;
        }

        SharedPreferences sp = ctx.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int layers = Math.max(1, Math.min(MaskService.MAX_LAYERS,
                sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS)));
        int offsetPx = Math.round(MaskService.offset(sp) * m.density);

        int bar = Math.round((w - h * ratio) / 2f) + offsetPx;
        bar = Math.max(0, Math.min(bar, w / 2));
        if (bar == 0) {
            remove(false);
            return;
        }

        // 层数、屏幕高度都没变：只改宽度，原有的悬浮窗直接调整
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

        remove(false);
        try {
            for (int i = 0; i < layers; i++) {
                bars.add(addBar(Gravity.TOP | Gravity.START, bar, h));
                bars.add(addBar(Gravity.TOP | Gravity.END, bar, h));
            }
        } catch (RuntimeException e) {      // 没有权限 / 窗口令牌失效：不要把服务带崩
            android.util.Log.w("Liubai", "add overlay failed", e);
            remove(false);
            return;
        }
        builtW = bar;
        builtH = h;
        builtLayers = layers;
        fade(new ArrayList<>(bars), true, FADE_IN_MS);
    }

    private View addBar(int gravity, int width, int height) {
        View v = new View(ctx);
        v.setBackgroundColor(Color.WHITE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                viewFade ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE);
        lp.gravity = gravity;
        lp.x = 0;
        lp.y = 0;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (viewFade) v.setAlpha(0f);
        else lp.alpha = 0f;
        wm.addView(v, lp);
        return v;
    }

    /** 整体淡入 / 淡出。淡出结束后把窗口摘掉。 */
    private void fade(final List<View> views, final boolean in, long ms) {
        if (fadeAnim != null) fadeAnim.cancel();
        final float to = in ? 1f : 0f;
        if (viewFade) {
            for (final View v : views) {
                v.animate().cancel();
                android.view.ViewPropertyAnimator a = v.animate().alpha(to).setDuration(ms);
                if (!in) a.withEndAction(() -> removeQuietly(v));
            }
            return;
        }
        final WindowManager manager = wm;
        ValueAnimator a = ValueAnimator.ofFloat(in ? 0f : 1f, to);
        a.setDuration(ms);
        final int[] last = {-1};
        a.addUpdateListener(an -> {
            // 分档：同一档内不重复更新，每一档只对每个窗口做一次跨进程调用
            int step = Math.round((Float) an.getAnimatedValue() * FADE_STEPS);
            if (step == last[0]) return;
            last[0] = step;
            float alpha = step / (float) FADE_STEPS;
            for (View v : views) {
                try {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                    if (lp == null) continue;
                    lp.alpha = alpha;
                    manager.updateViewLayout(v, lp);
                } catch (Exception ignored) { }
            }
        });
        if (!in) {
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // 被取消（比如又要显示了）也要把旧窗口摘掉，否则会残留
                    for (View v : views) removeQuietly(v);
                }
            });
        }
        fadeAnim = a;
        a.start();
    }

    private void removeQuietly(View v) {
        try { wm.removeView(v); } catch (Exception ignored) { }
    }

    /** 去掉全部遮罩；animate 为 true 时先淡出再去掉（只用在整个遮罩关闭时）。 */
    void remove(boolean animate) {
        if (bars.isEmpty()) return;
        List<View> old = new ArrayList<>(bars);
        bars.clear();
        builtW = builtH = builtLayers = 0;
        if (animate) {
            fade(old, false, FADE_OUT_MS);
        } else {
            if (fadeAnim != null) fadeAnim.cancel();
            for (View v : old) {
                if (viewFade) v.animate().cancel();
                removeQuietly(v);
            }
        }
    }
}
