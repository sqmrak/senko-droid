package com.senko.droid;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * what a server is called on screen. subscriptions put a flag emoji in front of
 * a city and the transport behind it, and some append the resolved address.
 * the flag becomes a picture, the rest is dropped so rows group by place
 */
final class Names {
    private Names() {}

    private static final Set<String> TECHNICAL = new HashSet<String>(Arrays.asList(
            "grpc", "h2", "http2", "ws", "websocket", "tcp", "tls", "reality", "xhttp", "vless",
            "trojan", "ss", "shadowsocks", "vmess", "tuic", "hy2", "hysteria2"));

    /** a regional indicator letter is the low surrogate DDE6 to DDFF after D83C */
    private static boolean regional(char c) {
        return c >= 0xDDE6 && c <= 0xDDFF;
    }

    /** percent escapes undone as utf-8, the text as it came when it cannot be read */
    static String unescape(String raw) {
        if (raw == null || raw.indexOf('%') < 0) return raw;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < raw.length(); ++i) {
                char c = raw.charAt(i);
                if (c == '%' && i + 2 < raw.length()
                        && Character.digit(raw.charAt(i + 1), 16) >= 0 && Character.digit(raw.charAt(i + 2), 16) >= 0) {
                    out.write(Character.digit(raw.charAt(i + 1), 16) * 16 + Character.digit(raw.charAt(i + 2), 16));
                    i += 2;
                } else {
                    byte[] bytes = String.valueOf(c).getBytes("UTF-8");
                    out.write(bytes, 0, bytes.length);
                }
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return raw;
        }
    }

    /** the index of the first flag (two regional indicators) in the text, or -1 */
    private static int flagAt(String text) {
        for (int i = 0; i + 3 < text.length(); ++i) {
            if (text.charAt(i) == 0xD83C && text.charAt(i + 2) == 0xD83C
                    && regional(text.charAt(i + 1)) && regional(text.charAt(i + 3)))
                return i;
        }
        return -1;
    }

    /** "de" for a remark with the german flag in it, null without a flag */
    static String flagCode(String remark) {
        if (remark == null || remark.isEmpty()) return null;
        String text = unescape(remark);
        int at = flagAt(text);
        if (at < 0) return null;
        char a = text.charAt(at + 1);
        char b = text.charAt(at + 3);
        return "" + (char) ('a' + a - 0xDDE6) + (char) ('a' + b - 0xDDE6);
    }

    private static boolean technicalSuffix(String suffix) {
        if (suffix.isEmpty()) return false;
        boolean found = false;
        for (String part : suffix.split("[ /+|,]")) {
            String token = part.trim().toLowerCase(Locale.US);
            if (token.isEmpty()) continue;
            if (!TECHNICAL.contains(token)) return false;
            found = true;
        }
        return found;
    }

    private static boolean ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); ++i)
                if (part.charAt(i) < '0' || part.charAt(i) > '9') return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /** the remark without its flag, a trailing "(grpc tls)" style suffix and a trailing address */
    static String displayName(String remark) {
        if (remark == null || remark.isEmpty()) return "";
        String text = unescape(remark);
        int at = flagAt(text);
        if (at >= 0) text = text.substring(0, at) + text.substring(at + 4);
        text = text.trim();
        for (;;) {
            int close = text.lastIndexOf(')');
            if (close < 0 || close + 1 != text.length()) break;
            int open = text.lastIndexOf('(', close - 1);
            if (open <= 0) break;
            if (!technicalSuffix(text.substring(open + 1, close))) break;
            text = text.substring(0, open).trim();
        }
        // some panels append the resolved ipv4 address to a country name
        int split = -1;
        for (int i = text.length() - 1; i >= 0; --i) {
            if (Character.isWhitespace(text.charAt(i))) {
                split = i;
                break;
            }
        }
        if (split >= 0 && split + 1 < text.length()) {
            String tail = text.substring(split + 1);
            int colon = tail.lastIndexOf(':');
            if (colon >= 0 && colon + 1 < tail.length()) {
                String port = tail.substring(colon + 1);
                boolean digits = true;
                for (int i = 0; i < port.length(); ++i)
                    if (port.charAt(i) < '0' || port.charAt(i) > '9') digits = false;
                if (digits) tail = tail.substring(0, colon);
            }
            if (ipv4(tail)) text = text.substring(0, split).trim();
        }
        return text;
    }

    /** "vless/tcp/reality", the line under a server name */
    static String protocolLabel(Model.Server server) {
        String proto = server.proto;
        if (proto.equals("vless") || proto.equals("vmess"))
            return proto + "/" + orElse(server.net, "tcp") + "/" + orElse(server.security, "none");
        if (proto.equals("trojan"))
            return proto + "/" + orElse(server.net, "tcp") + "/" + orElse(server.security, "tls");
        if (proto.equals("shadowsocks") || proto.equals("ss"))
            return "ss/" + orElse(server.security, "aead");
        if (proto.equals("hysteria2")) return "hysteria2/quic";
        // the engine sends a placeholder's reason with spaces turned into dashes
        if (!server.supported && !proto.isEmpty()) return proto.replace('-', ' ');
        return proto.isEmpty() ? "unknown" : proto;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** the title a server shows: its cleaned remark, or the host when it has none */
    static String title(Model.Server server) {
        String name = displayName(server.remark);
        if (!name.isEmpty()) return name;
        return server.host.isEmpty() ? "server" : server.host;
    }
}
