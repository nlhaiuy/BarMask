package com.example.blackbarmask;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 白名单：选择“在哪些软件里自动开启白边”，支持搜索，可同时选多个。 */
public class AppPickerActivity extends Activity {
    private static class Item {
        String pkg;
        CharSequence label;
        String key;      // 小写的名称 + 包名，用来搜索
        Drawable icon;
        boolean checked;
    }

    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private ListView list;
    private BaseAdapter adapter;
    private EditText search;
    private TextView countTv;
    private float density;
    private boolean night;
    private int bg, text, sub, field;

    private int dp(int v) {
        return Math.round(v * density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        bg = night ? 0xFF121218 : 0xFFF6F5FB;
        text = night ? 0xFFE5E1E9 : 0xFF1B1B21;
        sub = night ? 0xFF9E9DAC : 0xFF5F6070;
        field = night ? 0xFF2B2B36 : 0xFFEEF0F6;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(night ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(16), dp(20), dp(8));
        TextView title = new TextView(this);
        title.setText("白名单");
        title.setTextSize(20);
        title.setTextColor(text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        countTv = new TextView(this);
        countTv.setTextSize(13);
        countTv.setTextColor(sub);
        countTv.setText("");
        head.addView(countTv);

        // 搜索框
        search = new EditText(this);
        search.setHint("搜索软件名称");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(field);
        search.setTextColor(text);
        search.setHintTextColor(sub);
        sbg.setCornerRadius(dp(12));
        search.setBackground(sbg);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        head.addView(search, slp);
        page.addView(head);

        list = new ListView(this);
        list.setDividerHeight(0);
        page.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { refilter(); }
        });

        new Thread(this::load).start();
    }

    private void load() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        Set<String> sel = AutoMode.selectedApps(this);
        Map<String, Item> map = new HashMap<>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
            Item it = new Item();
            it.pkg = pkg;
            it.label = ri.loadLabel(pm);
            it.key = (it.label.toString() + " " + pkg).toLowerCase(Locale.ROOT);
            it.icon = ri.loadIcon(pm);
            it.checked = sel.contains(pkg);
            map.put(pkg, it);
        }
        runOnUiThread(() -> {
            all.clear();
            all.addAll(map.values());
            adapter = new Adapter();
            list.setAdapter(adapter);
            list.setOnItemClickListener((p, v, pos, id) -> {
                Item it = shown.get(pos);
                it.checked = !it.checked;   // 只改状态，不重排，避免列表乱跳
                adapter.notifyDataSetChanged();
                save();
            });
            refilter();
        });
    }

    /** 按搜索词过滤；已勾选的排前面，其余按名称排序。选中状态在搜索前后保持不变。 */
    private void refilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Item it : all) {
            if (q.isEmpty() || it.key.contains(q)) shown.add(it);
        }
        Collator col = Collator.getInstance(Locale.CHINA);
        shown.sort((a, c) -> {
            if (a.checked != c.checked) return a.checked ? -1 : 1;
            return col.compare(a.label.toString(), c.label.toString());
        });
        if (adapter != null) adapter.notifyDataSetChanged();
        updateCount();
    }

    private void save() {
        Set<String> s = new HashSet<>();
        for (Item it : all) if (it.checked) s.add(it.pkg);
        SharedPreferences sp = getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        sp.edit().putStringSet(AutoMode.KEY_APPS, s).apply();
        updateCount();
    }

    private void updateCount() {
        int n = 0;
        for (Item it : all) if (it.checked) n++;
        countTv.setText("已选 " + n + " 个"
                + (search.getText().length() > 0 ? "（搜到 " + shown.size() + " 个）" : ""));
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Item it = shown.get(i);
            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(10), dp(20), dp(10));

            ImageView icon = new ImageView(AppPickerActivity.this);
            icon.setImageDrawable(it.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView name = new TextView(AppPickerActivity.this);
            name.setText(it.label);
            name.setTextSize(16);
            name.setTextColor(text);
            name.setPadding(dp(14), 0, dp(8), 0);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            CheckBox cb = new CheckBox(AppPickerActivity.this);
            cb.setChecked(it.checked);
            cb.setClickable(false);   // 点击整行来切换
            cb.setFocusable(false);
            row.addView(cb);
            return row;
        }
    }
}
