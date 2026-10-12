package com.liubai.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * 顶部演示：白色卡片里一块横屏，开启后两侧的黑边变成白色。
 * 比例、宽度微调与真实遮罩算法一致；屏幕外有一圈很细的浅蓝灰描边，白边才看得出边界。
 */
public class PreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Path tri = new Path();
    private final SharedPreferences sp;
    private float amount; // 0 = 黑边，1 = 白边
    private ValueAnimator anim;

    public PreviewView(Context c) {
        super(c);
        sp = c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        amount = AutoMode.isMaster(c) ? 1f : 0f;
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // 让卡片阴影在所有机型上都能画出来
    }

    public void setOn(boolean on) {
        float to = on ? 1f : 0f;
        if (anim != null) anim.cancel();
        if (!isLaidOut()) { amount = to; invalidate(); return; }
        anim = ValueAnimator.ofFloat(amount, to);
        anim.setDuration(380);
        anim.addUpdateListener(a -> { amount = (Float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private float aspect() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        float longPx = Math.max(m.widthPixels, m.heightPixels);
        float shortPx = Math.min(m.widthPixels, m.heightPixels);
        return Math.max(16f / 9f, Math.min(2.4f, longPx / shortPx));
    }

    @Override
    protected void onMeasure(int w, int h) {
        Context c = getContext();
        int width = MeasureSpec.getSize(w);
        float sw = width - Ui.dp(c, 28);
        float sh = sw / aspect();
        setMeasuredDimension(width, Math.round(Ui.dp(c, 16) + sh + Ui.dp(c, 50)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        float W = getWidth(), H = getHeight(), density = m.density;
        float longPx = Math.max(m.widthPixels, m.heightPixels);

        // 白色卡片（带一点淡蓝阴影）
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.CARD);
        paint.setShadowLayer(Ui.dp(c, 14), 0, Ui.dp(c, 6), 0x224F7CFF);
        rect.set(Ui.dp(c, 4), Ui.dp(c, 2), W - Ui.dp(c, 4), H - Ui.dp(c, 12));
        cv.drawRoundRect(rect, Ui.dp(c, 28), Ui.dp(c, 28), paint);
        paint.clearShadowLayer();

        float aspect = aspect();
        float sw = W - Ui.dp(c, 28) - Ui.dp(c, 8), sh = sw / aspect;
        float l = (W - sw) / 2f, t = Ui.dp(c, 16), r = l + sw, b = t + sh;
        float scale = sw / (longPx / density);

        // 屏幕外的细描边
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(c, 1));
        paint.setColor(Ui.OUTLINE);
        rect.set(l - Ui.dp(c, 0.5f), t - Ui.dp(c, 0.5f), r + Ui.dp(c, 0.5f), b + Ui.dp(c, 0.5f));
        cv.drawRoundRect(rect, Ui.dp(c, 18), Ui.dp(c, 18), paint);

        clip.reset();
        clip.addRoundRect(new RectF(l, t, r, b), Ui.dp(c, 18), Ui.dp(c, 18), Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.EDGE);
        cv.drawRect(l, t, r, b, paint);

        // 视频画面
        float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        float vw = Math.min(sw, sh * ratio);
        float vl = l + (sw - vw) / 2f;
        paint.setShader(new LinearGradient(vl, t, vl + vw, b,
                new int[]{0xFF7F93D8, 0xFF4A5BA8, 0xFF323F7A}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        cv.drawRect(vl, t, vl + vw, b, paint);
        paint.setShader(null);

        // 播放键
        paint.setColor(0xD9FFFFFF);
        float cx = l + sw / 2f, cy = t + sh / 2f, ts = sh * 0.14f;
        tri.reset();
        tri.moveTo(cx - ts * 0.6f, cy - ts);
        tri.lineTo(cx - ts * 0.6f, cy + ts);
        tri.lineTo(cx + ts, cy);
        tri.close();
        cv.drawPath(tri, paint);

        // 两侧遮罩：黑边 → 白边
        int off = sp.getInt(MaskService.KEY_OFFSET, 0);
        float bar = (sw - sh * ratio) / 2f + off * density * scale;
        bar = Math.max(0f, Math.min(bar, sw / 2f));
        paint.setColor(Ui.blend(Ui.EDGE, 0xFFFFFFFF, amount));
        cv.drawRect(l, t, l + bar, b, paint);
        cv.drawRect(r - bar, t, r, b, paint);
        cv.restore();

        // 说明文字：左边“16:9 视频”，右边“微调 +x dp”
        float ty = b + Ui.dp(c, 26);
        float x0 = l + Ui.dp(c, 6);
        paint.setTextSize(Ui.sp(c, 12));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setColor(Ui.INK);
        String rt = ratio > 1.5f ? "16:9" : "4:3";
        cv.drawText(rt, x0, ty, paint);
        float rw = paint.measureText(rt + " ");
        paint.setFakeBoldText(false);
        paint.setColor(Ui.DIM);
        cv.drawText("视频", x0 + rw, ty, paint);

        String ov = (off > 0 ? "+" : "") + off + " dp";
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setFakeBoldText(true);
        paint.setColor(Ui.INK);
        float xr = r - Ui.dp(c, 6);
        cv.drawText(ov, xr, ty, paint);
        float ow = paint.measureText(ov + " ");
        paint.setFakeBoldText(false);
        paint.setColor(Ui.DIM);
        cv.drawText("微调", xr - ow, ty, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }
}
