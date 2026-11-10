package com.senko.droid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class NamesTest {
    // 🇩🇪 and 🇺🇸 as the engine and the panels write them
    private static final String DE = "🇩🇪";
    private static final String US = "🇺🇸";

    private static Model.Server server(String proto, String net, String security, boolean supported) {
        Model.Server server = new Model.Server();
        server.proto = proto;
        server.net = net;
        server.security = security;
        server.supported = supported;
        server.host = "h.example";
        return server;
    }

    @Test
    public void flagCodeFromTheRemark() {
        assertEquals("de", Names.flagCode(DE + " Frankfurt"));
        assertEquals("us", Names.flagCode("Dallas " + US));
        assertNull(Names.flagCode("Frankfurt"));
        assertNull(Names.flagCode(""));
        assertNull(Names.flagCode(null));
        assertNull("a lone regional indicator is not a flag", Names.flagCode("🇩 x"));
    }

    @Test
    public void flagCodeSurvivesPercentEscapes() {
        assertEquals("de", Names.flagCode("%F0%9F%87%A9%F0%9F%87%AA%20Berlin"));
    }

    @Test
    public void displayNameDropsTheFlag() {
        assertEquals("Frankfurt", Names.displayName(DE + " Frankfurt"));
        assertEquals("Frankfurt am Main", Names.displayName("Frankfurt " + DE + " am Main").replace("  ", " "));
    }

    @Test
    public void displayNameDropsTechnicalSuffixes() {
        assertEquals("Frankfurt", Names.displayName("Frankfurt (grpc)"));
        assertEquals("Frankfurt", Names.displayName("Frankfurt (vless/tcp/reality)"));
        assertEquals("Frankfurt", Names.displayName("Frankfurt (ws) (tls)"));
        assertEquals("a suffix with other words stays", "Frankfurt (backup node)",
                Names.displayName("Frankfurt (backup node)"));
        assertEquals("(grpc)", Names.displayName("(grpc)"));
    }

    @Test
    public void displayNameDropsATrailingAddress() {
        assertEquals("Germany", Names.displayName("Germany 203.0.113.7"));
        assertEquals("Germany", Names.displayName("Germany 203.0.113.7:443"));
        assertEquals("Germany 203.0.113", Names.displayName("Germany 203.0.113"));
        assertEquals("Germany 999.1.1.1x", Names.displayName("Germany 999.1.1.1x"));
        assertEquals("Germany 300.1.1.1", Names.displayName("Germany 300.1.1.1"));
    }

    @Test
    public void displayNameOfNothing() {
        assertEquals("", Names.displayName(null));
        assertEquals("", Names.displayName(""));
        assertEquals("", Names.displayName(DE));
    }

    @Test
    public void protocolLabelsPerProtocol() {
        assertEquals("vless/tcp/reality", Names.protocolLabel(server("vless", "tcp", "reality", true)));
        assertEquals("vmess/ws/tls", Names.protocolLabel(server("vmess", "ws", "tls", true)));
        assertEquals("vless/tcp/none", Names.protocolLabel(server("vless", "", "", true)));
        assertEquals("trojan/tcp/tls", Names.protocolLabel(server("trojan", "", "", true)));
        assertEquals("ss/aead", Names.protocolLabel(server("ss", "", "", true)));
        assertEquals("ss/chacha20-poly1305", Names.protocolLabel(server("shadowsocks", "tcp", "chacha20-poly1305", true)));
        assertEquals("hysteria2/quic", Names.protocolLabel(server("hysteria2", "udp", "tls", true)));
        assertEquals("shadowsocks plugin obfs", Names.protocolLabel(server("shadowsocks-plugin-obfs", "", "", false)));
        assertEquals("unknown", Names.protocolLabel(server("", "", "", true)));
    }

    @Test
    public void titleFallsBackToTheHost() {
        Model.Server server = server("vless", "tcp", "none", true);
        server.remark = DE;
        assertEquals("h.example", Names.title(server));
        server.remark = DE + " (tcp)";
        assertEquals("a leading bracket is kept, as on ios", "(tcp)", Names.title(server));
        server.remark = DE + " Berlin";
        assertEquals("Berlin", Names.title(server));
    }
}
