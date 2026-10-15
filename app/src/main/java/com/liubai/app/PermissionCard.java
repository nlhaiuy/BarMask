package com.liubai.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static com.liubai.app.Ui.lp;

/**
 * 主页上的授权提示卡：只列出还没开的权限，一项一个“开启”，开齐后整块收起。
 * 所有权限都由用户手动点“开启”，不会自动跳转。
 */
final class PermissionCard extends LinearLayout {
    // 权限：0 悬浮窗、1 通知、2 后台运行、3 无障碍
    private static final int[] NAMES = {
            R.string.perm_overlay, R.string.perm_notification,
            R.string.perm_background, R.string.perm_accessibility};

    private final Activity act;
    private final SharedPreferences sp;
    private boolean awaitingA11y = false;

    PermissionCard(Activity a) {
        super(a);
        act = a;
        sp = a.getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setBackground(Ui.round(Ui.WARN_BG, dp(22)));
        Ui.lift(this, 3, 0x337A5A12, 0x557A5A12);
    }

    private int dp(float v) { return Ui.px(act, v); }

    // ---------------- 对外 ----------------

    /** 提醒：授权卡抖一下。 */
    void shake() {
        if (getVisibility() != VISIBLE) return;
        ObjectAnimator.ofFloat(this, "translationX", 0, -dp(6), dp(6), -dp(4), dp(3), 0)
                .setDuration(420).start();
    }

    /** 无障碍这一项现在是需要的、并且还没开。 */
    boolean accessibilityMissing() {
        return applicable(3) && missing(3);
    }

    /** 刚才点过“开启无障碍”吗？取一次就清掉。 */
    boolean consumeAwaitingA11y() {
        boolean was = awaitingA11y;
        awaitingA11y = false;
        return was;
    }

    /** 只列出还没开的权限；回到页面时刷新，开齐后整块收起。 */
    void refresh() {
        removeAllViews();
        List<Integer> req = new ArrayList<>(), opt = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            if (applicable(i) && missing(i)) (required(i) ? req : opt).add(i);
        }
        if (req.isEmpty() && opt.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        for (int k = 0; k < req.size(); k++) addView(permRow(req.get(k), k > 0));
        if (!opt.isEmpty()) addView(optRow(opt, !req.isEmpty()));
    }

