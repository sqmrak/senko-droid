/* the profiles only senko-core (xray) carries: they parse from links and json, are listed as runnable
   only while the core can load, survive the saved catalog, and are written into the core's config */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "third_party/cJSON.h"
#include "core/b64.h"
#include "core/config.h"
#include "core/senko_core_config.h"
#include "core/store.h"
#include "core/vlenc_conf.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); ++failures; } } while (0)

#define UUID "11111111-1111-4111-8111-111111111111"
#define PBK "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

static int parse(const char *link, vl_server_t *s) {
    return cfg_parse_link(link, s) == CFG_OK;
}

static int valid(const vl_server_t *s, char *why, size_t cap) {
    return cfg_validate_server(s, why, cap);
}

static void test_parse_and_gate(void) {
    vl_server_t s;
    char why[160];

    CHECK(parse("vless://" UUID "@a.example:443?security=tls&type=httpupgrade&path=%2Fup&host=h.example&alpn=h2%2Chttp%2F1.1#n", &s));
    CHECK(s.net == VL_NET_HTTPUPGRADE && strcmp(s.path, "/up") == 0 && strcmp(s.ws_host, "h.example") == 0);
    CHECK(strcmp(s.alpn, "h2,http/1.1") == 0);
    cfg_set_core_available(0);
    CHECK(!valid(&s, why, sizeof why) && strcmp(why, "httpupgrade transport, needs the Xray core") == 0);
    CHECK(cfg_server_needs_core(&s, why, sizeof why) == 1);
    cfg_set_core_available(1);
    CHECK(valid(&s, why, sizeof why));

    CHECK(parse("vless://" UUID "@a.example:443?security=none&type=kcp&seed=sekret&headerType=wechat-video#k", &s));
    CHECK(s.net == VL_NET_KCP && strcmp(s.kcp_seed, "sekret") == 0 && strcmp(s.kcp_header, "wechat-video") == 0);
    CHECK(valid(&s, why, sizeof why));
    cfg_set_core_available(0);
    CHECK(!valid(&s, why, sizeof why) && strstr(why, "kcp transport") != NULL);
    cfg_set_core_available(1);

    /* a vless encryption profile is pooled whole and the engine carries it without the core */
    static const char want[] = "mlkem768x25519plus.native.0rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    char link[512];
    snprintf(link, sizeof link, "vless://" UUID "@a.example:443?security=reality&type=tcp&pbk=" PBK "&sid=ab&spx=%%2Fx&encryption=%s#e", want);
    CHECK(parse(link, &s));
    CHECK(s.venc != 0 && vlenc_text(s.venc) && strcmp(vlenc_text(s.venc), want) == 0);
    CHECK(strcmp(s.spx, "/x") == 0 && strcmp(s.encryption, "none") == 0);
    CHECK(valid(&s, why, sizeof why));
    cfg_set_core_available(0);
    CHECK(valid(&s, why, sizeof why));
    cfg_set_core_available(1);

    /* any shadowsocks cipher, over a transport */
    CHECK(parse("ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206cGFzc3dvcmQ=@a.example:8388?security=tls&type=ws&path=%2Fss#s", &s));
    CHECK(s.proto == VL_PROTO_SHADOWSOCKS && s.net == VL_NET_WS && strcmp(s.encryption, "2022-blake3-aes-128-gcm") == 0);
    CHECK(valid(&s, why, sizeof why));
    cfg_set_core_available(0);
    CHECK(!valid(&s, why, sizeof why));
    cfg_set_core_available(1);

    /* trojan over grpc */
    CHECK(parse("trojan://pw@a.example:443?security=tls&type=grpc&serviceName=svc#t", &s));
    CHECK(s.net == VL_NET_GRPC && valid(&s, why, sizeof why));

    /* what even the core refuses stays refused, with the engine's own words */
    CHECK(parse("trojan://pw@a.example:443?security=reality&type=ws&pbk=" PBK "&sid=ab#t", &s));
    CHECK(!valid(&s, why, sizeof why) && strcmp(why, "trojan ws over reality") == 0);
    CHECK(parse("vless://" UUID "@a.example:443?security=tls&type=h2#h", &s));
    CHECK(!valid(&s, why, sizeof why));
    CHECK(parse("vless://" UUID "@a.example:443?security=reality&type=kcp&pbk=" PBK "&sid=ab#r", &s));
    CHECK(!valid(&s, why, sizeof why));
    CHECK(parse("vless://" UUID "@a.example:443?security=tls&type=ws&flow=xtls-rprx-vision#f", &s));
    CHECK(!valid(&s, why, sizeof why));
    CHECK(cfg_server_needs_core(&s, why, sizeof why) == 0);

    /* a plain vless tcp node needs nothing */
    CHECK(parse("vless://" UUID "@a.example:443?security=tls&type=tcp#p", &s));
    CHECK(cfg_server_needs_core(&s, why, sizeof why) == 0 && valid(&s, why, sizeof why));
}

