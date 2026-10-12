package com.example.blackbarmask;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 分段开关：深色底槽里一块纸白色滑块，点哪段滑块就滑过去。 */
public class Segmented extends View {
    public interface OnSelect { void onSelect(int index); }

    private final String[] labels;
    private int sel;
    private float pos;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator anim;
    private OnSelect cb;

    public Segmented(Context c, String[] labels, int sel) {
        super(c);
        this.labels = labels;
        this.sel = sel;
        this.pos = sel;
        paint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void setOnSelect(OnSelect cb) { this.cb = cb; }

    public int getSelected() { return sel; }

    public void setSelected(int i, boolean animate) {
        sel = i;
        if (anim != null) anim.cancel();
        if (!animate || !isLaidOut()) {
            pos = i;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(pos, i);
        anim.setDuration(240);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { pos = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(Ui.dp(getContext(), 46)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight(), pad = Ui.dp(getContext(), 4);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.PANEL);
        rect.set(0, 0, w, h);
        cv.drawRoundRect(rect, h / 2, h / 2, paint);

        float segW = (w - 2 * pad) / labels.length;
        paint.setColor(Ui.PAPER);
        rect.set(pad + pos * segW, pad, pad + (pos + 1) * segW, h - pad);
        cv.drawRoundRect(rect, (h - 2 * pad) / 2, (h - 2 * pad) / 2, paint);

        paint.setTextSize(Ui.sp(getContext(), 15));
        float ty = h / 2 - (paint.ascent() + paint.descent()) / 2;
        for (int i = 0; i < labels.length; i++) {
            float near = 1f - Math.min(1f, Math.abs(i - pos));
            paint.setColor(Ui.blend(Ui.DIM, Ui.INK, near));
            paint.setFakeBoldText(near > 0.5f);
            cv.drawText(labels[i], pad + (i + 0.5f) * segW, ty, paint);
        }
        paint.setFakeBoldText(false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float pad = Ui.dp(getContext(), 4);
            float segW = (getWidth() - 2 * pad) / labels.length;
            int idx = (int) ((e.getX() - pad) / segW);
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
