package com.senko.droid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * the sixteen colours every screen is painted from, taken from the ios themes.
 * a family has a light and a dark palette, and some only have one of them.
 * pure java apart from the arrays loaded from assets/themes.tsv
 */
final class Theme {
    private Theme() {}

    // slot order of ui_theme.h
    static final int BG = 0;
    static final int BG_BOTTOM = 1;
    static final int FELT = 2;
    static final int CONN_ON = 3;
    static final int CONN_ON_LO = 4;
    static final int IDLE = 5;
    static final int IDLE_LO = 6;
    static final int INK = 7;
    static final int INK_MUTED = 8;
    static final int ACCENT = 9;
    static final int ACCENT_LO = 10;
    static final int CHROME_HI = 11;
    static final int CHROME_LO = 12;
    static final int CELL_HI = 13;
    static final int CELL_LO = 14;
    static final int WELL = 15;
    static final int SLOTS = 16;

    static final String DEFAULT_ID = "senko-ios6";

    static final class Def {
        String id = "";
        String name = "";
        String blurb = "";
        final Set<String> flags = new HashSet<String>();
        float radius = 10;
        String group = "ios";
        String aliases = "";
        int[] light = new int[SLOTS];
        int[] dark = new int[SLOTS];

        boolean flat() {
            return flags.contains("flat");
        }

        boolean glass() {
            return flags.contains("ios26");
        }

        boolean lightOnly() {
            return flags.contains("lightonly") || flags.contains("aero");
        }

        boolean darkOnly() {
            return flags.contains("darkonly");
        }

        boolean allowsDark() {
            return !flags.contains("lightonly");
        }
    }

    interface Listener {
        void onThemeChanged();
    }

    private static final List<Def> DEFS = new ArrayList<Def>();
    /** themes made on the device, kept apart so the table of the app stays as shipped */
    private static final List<Def> CUSTOM = new ArrayList<Def>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static Def current;
    private static boolean light;
    private static int accent6;
    private static int[] palette = new int[SLOTS];

    /** the accent choices of the ios 6 style: orange, red, blue, green, yellow, purple */
    static final int ACCENT_COUNT = 6;

    // connOn, connOnLo, idle, idleLo, accent, accentLo per accent, light then dark
    private static final float[][][] IOS6_LIGHT = {
        { { 1.000f, 0.620f, 0.220f }, { 0.900f, 0.480f, 0.140f }, { 0.980f, 0.780f, 0.580f }, { 0.920f, 0.660f, 0.420f }, { 0.950f, 0.480f, 0.120f }, { 0.820f, 0.380f, 0.080f } },
        { { 0.950f, 0.300f, 0.260f }, { 0.800f, 0.180f, 0.160f }, { 0.980f, 0.680f, 0.650f }, { 0.900f, 0.520f, 0.500f }, { 0.880f, 0.220f, 0.200f }, { 0.720f, 0.140f, 0.120f } },
        { { 0.250f, 0.550f, 0.980f }, { 0.140f, 0.400f, 0.850f }, { 0.660f, 0.800f, 0.980f }, { 0.500f, 0.660f, 0.900f }, { 0.150f, 0.450f, 0.920f }, { 0.080f, 0.330f, 0.780f } },
        { { 0.300f, 0.780f, 0.340f }, { 0.180f, 0.620f, 0.240f }, { 0.700f, 0.900f, 0.700f }, { 0.540f, 0.780f, 0.560f }, { 0.200f, 0.660f, 0.280f }, { 0.120f, 0.520f, 0.200f } },
        { { 0.980f, 0.820f, 0.180f }, { 0.880f, 0.680f, 0.080f }, { 0.990f, 0.920f, 0.620f }, { 0.920f, 0.820f, 0.420f }, { 0.900f, 0.700f, 0.060f }, { 0.760f, 0.560f, 0.040f } },
        { { 0.660f, 0.400f, 0.920f }, { 0.520f, 0.260f, 0.800f }, { 0.800f, 0.700f, 0.940f }, { 0.680f, 0.550f, 0.860f }, { 0.580f, 0.320f, 0.860f }, { 0.460f, 0.220f, 0.720f } },
    };

