package com.example.blackbarmask;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 卷尺式滑块：中间一根指针，左右拖动刻度尺改变数值，每 1dp 一格，带惯性。范围 -100 ~ +100。 */
public class Ruler extends View {
    public interface OnChange { void onChange(int value, boolean fromUser); }

    public static final int MIN = -100, MAX = 100;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float pxPer;
    private float value;
    private int lastInt;
    private float downX, downValue;
    private VelocityTracker vt;
    private ValueAnimator fling;
    private OnChange cb;

    public Ruler(Context c) {
        super(c);
        pxPer = Ui.dp(c, 9);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
    }

    public void setOnChange(OnChange cb) { this.cb = cb; }

    public int getValue() { return Math.round(value); }

    public void setValue(int v) {
        if (fling != null) fling.cancel();
        value = clamp(v);
        lastInt = Math.round(value);
        invalidate();
    }

    private float clamp(float v) { return Math.max(MIN, Math.min(MAX, v)); }

    private void move(float v, boolean user) {
        value = clamp(v);
        int i = Math.round(value);
        if (i != lastInt) {
            lastInt = i;
            if (cb != null) cb.onChange(i, user);
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(Ui.dp(getContext(), 64)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float w = getWidth(), h = getHeight(), cx = w / 2f, base = h - Ui.dp(c, 2);
        int from = (int) Math.floor(value - cx / pxPer) - 1;
        int to = (int) Math.ceil(value + cx / pxPer) + 1;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(c, 1.5f));
        for (int i = Math.max(MIN, from); i <= Math.min(MAX, to); i++) {
            float x = cx + (i - value) * pxPer;
            float d = Math.abs(x - cx) / cx;
            float a = 1f - (float) Math.pow(Math.min(1f, d), 1.8);
            if (a <= 0.02f) continue;
            boolean ten = i % 10 == 0, five = i % 5 == 0;
            float len = Ui.dp(c, ten ? 26 : five ? 18 : 11);
            paint.setColor(ten ? Ui.DIM : Ui.MUTE);
            paint.setAlpha(Math.round(255 * a));
            cv.drawLine(x, base, x, base - len, paint);
        }
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.PAPER);
        float pw = Ui.dp(c, 3);
        rect.set(cx - pw / 2, base - Ui.dp(c, 44), cx + pw / 2, base);
        cv.drawRoundRect(rect, pw / 2, pw / 2, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (fling != null) fling.cancel();
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX();
                downValue = value;
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                vt.addMovement(e);
                move(downValue - (e.getX() - downX) / pxPer, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                vt.addMovement(e);
                vt.computeCurrentVelocity(1000);
                float vx = vt.getXVelocity();
                vt.recycle();
                vt = null;
                if (Math.abs(vx) > Ui.dp(getContext(), 300)) {
                    final float start = value;
                    final float target = clamp(start - vx / pxPer * 0.22f);
                    fling = ValueAnimator.ofFloat(start, target);
                    fling.setDuration(520);
                    fling.setInterpolator(new DecelerateInterpolator(1.6f));
                    fling.addUpdateListener(a -> move((Float) a.getAnimatedValue(), true));
                    fling.start();
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
