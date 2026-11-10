package com.senko.droid;

/** tracks the active physical network, ignoring duplicate broadcasts */
final class NetworkChanges {
    private boolean known;
    private boolean online;
    private int type = -1;
    private String name = "";

    boolean update(int nextType, String nextName, boolean connected) {
        if (!connected) {
            if (known && type == nextType) online = false;
            return false;
        }
        String value = nextName == null ? "" : nextName;
        boolean changed = known && (!online || type != nextType || !name.equals(value));
        known = true;
        online = true;
        type = nextType;
        name = value;
        return changed;
    }
}
