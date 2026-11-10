package com.senko.droid;

import java.util.Locale;

/** text for the screens, pure java so a jvm test covers it */
final class Format {
    private Format() {}

    private static String number(double amount, boolean wholeBelowTen, int lang) {
        String text = String.format(Locale.US, (amount >= 10.0 || wholeBelowTen) ? "%.0f" : "%.1f", amount);
        return lang == Tr.RUSSIAN ? text.replace('.', ',') : text;
    }

    /** 1536 is "1.5 KB". fractions only below ten of a unit, the russian side writes a comma */
    static String bytes(long count, int lang) {
        String[] units = lang == Tr.RUSSIAN
                ? new String[] { "Б", "КБ", "МБ", "ГБ", "ТБ" }
                : new String[] { "B", "KB", "MB", "GB", "TB" };
        double amount = Math.max(count, 0);
        int unit = 0;
        while (amount >= 1024.0 && unit + 1 < units.length) {
            amount /= 1024.0;
            unit++;
        }
        return number(amount, unit == 0, lang) + " " + units[unit];
    }

    static String bytes(long count) {
        return bytes(count, Tr.language());
    }

    /**
     * link speeds are quoted in bits, the way a carrier writes its plan. the unit
     * follows the amount, so a quiet keepalive does not round down to zero
     */
    static String rate(double bytesPerSecond, int lang) {
        String[] units = { "bit/s", "Kbit/s", "Mbit/s", "Gbit/s" };
        double amount = bytesPerSecond > 0.0 ? bytesPerSecond * 8.0 : 0.0;
        int unit = 0;
        while (amount >= 1000.0 && unit + 1 < units.length) {
            amount /= 1000.0;
            unit++;
        }
        return number(amount, unit == 0, lang) + " " + Tr.in(units[unit], lang);
    }

    static String rate(double bytesPerSecond) {
        return rate(bytesPerSecond, Tr.language());
    }

    /** h:mm:ss past an hour, m:ss below, which is what a session reads like */
    static String uptime(long seconds) {
        if (seconds < 0) return "";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    /** the panel host is the only name a bare address carries */
    static String subscriptionName(String url) {
        try {
            String host = new java.net.URI(url).getHost();
            if (host != null && !host.isEmpty()) return host;
        } catch (java.net.URISyntaxException ignored) {
            // an address the engine will refuse anyway, the name does not matter
        }
        return "subscription";
    }
}
