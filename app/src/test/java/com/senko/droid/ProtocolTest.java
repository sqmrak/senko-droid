package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProtocolTest {
    @Test
    public void serverLineCarriesEveryField() {
        Model.Server server = Protocol.parseServer(
                "SRV 3 1 2 vless tcp reality 1 example.com 443 7 Frankfurt fast node");
        assertNotNull(server);
        assertEquals(3, server.index);
        assertTrue(server.selected);
        assertEquals(2, server.group);
        assertEquals("vless", server.proto);
        assertEquals("tcp", server.net);
        assertEquals("reality", server.security);
        assertTrue(server.supported);
        assertEquals("example.com", server.host);
        assertEquals(443, server.port);
        assertEquals("the config of a json feed", 7, server.profile);
        assertEquals("the remark keeps its spaces", "Frankfurt fast node", server.remark);
    }

    @Test
    public void panelHeadersLandOnTheSubscription() {
        Model.Catalog catalog = Protocol.parseCatalog(Arrays.asList(
                "SUB 2 panel https://panel.example/feed",
                "SUBPANEL 2 6 1796083800 https%3A%2F%2Fpanel.example%2Fme Telegram%3A%20%40bot"));
        Model.Sub sub = catalog.sub(2);
        assertNotNull(sub);
        assertEquals(6, sub.updateHours);
        assertEquals(1796083800L, sub.refillDate);
        assertEquals("https://panel.example/me", sub.webPageUrl);
        assertEquals("Telegram: @bot", sub.announce);
        catalog = Protocol.parseCatalog(Arrays.asList(
                "SUB 2 panel https://panel.example/feed", "SUBPANEL 2 0 0 - -"));
        assertEquals(0, catalog.sub(2).updateHours);
        assertEquals("", catalog.sub(2).webPageUrl);
        assertEquals("", catalog.sub(2).announce);
    }

    @Test
    public void unsupportedAndUnselectedServer() {
        Model.Server server = Protocol.parseServer("SRV 0 0 0 trojan ws tls 0 h.example 8443 0 x");
        assertNotNull(server);
        assertFalse(server.selected);
        assertFalse(server.supported);
    }

    @Test
    public void malformedServerLinesAreRefused() {
        assertNull(Protocol.parseServer(null));
        assertNull(Protocol.parseServer("SUB 0 name url"));
        assertNull(Protocol.parseServer("SRV 1 2 3"));
        assertNull(Protocol.parseServer("SRV x 0 0 vless tcp tls 1 h 443 0 r"));
        assertNull(Protocol.parseServer("SRV 0 0 0 vless tcp tls 1 h port 0 r"));
    }

    @Test
    public void subscriptionNameLosesItsUnderscores() {
        Model.Sub sub = Protocol.parseSubscription("SUB 1 My_panel https://panel.example/sub/abc");
        assertNotNull(sub);
        assertEquals(1, sub.index);
        assertEquals("My panel", sub.name);
        assertEquals("https://panel.example/sub/abc", sub.url);
        assertNull(Protocol.parseSubscription("SUB 1 name"));
        assertNull(Protocol.parseSubscription("SUB x name url"));
    }

    @Test
    public void stateWithAndWithoutUptime() {
        Protocol.State idle = Protocol.parseState("STATE idle");
        assertNotNull(idle);
        assertEquals("idle", idle.name);
        assertEquals(0, idle.uptime);
        Protocol.State up = Protocol.parseState("STATE connected 125");
        assertNotNull(up);
        assertEquals(125, up.uptime);
        assertNull(Protocol.parseState("STATE"));
        assertNull(Protocol.parseState("STATE connected soon"));
        assertNull(Protocol.parseState("STATE a b c"));
        assertNull(Protocol.parseState("PONG 1 2"));
    }

    @Test
    public void trafficAndPong() {
        long[] traffic = Protocol.parseTraffic("STAT 4294967300 8589934600");
        assertNotNull(traffic);
        assertEquals("a count past 32 bits survives", 4294967300L, traffic[0]);
        assertEquals(8589934600L, traffic[1]);
        assertNull(Protocol.parseTraffic("STAT 1"));
        assertNull(Protocol.parseTraffic("STAT a b"));
        int[] pong = Protocol.parsePong("PONG 4 -1");
        assertNotNull(pong);
        assertEquals(4, pong[0]);
        assertEquals(-1, pong[1]);
        assertNull(Protocol.parsePong("PONG 4"));
    }

    @Test
    public void okAndErrorLines() {
        assertTrue(Protocol.isOk("OK"));
        assertTrue(Protocol.isOk("OK server added"));
        assertFalse(Protocol.isOk("OKAY"));
        assertTrue(Protocol.isError("ERR"));
        assertTrue(Protocol.isError("ERR tunnel: the tunnel could not start"));
        assertFalse(Protocol.isError("ERROR"));
        assertEquals("tunnel: x y", Protocol.message("ERR tunnel: x y"));
        assertEquals("", Protocol.message("OK"));
        assertEquals("", Protocol.message(null));
    }

    @Test
    public void settingsReplyKeepsKeysAndValues() {
        List<String> lines = new ArrayList<String>(Arrays.asList(
                "SET dns_upstream 8.8.8.8", "SET failover 1", "SET broken", "STATE idle", "SETEND"));
        List<String[]> pairs = Protocol.parseSettings(lines);
        assertEquals(2, pairs.size());
        assertEquals("dns_upstream", pairs.get(0)[0]);
        assertEquals("8.8.8.8", pairs.get(0)[1]);
        assertEquals("1", pairs.get(1)[1]);
    }

    @Test
    public void onlyConnectingAndConnectedHoldTheSession() {
        assertTrue(Protocol.isActive("connecting"));
        assertTrue(Protocol.isActive("connected"));
        assertFalse(Protocol.isActive("idle"));
        assertFalse(Protocol.isActive("error"));
        assertFalse(Protocol.isActive(null));
    }
}
