package com.senko.droid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * what the server list shows, built from the catalog: one section per
 * subscription and one for the servers added by hand, rows that stand in for
 * their same-named siblings, sorted and narrowed by the search and the group
 * chip. pure java, so a jvm test covers it
 */
final class ServerList {
    private ServerList() {}

    static final int SORT_ENGINE = 0;
    static final int SORT_PING = 1;
    static final int SORT_NAME = 2;
    /** the chip that stands for the amneziawg profile alone */
    static final int FILTER_AWG = -2;
    static final int MANUAL = -1;

    static final class Row {
        Model.Server server;
        /** what the row shows: the remark without the head every row of its section shares */
        String name = "";
        /** every catalog index this row stands for, its own first */
        final List<Integer> members = new ArrayList<Integer>();
        /** the servers behind members, in the same order */
        final List<Model.Server> nodes = new ArrayList<Model.Server>();
    }

    static final class Section {
        int subIdx;
        String title = "";
        String flag = "";
        /** usage and expiry of the subscription, joined for one line */
        String meta = "";
        final List<Row> rows = new ArrayList<Row>();

        boolean manual() {
            return subIdx == MANUAL;
        }
    }

    /** the best (lowest) ping of a row's members. -3 while one is running, -1 no answer, -2 unknown */
    interface Pings {
        int of(int serverIndex);
    }

    static final class Result {
        final List<Section> sections = new ArrayList<Section>();
        /** one entry per chip: the section index, null for All, FILTER_AWG for the profile */
        final List<Integer> chipKeys = new ArrayList<Integer>();
        final List<String> chipTitles = new ArrayList<String>();
        boolean awgRowShown;
    }


    private static final String SEPARATORS = " |·-—:/,";

    /** the leading text every name shares, cut after a separator, or null when it is short or not shared */
    static String sharedHead(List<String> names) {
        if (names.size() < 2) return null;
        String first = names.get(0);
        int common = first.length();
        for (String name : names) {
            int i = 0;
            while (i < common && i < name.length() && name.charAt(i) == first.charAt(i)) ++i;
            common = i;
            if (common == 0) return null;
        }
        int cut = 0;
        for (int i = 0; i < common; ++i)
            if (SEPARATORS.indexOf(first.charAt(i)) >= 0) cut = i + 1;
        if (cut < 8) return null;
        return first.substring(0, cut);
    }

    private static List<String> shownNames(List<Model.Server> servers) {
        List<String> raw = new ArrayList<String>();
        for (Model.Server server : servers) raw.add(Names.displayName(server.remark));
        String head = sharedHead(raw);
        List<String> shown = new ArrayList<String>();
        for (String name : raw) {
            String out = name;
            if (head != null && !head.isEmpty() && name.length() > head.length()) {
                out = name.substring(head.length()).trim();
                if (out.isEmpty()) out = name;
            }
            shown.add(out);
        }
        return shown;
    }

    /**
     * one row per location, the way Happ lists a subscription: every node that shows the same name is
     * one row, whether it came from one config of a json feed or from several links. a tap connects
     * the fastest of them and the engine falls back through the rest, so the user sees "Germany" once
     * and not every host behind it
     */
    static List<Row> collapse(List<Model.Server> servers, int selectedIndex) {
        List<Model.Server> heads = new ArrayList<Model.Server>();
        List<Row> out = new ArrayList<Row>();
        Map<String, Row> byKey = new HashMap<String, Row>();
        for (Model.Server server : servers) {
            String visible = Names.displayName(server.remark);
            String key = visible.isEmpty() ? null : visible;
            Row known = key != null ? byKey.get(key) : null;
            if (known != null) {
                known.members.add(server.index);
                known.nodes.add(server);
                // a row stands for a node that can run, when the group has one
                if (!known.server.supported && server.supported) {
                    heads.set(out.indexOf(known), server);
                    known.server = server;
                }
                continue;
            }
            Row row = new Row();
            row.server = server;
            row.members.add(server.index);
            row.nodes.add(server);
            out.add(row);
            heads.add(server);
            if (key != null) byKey.put(key, row);
        }
        List<String> shown = shownNames(heads);
        for (int i = 0; i < out.size(); ++i) out.get(i).name = shown.get(i);
        return out;
    }

