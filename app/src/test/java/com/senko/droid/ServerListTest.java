package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ServerListTest {
    private static final String DE = "🇩🇪";

    private static Model.Server server(int index, int group, String proto, String remark, String host) {
        Model.Server server = new Model.Server();
        server.index = index;
        server.group = group;
        server.proto = proto;
        server.remark = remark;
        server.host = host;
        server.supported = true;
        server.port = 443;
        return server;
    }

    private static Model.Sub sub(int index, String name) {
        Model.Sub sub = new Model.Sub();
        sub.index = index;
        sub.name = name;
        return sub;
    }

    private static final ServerList.Pings NONE = new ServerList.Pings() {
        @Override
        public int of(int serverIndex) {
            return -2;
        }
    };

    private static ServerList.Result build(Model.Catalog catalog, int sort, ServerList.Pings pings,
                                           Integer filter, String query, boolean awg) {
        return ServerList.build(catalog, -1, sort, pings, filter, query, awg, 1_700_000_000L, Tr.ENGLISH);
    }

    @Test
    public void sharedHeadIsCutAfterASeparator() {
        assertEquals("Provider | ", ServerList.sharedHead(Arrays.asList("Provider | Berlin", "Provider | Paris")));
        assertNull("two names are needed", ServerList.sharedHead(Arrays.asList("Provider | Berlin")));
        assertNull("a short head is not worth cutting", ServerList.sharedHead(Arrays.asList("ab | x", "ab | y")));
        assertNull("nothing shared", ServerList.sharedHead(Arrays.asList("Berlin", "Paris")));
        assertNull("a head that ends inside a word", ServerList.sharedHead(Arrays.asList("Frankfurt1", "Frankfurt2")));
    }

    @Test
    public void rowsOfOneSectionLoseTheirSharedHead() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(0, "Panel"));
        catalog.servers.add(server(0, 0, "vless", "FastVPN | Berlin", "a.example"));
        catalog.servers.add(server(1, 0, "vless", "FastVPN | Paris", "b.example"));
        ServerList.Result result = build(catalog, ServerList.SORT_ENGINE, NONE, null, "", false);
        assertEquals("Berlin", result.sections.get(0).rows.get(0).name);
        assertEquals("Paris", result.sections.get(0).rows.get(1).name);
    }

    @Test
    public void nodesThatShowTheSameNameAreOneRow() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(0, "Panel"));
        catalog.servers.add(server(0, 0, "vless", DE + " Berlin (tcp)", "a.example"));
        catalog.servers.add(server(1, 0, "vless", DE + " Berlin (grpc)", "b.example"));
        catalog.servers.add(server(2, 0, "hysteria2", DE + " Berlin", "c.example"));
        catalog.servers.add(server(3, 0, "vless", "Paris", "d.example"));
        List<ServerList.Row> rows = build(catalog, 0, NONE, null, "", false).sections.get(0).rows;
        assertEquals(2, rows.size());
        assertEquals("no running numbers, the name is shown once", "Berlin", rows.get(0).name);
        assertEquals(Arrays.asList(0, 1, 2), rows.get(0).members);
        assertEquals("Paris", rows.get(1).name);
    }

    @Test
    public void aTapOnARowPicksTheFastestRunnableNode() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(0, "Panel"));
        // a balancer profile of three nodes, another node with the same name, and a lone node
        for (int i = 0; i < 3; ++i) {
            Model.Server s = server(i, 0, "vless", "Auto", "n" + i + ".example");
            s.profile = 1;
            catalog.servers.add(s);
        }
        catalog.servers.add(server(3, 0, "vless", "Auto", "other.example"));
        catalog.servers.add(server(4, 0, "vless", "Germany", "de.example"));
        catalog.servers.get(0).supported = false;
        List<ServerList.Row> rows = build(catalog, ServerList.SORT_ENGINE, NONE, null, "", false).sections.get(0).rows;
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList(0, 1, 2, 3), rows.get(0).members);
        assertEquals("a row stands for a node that can run", 1, rows.get(0).server.index);
        assertEquals("Auto", rows.get(0).name);
        assertEquals("Germany", rows.get(1).name);

        final Map<Integer, Integer> ms = new HashMap<Integer, Integer>();
        ms.put(0, 5);
        ms.put(1, 90);
        ms.put(2, 40);
        ServerList.Pings pings = new ServerList.Pings() {
            @Override
            public int of(int index) {
                Integer value = ms.get(index);
                return value == null ? -2 : value;
            }
        };
        ms.put(3, 400);
        assertEquals("the unsupported node is never picked", 2, ServerList.pick(rows.get(0), pings));
        assertEquals("without pings the first runnable node", 1, ServerList.pick(rows.get(0), NONE));
    }

    @Test
    public void anUnsupportedPlaceholderNeverHidesAWorkingRow() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(0, "Panel"));
        catalog.servers.add(server(0, 0, "tuic", "Berlin", "a.example"));
        catalog.servers.get(0).supported = false;
        catalog.servers.add(server(1, 0, "tuic", "Berlin", "b.example"));
        List<ServerList.Row> rows = build(catalog, 0, NONE, null, "", false).sections.get(0).rows;
        assertEquals(1, rows.size());
        assertEquals("the placeholder never stands for the row", 1, rows.get(0).server.index);
        assertEquals(2, rows.get(0).members.size());
    }

    @Test
    public void sortByPingPutsUnknownAfterMeasuredAndFailedLast() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(0, "Panel"));
        catalog.servers.add(server(0, 0, "vless", "Alpha", "a"));
        catalog.servers.add(server(1, 0, "vless", "Bravo", "b"));
        catalog.servers.add(server(2, 0, "vless", "Charlie", "c"));
        catalog.servers.add(server(3, 0, "vless", "Delta", "d"));
        final Map<Integer, Integer> ms = new HashMap<Integer, Integer>();
        ms.put(0, -1);
        ms.put(1, 80);
        ms.put(3, 20);
        ServerList.Pings pings = new ServerList.Pings() {
            @Override
            public int of(int index) {
                Integer value = ms.get(index);
                return value == null ? -2 : value;
            }
        };
        List<ServerList.Row> rows = build(catalog, ServerList.SORT_PING, pings, null, "", false).sections.get(0).rows;
        assertEquals("Delta", rows.get(0).name);
        assertEquals("Bravo", rows.get(1).name);
        assertEquals("unknown before failed", "Charlie", rows.get(2).name);
        assertEquals("Alpha", rows.get(3).name);
    }

    @Test
    public void sortByNameIgnoresCase() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(0, "Panel"));
        catalog.servers.add(server(0, 0, "vless", "zeta", "a"));
        catalog.servers.add(server(1, 0, "vless", "Alpha", "b"));
        List<ServerList.Row> rows = build(catalog, ServerList.SORT_NAME, NONE, null, "", false).sections.get(0).rows;
        assertEquals("Alpha", rows.get(0).name);
    }

    @Test
    public void manualSectionComesFromServersWithoutAGroup() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.servers.add(server(0, -1, "vless", "Home", "home.example"));
        ServerList.Result result = build(catalog, 0, NONE, null, "", false);
        assertEquals(1, result.sections.size());
        assertTrue(result.sections.get(0).manual());
        assertEquals(Arrays.asList("All", "Manual"), result.chipTitles);
    }

    @Test
    public void anAwgProfileAddsAChipAndKeepsAnEmptyManualSection() {
        Model.Catalog catalog = new Model.Catalog();
        ServerList.Result result = build(catalog, 0, NONE, null, "", true);
        assertEquals(1, result.sections.size());
        assertEquals("AmneziaWG", result.chipTitles.get(result.chipTitles.size() - 1));
        assertTrue(result.awgRowShown);
        ServerList.Result only = build(catalog, 0, NONE, ServerList.FILTER_AWG, "", true);
        assertEquals(1, only.sections.size());
        assertTrue(only.sections.get(0).rows.isEmpty());
    }

    @Test
    public void groupFilterAndSearchNarrowTheList() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(0, "One"));
        catalog.subs.add(sub(1, "Two"));
        catalog.servers.add(server(0, 0, "vless", DE + " Berlin", "a.example"));
        catalog.servers.add(server(1, 1, "vless", "Paris", "b.example"));
        catalog.servers.add(server(2, 1, "vless", "Madrid", "c.example"));
        assertEquals(2, build(catalog, 0, NONE, null, "", false).sections.size());
        ServerList.Result second = build(catalog, 0, NONE, 1, "", false);
        assertEquals(1, second.sections.size());
        assertEquals(2, second.sections.get(0).rows.size());
        ServerList.Result found = build(catalog, 0, NONE, null, "madr", false);
        assertEquals(1, found.sections.size());
        assertEquals("Madrid", found.sections.get(0).rows.get(0).name);
        assertEquals("a country code finds its servers", 1,
                build(catalog, 0, NONE, null, "de", false).sections.size());
        assertEquals("a host finds its server", 1, build(catalog, 0, NONE, null, "b.example", false).sections.size());
        assertTrue("nothing matches", build(catalog, 0, NONE, null, "tokyo", false).sections.isEmpty());
        assertEquals("a filter that no longer exists is dropped", 2,
                build(catalog, 0, NONE, 9, "", false).sections.size());
    }

    @Test
    public void sectionOrderFollowsTheEngine() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(3, "Three"));
        catalog.subs.add(sub(5, "Five"));
        catalog.order = new int[] { 5, 3 };
        ServerList.Result result = build(catalog, 0, NONE, null, "", false);
        assertEquals(5, result.sections.get(0).subIdx);
        assertEquals(3, result.sections.get(1).subIdx);
    }

    @Test
    public void subscriptionTitleSplitsOffTheFlagAndLeaders() {
        String[] a = ServerList.subscriptionTitle(DE + " Fast VPN");
        assertEquals("Fast VPN", a[0]);
        assertEquals(DE, a[1]);
        String[] b = ServerList.subscriptionTitle("… Fast VPN");
        assertEquals("Fast VPN", b[0]);
        assertEquals("", b[1]);
        assertEquals("Subscription", ServerList.subscriptionTitle("")[0]);
        assertEquals("Subscription", ServerList.subscriptionTitle("...")[0]);
        assertEquals("flags written as escapes are expanded", DE,
                ServerList.subscriptionTitle("\\ud83c\\udde9\\ud83c\\uddea Panel")[1]);
    }

    @Test
    public void usageAndExpiryLines() {
        Model.Sub sub = new Model.Sub();
        assertNull(ServerList.usage(sub, Tr.ENGLISH));
        sub.total = 10L * 1024 * 1024 * 1024;
        sub.upload = 1024L * 1024 * 1024;
        sub.download = 2L * 1024 * 1024 * 1024;
        assertEquals("7.0 GB left", ServerList.usage(sub, Tr.ENGLISH));
        assertEquals("осталось 7,0 ГБ", ServerList.usage(sub, Tr.RUSSIAN));
        sub.total = 0;
        assertEquals("3.0 GB used", ServerList.usage(sub, Tr.ENGLISH));
        assertNull(ServerList.expiry(0, 100, Tr.ENGLISH));
        assertEquals("expired", ServerList.expiry(50, 100, Tr.ENGLISH));
        assertEquals("истекла", ServerList.expiry(50, 100, Tr.RUSSIAN));
        assertTrue(ServerList.expiry(4_000_000_000L, 100, Tr.ENGLISH).startsWith("until "));
        assertTrue(ServerList.expiry(4_000_000_000L, 100, Tr.RUSSIAN).startsWith("до "));
        assertTrue(ServerList.expiry(4_000_000_000L, 100, Tr.CHINESE).startsWith("至 "));
    }

    @Test
    public void aSectionMetaJoinsUsageAndExpiry() {
        Model.Catalog catalog = new Model.Catalog();
        Model.Sub sub = sub(0, "Panel");
        sub.total = 2048;
        sub.expire = 4_000_000_000L;
        catalog.subs.add(sub);
        ServerList.Result result = build(catalog, 0, NONE, null, "", false);
        String meta = result.sections.get(0).meta;
        assertTrue(meta, meta.startsWith("2.0 KB left  •  until "));
        assertFalse(meta.isEmpty());
        assertNotNull(result.sections.get(0).title);
    }

    @Test
    public void groupFlagFallsBackToTheFirstServerFlag() {
        Model.Catalog catalog = new Model.Catalog();
        catalog.subs.add(sub(0, "Panel"));
        catalog.servers.add(server(0, 0, "vless", DE + " Berlin", "a.example"));
        assertEquals(DE, build(catalog, 0, NONE, null, "", false).sections.get(0).flag);
    }
}
