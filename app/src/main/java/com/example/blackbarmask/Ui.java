package com.example.blackbarmask;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/** 白边的视觉常量：黑色“舞台”、纸白、一条细线，只有黑白两个极端。 */
final class Ui {
    static final int INK = 0xFF0B0B0D;    // 舞台底色
    static final int PANEL = 0xFF16161A;  // 控件底
    static final int LINE = 0xFF2B2B31;   // 细分隔线
    static final int PAPER = 0xFFF5F5F1;  // “白边”本身，也是选中/开启的颜色
    static final int DIM = 0xFF8C8C95;    // 次要文字
    static final int MUTE = 0xFF55555D;   // 刻度、描边

    private static final ArgbEvaluator EV = new ArgbEvaluator();

    private Ui() { }

    static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return (Integer) EV.evaluate(t, a, b);
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    static float sp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().scaledDensity;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }
}