static void test_vmess_and_json(void) {
    vl_server_t s;
    char why[160];
    cfg_set_core_available(1);
    /* v2rayn vmess json: {"ps":"K","add":"v.example","port":"8443","id":UUID,"net":"kcp","type":"srtp","path":"seed1","alpn":"h2"} */
    const char *body = "{\"ps\":\"K\",\"add\":\"v.example\",\"port\":\"8443\",\"id\":\"" UUID "\",\"net\":\"kcp\",\"type\":\"srtp\",\"path\":\"seed1\",\"tls\":\"tls\",\"alpn\":\"h2\"}";
    char b64[512];
    size_t n = 0;
    CHECK(b64_encode((const unsigned char *)body, strlen(body), b64, sizeof b64, &n) == 0);
    char link[600];
    snprintf(link, sizeof link, "vmess://%s", b64);
    CHECK(parse(link, &s));
    CHECK(s.net == VL_NET_KCP && strcmp(s.kcp_header, "srtp") == 0 && strcmp(s.kcp_seed, "seed1") == 0);
    CHECK(strcmp(s.alpn, "h2") == 0 && s.path[0] == '\0' && valid(&s, why, sizeof why));

    /* xray json: kcp, httpupgrade, tls alpn, reality spiderX and a vless encryption string */
    cfg_import_stats_t stats;
    memset(&stats, 0, sizeof stats);
    const char *cfg =
        "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"a.example\",\"port\":443,"
        "\"users\":[{\"id\":\"" UUID "\",\"encryption\":\"mlkem768x25519plus.native.0rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}]}]},"
        "\"streamSettings\":{\"network\":\"httpupgrade\",\"security\":\"tls\","
        "\"tlsSettings\":{\"serverName\":\"a.example\",\"alpn\":[\"h2\",\"http/1.1\"]},"
        "\"httpupgradeSettings\":{\"path\":\"/u\",\"host\":\"h.example\"}}}]}";
    vl_server_t out[4];
    size_t count = 0;
    CHECK(cfg_parse_subscription_ex(cfg, strlen(cfg), out, 4, &count, &stats) == CFG_OK && count == 1);
    if (count == 1) {
        CHECK(out[0].net == VL_NET_HTTPUPGRADE && strcmp(out[0].path, "/u") == 0 && strcmp(out[0].ws_host, "h.example") == 0);
        CHECK(strcmp(out[0].alpn, "h2,http/1.1") == 0);
        CHECK(out[0].venc != 0 && strcmp(vlenc_text(out[0].venc), "mlkem768x25519plus.native.0rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") == 0);
    }
}

