package com.example.blackbarmask;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;

/**
 * 带刻度尺的滑块：关掉自带的圆点，在轨道里画真实的刻度。
 * 每 10dp 一格，±100 / ±50 / 0 是长刻度，画在轨道里面。
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
        // 刻度画在轨道里面：落在已填充的一侧用浅色，落在未填充的一侧用深色
        int onActive = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF);
        int onInactive = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, 0xFF4F46E5);
        float thumbX = pad + w * (getValue() + 100) / 200f;
        for (int v = -100; v <= 100; v += 10) {
            float x = pad + w * (v + 100) / 200f;
            if (Math.abs(x - thumbX) < 7 * density) continue; // 避开滑块手柄
            boolean big = v % 50 == 0;
            paint.setColor(x < thumbX ? onActive : onInactive);
            paint.setAlpha(big ? 230 : 140);
            paint.setStrokeWidth(1.5f * density);
            float half = (big ? 4.5f : 2.5f) * density;
            canvas.drawLine(x, cy - half, x, cy + half, paint);
        }
    }
}