    /** 无障碍没开成功：最常见的原因是系统对“非商店安装”的软件置灰了该选项。 */
    void showRestrictedHelp() {
        new AlertDialog.Builder(act)
                .setTitle(R.string.restricted_title)
                .setMessage(R.string.restricted_message)
                .setPositiveButton(R.string.restricted_app_info, (d, w) -> act.startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + act.getPackageName()))))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------- 状态 ----------------

    /** 这一项当前是否需要（通知仅 Android 13+，无障碍仅白名单模式）。 */
    private boolean applicable(int i) {
        if (i == 1) return Build.VERSION.SDK_INT >= 33;
        if (i == 3) return AutoMode.isEnabled(act);
        return true;
    }

    private boolean missing(int i) {
        switch (i) {
            case 0:
                return !Settings.canDrawOverlays(act);
            case 1:
                return Build.VERSION.SDK_INT >= 33
                        && act.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED;
            case 2:
                return !((PowerManager) act.getSystemService(Context.POWER_SERVICE))
                        .isIgnoringBatteryOptimizations(act.getPackageName());
            default:
                return !AutoMode.isAccessibilityEnabled(act);
        }
    }

    /** 必需的：悬浮窗；白名单模式下还有无障碍。其余是可选的。 */
    private boolean required(int i) {
        return i == 0 || i == 3;
    }

    private int descRes(int i) {
        return i == 0 ? R.string.perm_overlay_desc : R.string.perm_accessibility_desc;
    }

    // ---------------- 界面 ----------------

    private void divider(boolean show) {
        if (!show) return;
        View v = new View(act);
        v.setBackgroundColor(Ui.WARN_LINE);
        addView(v, lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));
    }

    private View permRow(int i, boolean divided) {
        divider(divided);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(13), dp(14), dp(13));

        LinearLayout text = new LinearLayout(act);
        text.setOrientation(VERTICAL);
        TextView name = new TextView(act);
        name.setText(NAMES[i]);
        name.setTextSize(14);
        name.setTextColor(Ui.WARN_TX);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name);
        TextView desc = new TextView(act);
        desc.setText(descRes(i));
        desc.setTextSize(12);
        desc.setTextColor(Ui.WARN_SUB);
        desc.setPadding(0, dp(2), 0, 0);
        text.addView(desc);
        LinearLayout.LayoutParams tlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1f;
        row.addView(text, tlp);

        TextView go = new TextView(act);
        go.setText(R.string.perm_enable);
        go.setTextSize(13);
        go.setTextColor(0xFFFFFFFF);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setPadding(dp(16), dp(8), dp(16), dp(8));
        go.setBackground(Ui.gradient(dp(20)));
        Ui.lift(go, 3, 0x404F7CFF, 0x884F7CFF);
        go.setOnClickListener(v -> launch(i));
        Ui.press(go, 0.94f);
        LinearLayout.LayoutParams glp = lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(12);
        row.addView(go, glp);
        return wrapRow(row);
    }

    private View optRow(List<Integer> opt, boolean divided) {
        divider(divided);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        TextView label = new TextView(act);
        label.setText(R.string.perm_optional);
        label.setTextSize(12);
        label.setTextColor(Ui.WARN_SUB);
        row.addView(label);
        for (int i : opt) {
            SpannableString s = new SpannableString("＋ " + act.getString(NAMES[i]));
            s.setSpan(new ForegroundColorSpan(Ui.PRI), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView chip = new TextView(act);
            chip.setText(s);
            chip.setTextSize(12);
            chip.setTextColor(Ui.WARN_TX);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(11), dp(6), dp(12), dp(6));
            GradientDrawable cb = Ui.round(0xFFFFFFFF, dp(20));
            cb.setStroke(Math.max(1, dp(1)), 0x247A5A12);
            chip.setBackground(cb);
            Ui.lift(chip, 2, 0x337A5A12, 0x557A5A12);
            chip.setOnClickListener(v -> launch(i));
            Ui.press(chip, 0.94f);
            LinearLayout.LayoutParams clp = lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(8);
            row.addView(chip, clp);
        }
        return wrapRow(row);
    }

    private View wrapRow(LinearLayout row) {
        row.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    // ---------------- 跳转 ----------------

    /** 一项权限，直达对应的系统授权页。只在你点“开启”时才会跳。 */
    private void launch(int i) {
        try {
            switch (i) {
                case 0:
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + act.getPackageName())));
                    break;
                case 1:
                    act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                    break;
                case 2:
                    try {
                        act.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + act.getPackageName())));
                    } catch (Exception e) {
                        act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    break;
                default:
                    // 开启成功后，留白会自己跳回前台，不用手动返回
                    sp.edit().putBoolean(AutoAccessibilityService.KEY_RETURN, true).apply();
                    awaitingA11y = true;
                    openAccessibility();
            }
        } catch (Exception e) {
            // 实在打不开专用页面，就去应用信息页，让用户手动设置
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + act.getPackageName())));
        }
    }

    /**
     * 无障碍直达：Android 14+ 直接打开“留白”自己的无障碍详情页，
     * 更低版本打开无障碍列表并把“留白”那一项高亮出来。
     */
    private void openAccessibility() {
        String flat = new ComponentName(act, AutoAccessibilityService.class).flattenToString();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Intent direct = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                direct.putExtra("android.intent.extra.COMPONENT_NAME", flat);
                act.startActivity(direct);
                return;
            } catch (Exception ignored) { /* 个别系统没有这个页面，退回列表 */ }
        }
        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", flat);
        list.putExtra(":settings:fragment_args_key", flat);
        list.putExtra(":settings:show_fragment_args", args);
        act.startActivity(list);
    }
}
