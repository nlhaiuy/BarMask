package com.example.blackbarmask;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;

/**
 * 带刻度尺的滑块：关掉自带的圆点，在轨道下方画一把真实的尺子。
 * 每 10dp 一格，±100 / ±50 / 0 是长刻度，0 点用主色标出。
 */
public class TickSlider extends Slider {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public TickSlider(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 轨道两端的留白（滑块左右不是贴边的）；取不到时按 15dp 估算。 */
    private float sidePadding() {
        try {
            java.lang.reflect.Method m = Slider.class.getMethod("getTrackSidePadding");
            return ((Number) m.invoke(this)).floatValue();
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method m = getClass().getSuperclass().getSuperclass()
                        .getDeclaredMethod("getTrackSidePadding");
                m.setAccessible(true);
                return ((Number) m.invoke(this)).floatValue();
            } catch (Throwable t2) {
                return 15f * density;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = sidePadding();
        float w = getWidth() - 2 * pad;
        if (w <= 0) return;
        float cy = getHeight() / 2f;
        float top = cy + 13 * density;
        int minor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOutline, 0xFF888888);
        int major = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF555555);
        int accent = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, 0xFF4F46E5);
        float thumbX = pad + w * (getValue() + 100) / 200f;
        for (int v = -100; v <= 100; v += 10) {
            float x = pad + w * (v + 100) / 200f;
            if (Math.abs(x - thumbX) < 7 * density) continue; // 避开滑块手柄
            boolean big = v % 50 == 0;
            paint.setColor(v == 0 ? accent : big ? major : minor);
            paint.setStrokeWidth((v == 0 ? 2.5f : 1.5f) * density);
            canvas.drawLine(x, top, x, top + (big ? 9 : 5) * density, paint);
        }
    }
}
