package com.example.blackbarmask;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.TextView;

/** 总开关：关着时是一圈细线的“开启白边”，开着时翻成整块纸白色的“关闭白边”。 */
public class PowerButton extends TextView {
    private float t;            // 0 = 关，1 = 开
    private ValueAnimator anim;
    private final GradientDrawable bg = new GradientDrawable();

    public PowerButton(Context c) {
        super(c);
        setGravity(Gravity.CENTER);
        setTextSize(18);
        setTypeface(Typeface.DEFAULT_BOLD);
        setLetterSpacing(0.04f);
        bg.setCornerRadius(Ui.dp(c, 32));
        setBackground(bg);
        setClickable(true);
        apply();
    }

    public void setOn(boolean on, boolean animate) {
        float to = on ? 1f : 0f;
        if (anim != null) anim.cancel();
        setText(on ? "关闭白边" : "开启白边");
        if (!animate || !isLaidOut()) { t = to; apply(); return; }
        anim = ValueAnimator.ofFloat(t, to);
        anim.setDuration(300);
        anim.addUpdateListener(a -> { t = (Float) a.getAnimatedValue(); apply(); });
        anim.start();
    }

    private void apply() {
        bg.setColor(Ui.blend(Ui.INK, Ui.PAPER, t));
        bg.setStroke(Math.round(Ui.dp(getContext(), 1.5f)), Ui.blend(Ui.MUTE, Ui.PAPER, t));
        setTextColor(Ui.blend(Ui.PAPER, Ui.INK, t));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }
}
