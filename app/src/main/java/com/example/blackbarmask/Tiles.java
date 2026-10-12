package com.example.blackbarmask;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** 视频比例选择：每个选项里画出对应比例的小矩形，选中的整块翻成纸白色。 */
public class Tiles extends View {
    public interface OnSelect { void onSelect(int index); }

    private final String[] labels;
    private final float[] aspects;
    private int sel;
    private final float[] t;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator anim;
    private OnSelect cb;

    public Tiles(Context c, String[] labels, float[] aspects, int sel) {
        super(c);
        this.labels = labels;
        this.aspects = aspects;
        this.sel = sel;
        this.t = new float[labels.length];
        for (int i = 0; i < t.length; i++) t[i] = i == sel ? 1f : 0f;
        setClickable(true);
    }

    public void setOnSelect(OnSelect cb) { this.cb = cb; }

    public void setSelected(int i, boolean animate) {
        sel = i;
        if (anim != null) anim.cancel();
        final float[] from = t.clone();
        if (!animate || !isLaidOut()) {
            for (int k = 0; k < t.length; k++) t[k] = k == i ? 1f : 0f;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(220);
        anim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            for (int k = 0; k < t.length; k++) {
                float to = k == sel ? 1f : 0f;
                t[k] = from[k] + (to - from[k]) * f;
            }
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(Ui.dp(getContext(), 64)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float gap = Ui.dp(c, 10), h = getHeight();
        float tw = (getWidth() - gap * (labels.length - 1)) / labels.length;
        paint.setTextSize(Ui.sp(c, 15));
        paint.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < labels.length; i++) {
            float left = i * (tw + gap);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.blend(Ui.PANEL, Ui.PAPER, t[i]));
            rect.set(left, 0, left + tw, h);
            cv.drawRoundRect(rect, Ui.dp(c, 16), Ui.dp(c, 16), paint);

            int fg = Ui.blend(Ui.DIM, Ui.INK, t[i]);
            // 比例小矩形：宽固定，高按比例算
            float gw = Ui.dp(c, 30), gh = gw / aspects[i];
            float gx = left + Ui.dp(c, 18), gy = (h - gh) / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 2));
            paint.setColor(fg);
            rect.set(gx, gy, gx + gw, gy + gh);
            cv.drawRoundRect(rect, Ui.dp(c, 3), Ui.dp(c, 3), paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setFakeBoldText(t[i] > 0.5f);
            cv.drawText(labels[i], gx + gw + Ui.dp(c, 12),
                    h / 2 - (paint.ascent() + paint.descent()) / 2, paint);
            paint.setFakeBoldText(false);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float gap = Ui.dp(getContext(), 10);
            float tw = (getWidth() - gap * (labels.length - 1)) / labels.length;
            int idx = (int) (e.getX() / (tw + gap));
            idx = Math.max(0, Math.min(labels.length - 1, idx));
            if (idx != sel) {
                setSelected(idx, true);
                if (cb != null) cb.onSelect(idx);
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
