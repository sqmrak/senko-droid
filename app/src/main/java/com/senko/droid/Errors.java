package com.senko.droid;

/**
 * the engine keeps its reasons in english for the log, and the screens show a
 * sentence a person can act on. the mapping is the ios one with the parts that
 * only made sense there (utun, the connect hook, the socks listener) replaced
 */
final class Errors {
    private Errors() {}

    private static final String GATE_PREFIX = "subscription refused this device: ";

    /** the raw text of an ERR line, or any message the engine sent */
    static String human(String text) {
        if (text == null) return "";
        String raw = text.trim();
        if (raw.startsWith("ERR ")) raw = raw.substring(4);
        String detail = raw.startsWith("server: ") ? raw.substring(8) : raw;
        String network = Tr.t(detail);
        if (!network.equals(detail)) return network;

        String message = null;
        if (raw.contains("handshake timed out without udp response"))
            message = "AmneziaWG server does not answer: check the endpoint, port, keys and whether UDP is blocked";
        else if (raw.contains("unrelated udp responses") || raw.contains("handshake authentication failed")
                || raw.contains("invalid response packet"))
            message = "AmneziaWG received packets that do not match this profile: check the keys and packet settings";
        else if (raw.contains("udp transport failed") || raw.contains("udp socket")
                || raw.startsWith("error endpoint udp"))
            message = "AmneziaWG could not send or receive data: check the network and System Logs";
        else if (raw.startsWith("tunnel: cannot open the tunnel") || raw.startsWith("error cannot open the tunnel"))
            message = "The tunnel could not be opened. The VPN permission may have been withdrawn: connect again.";
        else if (raw.equals("server: dns resolution failed"))
            message = "The server name could not be resolved. Check the internet connection and server address.";
        else if (raw.equals("routing: the tunnel stopped before its check could run"))
            message = "The tunnel stopped right after it started. Open the log from the menu for the reason.";
        else if (raw.equals("tunnel: the tunnel could not start") || raw.startsWith("tunnel:"))
            message = "The tunnel could not start. Open the log from the menu to see what failed.";
        else if (raw.equals("server: unsupported protocol or security"))
            message = "This server uses a protocol or security mode that Senko does not support.";
        else if (raw.equals("server: bad uuid in server link"))
            message = "The server link has an invalid UUID. Import the link again from its source.";
        else if (raw.startsWith("this address only hands back its own link"))
            message = "This device received a link back to the same address instead of a subscription feed. Check device access and ask the provider for the feed URL.";
        else if (raw.startsWith("the happ crypt5 bundle on this page could not be opened"))
            message = "The Happ crypt5 bundle on this page could not be opened. It is either damaged or sealed with a key this build does not carry.";
        else if (raw.startsWith("this address opens a web page"))
            message = "This address opens a web page instead of a subscription feed. Copy the subscription link the page offers, not the page address.";
        else if (raw.equals("connect timeout") || raw.equals("switch timeout"))
            message = "The connection attempt timed out. Check the network and try another server.";
        else if (raw.startsWith("daemon offline") || raw.equals("engine offline"))
            message = "The Senko engine is not responding. Open the app again.";
        else if (raw.equals("server: no working server"))
            message = "Could not connect to the server. Check the address, network, and server availability.";
        else if (raw.startsWith("profile handshake failed:"))
            message = "The server port is reachable, but the profile could not complete a real connection. Check its UUID or password, security, SNI, and transport settings.";
        else if (raw.equals("disconnect before checking another profile"))
            message = "This profile cannot be checked while another profile is connected. Disconnect first.";
        else if (raw.equals("server address is invalid") || raw.equals("server address is unsafe or cannot be resolved")
                || raw.equals("unsafe or unresolved address"))
            message = "The server address is invalid, unsafe, or cannot be resolved.";
        else if (raw.equals("profile uses an unsupported transport or security mode"))
            message = "This server uses a protocol or security mode that Senko does not support.";
        else if (raw.equals("profile has an invalid UUID"))
            message = "The server link has an invalid UUID. Import the link again from its source.";
        else if (raw.equals("unknown content type"))
            message = "Unknown content type. This is not a server link, a subscription, or a profile Senko can read.";
        else if (raw.equals("no server senko can run in this file"))
            message = "No server Senko can run was found in this content.";
        else if (raw.equals("every server in this file is already saved"))
            message = "Every server in this content is already saved.";
        else if (raw.equals("nothing to import"))
            message = "There was nothing to import.";
        else if (raw.equals("no configuration is selected"))
            message = "No configuration is selected. Pick a server first.";
        else if (raw.equals("the clipboard is empty"))
            message = "The clipboard is empty.";
        else if (raw.equals("the file is empty or could not be read"))
            message = "The file is empty or could not be read.";
        else if (raw.startsWith(GATE_PREFIX))
            return Tr.t("The subscription refused this device.") + " " + raw.substring(GATE_PREFIX.length());
        return Tr.t(message != null ? message : raw);
    }

    /** removes what must not reach a shared log: credentials, tokens and uuids */
    static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String safe = text;
        safe = safe.replaceAll("(?i)(authorization|proxy-authorization|cookie|set-cookie|privatekey|presharedkey)\\s*[:=]\\s*[^\\r\\n]+",
                "$1: <redacted>");
        safe = safe.replaceAll("(://)[^/@\\s]+@", "$1<redacted>@");
        safe = safe.replaceAll("(?i)([?&](token|key|password|pass|uuid|pbk|sid)=)[^&#\\s]+", "$1<redacted>");
        return safe;
    }
}
