package com.liubai.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * 宽度微调滑条：-100 ~ +100，每 1dp 一档。
 * 蓝色渐变的已走过部分，白色圆形滑块，当前数值直接写在滑块里，轨道正中间有一道 0 刻度。
 */
public class Slider extends View {
    public interface OnChange { void onChange(int value, boolean fromUser); }

    public static final int MAX = 100;
    private int min = -100;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int value;
    private boolean dragging;
    private OnChange cb;

    public Slider(Context c) {
        super(c);
        setClickable(true);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // 让滑块的阴影在所有机型上都能画出来
        paint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnChange(OnChange cb) { this.cb = cb; }

    public int getValue() { return value; }

    public int getMin() { return min; }

    /** 下限随黑边宽度变（再往负就没有意义了）。 */
    public void setMin(int m) {
        min = Math.min(-10, m);
        value = clamp(value);
        invalidate();
    }

    public void setValue(int v) {
        value = clamp(v);
        invalidate();
    }

    private int clamp(int v) { return Math.max(min, Math.min(MAX, v)); }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(Ui.dp(getContext(), 56)));
    }

    private float thumbR() { return Ui.dp(getContext(), 16); }

    /** 两头留出滑块和阴影的位置，阴影不会被控件边界切掉。 */
    private float inset() { return thumbR() + Ui.dp(getContext(), 10); }

    private float xFor(int v) {
        float r = inset();
        return r + (v - min) / (float) (MAX - min) * (getWidth() - 2 * r);
    }

    private int valueAt(float x) {
        float r = inset();
        float t = (x - r) / Math.max(1f, getWidth() - 2 * r);
        return clamp(Math.round(min + t * (MAX - min)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float h = getHeight(), cy = h / 2f, half = Ui.dp(c, 2), x = xFor(value);
        float left = inset(), right = getWidth() - inset();

        // 轨道
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.TRACK);
        rect.set(left, cy - half, right, cy + half);
        cv.drawRoundRect(rect, half, half, paint);

        // 走过的部分
        paint.setShader(new LinearGradient(left, 0, right, 0, Ui.PRI2, Ui.PRI, Shader.TileMode.CLAMP));
        rect.set(left, cy - half, Math.max(left + 1, x), cy + half);
        cv.drawRoundRect(rect, half, half, paint);
        paint.setShader(null);

        // 0 刻度
        float mid = xFor(0);
        paint.setColor(0xFFC9D5EE);
        rect.set(mid - Ui.dp(c, 1), cy - Ui.dp(c, 7), mid + Ui.dp(c, 1), cy + Ui.dp(c, 7));
        cv.drawRoundRect(rect, Ui.dp(c, 1), Ui.dp(c, 1), paint);

        // 滑块：白底 + 蓝色细环 + 柔和阴影，数值写在里面
        float r = thumbR() * (dragging ? 1.08f : 1f);
        paint.setColor(0xFFFFFFFF);
        paint.setShadowLayer(Ui.dp(c, dragging ? 8 : 6), 0, Ui.dp(c, dragging ? 3 : 2), 0x554F7CFF);
        cv.drawCircle(x, cy, r, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(c, 2));
        paint.setColor(Ui.PRI);
        cv.drawCircle(x, cy, r - Ui.dp(c, 1), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setFakeBoldText(true);
        paint.setTextSize(Ui.sp(c, Math.abs(value) >= 100 ? 10 : 11));
        paint.setLetterSpacing(-0.02f);
        String t = (value > 0 ? "+" : "") + value;
        cv.drawText(t, x, cy - (paint.ascent() + paint.descent()) / 2f, paint);
        paint.setFakeBoldText(false);
        paint.setLetterSpacing(0f);
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
