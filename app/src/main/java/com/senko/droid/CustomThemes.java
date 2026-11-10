package com.senko.droid;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * themes the user made: sixteen colours for light and optionally dark, a style
 * and a corner radius. they live in the preferences as json and are registered
 * into the theme table, so the picker and the apply path need no special case.
 * the slot keys are the stored schema, so they are never renamed
 */
final class CustomThemes {
    private CustomThemes() {}

    static final int MAX = 64;
    static final String FORMAT = "senko-theme-v1";
    static final String PREF = "custom_themes";
    static final String PREFIX = "custom-";

    static final String[] KEYS = { "bg", "bgBot", "felt", "connOn", "connOnLo", "idle", "idleLo", "ink",
            "inkMuted", "accent", "accentLo", "chromeHi", "chromeLo", "cellHi", "cellLo", "well" };
    static final String[] TITLES = { "Background", "Background low", "Felt", "Online", "Online low", "Offline",
            "Offline low", "Text", "Text muted", "Accent", "Accent low", "Chrome", "Chrome low", "Cell",
            "Cell low", "Well" };
    static final String[] HINTS = { "wallpaper top", "wallpaper bottom", "list backdrop", "connected button top",
            "connected button bottom", "disconnected button top", "disconnected button bottom", "primary label",
            "secondary label", "links and glyphs", "pressed accent", "bars top", "bars bottom", "row top",
            "row bottom", "list inset tint" };

    static final int STYLE_CLASSIC = 0;
    static final int STYLE_FLAT = 1;
    static final int STYLE_GLASS = 2;

    /** a theme being edited, in the stored schema */
    static final class Draft {
        String id = "";
        String name = "";
        int style = STYLE_FLAT;
        float radius = 12;
        int[] light = new int[Theme.SLOTS];
        /** null for a theme with a light palette only */
        int[] dark;
    }


    static String hex(int color) {
        return String.format(Locale.US, "#%06X", color & 0xffffff);
    }

    /** "#RRGGBB" or "RRGGBB", null for anything else */
    static Integer parseHex(String text) {
        if (text == null) return null;
        String body = text.trim();
        if (body.startsWith("#")) body = body.substring(1);
        if (body.length() != 6) return null;
        for (int i = 0; i < 6; ++i) if (Character.digit(body.charAt(i), 16) < 0) return null;
        return 0xff000000 | Integer.parseInt(body, 16);
    }


    private static JSONObject palette(int[] colors) throws JSONException {
        JSONObject out = new JSONObject();
        for (int i = 0; i < Theme.SLOTS; ++i) out.put(KEYS[i], hex(colors[i]));
        return out;
    }

    /** every slot has to parse before the theme may enter the table */
    private static int[] readPalette(JSONObject raw) {
        if (raw == null) return null;
        int[] colors = new int[Theme.SLOTS];
        for (int i = 0; i < Theme.SLOTS; ++i) {
            Integer color = parseHex(raw.optString(KEYS[i], null));
            if (color == null) return null;
            colors[i] = color;
        }
        return colors;
    }

