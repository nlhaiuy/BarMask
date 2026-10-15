package com.liubai.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 一组贴在屏幕左右两侧的白色悬浮窗（每侧 layers 层）。
 * 窗口类型由使用者决定：普通悬浮窗（TYPE_APPLICATION_OVERLAY），
 * 或者无障碍服务自己的悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）。
 */
final class MaskOverlay {
    static final long FADE_IN_MS = 180, FADE_OUT_MS = 160;

    private final Context ctx;
    private final WindowManager wm;
    private final int type;
    private final List<View> bars = new ArrayList<>();
    private final List<ValueAnimator> anims = new ArrayList<>();
    private int builtW, builtH;   // 当前这批遮罩是按什么尺寸建的

    MaskOverlay(Context ctx, WindowManager wm, int type) {
        this.ctx = ctx;
        this.wm = wm;
        this.type = type;
    }

    boolean isShown() {
        return !bars.isEmpty();
    }

    /**
     * 显示或原地更新：屏幕高度没变时宽度原地改；层数变了只加 / 减多出来的那几层，
     * 原来的不拆（标准 ⇄ 加强 切换时才不会闪一下黑边）。
     */
    void show(int bar, int h, int layers) {
        if (!bars.isEmpty() && builtH == h) {
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
            int have = bars.size() / 2;
            if (have < layers) {
                List<View> added = new ArrayList<>();
                for (int i = have; i < layers; i++) {
                    View l = addBar(Gravity.TOP | Gravity.START, bar, h);
                    View r = addBar(Gravity.TOP | Gravity.END, bar, h);
                    bars.add(l);
                    bars.add(r);
                    added.add(l);
                    added.add(r);
                }
                fade(added, 0f, 1f, FADE_IN_MS, false);
            } else if (have > layers) {
                List<View> gone = new ArrayList<>(bars.subList(layers * 2, bars.size()));
                for (int i = bars.size() - 1; i >= layers * 2; i--) bars.remove(i);
                fade(gone, currentAlpha(gone), 0f, FADE_OUT_MS, true);
            }
            return;
        }

        hide(false);
        for (int i = 0; i < layers; i++) {
            bars.add(addBar(Gravity.TOP | Gravity.START, bar, h));
            bars.add(addBar(Gravity.TOP | Gravity.END, bar, h));
        }
        builtW = bar;
        builtH = h;
        fade(new ArrayList<>(bars), 0f, 1f, FADE_IN_MS, false);
    }

    /** 去掉全部遮罩；animate 为 true 时先淡出再去掉（从当前透明度开始，不会突然一跳）。 */
    void hide(boolean animate) {
        if (bars.isEmpty()) return;
        // 先停掉还在跑的动画，免得它们对已经去掉的窗口每帧空转（淡出中的窗口会在这里一并被去掉）
        for (ValueAnimator a : new ArrayList<>(anims)) a.cancel();
        List<View> all = new ArrayList<>(bars);
        bars.clear();
        builtW = builtH = 0;
        if (animate) {
            fade(all, currentAlpha(all), 0f, FADE_OUT_MS, true);
        } else {
            for (View v : all) removeQuietly(v);
        }
    }

    private float currentAlpha(List<View> views) {
        try {
            if (!views.isEmpty()) {
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) views.get(0).getLayoutParams();
                if (lp != null) return lp.alpha;
            }
        } catch (Exception ignored) { }
        return 1f;
    }

    /**
     * 整体淡入 / 淡出：用窗口自身的透明度，不改变悬浮窗的像素格式（所以遮得还是一样实）。
     * 每个窗口一帧一帧地更新，总共约 0.2 秒。
     */
    private void fade(final List<View> views, float from, float to, long ms,
                      final boolean removeAtEnd) {
        final ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.addUpdateListener(an -> {
            float alpha = (Float) an.getAnimatedValue();
            for (View v : views) {
                try {
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
                    if (lp == null) continue;
                    lp.alpha = alpha;
                    wm.updateViewLayout(v, lp);
                } catch (Exception ignored) { }
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                anims.remove(a);
                if (removeAtEnd) {
                    for (View v : views) removeQuietly(v);
                }
            }
        });
        anims.add(a);
        a.start();
    }

    private void removeQuietly(View v) {
        try {
            wm.removeView(v);
        } catch (Exception ignored) { }
    }

    private View addBar(int gravity, int width, int height) {
        View v = new View(ctx);
        v.setBackgroundColor(Color.WHITE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height, type,
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
}
