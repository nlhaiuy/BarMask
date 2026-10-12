package com.liubai.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** 宽度微调滑条：-100 ~ +100，每 1dp 一档。蓝色已走过部分，白色圆形滑块。 */
public class Slider extends View {
    public interface OnChange { void onChange(int value, boolean fromUser); }

    public static final int MIN = -100, MAX = 100;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int value;
    private boolean dragging;
    private OnChange cb;

    public Slider(Context c) {
        super(c);
        setClickable(true);
    }

    public void setOnChange(OnChange cb) { this.cb = cb; }

    public int getValue() { return value; }

    public void setValue(int v) {
        value = clamp(v);
        invalidate();
    }

    private static int clamp(int v) { return Math.max(MIN, Math.min(MAX, v)); }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(Ui.dp(getContext(), 40)));
    }

    private float thumbR() { return Ui.dp(getContext(), 13); }

    private float xFor(int v) {
        float r = thumbR();
        return r + (v - MIN) / (float) (MAX - MIN) * (getWidth() - 2 * r);
    }

    private int valueAt(float x) {
        float r = thumbR();
        float t = (x - r) / Math.max(1f, getWidth() - 2 * r);
        return clamp(Math.round(MIN + t * (MAX - MIN)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float h = getHeight(), cy = h / 2f, tr = Ui.dp(c, 2), x = xFor(value);
        float left = thumbR(), right = getWidth() - thumbR();

        // 轨道
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.TRACK);
        rect.set(left, cy - tr, right, cy + tr);
        cv.drawRoundRect(rect, tr, tr, paint);

        // 走过的部分
        paint.setColor(Ui.PRI);
        rect.set(left, cy - tr, x, cy + tr);
        cv.drawRoundRect(rect, tr, tr, paint);

        // 滑块：白底蓝边，按下时稍微放大
        float r = thumbR() * (dragging ? 1.08f : 1f);
        paint.setColor(0x224F7CFF);
        cv.drawCircle(x, cy + Ui.dp(c, 2), r + Ui.dp(c, 1), paint);   // 一点点阴影
        paint.setColor(Ui.PRI);
        cv.drawCircle(x, cy, r, paint);
        paint.setColor(0xFFFFFFFF);
        cv.drawCircle(x, cy, r - Ui.dp(c, 2), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                apply(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                apply(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void apply(float x) {
        int v = valueAt(x);
        if (v != value) {
            value = v;
            if (cb != null) cb.onChange(v, true);
        }
        invalidate();
    }
}