    /**
     * the member a tap on a row connects to: the fastest one that answered, else the first that can
     * run. the engine then falls back through the other members of the config in latency order
     */
    static int pick(Row row, Pings pings) {
        int best = -1;
        int bestMs = Integer.MAX_VALUE;
        int firstRunnable = -1;
        for (Model.Server node : row.nodes) {
            if (!node.supported) continue;
            int index = node.index;
            if (firstRunnable < 0) firstRunnable = index;
            int ms = pings.of(index);
            if (ms >= 0 && ms < bestMs) {
                bestMs = ms;
                best = index;
            }
        }
        if (best >= 0) return best;
        return firstRunnable >= 0 ? firstRunnable : row.server.index;
    }

    private static int bestPing(Row row, Pings pings) {
        int best = -2;
        boolean checked = false;
        for (int index : row.members) {
            int ms = pings.of(index);
            if (ms == -2) continue;
            checked = true;
            if (ms == -3) return -3;
            if (ms >= 0 && (best < 0 || ms < best)) best = ms;
        }
        if (best >= 0) return best;
        return checked ? -1 : -2;
    }

    private static int rank(int ms) {
        if (ms == -2) return 1; // unknown: not fast, not failed
        return ms >= 0 ? 0 : 2;
    }

    static void sort(List<Row> rows, int mode, final Pings pings) {
        if (mode != SORT_NAME && mode != SORT_PING) return;
        final int sortMode = mode;
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                String na = Names.displayName(a.server.remark);
                String nb = Names.displayName(b.server.remark);
                if (na.isEmpty()) na = a.server.host;
                if (nb.isEmpty()) nb = b.server.host;
                if (sortMode == SORT_PING) {
                    int pa = bestPing(a, pings);
                    int pb = bestPing(b, pings);
                    int ra = rank(pa);
                    int rb = rank(pb);
                    if (ra != rb) return ra < rb ? -1 : 1;
                    if (ra == 0 && pa != pb) return pa < pb ? -1 : 1;
                }
                return na.compareToIgnoreCase(nb);
            }
        });
    }


    private static boolean regional(char c) {
        return c >= 0xDDE6 && c <= 0xDDFF;
    }

    /** "🇩" written out as text becomes the character */
    static String expandFlagEscapes(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ++i) {
            if (i + 11 < text.length() && escapedUnit(text, i) >= 0 && escapedUnit(text, i + 6) >= 0
                    && escapedUnit(text, i) == 0xD83C && regional((char) escapedUnit(text, i + 6))) {
                out.append((char) escapedUnit(text, i)).append((char) escapedUnit(text, i + 6));
                i += 11;
                continue;
            }
            out.append(text.charAt(i));
        }
        return out.toString();
    }

    private static int escapedUnit(String text, int at) {
        if (at + 5 >= text.length() || text.charAt(at) != '\\' || text.charAt(at + 1) != 'u') return -1;
        int value = 0;
        for (int i = 2; i < 6; ++i) {
            int digit = Character.digit(text.charAt(at + i), 16);
            if (digit < 0) return -1;
            value = (value << 4) | digit;
        }
        return value;
    }

    private static String stripLeaders(String text) {
        String out = text.trim();
        for (;;) {
            if (out.startsWith("...") || out.startsWith("•••")) out = out.substring(3).trim();
            else if (out.startsWith("…")) out = out.substring(1).trim();
            else return out;
        }
    }

    /** the title of a subscription without its flag, and the flag in element 1 (empty when none) */
    static String[] subscriptionTitle(String raw) {
        if (raw == null || raw.isEmpty()) return new String[] { "Subscription", "" };
        String text = expandFlagEscapes(Names.unescape(raw));
        for (int i = 0; i + 3 < text.length(); ++i) {
            if (text.charAt(i) == 0xD83C && text.charAt(i + 2) == 0xD83C
                    && regional(text.charAt(i + 1)) && regional(text.charAt(i + 3))) {
                String flag = text.substring(i, i + 4);
                String clean = stripLeaders(text.substring(0, i) + text.substring(i + 4));
                return new String[] { clean.isEmpty() ? "Subscription" : clean, flag };
            }
        }
        String clean = stripLeaders(text);
        return new String[] { clean.isEmpty() ? "Subscription" : clean, "" };
    }

    static String usage(Model.Sub sub, int lang) {
        if (sub == null || (sub.total == 0 && sub.upload == 0 && sub.download == 0)) return null;
        long used = sub.upload + sub.download;
        if (sub.total != 0) {
            long left = sub.total > used ? sub.total - used : 0;
            String size = Format.bytes(left, lang);
            return lang == Tr.CHINESE ? "剩余 " + size : lang == Tr.RUSSIAN ? "осталось " + size : size + " left";
        }
        String size = Format.bytes(used, lang);
        return lang == Tr.CHINESE ? "已用 " + size : lang == Tr.RUSSIAN ? "исп. " + size : size + " used";
    }

    /** "until 12/31/26", or "expired", in the chosen language. null when the subscription does not expire */
    static String expiry(long expireSeconds, long nowSeconds, int lang) {
        if (expireSeconds == 0) return null;
        if (expireSeconds <= nowSeconds)
            return lang == Tr.CHINESE ? "已过期" : lang == Tr.RUSSIAN ? "истекла" : "expired";
        java.util.Calendar date = java.util.Calendar.getInstance(java.util.TimeZone.getDefault());
        date.setTimeInMillis(expireSeconds * 1000L);
        int year = date.get(java.util.Calendar.YEAR);
        int month = date.get(java.util.Calendar.MONTH) + 1;
        int day = date.get(java.util.Calendar.DAY_OF_MONTH);
        if (lang == Tr.CHINESE) return String.format(Locale.US, "至 %04d-%02d-%02d", year, month, day);
        if (lang == Tr.RUSSIAN) return String.format(Locale.US, "до %02d.%02d.%02d", day, month, year % 100);
        return String.format(Locale.US, "until %02d/%02d/%02d", month, day, year % 100);
    }


    static boolean matches(Row row, String query) {
        String code = Names.flagCode(row.server.remark);
        String[] fields = { row.name, Names.displayName(row.server.remark), row.server.host,
                code == null ? "" : code };
        String needle = query.toLowerCase(Locale.US);
        for (String field : fields)
            if (field != null && !field.isEmpty() && field.toLowerCase(Locale.US).contains(needle)) return true;
        return false;
    }

    /**
     * the sections the list shows. filter is a section's subscription index, MANUAL,
     * FILTER_AWG, or null for all. query narrows by name, host and country code
     */
    static Result build(Model.Catalog catalog, int selectedIndex, int sortMode, Pings pings,
                        Integer filter, String query, boolean hasAwg, long nowSeconds, int lang) {
        Result result = new Result();
        List<Model.Server> manual = new ArrayList<Model.Server>();
        Map<Integer, List<Model.Server>> bySub = new HashMap<Integer, List<Model.Server>>();
        Map<Integer, String> flagBySub = new HashMap<Integer, String>();
        for (Model.Server server : catalog.servers) {
            if (server.group < 0) {
                manual.add(server);
                continue;
            }
            List<Model.Server> list = bySub.get(server.group);
            if (list == null) {
                list = new ArrayList<Model.Server>();
                bySub.put(server.group, list);
            }
            list.add(server);
            if (!flagBySub.containsKey(server.group)) {
                String code = flagOf(server.remark);
                if (code != null) flagBySub.put(server.group, code);
            }
        }

        List<Section> all = new ArrayList<Section>();
        Set<Integer> seen = new HashSet<Integer>();
        List<Integer> order = new ArrayList<Integer>();
        for (int key : catalog.order) order.add(key);
        if (order.isEmpty()) {
            order.add(MANUAL);
            for (Model.Sub sub : catalog.subs) order.add(sub.index);
        }
        for (Model.Sub sub : catalog.subs) if (!order.contains(sub.index)) order.add(sub.index);
        if (!order.contains(MANUAL) && (!manual.isEmpty() || hasAwg)) order.add(0, MANUAL);
        for (int key : order) {
            if (seen.contains(key)) continue;
            if (key == MANUAL) {
                if (manual.isEmpty() && !hasAwg) continue;
                Section section = new Section();
                section.subIdx = MANUAL;
                section.title = "Manual";
                section.rows.addAll(collapse(manual, selectedIndex));
                sort(section.rows, sortMode, pings);
                all.add(section);
                seen.add(key);
                continue;
            }
            Model.Sub sub = catalog.sub(key);
            if (sub == null) continue;
            Section section = new Section();
            section.subIdx = key;
            String[] title = subscriptionTitle(sub.name.isEmpty() ? "Subscription" : sub.name.replace('_', ' '));
            section.title = title[0];
            section.flag = title[1].isEmpty() && flagBySub.get(key) != null ? flagBySub.get(key) : title[1];
            List<String> parts = new ArrayList<String>();
            String used = usage(sub, lang);
            String until = expiry(sub.expire, nowSeconds, lang);
            if (used != null) parts.add(used);
            if (until != null) parts.add(until);
            StringBuilder meta = new StringBuilder();
            for (String part : parts) meta.append(meta.length() > 0 ? "  •  " : "").append(part);
            section.meta = meta.toString();
            List<Model.Server> rows = bySub.get(key);
            section.rows.addAll(collapse(rows == null ? new ArrayList<Model.Server>() : rows, selectedIndex));
            sort(section.rows, sortMode, pings);
            all.add(section);
            seen.add(key);
        }

        // the chips: All, one per section, then the profile
        result.chipTitles.add("All");
        result.chipKeys.add(null);
        for (Section section : all) {
            String title = section.manual() ? "Manual" : section.title;
            if (!section.flag.isEmpty()) title = section.flag + " " + title;
            result.chipTitles.add(title);
            result.chipKeys.add(section.subIdx);
        }
        if (hasAwg) {
            result.chipTitles.add("AmneziaWG");
            result.chipKeys.add(FILTER_AWG);
        }
        if (filter != null && !result.chipKeys.contains(filter)) filter = null;

        String needle = query == null ? "" : query.trim();
        boolean awgOnly = filter != null && filter == FILTER_AWG;
        result.awgRowShown = hasAwg && (needle.isEmpty() || "amneziawg".contains(needle.toLowerCase(Locale.US)));
        for (Section section : all) {
            if (awgOnly) {
                if (!section.manual() || !result.awgRowShown) continue;
                Section only = copy(section);
                result.sections.add(only);
                continue;
            }
            if (filter != null && section.subIdx != filter) continue;
            if (!needle.isEmpty()) {
                Section narrowed = copy(section);
                narrowed.rows.clear();
                for (Row row : section.rows) if (matches(row, needle)) narrowed.rows.add(row);
                if (narrowed.rows.isEmpty() && !(section.manual() && result.awgRowShown)) continue;
                result.sections.add(narrowed);
                continue;
            }
            result.sections.add(section);
        }
        return result;
    }

    private static Section copy(Section source) {
        Section section = new Section();
        section.subIdx = source.subIdx;
        section.title = source.title;
        section.flag = source.flag;
        section.meta = source.meta;
        return section;
    }

    private static String flagOf(String remark) {
        if (remark == null || remark.isEmpty()) return null;
        String text = expandFlagEscapes(Names.unescape(remark));
        for (int i = 0; i + 3 < text.length(); ++i)
            if (text.charAt(i) == 0xD83C && text.charAt(i + 2) == 0xD83C
                    && regional(text.charAt(i + 1)) && regional(text.charAt(i + 3)))
                return text.substring(i, i + 4);
        return null;
    }
}