    private static final float[][][] IOS6_DARK = {
        { { 1.00f, 0.58f, 0.12f }, { 0.82f, 0.36f, 0.04f }, null, null, { 1.00f, 0.55f, 0.10f }, { 0.78f, 0.32f, 0.04f } },
        { { 1.00f, 0.36f, 0.32f }, { 0.80f, 0.16f, 0.14f }, null, null, { 1.00f, 0.34f, 0.30f }, { 0.74f, 0.14f, 0.12f } },
        { { 0.30f, 0.60f, 1.00f }, { 0.10f, 0.36f, 0.80f }, null, null, { 0.28f, 0.58f, 1.00f }, { 0.08f, 0.32f, 0.76f } },
        { { 0.32f, 0.82f, 0.38f }, { 0.14f, 0.58f, 0.22f }, null, null, { 0.30f, 0.80f, 0.36f }, { 0.12f, 0.54f, 0.20f } },
        { { 1.00f, 0.86f, 0.22f }, { 0.80f, 0.62f, 0.06f }, null, null, { 1.00f, 0.84f, 0.20f }, { 0.78f, 0.60f, 0.05f } },
        { { 0.70f, 0.46f, 0.96f }, { 0.50f, 0.26f, 0.78f }, null, null, { 0.68f, 0.44f, 0.95f }, { 0.46f, 0.24f, 0.74f } },
    };

    /** tab separated rows of make_themes.py */
    static synchronized void load(InputStream in) throws IOException {
        DEFS.clear();
        add(in);
        if (DEFS.isEmpty()) throw new IOException("no themes in the table");
    }

