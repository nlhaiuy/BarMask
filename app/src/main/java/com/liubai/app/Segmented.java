package com.liubai.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

/**
 * 分段开关：浅色底槽里一块蓝色渐变滑块，点哪段滑块就滑过去。
 * 如果传入 glyphAspects，每段文字前面会画一个对应宽高比的小画幅图标（用于 16:9 / 4:3）。
 */
public class Segmented extends View {
    public interface OnSelect { void onSelect(int index); }

    /** 读屏时每一段对应的自定义操作的编号起点（必须带高位，才不会和系统自带的操作冲突）。 */
    private static final int ACTION_BASE = 0x7E010000;

    private final String[] labels;
    private final float[] glyphs;      // 可为 null
    private int sel;
    private float pos;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator anim;
    private OnSelect cb;
    private Shader thumbShader;        // 滑块渐变：按“一段”的大小建一次，画的时候平移

    public Segmented(Context c, String[] labels, int sel) {
        this(c, labels, sel, null);
    }

    public Segmented(Context c, String[] labels, int sel, float[] glyphAspects) {
        super(c);
        this.labels = labels;
        this.glyphs = glyphAspects;
        this.sel = sel;
        this.pos = sel;
        paint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        Ui.softwareIfNeeded(this);   // 旧系统上让滑块的阴影也能画出来
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
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { pos = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(Ui.dp(getContext(), 54)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        Context c = getContext();
        float pad = Ui.dp(c, 4), vy = Ui.dp(c, 4);
        float segW = (w - 2 * pad) / labels.length;
        thumbShader = new LinearGradient(0, vy + pad, segW, h - vy - pad,
                Ui.PRI2, Ui.PRI, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float w = getWidth(), h = getHeight(), pad = Ui.dp(c, 4);

        // 底槽
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.CHIP);
        float vy = Ui.dp(c, 4);                    // 上下留空，滑块的阴影不被切
        rect.set(0, vy, w, h - vy);
        cv.drawRoundRect(rect, Ui.dp(c, 14), Ui.dp(c, 14), paint);

        // 滑块：渐变按一段的大小建好，画的时候平移到当前位置
        float segW = (w - 2 * pad) / labels.length;
        float l = pad + pos * segW;
        cv.save();
        cv.translate(l, 0);
        paint.setShader(thumbShader);
        paint.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 2), 0x554F7CFF);
        rect.set(0, vy + pad, segW, h - vy - pad);
        cv.drawRoundRect(rect, Ui.dp(c, 11), Ui.dp(c, 11), paint);
        paint.clearShadowLayer();
        paint.setShader(null);
        cv.restore();

        // 文字（和可选的画幅图标）
        paint.setTextSize(Ui.sp(c, 15));
        paint.setTextAlign(Paint.Align.LEFT);
        float ty = h / 2 - (paint.ascent() + paint.descent()) / 2;
        float gh = Ui.dp(c, 12), gap = Ui.dp(c, 8);
        for (int i = 0; i < labels.length; i++) {
            float near = 1f - Math.min(1f, Math.abs(i - pos));
            int color = Ui.blend(Ui.DIM, 0xFFFFFFFF, near);
            paint.setColor(color);
            paint.setFakeBoldText(near > 0.5f);

            float tw = paint.measureText(labels[i]);
            float gw = glyphs != null ? gh * glyphs[i] : 0f;
            float total = tw + (glyphs != null ? gw + gap : 0f);
            float x = pad + (i + 0.5f) * segW - total / 2f;
            if (glyphs != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(c, 2));
                rect.set(x + Ui.dp(c, 1), h / 2 - gh / 2 + Ui.dp(c, 1),
                        x + gw - Ui.dp(c, 1), h / 2 + gh / 2 - Ui.dp(c, 1));
                cv.drawRoundRect(rect, Ui.dp(c, 3), Ui.dp(c, 3), paint);
                paint.setStyle(Paint.Style.FILL);
                x += gw + gap;
            }
            cv.drawText(labels[i], x, ty, paint);
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
            choose(idx);
            performClick();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void choose(int idx) {
        if (idx == sel) return;
        setSelected(idx, true);
        if (cb != null) cb.onSelect(idx);
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    // ---------------- 读屏（TalkBack） ----------------

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) all.append(" / ");
            all.append(labels[i]);
        }
        info.setClassName("android.widget.RadioGroup");
        info.setContentDescription(getContext().getString(
                R.string.a11y_segment_state, all.toString(), labels[sel]));
        for (int i = 0; i < labels.length; i++) {
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_BASE + i, labels[i]));
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            selectByReader((sel + 1) % labels.length);     // 双击：切到下一项
            return true;
        }
        int i = action - ACTION_BASE;
        if (i >= 0 && i < labels.length) {
            selectByReader(i);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    private void selectByReader(int idx) {
        choose(idx);
        announceForAccessibility(labels[sel]);
    }
}
