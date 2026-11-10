package com.senko.droid;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** what this build is, the people behind it, and the quiet switch for the developer tools */
public final class AboutActivity extends BaseActivity {
    private static final int TAPS_TO_UNLOCK = 5;
    private static final String THANKS = "@CookieValerka, @inraxx, @not_a_modder, @s3dativee, @Lineysom, "
            + "@shizotoaster, @fly1ngangel, @anazerka, @fr0n1k, @vladosik228, @kfrom7, @ningmeng, @Vladus324";
    private static final String SPONSORS = "@s3dativee, @shizotoaster, @not_a_modder";
    private static final String TESTERS = "@inraxx, @s3dativee, @rafal_official, @QuaIcomm, @belo4kaFLUNI, "
            + "@Wolfer_QUIC, @koshdevv, @Lineysom, @Lime_iOS6, @ogeprint, @ra1n_developer";

    private int taps;

    static String versionName(android.content.Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static String abi() {
        if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS.length > 0) return Build.SUPPORTED_ABIS[0];
        return Build.CPU_ABI;
    }

    /** the name the owner sees in Settings, "Galaxy S25 Ultra" and not the build code SM-S938B */
    static String deviceName(android.content.Context context) {
        String name = null;
        try {
            if (Build.VERSION.SDK_INT >= 17)
                name = android.provider.Settings.Global.getString(context.getContentResolver(), "device_name");
        } catch (RuntimeException e) {
            // an oem that locks the setting down leaves only the build fields below
        }
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (model.toLowerCase(java.util.Locale.US).startsWith(maker.toLowerCase(java.util.Locale.US))) return model;
        return maker.isEmpty() ? model : Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
    }

    static String deviceLine() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API "
                + Build.VERSION.SDK_INT + ") · " + abi();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setScreenTitle("About");
        Rows rows = new Rows(this);
        setBody(rows.view());
        // the sprites of the boykisser theme fall behind the list here, as on the home screen. they
        // sit in the body, not in the capped column, so they cross a wide screen
        if (Theme.current().flags.contains("boykisser"))
            body().addView(new FallField(this, "boykisser"), 0, new android.widget.FrameLayout.LayoutParams(-1, -1));
        rows.root().addView(hero());

        rows.section("Device");
        rows.detail("info", "Model", deviceName(this), null);
        rows.detail("gear", "Android version", Build.VERSION.RELEASE, null);
        rows.detail("hash", "Architecture", abi(), null);
        rows.detail("shield", "TLS mode", Tr.t("Built-in TLS · bundled trust roots"), null);

        rows.section("Senko");
        rows.detail("globe", "How it works", Tr.t("Apps and system traffic use the selected profile through a "
                + "VPN service. No root is needed."), null);
        rows.detail("split", "Transports", "TCP · TLS · REALITY + Vision\nWebSocket · XHTTP · gRPC\n"
                + "AmneziaWG · SOCKS5 · HTTP(S) CONNECT", null);
        rows.detail("repeat", "Compatibility", "Android 4.1+ · armeabi-v7a + arm64-v8a + x86", null);
        rows.detail("shield", "Security", Tr.t("Token-authenticated control socket · subscription SSRF "
                + "protection · secret redaction · real transport checks."), null);

        rows.section("Links");
        rows.detail("code", "GitHub", "github.com/sqmrak/Senko", link("https://github.com/sqmrak/Senko"));
        rows.detail("globe", "Telegram", "t.me/sqmrakdev", link("https://t.me/sqmrakdev"));

        rows.section("Credits");
        rows.detail(null, "Developer", "sqmrak", null);
        rows.detail("bolt", "Special thanks", THANKS, null);
        rows.detail("rocket", "Sponsors", SPONSORS, null);
        rows.detail("search", "Testers", TESTERS, null);
        rows.detail("palette", "Emoji artwork",
                Tr.t("Twemoji by Twitter, Inc. and contributors (CC BY 4.0)"), null);
    }

    private View.OnClickListener link(final String url) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (android.content.ActivityNotFoundException e) {
                    toast("No app can open this link");
                }
            }
        };
    }

    private View hero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, Ui.dp(16), 0, Ui.dp(8));
        ImageView icon = new ImageView(this);
        // the corner radius of an ios app icon, so the art matches the launcher icon
        icon.setImageDrawable(Icons.rounded(this, R.drawable.senko_icon, 0.2237f));
        hero.addView(icon, new LinearLayout.LayoutParams(Ui.dp(88), Ui.dp(88)));
        TextView name = Ui.text(this, "Senko", 30, Theme.ink(), true);
        name.setPadding(0, Ui.dp(8), 0, 0);
        hero.addView(name);
        hero.addView(Ui.text(this, Tr.t("Amnezia/VLESS Client"), 14, Theme.color(Theme.INK_MUTED), false));
        LinearLayout tiles = new LinearLayout(this);
        tiles.setPadding(0, Ui.dp(14), 0, 0);
        final String version = versionName(this);
        View versionTile = tile("Version", version);
        versionTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                versionTapped();
            }
        });
        tiles.addView(versionTile, tileParams(true));
        tiles.addView(tile("Android", Build.VERSION.RELEASE + " · " + abi()), tileParams(false));
        hero.addView(tiles, new LinearLayout.LayoutParams(-1, -2));
        return hero;
    }

    private LinearLayout.LayoutParams tileParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (first) params.rightMargin = Ui.dp(5);
        else params.leftMargin = Ui.dp(5);
        return params;
    }

    private View tile(String caption, String value) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        Ui.background(tile, Ui.plate());
        tile.addView(Ui.text(this, Tr.t(caption), 12, Theme.color(Theme.INK_MUTED), false));
        TextView text = Ui.text(this, value, 17, Theme.ink(), true);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tile.addView(text);
        return tile;
    }

    /** five taps on the version unlock the developer tools; the section heading hides them again */
    private void versionTapped() {
        if (Prefs.flag("dev_menu", false)) return;
        taps++;
        int left = TAPS_TO_UNLOCK - taps;
        if (left > 0) {
            // silence until the third tap, so an accidental double tap says nothing
            if (left <= 2) Toast.makeText(this, Tr.f("%d more", left), Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.putFlag("dev_menu", true);
        Dialogs.message(this, Tr.t("Developer settings"),
                Tr.t("Developer settings are now visible in Settings. Tap the section heading five times to hide them again."));
    }
}
