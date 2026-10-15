package com.liubai.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 宽度微调滑条：-100 ~ +100，每 1dp 一档。
 * 蓝色渐变的已走过部分，白色圆形滑块，当前数值直接写在滑块里，轨道正中间有一道 0 刻度。
 *
 * 手势是“相对拖动”：手指落在哪里都不会让滑块跳过去，只按手指移动的距离推动滑块，
 * 微调时不会因为误碰轨道就让遮罩突然变宽 / 变窄。
 */
public class Slider extends View {
    public interface OnChange { void onChange(int value, boolean fromUser); }

    public static final int MAX = 100;
    private int min = -100;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;                  // density，onDraw 里反复用
    private Shader progressShader;          // 蓝色渐变：只在尺寸变化时重建
    private int value;
    private boolean dragging;
    private float downX, downThumbX;        // 按下时手指和滑块的位置
    private OnChange cb;

    public Slider(Context c) {
        super(c);
        d = Ui.dp(c, 1);
        setClickable(true);
        Ui.softwareIfNeeded(this);          // 旧系统上让滑块的阴影也能画出来
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFontFeatureSettings("tnum");   // 等宽数字：拖动时数字宽度不抖
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
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(56 * d));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        progressShader = new LinearGradient(inset(), 0, w - inset(), 0, Ui.PRI2, Ui.PRI,
                Shader.TileMode.CLAMP);
    }

    private float thumbR() { return 16 * d; }

    /** 两头留出滑块和阴影的位置，阴影不会被控件边界切掉。 */
    private float inset() { return thumbR() + 10 * d; }

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
        float h = getHeight(), cy = h / 2f, half = 2 * d, x = xFor(value);
        float left = inset(), right = getWidth() - inset();

        // 轨道
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.TRACK);
        rect.set(left, cy - half, right, cy + half);
        cv.drawRoundRect(rect, half, half, paint);

        // 走过的部分：从 0 刻度往滑块方向填，0 的时候没有蓝色
        paint.setShader(progressShader);
        float zx = xFor(0);
        rect.set(Math.min(zx, x), cy - half, Math.max(zx, x), cy + half);
        if (rect.width() > 0.5f) cv.drawRoundRect(rect, half, half, paint);
        paint.setShader(null);

        // 0 刻度
        float mid = xFor(0);
        paint.setColor(0xFFC9D5EE);
        rect.set(mid - d, cy - 7 * d, mid + d, cy + 7 * d);
        cv.drawRoundRect(rect, d, d, paint);

        // 滑块：白底 + 蓝色细环 + 柔和阴影，数值写在里面
        float r = thumbR() * (dragging ? 1.08f : 1f);
        paint.setColor(0xFFFFFFFF);
        paint.setShadowLayer((dragging ? 8 : 6) * d, 0, (dragging ? 3 : 2) * d, 0x554F7CFF);
        cv.drawCircle(x, cy, r, paint);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * d);
        paint.setColor(Ui.PRI);
        cv.drawCircle(x, cy, r - d, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setFakeBoldText(true);
        paint.setTextSize(Ui.sp(getContext(), Math.abs(value) >= 100 ? 10 : 11));
        paint.setLetterSpacing(-0.02f);
        String t = (value > 0 ? "+" : "") + value;
        cv.drawText(t, x, cy - (paint.ascent() + paint.descent()) / 2f, paint);
        paint.setFakeBoldText(false);
        paint.setLetterSpacing(0f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                downX = e.getX();
                downThumbX = xFor(value);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                apply(downThumbX + (e.getX() - downX));
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

    // ---------------- 读屏（TalkBack） ----------------

    @Override
    @SuppressWarnings("deprecation")
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min, MAX, value));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (Build.VERSION.SDK_INT >= 30) {
            info.setStateDescription(getContext().getString(R.string.a11y_slider_value, value));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return setByReader(value + 1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return setByReader(value - 1);
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                && args != null) {
            float v = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
            return setByReader(Math.round(v));
        }
        return super.performAccessibilityAction(action, args);
    }

    private boolean setByReader(int v) {
        v = clamp(v);
        if (v == value) return false;
        value = v;
        if (cb != null) cb.onChange(v, true);
        invalidate();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        return true;
    }
}