    /** more rows after the ios table: the themes of the android app, kept in a table of their own */
    static synchronized void add(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 9) continue;
            Def def = new Def();
            def.id = parts[0];
            def.name = parts[1];
            def.blurb = parts[2];
            if (!parts[3].isEmpty()) def.flags.addAll(Arrays.asList(parts[3].split(",")));
            def.radius = Float.parseFloat(parts[4]);
            def.group = parts[5];
            def.aliases = parts[6];
            def.light = colors(parts[7]);
            def.dark = colors(parts[8]);
            DEFS.add(def);
        }
    }

    private static int[] colors(String text) {
        String[] parts = text.split(",");
        int[] out = new int[SLOTS];
        for (int i = 0; i < SLOTS && i < parts.length; ++i) out[i] = (int) Long.parseLong(parts[i].substring(1), 16);
        return out;
    }

    static synchronized List<Def> all() {
        List<Def> all = new ArrayList<Def>(DEFS);
        all.addAll(CUSTOM);
        return all;
    }

    static synchronized Def find(String id) {
        for (Def def : DEFS) if (def.id.equals(id)) return def;
        for (Def def : CUSTOM) if (def.id.equals(id)) return def;
        return null;
    }

    /** the themes of the user replace the previous set, and the current one is rebuilt from them */
    static synchronized void setCustom(List<Def> defs) {
        CUSTOM.clear();
        CUSTOM.addAll(defs);
        if (current != null && current.group.equals("custom")) {
            Def fresh = find(current.id);
            current = fresh != null ? fresh : find(DEFAULT_ID);
            rebuild();
        }
    }

    /** the ids of the groups in the order the picker shows them, the android themes first, then the ios family */
    static synchronized List<String> groups() {
        List<String> groups = new ArrayList<String>();
        for (Def def : all()) if (!groups.contains(def.group)) groups.add(def.group);
        java.util.Collections.sort(groups, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return rank(a) - rank(b);
            }

            private int rank(String group) {
                return group.equals("android") ? 0 : group.equals("ios") ? 1 : group.equals("custom") ? 2 : 3;
            }
        });
        return groups;
    }

    static String groupTitle(String group) {
        if (group.equals("android")) return "Android";
        if (group.equals("ios")) return "iOS";
        if (group.equals("custom")) return "Custom";
        return group.isEmpty() ? "Custom" : Character.toUpperCase(group.charAt(0)) + group.substring(1);
    }

    static synchronized List<Def> inGroup(String group) {
        List<Def> list = new ArrayList<Def>();
        for (Def def : all()) if (def.group.equals(group)) list.add(def);
        return list;
    }

    static synchronized String currentId() {
        return current().id;
    }

    static synchronized Def current() {
        if (current == null) current = find(DEFAULT_ID);
        if (current == null) current = fallback();
        return current;
    }

    /** a dark palette in code, for the case the themes table could not be read */
    private static Def fallback() {
        Def def = new Def();
        def.id = DEFAULT_ID;
        def.name = "Senko";
        int[] dark = { 0xff141416, 0xff08080a, 0xff1f1f21, 0xff3dc75c, 0xff2a9d44, 0xff6b6b72,
                0xff38383c, 0xffffebd1, 0xffb8a994, 0xffff8c1a, 0xffc7520a, 0xff615c57,
                0xff292624, 0xff474240, 0xff24211f, 0xff0f0f12 };
        def.dark = dark;
        def.light = dark;
        DEFS.add(def);
        return def;
    }

    /** picks a theme by id and the light flag, falling back to the default and to what the family allows */
    static synchronized void apply(String id, boolean wantLight, int accent) {
        Def def = find(id);
        if (def == null) def = find(DEFAULT_ID);
        if (def == null) def = fallback();
        current = def;
        accent6 = accent < 0 || accent >= ACCENT_COUNT ? 0 : accent;
        if (def.darkOnly()) light = false;
        else if (def.lightOnly()) light = true;
        else light = wantLight;
        rebuild();
    }

    private static void rebuild() {
        int[] base = light ? current.light : current.dark;
        palette = base.clone();
        if (current.id.equals("senko-ios6")) {
            float[][][] table = light ? IOS6_LIGHT : IOS6_DARK;
            float[][] a = table[accent6];
            palette[CONN_ON] = rgb(a[0]);
            palette[CONN_ON_LO] = rgb(a[1]);
            if (a[2] != null) {
                palette[IDLE] = rgb(a[2]);
                palette[IDLE_LO] = rgb(a[3]);
            }
            palette[ACCENT] = rgb(a[4]);
            palette[ACCENT_LO] = rgb(a[5]);
        }
    }

    private static int rgb(float[] c) {
        return 0xff000000 | (Math.round(c[0] * 255) << 16) | (Math.round(c[1] * 255) << 8) | Math.round(c[2] * 255);
    }

    /** the choice of the user: stored, applied, and every screen told */
    static void choose(String id, boolean wantLight, int accent) {
        Prefs.setTheme(id);
        Prefs.setLight(wantLight);
        Prefs.setIos6Accent(accent);
        apply(id, wantLight, accent);
        changed();
    }

    /** the swatch the ios 6 accent picker shows */
    static int accentSwatch(int index) {
        return rgb(IOS6_LIGHT[index < 0 || index >= ACCENT_COUNT ? 0 : index][4]);
    }

    static synchronized boolean isLight() {
        return light;
    }

    static int color(int slot) {
        return palette[slot];
    }

    static int bg() {
        return palette[BG];
    }

    static int ink() {
        return palette[INK];
    }

    static int heading() {
        return light ? 0xff111111 : 0xffffffff;
    }

    static int muted() {
        return palette[INK_MUTED];
    }

    static int accent() {
        return palette[ACCENT];
    }

    static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    // a screen that is stopped has left the listener list, so it compares this when it comes back
    private static volatile int version;

    static int version() {
        return version;
    }

    static void changed() {
        version++;
        for (Listener listener : LISTENERS) listener.onThemeChanged();
    }

    /** "Senko-iOS6    Dark", the settings row */
    static String statusLine() {
        Def def = current();
        return def.name + "    " + Tr.t(light ? "Light" : "Dark");
    }

    /** the colour of the power button and the state text: green up, red failed, the accent otherwise */
    static int stateColor(String state) {
        if ("connected".equals(state)) return 0xff3dc75c;
        if ("error".equals(state)) return 0xfff24d42;
        return palette[ACCENT];
    }
}
