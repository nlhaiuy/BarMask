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
import android.view.View;

/**
 * 演示屏：一块横屏，开启后两侧的黑边变成白色。
 * 比例、宽度微调与真实遮罩算法一致；屏幕外有一圈很细的浅蓝灰描边，白边才看得出边界。
 * 外面的白色卡片由使用它的布局提供。
 *
 * 绘制性能：渐变、圆角裁剪路径、屏幕尺寸都在尺寸变化时算好并缓存，
 * 拖动微调时每帧只做必要的几次绘制，不再每帧创建对象。
 */
public class PreviewView extends View {
    /** 为阴影留的空间（dp）：布局里要用同样大小的负边距把它抵消掉。 */
    public static final int PAD_X = 12, PAD_T = 8, PAD_B = 22;

    private static final float PLAYED = 0.38f;   // 演示进度条已播放的比例

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Path tri = new Path();
    private final SharedPreferences sp;

    private final float density;     // 真实屏幕的 density
    private final float longPx;      // 真实屏幕长边（px）
    private final float aspect;      // 演示屏的长宽比

    private float amount;            // 0 = 黑边，1 = 白边
    private ValueAnimator anim;
    private float shownRatio;        // 正在显示的画幅（切换 16:9 / 4:3 时平滑过渡）
    private ValueAnimator ratioAnim;
    private int offset;              // 宽度微调（dp），拖动时由 refresh() 更新

    // 尺寸相关的缓存（onSizeChanged 时重算）
    private float sw, sh, rad, edgeW;
    private Shader glassShader, darkShader, edgeShader;
    // 视频画面渐变：只在画幅动画期间会变
    private Shader videoShader;
    private float videoL = -1f, videoW = -1f, videoH = -1f;

