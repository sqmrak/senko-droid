package com.senko.droid;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * which apps skip the tunnel. the vpn builder excludes packages from android
 * 5.0, so this screen exists there only, and an app cannot be blocked this way
 * (the ios build can), only sent direct
 */
public final class AppRoutingActivity extends BaseActivity {
    private static final class App {
        String pkg;
        String label;
        ApplicationInfo info;
    }

    private final List<App> all = new ArrayList<App>();
    private final List<App> shown = new ArrayList<App>();
    private final Set<String> direct = new HashSet<String>();
    private String query = "";
    private Adapter adapter;
    private PackageManager packages;
    private java.util.LinkedHashMap<String, Drawable> icons;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("Apps");
        packages = getPackageManager();
        direct.addAll(Prefs.appDirect());
        // an icon is a few kilobytes and the list scrolls, so the cache stays bounded
        icons = new java.util.LinkedHashMap<String, Drawable>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, Drawable> eldest) {
                return size() > 80;
            }
        };

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(Tr.t("Search"));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTextColor(Theme.ink());
        search.setHintTextColor(Theme.color(Theme.INK_MUTED));
        search.setTextSize(15);
        search.setPadding(Ui.dp(14), 0, Ui.dp(14), 0);
        Ui.background(search, Ui.plate(10, Theme.color(Theme.WELL)));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                filter();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(40));
        params.setMargins(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(6));
        column.addView(search, params);
        TextView note = Ui.text(this, Tr.t("Apps marked Direct skip the tunnel. A change applies the next time the tunnel connects."),
                12, Theme.color(Theme.INK_MUTED), false);
        note.setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(6));
        column.addView(note);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(Ui.listSelector());
        adapter = new Adapter();
        list.setAdapter(adapter);
        column.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setBody(column);
        load();
    }

    private void load() {
        // the package list is a few hundred entries and reading labels takes a while on api 16 hardware
        new Thread(new Runnable() {
            @Override
            public void run() {
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                final List<App> found = new ArrayList<App>();
                Set<String> seen = new HashSet<String>();
                for (ResolveInfo resolved : packages.queryIntentActivities(launcher, 0)) {
                    String pkg = resolved.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                    App app = new App();
                    app.pkg = pkg;
                    app.info = resolved.activityInfo.applicationInfo;
                    app.label = String.valueOf(resolved.loadLabel(packages));
                    found.add(app);
                }
                Collections.sort(found, new Comparator<App>() {
                    @Override
                    public int compare(App a, App b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(found);
                        filter();
                    }
                });
            }
        }, "senko-apps").start();
    }

    private void filter() {
        shown.clear();
        String needle = query.trim().toLowerCase(Locale.US);
        for (App app : all) {
            if (needle.isEmpty() || app.label.toLowerCase(Locale.US).contains(needle)
                    || app.pkg.toLowerCase(Locale.US).contains(needle)) shown.add(app);
        }
        adapter.notifyDataSetChanged();
    }

    private void toggle(App app) {
        if (!direct.remove(app.pkg)) direct.add(app.pkg);
        Prefs.setAppDirect(new HashSet<String>(direct));
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            final App app = shown.get(position);
            LinearLayout line = new LinearLayout(AppRoutingActivity.this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(Ui.dp(14), Ui.dp(6), Ui.dp(14), Ui.dp(6));
            Ui.background(line, Ui.rowPress());
            ImageView icon = new ImageView(AppRoutingActivity.this);
            Drawable cached = icons.get(app.pkg);
            if (cached == null) {
                cached = app.info.loadIcon(packages);
                icons.put(app.pkg, cached);
            }
            icon.setImageDrawable(cached);
            line.addView(icon, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
            LinearLayout texts = new LinearLayout(AppRoutingActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(Ui.dp(12), 0, Ui.dp(8), 0);
            TextView name = Ui.text(AppRoutingActivity.this, app.label, 16, Theme.ink(), false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(name);
            TextView pkg = Ui.text(AppRoutingActivity.this, app.pkg, 11, Theme.color(Theme.INK_MUTED), false);
            pkg.setSingleLine(true);
            pkg.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(pkg);
            line.addView(texts, Ui.weighted(1));
            boolean skip = direct.contains(app.pkg);
            line.addView(Ui.text(AppRoutingActivity.this, Tr.t(skip ? "Direct" : "Through the tunnel"), 13,
                    skip ? Theme.color(Theme.ACCENT) : Theme.color(Theme.INK_MUTED), skip));
            line.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    toggle(app);
                }
            });
            return line;
        }
    }
}