static void test_saved_catalog(void) {
    static store_t st, back;
    static char saved[65536];
    size_t len = 0;
    cfg_set_core_available(1);
    store_init(&st);
    const char *links[] = {
        "vless://" UUID "@a.example:443?security=tls&type=httpupgrade&path=%2Fup&host=h.example&alpn=h2#a",
        "vless://" UUID "@b.example:443?security=none&type=kcp&seed=sekret&headerType=srtp#b",
        "vless://" UUID "@c.example:443?security=reality&type=tcp&pbk=" PBK "&sid=ab&spx=%2Fz&encryption=mlkem768x25519plus.native.0rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA#c",
        "trojan://pw@d.example:443?security=tls&type=grpc&serviceName=svc&alpn=h2#d",
        "ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206cGFzc3dvcmQ=@e.example:8388?security=tls&type=ws&path=%2Fss#e",
    };
    for (size_t i = 0; i < sizeof links / sizeof links[0]; ++i)
        CHECK(store_add_manual(&st, links[i], NULL) == STORE_OK);
    CHECK(st.n == 5);
    CHECK(store_serialize(&st, saved, sizeof saved, &len) == STORE_OK);
    CHECK(store_deserialize(&back, saved, len) == STORE_OK && back.n == 5);
    for (size_t i = 0; i < 5 && i < back.n; ++i) {
        const vl_server_t *a = &st.servers[i], *b = &back.servers[i];
        CHECK(a->proto == b->proto && a->net == b->net && a->security == b->security);
        CHECK(strcmp(a->alpn, b->alpn) == 0 && strcmp(a->spx, b->spx) == 0);
        CHECK(strcmp(a->kcp_seed, b->kcp_seed) == 0 && strcmp(a->kcp_header, b->kcp_header) == 0);
        CHECK(a->venc == b->venc && strcmp(a->path, b->path) == 0);
        CHECK(strcmp(a->encryption, b->encryption) == 0 && strcmp(a->ws_host, b->ws_host) == 0);
    }
    CHECK(back.servers[2].venc != 0 && strcmp(vlenc_text(back.servers[2].venc), "mlkem768x25519plus.native.0rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") == 0);
    CHECK(strcmp(back.servers[4].encryption, "2022-blake3-aes-128-gcm") == 0 && back.servers[4].net == VL_NET_WS);
}

static char out[64 * 1024];

/* the xhttp extra block of a whitelist panel: xmux, padding and placement. the engine cannot carry it,
   the core gets it verbatim, and a saved catalog keeps it */
