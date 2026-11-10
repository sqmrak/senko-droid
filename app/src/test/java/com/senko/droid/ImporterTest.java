package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ImporterTest {
    private static final Importer.Amnezia YES = new Importer.Amnezia() {
        @Override
        public boolean looksLikeBundle(byte[] data) {
            return new String(data).startsWith("vpn://");
        }
    };

    @Test
    public void emptyTextIsNothing() {
        assertEquals(Importer.Kind.EMPTY, Importer.classify(null, null));
        assertEquals(Importer.Kind.EMPTY, Importer.classify("  \n ", null));
        assertEquals(Importer.Kind.EMPTY, Importer.classify("﻿ ", null));
    }

    @Test
    public void awgConfigNeedsBothSections() {
        assertEquals(Importer.Kind.AWG_CONF, Importer.classify("[Interface]\nPrivateKey=x\n[Peer]\nPublicKey=y", null));
        assertEquals(Importer.Kind.AWG_CONF, Importer.classify("[INTERFACE]\n[peer]", null));
        assertEquals(Importer.Kind.CONTENT, Importer.classify("[Interface]\nPrivateKey=x", null));
    }

    @Test
    public void amneziaBundleGoesBeforeLinks() {
        assertEquals(Importer.Kind.AMNEZIA_BUNDLE, Importer.classify("vpn://AAAA", YES));
        assertEquals(Importer.Kind.CONTENT, Importer.classify("vpn://AAAA", null));
    }

    @Test
    public void serverLinks() {
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("vless://id@host:443?security=tls#n", null));
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("vmess://abc", null));
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("trojan://p@h:1", null));
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("ss://abc#x", null));
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("socks5://u:p@h:1080", null));
    }

    @Test
    public void anHttpAddressWithCredentialsOrAFragmentOrABarePortIsAServer() {
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("https://user:pass@h.example:443", null));
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("https://h.example/x#name", null));
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("https://h.example:8443", null));
        assertEquals(Importer.Kind.SERVER_LINK, Importer.classify("https://h.example:8443/", null));
    }

    @Test
    public void subscriptionAddresses() {
        assertEquals(Importer.Kind.SUBSCRIPTION, Importer.classify("https://panel.example/sub/abc", null));
        assertEquals(Importer.Kind.INSECURE_SUBSCRIPTION, Importer.classify("http://panel.example/sub/abc", null));
        assertEquals("a port with a path is a feed", Importer.Kind.SUBSCRIPTION,
                Importer.classify("https://panel.example:8443/sub/abc", null));
    }

    @Test
    public void severalLinesAreContent() {
        assertEquals(Importer.Kind.CONTENT, Importer.classify("vless://a@h:1\nvless://b@h:2", null));
        assertEquals(Importer.Kind.CONTENT, Importer.classify("proxies:\n  - name: x", null));
        assertEquals(Importer.Kind.CONTENT, Importer.classify("c3Rhbmd...", null));
        assertFalse(Importer.looksLikeSubscriptionUrl("https://a\nhttps://b"));
    }

    @Test
    public void filesFollowTheirExtensionAndContent() throws Exception {
        assertEquals(Importer.Kind.EMPTY, Importer.classifyFile("a.txt", new byte[0], YES));
        assertEquals(Importer.Kind.AMNEZIA_BUNDLE, Importer.classifyFile("x.vpn", "bare".getBytes(), YES));
        assertEquals(Importer.Kind.AMNEZIA_BUNDLE, Importer.classifyFile("x.txt", "vpn://abc".getBytes(), YES));
        assertEquals(Importer.Kind.AWG_CONF,
                Importer.classifyFile("a.conf", "[Interface]\n[Peer]\n".getBytes(), YES));
        assertEquals(Importer.Kind.SUBSCRIPTION,
                Importer.classifyFile("link.txt", "https://panel.example/s\n".getBytes(), YES));
        assertEquals("a server link in a file is content for the engine", Importer.Kind.CONTENT,
                Importer.classifyFile("link.txt", "vless://a@h:1\n".getBytes(), YES));
        assertEquals(Importer.Kind.CONTENT, Importer.classifyFile("sub.yaml", "proxies: []".getBytes(), YES));
        assertEquals(Importer.Kind.CONTENT, Importer.classifyFile("blob.bin", new byte[] { 1, 2, (byte) 0xff }, null));
    }

    @Test
    public void trailingNumberOfAnOkReply() {
        assertEquals(3, Importer.trailingInt("OK subscription 3"));
        assertEquals(-1, Importer.trailingInt("OK added"));
        assertEquals(-1, Importer.trailingInt(null));
        assertTrue(Importer.looksLikeSingleServerLink("vless://x"));
    }
}