    public PreviewView(Context c) {
        super(c);
        sp = c.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        amount = AutoMode.isMaster(c) ? 1f : 0f;
        shownRatio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        offset = sp.getInt(MaskService.KEY_OFFSET, 0);
        float[] scr = Ui.realScreen(c);
        longPx = scr[0];
        density = scr[2];
        aspect = Math.max(16f / 9f, Math.min(2.4f, scr[0] / scr[1]));
        Ui.softwareIfNeeded(this);   // 旧系统上让屏幕下面的阴影也能画出来
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

    /** 宽度微调变了：重新读一次数值并重绘。 */
    public void refresh() {
        offset = sp.getInt(MaskService.KEY_OFFSET, 0);
        invalidate();
    }

    /** 画幅改了：视频画面平滑地变宽 / 变窄，不再瞬间跳。 */
    public void ratioChanged() {
        float to = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        if (ratioAnim != null) ratioAnim.cancel();
        if (!isLaidOut() || Math.abs(to - shownRatio) < 0.001f) { shownRatio = to; invalidate(); return; }
        ratioAnim = ValueAnimator.ofFloat(shownRatio, to);
        ratioAnim.setDuration(260);
        ratioAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        ratioAnim.addUpdateListener(a -> { shownRatio = (Float) a.getAnimatedValue(); invalidate(); });
        ratioAnim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        Context c = getContext();
        int width = MeasureSpec.getSize(w);
        float inner = width - 2 * Ui.dp(c, PAD_X);
        setMeasuredDimension(width, Math.round(inner / aspect + Ui.dp(c, PAD_T + PAD_B)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        Context c = getContext();
        sw = w - 2 * Ui.dp(c, PAD_X);
        sh = sw / aspect;
        rad = Ui.dp(c, 18);
        edgeW = Ui.dp(c, 10);

        clip.reset();
        rect.set(0, 0, sw, sh);
        clip.addRoundRect(rect, rad, rad, Path.Direction.CW);

        // 玻璃感：左上一抹高光；底部一点暗（只盖在两条白边之间）
        glassShader = new LinearGradient(0, 0, sw * 0.45f, sh * 0.75f, 0x2AFFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP);
        darkShader = new LinearGradient(0, sh * 0.7f, 0, sh, 0x00141A32, 0x1A141A32,
                Shader.TileMode.CLAMP);
        // 白边靠视频一侧的一点阴影：形状固定，用画笔透明度控制深浅，用画布平移 / 翻转摆到两侧
        edgeShader = new LinearGradient(0, 0, edgeW, 0, 0x2E1F2638, 0x001F2638,
                Shader.TileMode.CLAMP);
        videoL = -1f;   // 视频渐变跟着重建
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (sw <= 0f) return;
        Context c = getContext();
        float scale = sw / longPx;               // 演示屏上 1 个真实 px 对应多少（dp 要先乘 density）
        cv.save();
        cv.translate(Ui.dp(c, PAD_X), Ui.dp(c, PAD_T));

        // 屏幕本体 + 两层阴影：宽而淡的环境影 + 紧贴屏幕的接触影
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.EDGE);
        paint.setShadowLayer(Ui.dp(c, 14), 0, Ui.dp(c, 7), 0x302B3A78);
        rect.set(0, 0, sw, sh);
        cv.drawRoundRect(rect, rad, rad, paint);
        paint.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1.5f), 0x262B3042);
        cv.drawRoundRect(rect, rad, rad, paint);
        paint.clearShadowLayer();

        cv.save();
        cv.clipPath(clip);
        paint.setColor(Ui.EDGE);
        cv.drawRect(0, 0, sw, sh, paint);

        // 视频画面
        float ratio = shownRatio;
        float vw = Math.min(sw, sh * ratio);
        float vl = (sw - vw) / 2f;
        if (videoShader == null || vl != videoL || vw != videoW || sh != videoH) {
            videoShader = new LinearGradient(vl, 0, vl + vw, sh,
                    new int[]{0xFF7F93D8, 0xFF4A5BA8, 0xFF323F7A}, new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP);
            videoL = vl;
            videoW = vw;
            videoH = sh;
        }
        paint.setShader(videoShader);
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

        // 底部进度条：更像一个真的播放器
        float tl = vl + vw * 0.07f, tr = vl + vw * 0.93f;
        float ty = sh * 0.88f, th = Math.max(Ui.dp(c, 2f), sh * 0.02f);
        float px = tl + (tr - tl) * PLAYED;
        paint.setColor(0x4DFFFFFF);
        rect.set(tl, ty - th / 2f, tr, ty + th / 2f);
        cv.drawRoundRect(rect, th / 2f, th / 2f, paint);
        paint.setColor(0xE6FFFFFF);
        rect.set(tl, ty - th / 2f, px, ty + th / 2f);
        cv.drawRoundRect(rect, th / 2f, th / 2f, paint);
        cv.drawCircle(px, ty, th * 1.9f, paint);

        // 两侧遮罩：黑边 → 白边
        float bar = (sw - sh * ratio) / 2f + offset * density * scale;
        bar = Math.max(0f, Math.min(bar, sw / 2f));
        paint.setColor(Ui.blend(Ui.EDGE, 0xFFFFFFFF, amount));
        cv.drawRect(0, 0, bar, sh, paint);
        cv.drawRect(sw - bar, 0, sw, sh, paint);

        // 白边靠视频一侧的一点点阴影，让白边和画面分得开
        if (amount > 0.01f && bar > 0f) {
            paint.setShader(edgeShader);
            paint.setAlpha(Math.round(255 * amount));
            cv.save();
            cv.translate(bar, 0);
            cv.drawRect(0, 0, edgeW, sh, paint);
            cv.restore();
            cv.save();
            cv.translate(sw - bar, 0);
            cv.scale(-1f, 1f);
            cv.drawRect(0, 0, edgeW, sh, paint);
            cv.restore();
            paint.setAlpha(255);
            paint.setShader(null);
        }

        // 玻璃感高光；底部压暗只盖在两条白边之间，白边保持纯白
        paint.setShader(glassShader);
        cv.drawRect(0, 0, sw, sh, paint);
        paint.setShader(darkShader);
        cv.drawRect(bar, sh * 0.7f, sw - bar, sh, paint);
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