    static JSONObject toJson(Draft draft) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", draft.id);
        out.put("name", draft.name);
        out.put("style", draft.style);
        out.put("radius", draft.radius);
        out.put("light", palette(draft.light));
        if (draft.dark != null) out.put("dark", palette(draft.dark));
        return out;
    }

    /** imported and stored data are both untrusted: known keys only, bounded values, null when unusable */
    static Draft fromJson(JSONObject raw) {
        if (raw == null) return null;
        Draft draft = new Draft();
        String name = raw.optString("name", "").trim();
        if (name.isEmpty()) return null;
        draft.name = name.length() > 40 ? name.substring(0, 40) : name;
        draft.light = readPalette(raw.optJSONObject("light"));
        if (draft.light == null) return null;
        JSONObject dark = raw.optJSONObject("dark");
        if (dark != null) {
            draft.dark = readPalette(dark);
            if (draft.dark == null) return null;
        }
        int style = raw.optInt("style", STYLE_FLAT);
        draft.style = style < 0 || style > 2 ? STYLE_FLAT : style;
        double radius = raw.optDouble("radius", 12);
        draft.radius = (float) Math.max(0, Math.min(32, Double.isNaN(radius) ? 12 : radius));
        String id = raw.optString("id", "");
        if (id.startsWith(PREFIX) && id.length() <= 48) draft.id = id;
        return draft;
    }


    static List<Draft> load() {
        List<Draft> list = new ArrayList<Draft>();
        String text = Prefs.string(PREF, "");
        if (text.isEmpty()) return list;
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length() && list.size() < MAX; ++i) {
                Draft draft = fromJson(array.optJSONObject(i));
                // a stored theme keeps its id, one without is not ours
                if (draft != null && !draft.id.isEmpty()) list.add(draft);
            }
        } catch (JSONException e) {
            return new ArrayList<Draft>();
        }
        return list;
    }

    private static void store(List<Draft> list) {
        JSONArray array = new JSONArray();
        try {
            for (Draft draft : list) array.put(toJson(draft));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Prefs.putString(PREF, array.toString());
        register();
    }

    static Draft find(String id) {
        for (Draft draft : load()) if (draft.id.equals(id)) return draft;
        return null;
    }

    static boolean isCustom(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** the stored id, or null when the limit is reached */
    static String save(Draft draft) {
        List<Draft> list = load();
        int at = -1;
        for (int i = 0; i < list.size(); ++i) if (list.get(i).id.equals(draft.id)) at = i;
        if (at < 0 && list.size() >= MAX) return null;
        if (draft.id.isEmpty()) draft.id = freshId(list);
        if (at >= 0) list.set(at, draft);
        else list.add(draft);
        store(list);
        return draft.id;
    }

    private static String freshId(List<Draft> list) {
        long base = System.currentTimeMillis();
        for (;;) {
            String candidate = PREFIX + Long.toHexString(base++);
            boolean taken = false;
            for (Draft draft : list) if (draft.id.equals(candidate)) taken = true;
            if (!taken) return candidate;
        }
    }

    static boolean delete(String id) {
        List<Draft> list = load();
        for (int i = 0; i < list.size(); ++i) {
            if (!list.get(i).id.equals(id)) continue;
            list.remove(i);
            store(list);
            return true;
        }
        return false;
    }

    /** a copy of any theme, builtin or custom, to start an edit from */
    static Draft draftFrom(String sourceId) {
        Draft stored = find(sourceId);
        if (stored != null) {
            Draft copy = new Draft();
            copy.name = stored.name;
            copy.style = stored.style;
            copy.radius = stored.radius;
            copy.light = stored.light.clone();
            copy.dark = stored.dark == null ? null : stored.dark.clone();
            return copy;
        }
        Theme.Def def = Theme.find(sourceId);
        if (def == null) def = Theme.current();
        Draft draft = new Draft();
        draft.name = def.name + " copy";
        draft.style = def.glass() ? STYLE_GLASS : def.flat() ? STYLE_FLAT : STYLE_CLASSIC;
        draft.radius = def.radius;
        draft.light = def.light.clone();
        draft.dark = def.lightOnly() ? null : def.dark.clone();
        return draft;
    }


    static Theme.Def toDef(Draft draft) {
        Theme.Def def = new Theme.Def();
        def.id = draft.id;
        def.name = draft.name;
        def.blurb = "made on this device";
        def.group = "custom";
        def.radius = draft.radius;
        if (draft.style >= STYLE_FLAT) def.flags.add("flat");
        if (draft.style == STYLE_GLASS) {
            def.flags.add("ios16");
            def.flags.add("ios26");
        }
        def.light = draft.light.clone();
        if (draft.dark == null) {
            def.flags.add("lightonly");
            def.dark = draft.light.clone();
        } else {
            def.dark = draft.dark.clone();
        }
        return def;
    }

    /** puts the stored themes in the table, called at startup and after every change */
    static void register() {
        List<Theme.Def> defs = new ArrayList<Theme.Def>();
        for (Draft draft : load()) defs.add(toDef(draft));
        Theme.setCustom(defs);
    }


    static String export(String id) {
        Draft draft = find(id);
        if (draft == null) return null;
        try {
            JSONObject out = toJson(draft);
            out.put("format", FORMAT);
            return out.toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** the id of the new theme. an imported theme never replaces a local one with the same id */
    static String importText(String text) throws IOException {
        if (text == null || text.length() > 65536) throw new IOException("Not a Senko theme file");
        JSONObject raw;
        try {
            raw = new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("Not a Senko theme file");
        }
        if (!FORMAT.equals(raw.optString("format"))) throw new IOException("Unsupported theme format");
        Draft draft = fromJson(raw);
        if (draft == null) throw new IOException("Theme file is incomplete");
        draft.id = "";
        String id = save(draft);
        if (id == null) throw new IOException("Custom theme limit reached");
        return id;
    }
}
