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
 * 演示屏：一块横屏，开启后两侧的黑边变成白色。
 * 比例、宽度微调与真实遮罩算法一致；屏幕外有一圈很细的浅蓝灰描边，白边才看得出边界。
 * 外面的白色卡片由使用它的布局提供。
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
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // 让屏幕下面的阴影在所有机型上都能画出来
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

    /** 为阴影留的空间（dp）：布局里要用同样大小的负边距把它抵消掉。 */
    public static final int PAD_X = 12, PAD_T = 8, PAD_B = 22;

    /** 用真实屏幕的比例，小窗 / 分屏时也和真正的遮罩一致。 */
    private float aspect() {
        float[] s = Ui.realScreen(getContext());
        return Math.max(16f / 9f, Math.min(2.4f, s[0] / s[1]));
    }

    @Override
    protected void onMeasure(int w, int h) {
        Context c = getContext();
        int width = MeasureSpec.getSize(w);
        float inner = width - 2 * Ui.dp(c, PAD_X);
        setMeasuredDimension(width, Math.round(inner / aspect() + Ui.dp(c, PAD_T + PAD_B)));
    }

    @Override
    protected void onDraw(Canvas cv) {
        Context c = getContext();
        float[] scr = Ui.realScreen(c);
        float density = scr[2];
        float longPx = scr[0];
        cv.save();
        cv.translate(Ui.dp(c, PAD_X), Ui.dp(c, PAD_T));
        float sw = getWidth() - 2 * Ui.dp(c, PAD_X), sh = sw / aspect();
        float scale = sw / (longPx / density);   // 演示屏上 1 个真实 px 对应多少
        float rad = Ui.dp(c, 18);

        // 屏幕本体 + 两层阴影：宽而淡的环境影 + 紧贴屏幕的接触影
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.EDGE);
        paint.setShadowLayer(Ui.dp(c, 14), 0, Ui.dp(c, 7), 0x302B3A78);
        rect.set(0, 0, sw, sh);
        cv.drawRoundRect(rect, rad, rad, paint);
        paint.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1.5f), 0x262B3042);
        cv.drawRoundRect(rect, rad, rad, paint);
        paint.clearShadowLayer();

        clip.reset();
        clip.addRoundRect(new RectF(0, 0, sw, sh), rad, rad, Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        paint.setColor(Ui.EDGE);
        cv.drawRect(0, 0, sw, sh, paint);

        // 视频画面
        float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        float vw = Math.min(sw, sh * ratio);
        float vl = (sw - vw) / 2f;
        paint.setShader(new LinearGradient(vl, 0, vl + vw, sh,
                new int[]{0xFF7F93D8, 0xFF4A5BA8, 0xFF323F7A}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        cv.drawRect(vl, 0, vl + vw, sh, paint);
        paint.setShader(null);

        // 播放键
        paint.setColor(0xE0FFFFFF);
        float cx = sw / 2f, cy = sh / 2f, ts = sh * 0.14f;
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
        cv.drawRect(0, 0, bar, sh, paint);
        cv.drawRect(sw - bar, 0, sw, sh, paint);

        // 白边靠视频一侧的一点点阴影，让白边和画面分得开
        if (amount > 0.01f && bar > 0f) {
            float edge = Ui.dp(c, 10);
            int a = Math.round(0x2E * amount);
            int dark = (a << 24) | 0x1F2638, none = 0x001F2638;
            paint.setShader(new LinearGradient(bar, 0, bar + edge, 0, dark, none, Shader.TileMode.CLAMP));
            cv.drawRect(bar, 0, bar + edge, sh, paint);
            paint.setShader(new LinearGradient(sw - bar, 0, sw - bar - edge, 0, dark, none, Shader.TileMode.CLAMP));
            cv.drawRect(sw - bar - edge, 0, sw - bar, sh, paint);
            paint.setShader(null);
        }

        // 玻璃感：左上一抹高光，底部一点暗
        paint.setShader(new LinearGradient(0, 0, sw * 0.45f, sh * 0.75f, 0x2AFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, sw, sh, paint);
        paint.setShader(new LinearGradient(0, sh * 0.7f, 0, sh, 0x00141A32, 0x1A141A32,
                Shader.TileMode.CLAMP));
        cv.drawRect(0, sh * 0.7f, sw, sh, paint);
        paint.setShader(null);
        cv.restore();

        // 屏幕外的细描边
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(c, 1));
        paint.setColor(Ui.OUTLINE);
        rect.set(Ui.dp(c, 0.5f), Ui.dp(c, 0.5f), sw - Ui.dp(c, 0.5f), sh - Ui.dp(c, 0.5f));
        cv.drawRoundRect(rect, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
        cv.restore();
    }
}