static void test_xhttp_extra(void) {
    static const char extra[] =
        "{\"xmux\":{\"maxConnections\":4,\"cMaxReuseTimes\":\"1024-2048\"},"
        "\"xPaddingBytes\":\"50-150\",\"sessionPlacement\":\"query\",\"uplinkHTTPMethod\":\"GET\"}";
    char cfg[1536], why[160];
    vl_server_t nodes[2];
    size_t count = 0;
    cfg_import_stats_t stats;
    memset(&stats, 0, sizeof stats);
    snprintf(cfg, sizeof cfg,
             "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"a.example\",\"port\":443,"
             "\"users\":[{\"id\":\"" UUID "\"}]}]},\"streamSettings\":{\"network\":\"xhttp\",\"security\":\"tls\","
             "\"tlsSettings\":{\"serverName\":\"a.example\"},"
             "\"xhttpSettings\":{\"mode\":\"packet-up\",\"path\":\"/p\",\"host\":\"h.example\",\"extra\":%s}}}]}", extra);
    cfg_set_core_available(1);
    CHECK(cfg_parse_subscription_ex(cfg, strlen(cfg), nodes, 2, &count, &stats) == CFG_OK && count == 1);
    if (count != 1) return;
    CHECK(strcmp(nodes[0].xhttp_extra, extra) == 0);
    CHECK(valid(&nodes[0], why, sizeof why));
    cfg_set_core_available(0);
    CHECK(!valid(&nodes[0], why, sizeof why) && strstr(why, "xhttp extra") != NULL && strstr(why, "Xray core") != NULL);
    cfg_set_core_available(1);

    /* the same node through a share link, written out and read back */
    static store_t st, back;
    static char saved[65536];
    size_t len = 0;
    store_init(&st);
    CHECK(store_add_manual(&st, "vless://" UUID "@a.example:443?security=tls&type=xhttp&mode=packet-up&path=%2Fp&host=h.example&extra=%7B%22xPaddingBytes%22%3A%2250-150%22%7D#x", NULL) == STORE_OK);
    CHECK(st.n == 1 && strcmp(st.servers[0].xhttp_extra, "{\"xPaddingBytes\":\"50-150\"}") == 0);
    CHECK(store_serialize(&st, saved, sizeof saved, &len) == STORE_OK);
    CHECK(store_deserialize(&back, saved, len) == STORE_OK && back.n == 1);
    CHECK(strcmp(back.servers[0].xhttp_extra, st.servers[0].xhttp_extra) == 0);

    /* the core sees the object inside xhttpSettings */
    CHECK(senko_core_config_render_rules(&nodes[0], "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) == 0);
    cJSON *root = cJSON_Parse(out);
    cJSON *ob = root ? cJSON_GetArrayItem(cJSON_GetObjectItem(root, "outbounds"), 0) : NULL;
    cJSON *xh = ob ? cJSON_GetObjectItem(cJSON_GetObjectItem(ob, "streamSettings"), "xhttpSettings") : NULL;
    CHECK(xh && cJSON_GetObjectItem(cJSON_GetObjectItem(xh, "extra"), "xPaddingBytes") != NULL);
    cJSON_Delete(root);

    /* a block that is not an object, or is too long, refuses the node and says why */
    why[0] = '\0';
    CHECK(cfg_parse_link_ex("vless://" UUID "@a.example:443?security=tls&type=xhttp&extra=notjson#x", nodes, why, sizeof why) == CFG_ERR_UNSUPPORTED &&
          strstr(why, "xhttp extra") != NULL);
    char longlink[2600];
    int n = snprintf(longlink, sizeof longlink, "vless://" UUID "@a.example:443?security=tls&type=xhttp&extra=%%7B%%22k%%22%%3A%%22");
    for (int i = 0; i < 1100; ++i) longlink[n++] = 'a';
    snprintf(longlink + n, sizeof longlink - (size_t)n, "%%22%%7D#x");
    why[0] = '\0';
    CHECK(cfg_parse_link_ex(longlink, nodes, why, sizeof why) == CFG_ERR_UNSUPPORTED && strstr(why, "too long") != NULL);
    cfg_set_core_available(0);
}

static void test_render(void) {
    vl_server_t s;
    cfg_set_core_available(1);
    CHECK(parse("vless://" UUID "@a.example:443?security=tls&type=httpupgrade&path=%2Fup&host=h.example&alpn=h2%2Chttp%2F1.1#n", &s));
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) == 0);
    CHECK(strstr(out, "\"network\":\"httpupgrade\"") != NULL);
    CHECK(strstr(out, "\"httpupgradeSettings\":{\"path\":\"/up\",\"host\":\"h.example\"}") != NULL);
    CHECK(strstr(out, "\"alpn\":[\"h2\",\"http/1.1\"]") != NULL);

    /* mkcp: the seed mask first, the header mask after it, in the udp masks that replaced both fields */
    CHECK(parse("vless://" UUID "@a.example:443?security=none&type=kcp&seed=sekret&headerType=wechat-video#k", &s));
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) == 0);
    CHECK(strstr(out, "\"network\":\"kcp\"") != NULL);
    const char *seed = strstr(out, "\"value\":\"sekret\"");
    const char *header = strstr(out, "\"header\":\"wechat\"");
    CHECK(seed && header && seed < header);
    CHECK(parse("vless://" UUID "@a.example:443?security=none&type=kcp#k", &s));
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) == 0);
    CHECK(strstr(out, "\"type\":\"mkcp-legacy\",\"settings\":{}") != NULL);
    CHECK(parse("vless://" UUID "@a.example:443?security=none&type=kcp&headerType=bogus#k", &s));
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) != 0);

    CHECK(parse("vless://" UUID "@c.example:443?security=reality&type=tcp&pbk=" PBK "&sid=ab&spx=%2Fz&encryption=mlkem768x25519plus.native.0rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA#c", &s));
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) == 0);
    CHECK(strstr(out, "\"encryption\":\"mlkem768x25519plus.native.0rtt.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"") != NULL);
    CHECK(strstr(out, "\"spiderX\":\"/z\"") != NULL);

    CHECK(parse("ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206cGFzc3dvcmQ=@e.example:8388?security=tls&type=ws&path=%2Fss#e", &s));
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) == 0);
    CHECK(strstr(out, "\"method\":\"2022-blake3-aes-128-gcm\"") != NULL && strstr(out, "\"wsSettings\"") != NULL);

    CHECK(parse("vless://" UUID "@a.example:443?security=tls&type=h2#h", &s));
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) != 0);
}

int main(void) {
    test_parse_and_gate();
    test_vmess_and_json();
    test_saved_catalog();
    test_render();
    test_xhttp_extra();
    cfg_set_core_available(0);
    if (failures) return 1;
    printf("test_config_core ok\n");
    return 0;
}
