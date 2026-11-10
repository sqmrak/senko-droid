package com.senko.droid;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * the session: state, server, uptime, the two totals and their rates, then
 * the plan left on every subscription as the panel reports it in its headers
 */
public final class StatsActivity extends BaseActivity {
    private final Handler ticker = new Handler();
    private LinearLayout column;
    private View dot;
    private TextView state;
    private TextView uptime;
    private TextView server;
    private TextView downTotal;
    private TextView downRate;
    private TextView upTotal;
    private TextView upRate;
    private LinearLayout subs;
    private Chart chart;
    private TextView chartPeak;
    private TextView average;
    private TextView peakDown;
    private TextView peakUp;
    private TextView transferred;
    private LinearLayout latency;
    private LinearLayout protocols;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            fillSession();
            if ("connected".equals(senko.state())) ticker.postDelayed(this, 1000);
        }
    };

    /** download and upload rate of the last minute and a half, two filled lines over a faint grid */
    private static final class Chart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path();
        private final Path area = new Path();
        private long[] down = new long[0];
        private long[] up = new long[0];
        // the lines are drawn in from the left once, when the screen opens
        private float reveal;

        Chart(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Tween sweep = Tween.ofFloat(0f, 1f);
            sweep.setDuration(700);
            sweep.setInterpolator(Motion.SWEEP);
            sweep.addUpdateListener(new Tween.Update() {
                @Override
                public void onUpdate(Tween animation) {
                    reveal = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            reveal = 0f;
            sweep.start();
        }

        void set(long[] downRates, long[] upRates) {
            down = downRates;
            up = upRates;
            invalidate();
        }

        long peak() {
            long peak = 0;
            for (long value : down) peak = Math.max(peak, value);
            for (long value : up) peak = Math.max(peak, value);
            return peak;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(1));
            paint.setColor(Ui.alpha(Theme.color(Theme.INK_MUTED), 0.25f));
            for (int i = 0; i <= 3; ++i) canvas.drawLine(0, h * i / 3f, w, h * i / 3f, paint);
            long peak = Math.max(peak(), 1024);
            canvas.save();
            canvas.clipRect(0, 0, w * reveal, h);
            plot(canvas, down, peak, w, h, Theme.color(Theme.ACCENT));
            plot(canvas, up, peak, w, h, Theme.stateColor("connected"));
            canvas.restore();
        }

        private void plot(Canvas canvas, long[] samples, long peak, float w, float h, int color) {
            if (samples.length < 2) return;
            float pad = Ui.dp(2);
            float step = w / (Senko.HISTORY - 1);
            // the newest sample sits at the right edge, a short history starts part way across
            float x0 = w - step * (samples.length - 1);
            line.reset();
            area.reset();
            for (int i = 0; i < samples.length; ++i) {
                float x = x0 + step * i;
                float y = h - pad - (h - 2 * pad) * (float) samples[i] / (float) peak;
                if (i == 0) {
                    line.moveTo(x, y);
                    area.moveTo(x, h);
                    area.lineTo(x, y);
                } else {
                    line.lineTo(x, y);
                    area.lineTo(x, y);
                }
            }
            area.lineTo(w, h);
            area.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.alpha(color, 0.18f));
            canvas.drawPath(area, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(2));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
            canvas.drawPath(line, paint);
        }
    }

    /** the thin bar under a subscription that shows how much of the plan is gone */
    private static final class Meter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float fraction;
        private final int fill;
        private final boolean grow;
        private float shown = 1f;
        private Tween growth;

        Meter(android.content.Context context, float fraction, int fill, boolean grow) {
            super(context);
            this.fraction = Math.max(0f, Math.min(1f, fraction));
            this.fill = fill;
            this.grow = grow;
            if (grow) shown = 0f;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!grow || fraction <= 0f) {
                shown = 1f;
                return;
            }
            growth = Tween.ofFloat(0f, 1f);
            growth.setDuration(520);
            growth.setInterpolator(Motion.ENTER);
            growth.addUpdateListener(new Tween.Update() {
                @Override
                public void onUpdate(Tween animation) {
                    shown = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            growth.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (growth != null) growth.cancel();
            shown = 1f;
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            box.set(0, 0, getWidth(), getHeight());
            paint.setColor(Theme.color(Theme.WELL));
            canvas.drawRoundRect(box, radius, radius, paint);
            if (fraction <= 0f) return;
            box.right = Math.max(getHeight(), getWidth() * fraction * shown);
            paint.setColor(fill);
            canvas.drawRoundRect(box, radius, radius, paint);
        }
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setScreenTitle("Statistics");
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setBody(scroll);
        buildHero();
        buildTotals();
        buildChart();
        buildDetails();
        latency = new LinearLayout(this);
        latency.setOrientation(LinearLayout.VERTICAL);
        column.addView(latency);
        protocols = new LinearLayout(this);
        protocols.setOrientation(LinearLayout.VERTICAL);
        column.addView(protocols);
        subs = new LinearLayout(this);
        subs.setOrientation(LinearLayout.VERTICAL);
        column.addView(subs);
        fillSession();
        fillServers();
        fillSubs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        senko.loadCatalog(null);
        ticker.post(tick);
    }

    @Override
    protected void onStop() {
        ticker.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    public void onChange(int what) {
        if ((what & (Senko.STATE | Senko.TRAFFIC)) != 0) {
            fillSession();
            ticker.removeCallbacks(tick);
            if ("connected".equals(senko.state())) ticker.postDelayed(tick, 1000);
        }
        if ((what & (Senko.CATALOG | Senko.PING)) != 0) fillServers();
        if ((what & Senko.CATALOG) != 0) fillSubs();
    }

    private void buildChart() {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, Tr.t("Throughput"), 14, Theme.color(Theme.INK_MUTED), true), Ui.weighted(1));
        chartPeak = Ui.text(this, "", 13, Theme.color(Theme.INK_MUTED), false);
        head.addView(chartPeak);
        card.addView(head);
        chart = new Chart(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(110));
        params.topMargin = Ui.dp(10);
        card.addView(chart, params);
        LinearLayout legend = new LinearLayout(this);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(0, Ui.dp(8), 0, 0);
        legend.addView(legendEntry("Download", Theme.color(Theme.ACCENT)));
        legend.addView(legendEntry("Upload", Theme.stateColor("connected")));
        TextView span = Ui.text(this, Tr.f("Last %d seconds", Senko.HISTORY), 12, Theme.color(Theme.INK_MUTED), false);
        span.setGravity(Gravity.END);
        legend.addView(span, Ui.weighted(1));
        card.addView(legend);
        column.addView(card, gap());
    }

    private View legendEntry(String english, int color) {
        LinearLayout entry = new LinearLayout(this);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        View swatch = new View(this);
        Ui.background(swatch, Ui.solid(color, 4));
        entry.addView(swatch, new LinearLayout.LayoutParams(Ui.dp(8), Ui.dp(8)));
        TextView label = Ui.text(this, Tr.t(english), 12, Theme.color(Theme.INK_MUTED), false);
        label.setPadding(Ui.dp(5), 0, Ui.dp(14), 0);
        entry.addView(label);
        return entry;
    }

    private void buildDetails() {
        LinearLayout card = card();
        card.addView(Ui.text(this, Tr.t("Session"), 14, Theme.color(Theme.INK_MUTED), true));
        average = detail(card, "Average speed");
        peakDown = detail(card, "Peak download");
        peakUp = detail(card, "Peak upload");
        transferred = detail(card, "Total transferred");
        column.addView(card, gap());
    }

    /** a caption at the left and its value at the right, the value view comes back */
    private TextView detail(LinearLayout card, String english) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, Ui.dp(8), 0, 0);
        line.addView(Ui.text(this, Tr.t(english), 15, Theme.ink(), false), Ui.weighted(1));
        TextView value = Ui.text(this, "—", 15, Theme.color(Theme.INK_MUTED), true);
        line.addView(value);
        card.addView(line);
        return value;
    }

    /** one labelled row of a bar chart: caption, a bar scaled to the largest value, the count */
    private View barRow(String caption, int count, int largest, int color) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, Ui.dp(7), 0, 0);
        TextView label = Ui.text(this, caption, 13, Theme.ink(), false);
        label.setSingleLine(true);
        line.addView(label, new LinearLayout.LayoutParams(Ui.dp(92), -2));
        line.addView(new Meter(this, largest == 0 ? 0f : (float) count / largest, color, false),
                new LinearLayout.LayoutParams(0, Ui.dp(8), 1));
        TextView number = Ui.text(this, String.valueOf(count), 13, Theme.color(Theme.INK_MUTED), true);
        number.setGravity(Gravity.END);
        number.setPadding(Ui.dp(8), 0, 0, 0);
        line.addView(number, new LinearLayout.LayoutParams(Ui.dp(36), -2));
        return line;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        Ui.background(card, Ui.plate());
        return card;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = Ui.dp(12);
        return params;
    }

    private void buildHero() {
        LinearLayout hero = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        top.addView(dot, new LinearLayout.LayoutParams(Ui.dp(12), Ui.dp(12)));
        state = Ui.text(this, "", 20, Theme.ink(), true);
        state.setPadding(Ui.dp(10), 0, 0, 0);
        top.addView(state, Ui.weighted(1));
        uptime = Ui.text(this, "", 18, Theme.color(Theme.INK_MUTED), false);
        top.addView(uptime);
        hero.addView(top);
        server = Ui.text(this, "", 14, Theme.color(Theme.INK_MUTED), false);
        server.setSingleLine(true);
        server.setEllipsize(android.text.TextUtils.TruncateAt.END);
        server.setPadding(0, Ui.dp(6), 0, 0);
        hero.addView(server);
        column.addView(hero, gap());
    }

    private void buildTotals() {
        LinearLayout row = new LinearLayout(this);
        TextView[] down = total("Downloaded", "arrow-down");
        TextView[] up = total("Uploaded", "arrow-up");
        downTotal = down[0];
        downRate = down[1];
        upTotal = up[0];
        upRate = up[1];
        row.addView((View) down[0].getParent(), cell(true));
        row.addView((View) up[0].getParent(), cell(false));
        column.addView(row, gap());
    }

    private LinearLayout.LayoutParams cell(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (first) params.rightMargin = Ui.dp(6);
        else params.leftMargin = Ui.dp(6);
        return params;
    }

    /** a card with a caption, a total and a rate, returned as {total, rate} */
    private TextView[] total(String caption, String glyph) {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View icon = new View(this);
        android.graphics.drawable.Drawable art = Icons.glyph(this, glyph, Theme.color(Theme.ACCENT));
        if (art != null) Ui.background(icon, art);
        head.addView(icon, new LinearLayout.LayoutParams(Ui.dp(18), Ui.dp(18)));
        TextView title = Ui.text(this, Tr.t(caption), 13, Theme.color(Theme.INK_MUTED), false);
        title.setPadding(Ui.dp(6), 0, 0, 0);
        head.addView(title);
        card.addView(head);
        TextView amount = Ui.text(this, "", 20, Theme.ink(), true);
        amount.setPadding(0, Ui.dp(8), 0, 0);
        card.addView(amount);
        TextView rate = Ui.text(this, "", 13, Theme.color(Theme.INK_MUTED), false);
        card.addView(rate);
        return new TextView[] { amount, rate };
    }

    private void fillSession() {
        String current = senko.state();
        boolean connected = "connected".equals(current);
        boolean connecting = "connecting".equals(current);
        Ui.background(dot, Ui.solid(connected ? Theme.stateColor("connected")
                : connecting ? Theme.color(Theme.ACCENT) : Theme.color(Theme.INK_MUTED), 6));
        state.setText(Tr.t(connected ? "Connected" : connecting ? "Connecting" : "Disconnected"));
        long age = senko.uptimeSeconds();
        uptime.setText(connected && age >= 0 ? Format.uptime(age) : "");
        String name = "";
        if (senko.isActive()) {
            if (senko.activeBackend() == Prefs.BACKEND_AWG) name = "AmneziaWG";
            else {
                Model.Server picked = senko.pickedServer();
                name = picked == null ? "" : Names.title(picked);
            }
        }
        server.setText(name);
        server.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
        downTotal.setText(Format.bytes(senko.down()));
        upTotal.setText(Format.bytes(senko.up()));
        downRate.setText(connected ? Format.rate(senko.downRate()) : "");
        upRate.setText(connected ? Format.rate(senko.upRate()) : "");
        chart.set(senko.downHistory(), senko.upHistory());
        long peak = chart.peak();
        chartPeak.setText(peak > 0 ? Tr.f("Peak %s", Format.rate(peak)) : "");
        long total = senko.down() + senko.up();
        transferred.setText(total > 0 ? Format.bytes(total) : "—");
        average.setText(connected && age > 0 && total > 0 ? Format.rate((double) total / age) : "—");
        peakDown.setText(senko.peakDown() > 0 ? Format.rate(senko.peakDown()) : "—");
        peakUp.setText(senko.peakUp() > 0 ? Format.rate(senko.peakUp()) : "—");
    }

    private void fillServers() {
        latency.removeAllViews();
        protocols.removeAllViews();
        List<Model.Server> servers = senko.catalog().servers;
        if (servers.isEmpty()) return;

        int[] buckets = new int[5];
        int measured = 0;
        long sum = 0;
        int best = Integer.MAX_VALUE;
        int worst = -1;
        for (Model.Server server : servers) {
            int ms = senko.ping(server);
            if (ms == -2 || ms == -3) continue;
            if (ms < 0) {
                buckets[4]++;
                continue;
            }
            measured++;
            sum += ms;
            best = Math.min(best, ms);
            worst = Math.max(worst, ms);
            buckets[ms < 100 ? 0 : ms < 200 ? 1 : ms < 400 ? 2 : 3]++;
        }
        LinearLayout card = card();
        card.addView(Ui.text(this, Tr.t("Latency"), 14, Theme.color(Theme.INK_MUTED), true));
        TextView count = detail(card, "Measured");
        count.setText(Tr.f("%s of %s", String.valueOf(measured + buckets[4]), String.valueOf(servers.size())));
        if (measured > 0) {
            detail(card, "Best").setText(best + " ms");
            detail(card, "Average").setText((sum / measured) + " ms");
            detail(card, "Worst").setText(worst + " ms");
        }
        if (measured + buckets[4] > 0) {
            int largest = 0;
            for (int value : buckets) largest = Math.max(largest, value);
            String[] names = { "< 100 ms", "100–200 ms", "200–400 ms", "> 400 ms", Tr.t("No answer") };
            int[] colors = { Theme.stateColor("connected"), Theme.stateColor("connected"), Theme.color(Theme.ACCENT),
                    0xffe67326, Theme.stateColor("error") };
            LinearLayout bars = new LinearLayout(this);
            bars.setOrientation(LinearLayout.VERTICAL);
            bars.setPadding(0, Ui.dp(6), 0, 0);
            for (int i = 0; i < buckets.length; ++i) bars.addView(barRow(names[i], buckets[i], largest, colors[i]));
            card.addView(bars);
        }
        latency.addView(card, gap());

        // protocols: what the catalog holds, the biggest group first
        Map<String, Integer> kinds = new TreeMap<String, Integer>();
        for (Model.Server server : servers) {
            String kind = server.proto.isEmpty() ? "?" : server.proto.toUpperCase(Locale.US);
            Integer seen = kinds.get(kind);
            kinds.put(kind, seen == null ? 1 : seen + 1);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(kinds.entrySet());
        java.util.Collections.sort(sorted, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        LinearLayout kindCard = card();
        kindCard.addView(Ui.text(this, Tr.t("Protocols"), 14, Theme.color(Theme.INK_MUTED), true));
        int largest = sorted.get(0).getValue();
        for (Map.Entry<String, Integer> entry : sorted)
            kindCard.addView(barRow(entry.getKey(), entry.getValue(), largest, Theme.color(Theme.ACCENT)));
        protocols.addView(kindCard, gap());
    }

    private void fillSubs() {
        subs.removeAllViews();
        List<Model.Sub> list = senko.catalog().subs;
        if (list.isEmpty()) return;
        TextView header = Ui.text(this, Tr.t("Subscriptions"), 14, Theme.color(Theme.INK_MUTED), true);
        header.setPadding(Ui.dp(4), 0, 0, Ui.dp(8));
        subs.addView(header);
        long now = System.currentTimeMillis() / 1000;
        for (Model.Sub sub : list) {
            LinearLayout card = card();
            String title = sub.name.isEmpty() ? Tr.t("Subscription") : sub.name.replace('_', ' ');
            TextView name = Ui.text(this, ServerList.subscriptionTitle(title)[0], 16, Theme.ink(), true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name);
            LinearLayout line = new LinearLayout(this);
            line.setPadding(0, Ui.dp(4), 0, 0);
            long used = sub.upload + sub.download;
            String usage = sub.total != 0 ? Tr.f("%s of %s", Format.bytes(used), Format.bytes(sub.total))
                    : used != 0 ? Format.bytes(used) : "—";
            line.addView(Ui.text(this, usage, 14, Theme.color(Theme.INK_MUTED), false), Ui.weighted(1));
            String until = ServerList.expiry(sub.expire, now, Prefs.language());
            if (until != null) {
                boolean expired = sub.expire <= now;
                line.addView(Ui.text(this, until, 14, expired ? Theme.stateColor("error")
                        : Theme.color(Theme.INK_MUTED), false));
            }
            card.addView(line);
            if (sub.total != 0) {
                float fraction = (float) ((double) used / (double) sub.total);
                // a plan nearly gone turns warm
                int fill = fraction >= 0.9f ? 0xffe67326 : Theme.color(Theme.ACCENT);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(6));
                params.topMargin = Ui.dp(8);
                card.addView(new Meter(this, fraction, fill, true), params);
            }
            subs.addView(card, gap());
        }
    }
}
