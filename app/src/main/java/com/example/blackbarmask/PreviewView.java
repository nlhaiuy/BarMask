package com.example.blackbarmask;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.View;

/** 顶部示意：一块横屏，开启后两侧的黑边变成纸白色。比例、宽度微调与真实遮罩算法一致。 */
public class PreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final SharedPreferences sp;
    private float amount; // 0 = 黑边，1 = 白边
    private ValueAnimator anim;

    public PreviewView(Context c) {
        super(c);
        sp = c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        amount = AutoMode.isMaster(c) ? 1f : 0f;
    }

    public void setOn(boolean on) {
        float to = on ? 1f : 0f;
        if (anim != null) anim.cancel();
        if (!isLaidOut()) { amount = to; invalidate(); return; }
        anim = ValueAnimator.ofFloat(amount, to);
        anim.setDuration(320);
        anim.addUpdateListener(a -> { amount = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round(Ui.dp(getContext(), 156)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float W = getWidth(), H = getHeight(), density = c.getResources().getDisplayMetrics().density;

        // 外框：一圈细线
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(c, 1));
        paint.setColor(Ui.LINE);
        rect.set(Ui.dp(c, 0.5f), Ui.dp(c, 0.5f), W - Ui.dp(c, 0.5f), H - Ui.dp(c, 0.5f));
        cv.drawRoundRect(rect, Ui.dp(c, 16), Ui.dp(c, 16), paint);

        DisplayMetrics m = c.getResources().getDisplayMetrics();
        float longPx = Math.max(m.widthPixels, m.heightPixels);
        float shortPx = Math.min(m.widthPixels, m.heightPixels);
        float aspect = Math.max(16f / 9f, Math.min(2.4f, longPx / shortPx));
        float availH = H - Ui.dp(c, 44);
        float sw = Math.min(W - Ui.dp(c, 64), availH * aspect), sh = sw / aspect;
        float l = (W - sw) / 2f, t = Ui.dp(c, 16), r = l + sw, b = t + sh;
        float scale = sw / (longPx / density);

        // 机身
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A2A31);
        rect.set(l - Ui.dp(c, 3), t - Ui.dp(c, 3), r + Ui.dp(c, 3), b + Ui.dp(c, 3));
        cv.drawRoundRect(rect, Ui.dp(c, 12), Ui.dp(c, 12), paint);

        Path clip = new Path();
        clip.addRoundRect(new RectF(l, t, r, b), Ui.dp(c, 9), Ui.dp(c, 9), Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        paint.setColor(0xFF000000);
        cv.drawRect(l, t, r, b, paint);

        float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        float vw = Math.min(sw, sh * ratio);
        float vl = l + (sw - vw) / 2f;
        paint.setColor(0xFF3A3C46);
        cv.drawRect(vl, t, vl + vw, b, paint);
        paint.setColor(0xB3FFFFFF);
        float cx = l + sw / 2f, cy = t + sh / 2f, ts = sh * 0.14f;
        Path tri = new Path();
        tri.moveTo(cx - ts * 0.6f, cy - ts);
        tri.lineTo(cx - ts * 0.6f, cy + ts);
        tri.lineTo(cx + ts, cy);
        tri.close();
        cv.drawPath(tri, paint);

        // 两侧遮罩
        int off = sp.getInt(MaskService.KEY_OFFSET, 0);
        float bar = (sw - sh * ratio) / 2f + off * density * scale;
        bar = Math.max(0f, Math.min(bar, sw / 2f));
        paint.setColor(Ui.blend(0xFF000000, Ui.PAPER, amount));
        cv.drawRect(l, t, l + bar, b, paint);
        cv.drawRect(r - bar, t, r, b, paint);
        cv.restore();

        // 说明文字
        paint.setTextSize(Ui.sp(c, 12));
        paint.setColor(Ui.DIM);
        float ty = H - Ui.dp(c, 12);
        paint.setTextAlign(Paint.Align.LEFT);
        cv.drawText(ratio > 1.5f ? "16:9" : "4:3", Ui.dp(c, 16), ty, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        cv.drawText((off > 0 ? "+" : "") + off + " dp", W - Ui.dp(c, 16), ty, paint);
    }
}
