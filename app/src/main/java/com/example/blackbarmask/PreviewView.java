package com.example.blackbarmask;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/** 横屏手机示意：黑色屏幕 + 视频区域，开启后两侧黑边变白。读取当前比例和微调值。 */
public class PreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator ev = new ArgbEvaluator();
    private final SharedPreferences sp;
    private final float density;
    private float amount; // 0=黑边 1=白边
    private ValueAnimator anim;

    public PreviewView(Context c, AttributeSet a) {
        super(c, a);
        sp = c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        density = c.getResources().getDisplayMetrics().density;
        amount = AutoMode.isMaster(c) ? 1f : 0f;
    }

    private float dp(float v) { return v * density; }

    public void setOn(boolean on) {
        float to = on ? 1f : 0f;
        if (anim != null) anim.cancel();
        if (!isLaidOut()) { amount = to; invalidate(); return; }
        anim = ValueAnimator.ofFloat(amount, to);
        anim.setDuration(260);
        anim.addUpdateListener(a -> { amount = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        DisplayMetrics m = getResources().getDisplayMetrics();
        float longPx = Math.max(m.widthPixels, m.heightPixels);
        float shortPx = Math.min(m.widthPixels, m.heightPixels);
        float aspect = Math.max(16f / 9f, Math.min(2.4f, longPx / shortPx));
        float sw = Math.min(W - dp(8), (H - dp(8)) * aspect), sh = sw / aspect;
        float l = (W - sw) / 2f, t = (H - sh) / 2f, r = l + sw, b = t + sh;
        float scale = sw / (longPx / density);

        // 机身
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOutline, 0xFF777777));
        cv.drawRoundRect(new RectF(l - dp(4), t - dp(4), r + dp(4), b + dp(4)), dp(14), dp(14), paint);

        Path clip = new Path();
        clip.addRoundRect(new RectF(l, t, r, b), dp(10), dp(10), Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        paint.setColor(0xFF000000);
        cv.drawRect(l, t, r, b, paint);

        float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        float vw = Math.min(sw, sh * ratio);
        float vl = l + (sw - vw) / 2f;
        paint.setColor(0xFF4B5563);
        cv.drawRect(vl, t, vl + vw, b, paint);
        // 播放三角
        paint.setColor(0xB3FFFFFF);
        float cx = l + sw / 2f, cy = t + sh / 2f, ts = sh * 0.16f;
        Path tri = new Path();
        tri.moveTo(cx - ts * 0.6f, cy - ts);
        tri.lineTo(cx - ts * 0.6f, cy + ts);
        tri.lineTo(cx + ts, cy);
        tri.close();
        cv.drawPath(tri, paint);

        // 两侧遮罩，宽度算法与真实遮罩一致
        float bar = (sw - sh * ratio) / 2f + sp.getInt(MaskService.KEY_OFFSET, 0) * density * scale;
        bar = Math.max(0f, Math.min(bar, sw / 2f));
        paint.setColor((Integer) ev.evaluate(amount, 0xFF000000, 0xFFFFFFFF));
        cv.drawRect(l, t, l + bar, b, paint);
        cv.drawRect(r - bar, t, r, b, paint);
        cv.restore();
    }
}
