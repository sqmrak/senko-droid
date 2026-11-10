package com.senko.droid;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * the engine log and the app's own log lines, filtered by source and by a
 * search, with faults, retries and tags coloured so a long log reads at a
 * glance. what leaves the device through copy or share is redacted first
 */
public final class LogsActivity extends BaseActivity {
    private static final int ALL = 0;
    private static final int ENGINE = 1;
    private static final int APP = 2;
    private static final int APP_LINES = 400;

    private static final String[] FAULTS = { "fail", "error", "cannot", "refused", "denied", "crash", "fault", "killed" };
    private static final String[] WARNS = { "warn", "retry", "timeout", "timed out", "lost", "unreachable" };

    private int filter = ALL;
    private String query = "";
    private String engineLog = "";
    private String appLog = "";
    private String shown = "";
    private TextView text;
    private TextView count;
    private final TextView[] tabs = new TextView[3];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("System Logs");
        addBarIcon(Icons.shape(this, Icons.Shape.REFRESH, barInk()), "Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                load();
            }
        });
        addBarText("Share", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                actions();
            }
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout segment = new LinearLayout(this);
        segment.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(4));
        String[] names = { Tr.t("all"), "core", "app" };
        for (int i = 0; i < 3; ++i) {
            final int value = i;
            TextView tab = new TextView(this);
            tab.setText(names[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, Ui.dp(7), 0, Ui.dp(7));
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    filter = value;
                    paintTabs();
                    refilter();
                }
            });
            tabs[i] = tab;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(Ui.dp(3), 0, Ui.dp(3), 0);
            segment.addView(tab, params);
        }
        column.addView(segment);
        paintTabs();

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(Tr.t("Search logs"));
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTextColor(Theme.ink());
        search.setHintTextColor(Theme.color(Theme.INK_MUTED));
        search.setTextSize(14);
        search.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        Ui.background(search, Ui.plate(10, Theme.color(Theme.WELL)));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                refilter();
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, Ui.dp(38));
        searchParams.setMargins(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
        column.addView(search, searchParams);

        count = Ui.text(this, "", 11, Theme.color(Theme.INK_MUTED), false);
        count.setPadding(Ui.dp(14), 0, Ui.dp(14), Ui.dp(2));
        column.addView(count);

        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11);
        text.setTextColor(Theme.ink());
        Compat.selectable(text, true);
        text.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setBody(column);
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    private void paintTabs() {
        int accent = Theme.color(Theme.ACCENT);
        for (int i = 0; i < 3; ++i) {
            boolean on = i == filter;
            tabs[i].setTextColor(on ? Ui.onColor(accent) : Theme.ink());
            tabs[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            Ui.background(tabs[i], on ? Ui.solid(accent, 8) : Ui.plate(8, Theme.color(Theme.WELL)));
        }
    }

    private void load() {
        text.setText(Tr.t("Loading logs..."));
        senko.run(false, new Senko.Work<String>() {
            @Override
            public String run(ControlClient client) throws IOException {
                return client.logs();
            }
        }, new Senko.Done<String>() {
            @Override
            public void done(String log, IOException error) {
                engineLog = tag(log == null ? "" : log, "core");
                appLog = tag(readOwnLog(), "app");
                refilter();
            }
        });
    }

    /** logcat of an app shows its own process since api 16, which is the app source */
    private static String readOwnLog() {
        try {
            Process process = Runtime.getRuntime().exec(new String[] { "logcat", "-d", "-v", "time", "-t",
                    String.valueOf(APP_LINES) });
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder();
            try {
                for (String line = reader.readLine(); line != null; line = reader.readLine())
                    out.append(line).append('\n');
            } finally {
                reader.close();
                process.destroy();
            }
            return out.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String tag(String log, String source) {
        StringBuilder out = new StringBuilder();
        for (String line : log.split("\n")) {
            if (line.isEmpty()) continue;
            out.append('[').append(source).append("] ").append(line).append('\n');
        }
        return out.toString();
    }

    private void refilter() {
        String base = filter == ENGINE ? engineLog : filter == APP ? appLog : engineLog + appLog;
        String needle = query.trim().toLowerCase(Locale.US);
        StringBuilder kept = new StringBuilder();
        int lines = 0;
        for (String line : base.split("\n")) {
            if (line.isEmpty()) continue;
            if (!needle.isEmpty() && !line.toLowerCase(Locale.US).contains(needle)) continue;
            kept.append(line).append('\n');
            lines++;
        }
        shown = kept.toString();
        if (shown.isEmpty()) {
            text.setText(Tr.t(filter == APP ? "No app log lines" : "No daemon logs available"));
            count.setText("");
            return;
        }
        count.setText(Tr.f("Lines: %d", lines));
        text.setText(colour(shown), TextView.BufferType.SPANNABLE);
    }

    private CharSequence colour(String log) {
        SpannableStringBuilder out = new SpannableStringBuilder(log);
        int fault = Theme.stateColor("error");
        int warn = 0xffe0a020;
        int tagColor = Theme.color(Theme.ACCENT);
        int start = 0;
        while (start < log.length()) {
            int end = log.indexOf('\n', start);
            if (end < 0) end = log.length();
            String line = log.substring(start, end).toLowerCase(Locale.US);
            int color = 0;
            if (has(line, FAULTS)) color = fault;
            else if (has(line, WARNS)) color = warn;
            if (color != 0) out.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int close = line.indexOf(']');
            if (line.startsWith("[") && close > 0)
                out.setSpan(new ForegroundColorSpan(tagColor), start, start + close + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end + 1;
        }
        return out;
    }

    private static boolean has(String line, String[] words) {
        for (String word : words) if (line.contains(word)) return true;
        return false;
    }

    private void actions() {
        if (shown.isEmpty()) return;
        List<String> labels = new ArrayList<String>();
        labels.add(Tr.t("Copy"));
        labels.add(Tr.t("Share"));
        Dialogs.menu(this, "System Logs", labels, new Dialogs.Pick() {
            @Override
            public void picked(int index) {
                String safe = Errors.redact(shown);
                if (index == 0) {
                    Dialogs.copy(LogsActivity.this, "logs", safe);
                    toast("Copied to the clipboard.");
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, safe);
                startActivity(Intent.createChooser(send, Tr.t("Share")));
            }
        });
    }
}
