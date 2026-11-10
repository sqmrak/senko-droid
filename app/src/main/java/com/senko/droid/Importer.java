package com.senko.droid;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * decides what pasted, scanned or picked text is, the way the ios app does:
 * one source of text, and the content tells which path it takes
 */
final class Importer {
    private Importer() {}

    enum Kind {
        /** nothing usable */
        EMPTY,
        /** an amneziawg or wireguard config with [Interface] and [Peer] */
        AWG_CONF,
        /** the amnezia client's vpn:// bundle or its json */
        AMNEZIA_BUNDLE,
        /** one server link, which keeps its precise per link error */
        SERVER_LINK,
        /** an https address of a subscription feed */
        SUBSCRIPTION,
        /** an http address: the feed travels without tls and the user is asked */
        INSECURE_SUBSCRIPTION,
        /** everything else goes to the engine, which owns the parsers */
        CONTENT
    }

    /** whether the amnezia decoder in the engine recognises the bytes */
    interface Amnezia {
        boolean looksLikeBundle(byte[] data);
    }

    static boolean isNativeAwg(String text) {
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("[interface]") && lower.contains("[peer]");
    }

    /** one line and a scheme senko can dial */
    static boolean looksLikeSingleServerLink(String text) {
        if (text.indexOf('\n') >= 0) return false;
        if (text.startsWith("vless://") || text.startsWith("vmess://") || text.startsWith("socks5://")
                || text.startsWith("trojan://") || text.startsWith("ss://")) return true;
        if (!text.startsWith("http://") && !text.startsWith("https://")) return false;
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getUserInfo() != null) return true;
        if (uri.getFragment() != null) return true;
        String path = uri.getRawPath();
        return uri.getPort() >= 0 && (path == null || path.isEmpty() || path.equals("/"));
    }

    static boolean looksLikeSubscriptionUrl(String text) {
        return text.indexOf('\n') < 0 && (text.startsWith("http://") || text.startsWith("https://"));
    }

    static Kind classify(String raw, Amnezia amnezia) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("\ufeff")) text = text.substring(1).trim();
        if (text.isEmpty()) return Kind.EMPTY;
        if (isNativeAwg(text)) return Kind.AWG_CONF;
        try {
            if (amnezia != null && amnezia.looksLikeBundle(text.getBytes("UTF-8"))) return Kind.AMNEZIA_BUNDLE;
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        if (looksLikeSingleServerLink(text)) return Kind.SERVER_LINK;
        if (looksLikeSubscriptionUrl(text)) return text.startsWith("http://") ? Kind.INSECURE_SUBSCRIPTION : Kind.SUBSCRIPTION;
        return Kind.CONTENT;
    }

    /**
     * a picked file: a .vpn file holds the bare base64 body of a vpn:// link,
     * which no sniffer can tell from a base64 feed, so the extension offers it
     * to the amnezia decoder first
     */
    static Kind classifyFile(String name, byte[] body, Amnezia amnezia) {
        if (body == null || body.length == 0) return Kind.EMPTY;
        boolean amneziaExtension = name != null && name.toLowerCase(Locale.US).endsWith(".vpn");
        if (amnezia != null && (amneziaExtension || amnezia.looksLikeBundle(body))) return Kind.AMNEZIA_BUNDLE;
        String text;
        try {
            text = new String(body, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) {
            if (isNativeAwg(text)) return Kind.AWG_CONF;
            if (looksLikeSubscriptionUrl(trimmed) && !looksLikeSingleServerLink(trimmed))
                return classify(trimmed, null);
        }
        return Kind.CONTENT;
    }

    /** the number the engine ends an OK reply with, such as the new subscription's index, -1 when none */
    static int trailingInt(String reply) {
        if (reply == null) return -1;
        String[] parts = reply.trim().split(" ");
        if (parts.length == 0) return -1;
        try {
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
