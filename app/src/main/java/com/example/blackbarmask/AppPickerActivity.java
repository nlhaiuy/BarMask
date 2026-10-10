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
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
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

/** 选择“在哪些软件里自动开启白边”。 */
public class AppPickerActivity extends Activity {
    private static class Item {
        String pkg;
        CharSequence label;
        Drawable icon;
        boolean checked;
    }

    private final List<Item> items = new ArrayList<>();
    private ListView list;
    private BaseAdapter adapter;
    private TextView countTv;
    private float density;

    private int dp(int v) {
        return Math.round(v * density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(16), dp(20), dp(12));
        TextView title = new TextView(this);
        title.setText("选择视频软件");
        title.setTextSize(20);
        title.setTextColor(0xFF111827);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        countTv = new TextView(this);
        countTv.setTextSize(13);
        countTv.setTextColor(0xFF6B7280);
        countTv.setText("正在加载应用列表…");
        head.addView(countTv);
        page.addView(head);

        list = new ListView(this);
        list.setDividerHeight(0);
        page.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);

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
            it.icon = ri.loadIcon(pm);
            it.checked = sel.contains(pkg);
            map.put(pkg, it);
        }
        List<Item> sorted = new ArrayList<>(map.values());
        Collator col = Collator.getInstance(Locale.CHINA);
        sorted.sort((a, c) -> {
            if (a.checked != c.checked) return a.checked ? -1 : 1; // 已勾选的排前面
            return col.compare(a.label.toString(), c.label.toString());
        });
        runOnUiThread(() -> {
            items.clear();
            items.addAll(sorted);
            adapter = new Adapter();
            list.setAdapter(adapter);
            list.setOnItemClickListener((p, v, pos, id) -> {
                items.get(pos).checked = !items.get(pos).checked;
                adapter.notifyDataSetChanged();
                save();
            });
            updateCount();
        });
    }

    private void save() {
        Set<String> s = new HashSet<>();
        for (Item it : items) if (it.checked) s.add(it.pkg);
        SharedPreferences sp = getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        sp.edit().putStringSet(AutoMode.KEY_APPS, s).apply();
        updateCount();
    }

    private void updateCount() {
        int n = 0;
        for (Item it : items) if (it.checked) n++;
        countTv.setText("已选 " + n + " 个，点一下即可勾选/取消");
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Item it = items.get(i);
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
            name.setTextColor(0xFF111827);
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
