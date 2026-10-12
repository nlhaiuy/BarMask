package com.liubai.app;

import android.animation.ArgbEvaluator;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** 留白的视觉常量：浅蓝白底、白色卡片、一抹蓝色，没有大面积的深色。 */
final class Ui {
    static final int BG = 0xFFF3F6FC;        // 页面底色
    static final int CARD = 0xFFFFFFFF;      // 卡片
    static final int INK = 0xFF1F2638;       // 主文字
    static final int DIM = 0xFF8791A6;       // 次要文字
    static final int LINE = 0xFFE9EEF7;      // 细分隔线
    static final int CHIP = 0xFFEEF2FA;      // 控件底槽
    static final int TRACK = 0xFFDDE5F3;     // 滑条未走过的部分
    static final int PRI = 0xFF4F7CFF;       // 主蓝
    static final int PRI2 = 0xFF7C9BFF;      // 浅一点的蓝（渐变起点）
    static final int GREEN = 0xFF2EB67D;     // “已开启”的绿点
    static final int EDGE = 0xFF2B3042;      // 演示屏里的黑边
    static final int OUTLINE = 0xFFC3D0EC;   // 演示屏外的细描边
    static final int WARN_BG = 0xFFFFF4DC;
    static final int WARN_TX = 0xFF7A5A12;

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

    /** 浅色页面：状态栏、导航栏跟随底色，图标用深色。 */
    @SuppressWarnings("deprecation")
    static void lightWindow(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG);
        a.getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
}
