package com.senko.droid;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * the lines of the control protocol the engine speaks, parsed. pure java, so a
 * jvm test covers it. a line this class does not know is returned as null,
 * never guessed at
 */
final class Protocol {
    private Protocol() {}

    static final class State {
        String name;
        long uptime; // seconds, 0 when the engine sent none
    }

    /** SRV idx selected group proto net sec supported host port profile remark... */
    static Model.Server parseServer(String line) {
        if (line == null || !line.startsWith("SRV ")) return null;
        // the remark is last and may hold spaces
        String[] parts = line.split(" ", 12);
        if (parts.length < 12) return null;
        try {
            Model.Server server = new Model.Server();
            server.index = Integer.parseInt(parts[1]);
            server.selected = parts[2].equals("1");
            server.group = Integer.parseInt(parts[3]);
            server.proto = parts[4];
            server.net = parts[5];
            server.security = parts[6];
            server.supported = parts[7].equals("1");
            server.host = parts[8];
            server.port = Integer.parseInt(parts[9]);
            server.profile = Integer.parseInt(parts[10]);
            server.remark = parts[11];
            return server;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** SUB idx name url, where the engine replaced spaces in the name with underscores */
    static Model.Sub parseSubscription(String line) {
        if (line == null || !line.startsWith("SUB ")) return null;
        String[] parts = line.split(" ", 4);
        if (parts.length < 4) return null;
        try {
            Model.Sub sub = new Model.Sub();
            sub.index = Integer.parseInt(parts[1]);
            sub.name = parts[2].replace('_', ' ');
            sub.url = parts[3];
            return sub;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** the engine escapes spaces, % # + and non ascii as %XX. a bare dash is an empty field */
    static String pctDecode(String text) {
        if (text == null || text.equals("-")) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length() && hex(text.charAt(i + 1)) >= 0
                    && hex(text.charAt(i + 2)) >= 0) {
                out.write(hex(text.charAt(i + 1)) * 16 + hex(text.charAt(i + 2)));
                i += 2;
            } else {
                byte[] bytes;
                try {
                    bytes = String.valueOf(c).getBytes("UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw new IllegalStateException(e);
                }
                out.write(bytes, 0, bytes.length);
            }
        }
        try {
            return out.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static long number(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * the reply of LIST: subscriptions with their meta lines, the section order,
     * the servers and their identities. a line of another kind is skipped
     */
    static Model.Catalog parseCatalog(List<String> lines) {
        Model.Catalog catalog = new Model.Catalog();
        for (String line : lines) {
            if (line.startsWith("SUB ")) {
                Model.Sub sub = parseSubscription(line);
                if (sub != null) catalog.subs.add(sub);
            } else if (line.startsWith("SUBMETA ")) {
                String[] parts = line.split(" ");
                if (parts.length == 3) {
                    Model.Sub sub = catalog.sub((int) number(parts[1]));
                    if (sub != null) sub.expire = number(parts[2]);
                }
            } else if (line.startsWith("SUBINFO ")) {
                String[] parts = line.split(" ");
                if (parts.length >= 7) {
                    Model.Sub sub = catalog.sub((int) number(parts[1]));
                    if (sub != null) {
                        sub.upload = number(parts[2]);
                        sub.download = number(parts[3]);
                        sub.total = number(parts[4]);
                        sub.description = pctDecode(parts[5]);
                        sub.supportUrl = pctDecode(parts[6]);
                    }
                }
            } else if (line.startsWith("SUBPANEL ")) {
                // SUBPANEL idx hours refill web notice, "-" standing for an empty text
                String[] parts = line.split(" ");
                if (parts.length == 6) {
                    Model.Sub sub = catalog.sub((int) number(parts[1]));
                    if (sub != null) {
                        sub.updateHours = (int) number(parts[2]);
                        sub.refillDate = number(parts[3]);
                        sub.webPageUrl = parts[4].equals("-") ? "" : pctDecode(parts[4]);
                        sub.announce = parts[5].equals("-") ? "" : pctDecode(parts[5]);
                    }
                }
            } else if (line.startsWith("SUBHDR ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length == 3) {
                    Model.Sub sub = catalog.sub((int) number(parts[1]));
                    if (sub != null) sub.header = pctDecode(parts[2]);
                }
            } else if (line.startsWith("SECTION")) {
                String[] parts = line.trim().split(" ");
                int[] order = new int[parts.length - 1];
                for (int i = 1; i < parts.length; ++i) order[i - 1] = (int) number(parts[i]);
                catalog.order = order;
            } else if (line.startsWith("SRV ")) {
                Model.Server server = parseServer(line);
                if (server != null) catalog.servers.add(server);
            } else if (line.startsWith("SRVID ")) {
                String[] parts = line.split(" ");
                if (parts.length == 3) {
                    Model.Server server = catalog.server((int) number(parts[1]));
                    if (server != null) server.identity = parts[2];
                }
            }
        }
        return catalog;
    }

    /** STATE name [uptime] */
    static State parseState(String line) {
        if (line == null || !line.startsWith("STATE ")) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2 || parts.length > 3) return null;
        State state = new State();
        state.name = parts[1];
        if (parts.length == 3) {
            try {
                state.uptime = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return state;
    }

    /** STAT up down, in bytes since the tunnel came up. null when malformed */
    static long[] parseTraffic(String line) {
        if (line == null || !line.startsWith("STAT ")) return null;
        String[] parts = line.split(" ");
        if (parts.length != 3) return null;
        try {
            return new long[] { Long.parseLong(parts[1]), Long.parseLong(parts[2]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** PONG idx ms, with ms -1 for a server that did not answer */
    static int[] parsePong(String line) {
        if (line == null || !line.startsWith("PONG ")) return null;
        String[] parts = line.split(" ");
        if (parts.length != 3) return null;
        try {
            return new int[] { Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isError(String line) {
        return line != null && (line.equals("ERR") || line.startsWith("ERR "));
    }

    static boolean isOk(String line) {
        return line != null && (line.equals("OK") || line.startsWith("OK "));
    }

    /** the text after OK or ERR, empty when there is none */
    static String message(String line) {
        if (line == null) return "";
        int space = line.indexOf(' ');
        return space < 0 ? "" : line.substring(space + 1);
    }

    /** SET key value lines of a SETTINGS reply, as key/value pairs */
    static List<String[]> parseSettings(List<String> lines) {
        List<String[]> out = new ArrayList<String[]>();
        for (String line : lines) {
            if (!line.startsWith("SET ")) continue;
            String[] parts = line.split(" ", 3);
            if (parts.length == 3) out.add(new String[] { parts[1], parts[2] });
        }
        return out;
    }

    /** RULE index action type hits value, the value is last */
    static Model.Rule parseRule(String line) {
        if (line == null || !line.startsWith("RULE ")) return null;
        String[] parts = line.split(" ", 6);
        if (parts.length < 6) return null;
        Model.Rule rule = new Model.Rule();
        rule.index = (int) number(parts[1]);
        rule.action = parts[2];
        rule.type = parts[3];
        rule.hits = number(parts[4]);
        rule.value = parts[5];
        return rule;
    }

    /** ROUTING sub active global rules unsupported geo updated name... */
    static Model.RoutingProfile parseRouting(String line) {
        if (line == null || !line.startsWith("ROUTING ")) return null;
        String[] parts = line.split(" ", 9);
        if (parts.length < 9) return null;
        Model.RoutingProfile profile = new Model.RoutingProfile();
        profile.index = (int) number(parts[1]);
        profile.active = number(parts[2]) != 0;
        profile.globalProxy = number(parts[3]) != 0;
        profile.rules = (int) number(parts[4]);
        profile.unsupported = (int) number(parts[5]);
        profile.geoTags = (int) number(parts[6]);
        profile.updated = number(parts[7]);
        profile.name = parts[8];
        return profile;
    }

    /** RENTRY action site|ip supported entry..., the entry is last, spaces and all */
    static Model.RoutingEntry parseRoutingEntry(String line) {
        if (line == null || !line.startsWith("RENTRY ")) return null;
        String[] parts = line.split(" ", 5);
        if (parts.length < 5) return null;
        Model.RoutingEntry entry = new Model.RoutingEntry();
        entry.action = parts[1];
        entry.isIp = parts[2].equals("ip");
        entry.supported = number(parts[3]) != 0;
        entry.entry = parts[4];
        return entry;
    }

    /** GEO busy stamp missing result message... */
    static Model.GeoState parseGeo(String line) {
        if (line == null || !line.startsWith("GEO ")) return null;
        String[] parts = line.split(" ", 6);
        if (parts.length < 5) return null;
        Model.GeoState geo = new Model.GeoState();
        geo.busy = number(parts[1]) != 0;
        geo.stamp = number(parts[2]);
        geo.missing = (int) number(parts[3]);
        geo.result = (int) number(parts[4]);
        geo.message = parts.length == 6 ? parts[5] : "";
        return geo;
    }

    /** DIAG key value, the value is free text */
    static Model.DiagFact parseDiag(String line) {
        if (line == null || !line.startsWith("DIAG ")) return null;
        String rest = line.substring(5);
        int space = rest.indexOf(' ');
        if (space <= 0) return null;
        Model.DiagFact fact = new Model.DiagFact();
        fact.key = rest.substring(0, space);
        fact.value = rest.substring(space + 1).trim();
        return fact;
    }

    /** STAGE ok ms name... */
    static Model.CheckStage parseStage(String line) {
        if (line == null || !line.startsWith("STAGE ")) return null;
        String[] parts = line.split(" ", 4);
        if (parts.length < 4) return null;
        Model.CheckStage stage = new Model.CheckStage();
        stage.ok = number(parts[1]) != 0;
        stage.ms = (int) number(parts[2]);
        stage.name = parts[3];
        return stage;
    }

    /** the lines of a CHECK reply: the stages, then PONG or ERR */
    static Model.CheckResult parseCheck(List<String> lines) {
        Model.CheckResult result = new Model.CheckResult();
        for (String raw : lines) {
            String line = raw.trim();
            Model.CheckStage stage = parseStage(line);
            if (stage != null) {
                result.stages.add(stage);
                continue;
            }
            int[] pong = parsePong(line);
            if (pong != null) {
                result.ms = pong[1];
                continue;
            }
            if (isError(line)) result.error = message(line);
        }
        if (result.ms < 0 && result.error.isEmpty()) result.error = "the engine did not answer";
        return result;
    }

    /**
     * the engine says the data path is up for connecting and connected. the
     * rest (idle, error) means no vpn session should be held
     */
    static boolean isActive(String state) {
        return "connecting".equals(state) || "connected".equals(state);
    }

    static List<String> copyOf(String... lines) {
        return new ArrayList<String>(Arrays.asList(lines));
    }
}
