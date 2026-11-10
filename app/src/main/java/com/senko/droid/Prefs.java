package com.senko.droid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * what the app itself remembers: look and language, and the view state of the
 * list. everything about servers, subscriptions and settings of the tunnel
 * lives in the engine and is read back from it
 */
final class Prefs {
    private static final String FILE = "senko";
    private static SharedPreferences store;

    private Prefs() {}

    static void init(Context context) {
        store = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int language() {
        return store.getInt("language", Tr.ENGLISH);
    }

    static void setLanguage(int value) {
        store.edit().putInt("language", value).apply();
        Tr.setLanguage(value);
    }

    /** the family id of the theme, the ios 6 look by default */
    static String theme() {
        return store.getString("theme", "senko-ios6");
    }

    static void setTheme(String id) {
        store.edit().putString("theme", id).apply();
    }

    static boolean light() {
        return store.getBoolean("light", false);
    }

    static void setLight(boolean value) {
        store.edit().putBoolean("light", value).apply();
    }

    static int ios6Accent() {
        return store.getInt("ios6.accent", 0);
    }

    static void setIos6Accent(int index) {
        store.edit().putInt("ios6.accent", index).apply();
    }

    static final int BACKEND_SERVER = 0;
    static final int BACKEND_AWG = 1;

    /** which backend the power button starts: a server of the catalog or the amneziawg profile */
    static int selectedBackend() {
        return store.getInt("backend", BACKEND_SERVER);
    }

    static void setSelectedBackend(int backend) {
        store.edit().putInt("backend", backend).apply();
    }

    /** the identity of the picked server, so the choice survives a restart and a reordered list */
    static String pickedIdentity() {
        return store.getString("picked.identity", "");
    }

    static void setPickedIdentity(String identity) {
        store.edit().putString("picked.identity", identity).apply();
    }

    /** connect to the fastest server of the catalog instead of the picked one */
    static boolean autoServer() {
        return store.getBoolean("auto.server", false);
    }

    static void setAutoServer(boolean value) {
        store.edit().putBoolean("auto.server", value).apply();
    }

    static boolean quickConnect() {
        return store.getBoolean("quick.connect", false);
    }

    static void setQuickConnect(boolean value) {
        store.edit().putBoolean("quick.connect", value).apply();
    }

    static boolean snow() {
        return store.getBoolean("snow", false);
    }

    static void setSnow(boolean value) {
        store.edit().putBoolean("snow", value).apply();
    }

    /** 0 as the engine ordered them, 1 by ping, 2 by name */
    static int sortMode() {
        return store.getInt("sort", 0);
    }

    static void setSortMode(int mode) {
        store.edit().putInt("sort", mode).apply();
    }

    private static void commit(String key, Set<String> values) {
        SharedPreferences.Editor editor = store.edit();
        Compat.putStringSet(editor, key, values);
        editor.apply();
    }

    static Set<String> collapsed() {
        return Compat.stringSet(store, "collapsed");
    }

    static void setCollapsed(Set<String> keys) {
        commit("collapsed", keys);
    }

    /** packages whose traffic skips the tunnel. the vpn builder takes them from android 5.0 */
    static Set<String> appDirect() {
        return Compat.stringSet(store, "app_direct");
    }

    static void setAppDirect(Set<String> packages) {
        commit("app_direct", packages);
    }

    /** the last ping of servers by identity, "identity=ms" lines, so a restart keeps the numbers */
    static String pings() {
        return store.getString("pings", "");
    }

    static void setPings(String text) {
        store.edit().putString("pings", text).apply();
    }

    static String string(String key, String fallback) {
        return store.getString(key, fallback);
    }

    static void putString(String key, String value) {
        store.edit().putString(key, value).apply();
    }

    static boolean flag(String key, boolean fallback) {
        return store.getBoolean(key, fallback);
    }

    static void putFlag(String key, boolean value) {
        store.edit().putBoolean(key, value).apply();
    }

    static int integer(String key, int fallback) {
        return store.getInt(key, fallback);
    }

    static void putInt(String key, int value) {
        store.edit().putInt(key, value).apply();
    }
}
