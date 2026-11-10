package com.senko.droid;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

/**
 * the home screen: the power button, what the tunnel is doing, which server it
 * dials and the settings and statistics entries. everything else opens from
 * the server card
 */
public final class HomeActivity extends Activity implements Senko.Listener, Theme.Listener {
    private static final int REQUEST_VPN = 1;

    private Senko senko;
    private final Handler ticker = new Handler();
    private OrbitButton power;
    private int themeVersion;
    private TextView stateView;
    private TextView detailView;
    private FrameLayout iconHolder;
    private TextView cardTitle;
    private TextView cardSubtitle;
    private View protocolDot;
    private TextView protocolLine;
    private String shownError = "";
    private FrameLayout root;
    private FallField field;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            showDetail();
            if ("connected".equals(senko.state())) ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        senko = Senko.get();
        themeVersion = Theme.version();
        build();
    }

    private View plateButton(Drawable icon, View.OnClickListener click) {
        FrameLayout holder = new FrameLayout(this);
        Ui.background(holder, Theme.current().flags.contains("expressive")
                ? Shapes.pressableCookie() : Ui.pressablePlate(Theme.current().radius, 0));
        ImageView image = new ImageView(this);
        image.setImageDrawable(icon);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        holder.addView(image, new FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        holder.setOnClickListener(click);
        Ui.pressEffect(holder);
        return holder;
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        Ui.background(root, Ui.page());
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(Theme.color(Theme.BG));

        HomeLayout column = new HomeLayout(this);

        HomeHeader header = new HomeHeader(this);
        header.setMinimumHeight(Ui.dp(64));
        View gear = plateButton(Icons.glyph(this, "gear", Theme.ink()), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });
        gear.setContentDescription(Tr.t("Settings"));
        header.addView(gear, new FrameLayout.LayoutParams(Ui.dp(48), Ui.dp(48), Gravity.LEFT | Gravity.TOP));
        View stats = plateButton(Icons.glyph(this, "chart", Theme.ink()), new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(HomeActivity.this, StatsActivity.class));
            }
        });
        stats.setContentDescription(Tr.t("Statistics"));
        header.addView(stats, new FrameLayout.LayoutParams(Ui.dp(48), Ui.dp(48), Gravity.RIGHT | Gravity.TOP));
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView word = Theme.current().flags.contains("shimmer")
                ? ShimmerText.create(this, "senko-droid", Wide.tablet() ? 46 : 34)
                : Ui.text(this, "senko-droid", Wide.tablet() ? 46 : 34, Theme.heading(), true);
        word.setGravity(Gravity.CENTER);
        word.setSingleLine(true);
        word.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.word = word;
        title.addView(word);
        TextView tag = Ui.caption(this, "by sqmrak :3");
        tag.setGravity(Gravity.CENTER);
        title.addView(tag);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        titleParams.leftMargin = Ui.dp(56);
        titleParams.rightMargin = Ui.dp(56);
        header.addView(title, titleParams);
        column.header = header;
        column.addView(header);

        FrameLayout orbitHolder = new FrameLayout(this);
        power = new OrbitButton(this);
        power.setContentDescription(Tr.t("Connect or disconnect"));
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });
        orbitHolder.addView(power, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        column.orbit = orbitHolder;
        column.addView(orbitHolder);

        // the state, the detail line, the server card and the protocol line stay together, so the
        // wide layout can put them beside the button
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        column.info = info;
        column.addView(info);

        stateView = Ui.text(this, "", Wide.tablet() ? 26 : 22, Theme.ink(), true);
        stateView.setGravity(Gravity.CENTER);
        info.addView(stateView, Ui.matchWrap());
        detailView = Ui.text(this, "", 15, Theme.muted(), false);
        detailView.setGravity(Gravity.CENTER);
        detailView.setMinLines(1);
        LinearLayout.LayoutParams detailParams = Ui.matchWrap();
        detailParams.topMargin = Ui.dp(6);
        detailParams.bottomMargin = Ui.dp(12);
        info.addView(detailView, detailParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
        Ui.background(card, Ui.pressablePlate(Theme.current().radius, 0));
        iconHolder = new FrameLayout(this);
        card.addView(iconHolder, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(12), 0, Ui.dp(8), 0);
        cardTitle = Ui.text(this, "", 17, Theme.ink(), true);
        cardTitle.setSingleLine(true);
        cardTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cardSubtitle = Ui.text(this, "", 13, Theme.muted(), false);
        cardSubtitle.setSingleLine(true);
        cardSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(cardTitle);
        texts.addView(cardSubtitle);
        card.addView(texts, Ui.weighted(1));
        View chevron = new View(this);
        Ui.background(chevron, Icons.shape(this, Icons.Shape.CHEVRON, Theme.muted()));
        card.addView(chevron, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(HomeActivity.this, ServerListActivity.class));
            }
        });
        info.addView(card, Ui.matchWrap());

        LinearLayout protocol = new LinearLayout(this);
        protocol.setGravity(Gravity.CENTER_VERTICAL);
        protocol.setPadding(Ui.dp(8), Ui.dp(10), 0, 0);
        protocolDot = new View(this);
        Ui.background(protocolDot, Ui.solid(Theme.color(Theme.ACCENT), 3));
        protocol.addView(protocolDot, new LinearLayout.LayoutParams(Ui.dp(6), Ui.dp(6)));
        protocolLine = Ui.text(this, "", 13, Theme.muted(), false);
        protocolLine.setPadding(Ui.dp(8), 0, 0, 0);
        protocol.addView(protocolLine);
        info.addView(protocol, Ui.matchWrap());

        if (Wide.dpad(this)) {
            // a remote starts on the power button, the one control the screen is about
            power.setFocusable(true);
            power.requestFocus();
            column.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    Wide.reachable(HomeActivity.this.root);
                }
            });
        }
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        this.root = root;
        setContentView(root);
    }

    /** snow or the theme's sprites sit under the controls and take no touches */
    private void syncField() {
        if (field != null) root.removeView(field);
        field = FallField.forCurrent(this);
        if (field != null) root.addView(field, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    protected void onStart() {
        super.onStart();
        senko.screenStarted();
        senko.addListener(this);
        Theme.addListener(this);
        senko.loadHwid();
        syncField();
        render();
        // the theme changed on a screen above this one, whose views still hold the old colours
        if (themeVersion != Theme.version()) {
            // posted: recreate() inside onStart is queued against a screen still starting, and a back
            // press that arrives meanwhile goes to the instance that is about to die
            getWindow().getDecorView().post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && themeVersion != Theme.version()) Compat.recreate(HomeActivity.this);
                }
            });
        }
    }

    @Override
    protected void onStop() {
        ticker.removeCallbacks(tick);
        Theme.removeListener(this);
        senko.removeListener(this);
        senko.screenStopped();
        super.onStop();
    }

    @Override
    public void onThemeChanged() {
        Compat.recreate(this);
    }

    @Override
    public void onChange(int what) {
        render();
        if ((what & Senko.ERROR) != 0) showError();
    }

    private String headline() {
        String state = senko.state();
        if (senko.disconnecting()) return Tr.t("Disconnecting");
        if ("connecting".equals(state)) return Tr.t("Connecting");
        if ("connected".equals(state)) return Tr.t("Connected");
        if (!senko.engineUp()) return Tr.t("Starting…");
        return Tr.t("Disconnected");
    }

    private void showDetail() {
        String state = senko.state();
        String text;
        if (senko.disconnecting()) {
            text = "";
        } else if ("connected".equals(state)) {
            long age = senko.uptimeSeconds();
            text = age < 0 ? "" : Format.uptime(age);
        } else if ("connecting".equals(state)) {
            text = "";
        } else {
            text = Tr.t("Tap to connect");
        }
        detailView.setText(text);
    }

    /** a new headline fades in, so a state change reads as a change and not as a jump */
    private void fadeText(TextView view, String next) {
        if (view.getText().toString().equals(next)) return;
        boolean visible = view.getWindowToken() != null && view.getText().length() > 0;
        view.setText(next);
        if (!visible) return;
        ViewFx.fadeIn(view, 220);
    }

    private void render() {
        String state = senko.state();
        power.setStateKey(state);
        power.setRunning(!senko.busy() || senko.disconnecting() || "connecting".equals(state) || "connected".equals(state));
        fadeText(stateView, headline());
        showDetail();
        ticker.removeCallbacks(tick);
        if ("connected".equals(state)) ticker.postDelayed(tick, 1000);
        showCard();
    }

    private void setIcon(Drawable icon, boolean flag) {
        iconHolder.removeAllViews();
        if (icon == null) return;
        ImageView image = new ImageView(this);
        image.setImageDrawable(icon);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (!flag) {
            Ui.background(iconHolder, Ui.solid(Ui.alpha(Theme.color(Theme.ACCENT), 0.18f), 22));
            iconHolder.addView(image, new FrameLayout.LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.CENTER));
        } else {
            Ui.background(iconHolder, null);
            iconHolder.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** the card names what the power button will dial: the fastest server, or one server and where it came from */
    private void showCard() {
        Model.Catalog catalog = senko.catalog();
        int accent = Theme.color(Theme.ACCENT);
        if (senko.awgSelected()) {
            cardTitle.setText("AmneziaWG");
            cardSubtitle.setText(Tr.t("AmneziaWG profile"));
            protocolLine.setText("AMNEZIAWG  ·  UDP");
            protocolDot.setVisibility(View.VISIBLE);
            setIcon(Icons.glyph(this, "shield", accent), false);
            return;
        }
        if (catalog.servers.isEmpty()) {
            cardTitle.setText(Tr.t("No servers"));
            cardSubtitle.setText(Tr.t("Add a subscription or a server"));
            setIcon(Icons.glyph(this, "cloud", accent), false);
            protocolLine.setText("");
            protocolDot.setVisibility(View.INVISIBLE);
            return;
        }
        Model.Server picked = senko.pickedServer();
        protocolDot.setVisibility(View.VISIBLE);
        if (Prefs.autoServer()) {
            boolean live = senko.isActive() && picked != null;
            protocolLine.setText(live ? Names.protocolLabel(picked).replace("/", "  ·  ").toUpperCase(java.util.Locale.US)
                    : Tr.f("Available servers: %d", catalog.servers.size()));
            cardTitle.setText(Tr.t("Auto"));
            cardSubtitle.setText(live ? Names.title(picked) : Tr.t("Fastest server"));
            setIcon(Icons.glyph(this, "bolt", accent), false);
            return;
        }
        if (picked == null) {
            cardTitle.setText(Tr.t("Choose a server"));
            cardSubtitle.setText(Tr.t("Tap to open the list"));
            setIcon(Icons.glyph(this, "globe", accent), false);
            protocolLine.setText("");
            protocolDot.setVisibility(View.INVISIBLE);
            return;
        }
        StringBuilder subtitle = new StringBuilder(sourceName(picked));
        int ms = senko.ping(picked);
        if (ms >= 0) subtitle.append(" · ").append(Tr.f("%d ms", ms));
        cardTitle.setText(Names.title(picked));
        cardSubtitle.setText(subtitle);
        protocolLine.setText(Names.protocolLabel(picked).replace("/", "  ·  ").toUpperCase(java.util.Locale.US));
        Drawable flag = Icons.flag(this, Names.flagCode(picked.remark));
        setIcon(flag != null ? flag : Icons.placeholder(this), true);
    }

    private String sourceName(Model.Server server) {
        Model.Sub sub = server.group >= 0 ? senko.catalog().sub(server.group) : null;
        if (sub != null && !sub.name.isEmpty()) return sub.name.replace('_', ' ');
        return Tr.t("Manual");
    }

    private void showError() {
        String error = senko.lastError();
        if (error.isEmpty()) {
            shownError = "";
            return;
        }
        String text = Errors.human(error);
        if (text.equals(shownError)) return;
        shownError = text;
        new SenkoDialog.Builder(this)
                .setMood(SenkoDialog.MOOD_ERROR)
                .setTitle(Tr.t("Connection failed"))
                .setMessage(text)
                .setPositiveButton(Tr.t("OK"), null)
                .show();
    }

    private void toggle() {
        // taps while a stop is in flight would queue a second DISCONNECT
        if (senko.disconnecting()) return;
        if (senko.isActive()) {
            senko.disconnect(null);
            return;
        }
        if (!senko.engineUp()) {
            senko.startEngine();
            return;
        }
        if (RootHelper.needed()) {
            startRootHelper();
            return;
        }
        Intent consent = VpnConsent.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN);
            return;
        }
        senko.connect(null);
    }

    /** android before 4.0 has no VpnService: root access takes its place, and su asks the user now */
    private void startRootHelper() {
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = null;
                try {
                    RootHelper.start(app);
                } catch (java.io.IOException e) {
                    failure = e.getMessage();
                }
                final String reason = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (reason == null) {
                            senko.connect(null);
                            return;
                        }
                        new SenkoDialog.Builder(HomeActivity.this)
                                .setMood(SenkoDialog.MOOD_ERROR)
                                .setTitle(Tr.t("Root access"))
                                .setMessage(Tr.t(reason))
                                .setPositiveButton(Tr.t("OK"), null).show();
                    }
                });
            }
        }, "senko-root-start").start();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_VPN) {
            super.onActivityResult(request, result, data);
            return;
        }
        if (result == RESULT_OK) senko.connect(null);
        else new SenkoDialog.Builder(this).setMood(SenkoDialog.MOOD_ASK).setMessage(Tr.t("Senko needs the VPN permission to route traffic"))
                .setPositiveButton(Tr.t("OK"), null).show();
    }

    private static final class HomeHeader extends FrameLayout {
        TextView word;

        HomeHeader(android.content.Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (word != null) {
                float max = (Wide.tablet() ? 46 : 34) * getResources().getDisplayMetrics().scaledDensity;
                float available = Math.max(0, MeasureSpec.getSize(widthSpec) - Ui.dp(112));
                float current = word.getTextSize();
                float textWidth = word.getPaint().measureText(word.getText().toString());
                float target = textWidth > 0 ? Math.min(max, current * available / textWidth) : max;
                if (Math.abs(current - target) > 0.5f)
                    word.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, target);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    /** the home screen stacks its controls in portrait and puts them side by side in landscape */
    private static final class HomeLayout extends ViewGroup {
        View header;
        View orbit;
        View info;
        private boolean sideBySide;

        HomeLayout(android.content.Context context) {
            super(context);
        }

        private static int margin(int width) {
            return Ui.dp(Wide.tablet() ? 28 : (width <= Ui.dp(320) ? 14 : 18));
        }

        private static int top() {
            return Ui.dp(10);
        }

        private static int bottom() {
            return Ui.dp(Wide.tablet() ? 28 : 16);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            int inner = width - 2 * margin(width);
            header.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int availH = height - top() - header.getMeasuredHeight() - bottom();
            sideBySide = width > height && height < Ui.dp(560);
            int infoW;
            int side;
            if (sideBySide) {
                int leftW = (int) (inner * 0.44f);
                infoW = Math.min(Ui.dp(Wide.tablet() ? 400 : 340), inner - leftW - Ui.dp(14));
                side = Math.min(availH, leftW);
            } else {
                infoW = Math.min(Ui.dp(Wide.tablet() ? 480 : 440), inner);
                side = (int) (infoW * (Wide.tablet() ? 0.7f : 0.82f));
            }
            info.measure(MeasureSpec.makeMeasureSpec(infoW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (!sideBySide) side = Math.min(side, availH - info.getMeasuredHeight() - Ui.dp(8));
            side = Math.max(0, Math.min(side, Ui.dp(Wide.tablet() ? 380 : 320)));
            orbit.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int height = b - t;
            int margin = margin(width);
            header.layout(margin, top(), margin + header.getMeasuredWidth(), top() + header.getMeasuredHeight());
            int below = top() + header.getMeasuredHeight();
            int availH = height - below - bottom();
            int side = orbit.getMeasuredWidth();
            if (sideBySide) {
                int leftW = (int) ((width - 2 * margin) * 0.44f);
                int ox = margin + (leftW - side) / 2;
                int oy = below + (availH - side) / 2;
                orbit.layout(ox, oy, ox + side, oy + side);
                int rightStart = margin + leftW + Ui.dp(14);
                int rightWidth = width - margin - rightStart;
                int rx = rightStart + (rightWidth - info.getMeasuredWidth()) / 2;
                int iy = below + Math.max(0, (availH - info.getMeasuredHeight()) / 2);
                info.layout(rx, iy, rx + info.getMeasuredWidth(), iy + info.getMeasuredHeight());
                return;
            }
            int infoX = (width - info.getMeasuredWidth()) / 2;
            int infoY = height - bottom() - info.getMeasuredHeight();
            info.layout(infoX, infoY, infoX + info.getMeasuredWidth(), infoY + info.getMeasuredHeight());
            int room = infoY - Ui.dp(8) - below;
            int ox = (width - side) / 2;
            int oy = below + Math.max(0, (room - side) / 2);
            orbit.layout(ox, oy, ox + side, oy + side);
        }
    }
}
