#define _GNU_SOURCE
#include <assert.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "third_party/cJSON.h"
#include "core/config.h"
#include "core/rules.h"
#include "core/senko_core_config.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); ++failures; } } while (0)

static char out[64 * 1024];
static ruleset_t rules;

static vl_server_t server(vl_proto_t proto, vl_net_t net, vl_sec_t sec) {
    vl_server_t s;
    memset(&s, 0, sizeof s);
    s.proto = proto;
    s.net = net;
    s.security = sec;
    s.port = 443;
    snprintf(s.host, sizeof s.host, "node.example.com");
    snprintf(s.uuid, sizeof s.uuid, "11111111-1111-4111-8111-111111111111");
    snprintf(s.sni, sizeof s.sni, "cdn.example");
    return s;
}

static cJSON *render(const vl_server_t *s, const ruleset_t *r, const char *ca) {
    if (senko_core_config_render_rules(s, "203.0.113.7", "tun0", 1500, r, NULL, ca, out, sizeof out) != 0) return NULL;
    return cJSON_Parse(out);
}

static cJSON *get(cJSON *node, const char *key) { return cJSON_GetObjectItemCaseSensitive(node, key); }
static const char *text(cJSON *node, const char *key) {
    cJSON *v = get(node, key);
    return cJSON_IsString(v) ? v->valuestring : "";
}
static cJSON *first_outbound(cJSON *root) { return cJSON_GetArrayItem(get(root, "outbounds"), 0); }

static void test_frame(void) {
    vl_server_t s = server(VL_PROTO_VLESS, VL_NET_TCP, VL_SEC_REALITY);
    snprintf(s.pbk, sizeof s.pbk, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    snprintf(s.sid, sizeof s.sid, "ab12");
    snprintf(s.flow, sizeof s.flow, "xtls-rprx-vision");
    cJSON *root = render(&s, NULL, NULL);
    CHECK(root != NULL);
    if (!root) return;
    CHECK(strcmp(text(get(root, "log"), "loglevel"), "warning") == 0);
    /* the bytes of the tun inbound are counted under this tag, see xraycore/main.go */
    cJSON *inbound = cJSON_GetArrayItem(get(root, "inbounds"), 0);
    CHECK(strcmp(text(inbound, "tag"), "senko-tun") == 0 && strcmp(text(inbound, "protocol"), "tun") == 0);
    CHECK(get(get(inbound, "settings"), "mtu")->valueint == 1500);
    CHECK(cJSON_IsObject(get(root, "stats")));
    cJSON *system = get(get(root, "policy"), "system");
    CHECK(cJSON_IsTrue(get(system, "statsInboundUplink")) && cJSON_IsTrue(get(system, "statsInboundDownlink")));
    CHECK(cJSON_GetArraySize(get(root, "outbounds")) == 3);
    cJSON *proxy = first_outbound(root);
    CHECK(strcmp(text(proxy, "tag"), "senko-out") == 0 && strcmp(text(proxy, "protocol"), "vless") == 0);
    cJSON *settings = get(proxy, "settings");
    /* the address is the resolved one, the host stays out of the dial */
    CHECK(strcmp(text(settings, "address"), "203.0.113.7") == 0);
    CHECK(strcmp(text(settings, "encryption"), "none") == 0 && strcmp(text(settings, "flow"), "xtls-rprx-vision") == 0);
    cJSON *stream = get(proxy, "streamSettings");
    CHECK(strcmp(text(stream, "security"), "reality") == 0);
    CHECK(strcmp(text(get(stream, "realitySettings"), "serverName"), "cdn.example") == 0);
    CHECK(strcmp(text(get(stream, "realitySettings"), "fingerprint"), "chrome") == 0);
    cJSON_Delete(root);

    snprintf(s.flow, sizeof s.flow, "xtls-rprx-vision-udp443");
    root = render(&s, NULL, NULL);
    CHECK(root != NULL);
    if (root) {
        CHECK(strcmp(text(get(first_outbound(root), "settings"), "flow"),
                     "xtls-rprx-vision-udp443") == 0);
        cJSON_Delete(root);
    }
}

static void test_transports(void) {
    vl_server_t ws = server(VL_PROTO_VLESS, VL_NET_WS, VL_SEC_TLS);
    snprintf(ws.path, sizeof ws.path, "/socket");
    snprintf(ws.ws_host, sizeof ws.ws_host, "front.example");
    cJSON *root = render(&ws, NULL, "/data/data/x/files/cacert.pem");
    CHECK(root != NULL);
    if (root) {
        cJSON *stream = get(first_outbound(root), "streamSettings");
        CHECK(strcmp(text(stream, "network"), "ws") == 0);
        CHECK(strcmp(text(get(stream, "wsSettings"), "path"), "/socket") == 0);
        CHECK(strcmp(text(get(stream, "wsSettings"), "host"), "front.example") == 0);
        /* the apk's roots replace the system store, which android before 7.1.1 has out of date */
        cJSON *tls = get(stream, "tlsSettings");
        CHECK(cJSON_IsTrue(get(tls, "disableSystemRoot")));
        CHECK(strstr(text(cJSON_GetArrayItem(get(tls, "certificates"), 0), "certificateFile"), "cacert.pem") != NULL);
        cJSON_Delete(root);
    }
    root = render(&ws, NULL, NULL);
    CHECK(root && get(get(get(first_outbound(root), "streamSettings"), "tlsSettings"), "disableSystemRoot") == NULL);
    cJSON_Delete(root);

    vl_server_t grpc = server(VL_PROTO_VLESS, VL_NET_GRPC, VL_SEC_TLS);
    snprintf(grpc.path, sizeof grpc.path, "/svc/Tun");
    root = render(&grpc, NULL, NULL);
    CHECK(root && strcmp(text(get(get(first_outbound(root), "streamSettings"), "grpcSettings"), "serviceName"), "svc") == 0);
    cJSON_Delete(root);

    vl_server_t xh = server(VL_PROTO_VLESS, VL_NET_XHTTP, VL_SEC_TLS);
    snprintf(xh.mode, sizeof xh.mode, "stream-up");
    root = render(&xh, NULL, NULL);
    CHECK(root && strcmp(text(get(get(first_outbound(root), "streamSettings"), "xhttpSettings"), "mode"), "stream-up") == 0);
    cJSON_Delete(root);

    vl_server_t http = server(VL_PROTO_VLESS, VL_NET_HTTP, VL_SEC_TLS);
    CHECK(senko_core_config_render_rules(&http, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) != 0);
}

static void test_protocols(void) {
    vl_server_t vmess = server(VL_PROTO_VMESS, VL_NET_TCP, VL_SEC_TLS);
    cJSON *root = render(&vmess, NULL, NULL);
    CHECK(root && strcmp(text(first_outbound(root), "protocol"), "vmess") == 0);
    CHECK(root && strcmp(text(get(first_outbound(root), "settings"), "security"), "auto") == 0);
    cJSON_Delete(root);

    vl_server_t trojan = server(VL_PROTO_TROJAN, VL_NET_TCP, VL_SEC_TLS);
    snprintf(trojan.pass, sizeof trojan.pass, "p\"w\\d");
    root = render(&trojan, NULL, NULL);
    /* a password with quotes and backslashes survives the json round trip */
    CHECK(root && strcmp(text(cJSON_GetArrayItem(get(get(first_outbound(root), "settings"), "servers"), 0), "password"), "p\"w\\d") == 0);
    cJSON_Delete(root);

    vl_server_t ss = server(VL_PROTO_SHADOWSOCKS, VL_NET_TCP, VL_SEC_NONE);
    snprintf(ss.encryption, sizeof ss.encryption, "aes-256-gcm");
    snprintf(ss.pass, sizeof ss.pass, "secret");
    root = render(&ss, NULL, NULL);
    CHECK(root && strcmp(text(cJSON_GetArrayItem(get(get(first_outbound(root), "settings"), "servers"), 0), "method"), "aes-256-gcm") == 0);
    cJSON_Delete(root);

    vl_server_t hy = server(VL_PROTO_HYSTERIA2, VL_NET_UNKNOWN, VL_SEC_TLS);
    snprintf(hy.pass, sizeof hy.pass, "auth");
    snprintf(hy.obfs, sizeof hy.obfs, "salamander");
    snprintf(hy.obfs_password, sizeof hy.obfs_password, "mask");
    snprintf(hy.port_hop, sizeof hy.port_hop, "5000-6000");
    snprintf(hy.pin_sha256, sizeof hy.pin_sha256, "ab:cd");
    root = render(&hy, NULL, NULL);
    CHECK(root != NULL);
    if (root) {
        cJSON *proxy = first_outbound(root);
        CHECK(strcmp(text(proxy, "protocol"), "hysteria") == 0);
        cJSON *stream = get(proxy, "streamSettings");
        CHECK(strcmp(text(get(stream, "hysteriaSettings"), "auth"), "auth") == 0);
        CHECK(strcmp(text(get(stream, "tlsSettings"), "pinnedPeerCertSha256"), "ab:cd") == 0);
        cJSON *mask = get(get(stream, "finalmask"), "udp");
        CHECK(strcmp(text(cJSON_GetArrayItem(mask, 0), "type"), "salamander") == 0);
        CHECK(strcmp(text(get(get(get(stream, "finalmask"), "quicParams"), "udpHop"), "ports"), "5000-6000") == 0);
        cJSON_Delete(root);
    }
}

static void add_rule(rule_type_t type, rule_action_t action, const char *value) {
    rule_t *r = &rules.entries[rules.count++];
    memset(r, 0, sizeof *r);
    r->type = type;
    r->action = action;
    snprintf(r->value, sizeof r->value, "%s", value);
}

static void test_rules(void) {
    vl_server_t s = server(VL_PROTO_VLESS, VL_NET_TCP, VL_SEC_TLS);
    memset(&rules, 0, sizeof rules);
    ruleset_init(&rules);
    add_rule(RULE_TYPE_DOMAIN_SUFFIX, RULE_ACTION_DIRECT, "example.ru");
    add_rule(RULE_TYPE_DOMAIN_FULL, RULE_ACTION_BLOCK, "ads.example.com");
    add_rule(RULE_TYPE_IP_CIDR, RULE_ACTION_DIRECT, "10.1.0.0/16");
    add_rule(RULE_TYPE_DOMAIN_KEYWORD, RULE_ACTION_PROXY, "video");
    add_rule(RULE_TYPE_GEOIP, RULE_ACTION_DIRECT, "private");
    /* an app rule is the vpn service's business and costs the core nothing */
    add_rule(RULE_TYPE_APP, RULE_ACTION_DIRECT, "com.example.app");
    CHECK(senko_core_config_unsupported(&rules, NULL) == 0);
    cJSON *root = render(&s, &rules, NULL);
    CHECK(root != NULL);
    if (root) {
        cJSON *list = get(get(root, "routing"), "rules");
        /* blocks first, then direct, then proxy, as the engine's own order */
        CHECK(cJSON_GetArraySize(list) == 5);
        CHECK(strcmp(text(cJSON_GetArrayItem(list, 0), "outboundTag"), "block") == 0);
        CHECK(strcmp(text(cJSON_GetArrayItem(list, 1), "outboundTag"), "direct") == 0);
        cJSON *domains = get(cJSON_GetArrayItem(list, 0), "domain");
        CHECK(domains && strcmp(cJSON_GetArrayItem(domains, 0)->valuestring, "full:ads.example.com") == 0);
        CHECK(strcmp(text(cJSON_GetArrayItem(list, 4), "outboundTag"), "senko-out") == 0);
        cJSON_Delete(root);
    }

    /* a geo set the core has no dat for is lost: the engine's backend must take the profile */
    add_rule(RULE_TYPE_GEOSITE, RULE_ACTION_DIRECT, "google");
    CHECK(senko_core_config_unsupported(&rules, NULL) == 1);
    rules.fallback = RULE_ACTION_DIRECT;
    root = render(&s, &rules, NULL);
    /* and then no catch-all direct rule is written, so a skipped exception never leaks traffic direct */
    CHECK(root != NULL);
    if (root) {
        cJSON *list = get(get(root, "routing"), "rules");
        CHECK(cJSON_GetArraySize(list) == 5);
        cJSON_Delete(root);
    }
}

static void test_geo_dat(void) {
    char dir[] = "/tmp/senko-core-geo-XXXXXX";
    char path[300];
    if (!mkdtemp(dir)) { ++failures; return; }
    vl_server_t s = server(VL_PROTO_VLESS, VL_NET_TCP, VL_SEC_TLS);
    memset(&rules, 0, sizeof rules);
    ruleset_init(&rules);
    add_rule(RULE_TYPE_GEOSITE, RULE_ACTION_DIRECT, "category-ru");
    add_rule(RULE_TYPE_GEOIP, RULE_ACTION_DIRECT, "ru");
    add_rule(RULE_TYPE_GEOSITE, RULE_ACTION_BLOCK, "category-ads-all");
    rules.fallback = RULE_ACTION_PROXY;
    /* no dat: every geo rule is lost and the profile goes to the engine's own backend */
    CHECK(senko_core_config_unsupported(&rules, dir) == 3);
    snprintf(path, sizeof path, "%s/geosite.dat", dir);
    FILE *f = fopen(path, "wb");
    CHECK(f != NULL);
    if (f) { fputs("x", f); fclose(f); }
    CHECK(senko_core_config_unsupported(&rules, dir) == 1);
    snprintf(path, sizeof path, "%s/geoip.dat", dir);
    f = fopen(path, "wb");
    if (f) { fputs("x", f); fclose(f); }
    CHECK(senko_core_config_unsupported(&rules, dir) == 0);
    /* the sets are read by the core from the dat, whether or not the engine bound its own copies */
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, &rules, dir, NULL, out, sizeof out) == 0);
    cJSON *root = cJSON_Parse(out);
    CHECK(root != NULL);
    if (root) {
        cJSON *list = get(get(root, "routing"), "rules");
        CHECK(cJSON_GetArraySize(list) == 3);
        cJSON *first = cJSON_GetArrayItem(list, 0);
        CHECK(strcmp(text(first, "outboundTag"), "block") == 0);
        CHECK(strcmp(cJSON_GetArrayItem(get(first, "domain"), 0)->valuestring, "geosite:category-ads-all") == 0);
        cJSON *ip = get(cJSON_GetArrayItem(list, 2), "ip");
        CHECK(ip && strcmp(cJSON_GetArrayItem(ip, 0)->valuestring, "geoip:ru") == 0);
        cJSON_Delete(root);
    }
    snprintf(path, sizeof path, "%s/geosite.dat", dir);
    unlink(path);
    snprintf(path, sizeof path, "%s/geoip.dat", dir);
    unlink(path);
    rmdir(dir);
}

static void test_refusals(void) {
    vl_server_t s = server(VL_PROTO_VLESS, VL_NET_TCP, VL_SEC_TLS);
    char small[64];
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, small, sizeof small) != 0);
    CHECK(senko_core_config_render_rules(NULL, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) != 0);
    CHECK(senko_core_config_render_rules(&s, "", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) != 0);
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "", 1500, NULL, NULL, NULL, out, sizeof out) != 0);
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 100, NULL, NULL, NULL, out, sizeof out) != 0);
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 70000, NULL, NULL, NULL, out, sizeof out) != 0);
    s.uuid[0] = '\0';
    CHECK(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) != 0);
}

/* the profile reaches the core as the string it was read as, and a lost pool id never renders as none */
static void test_vless_encryption_render(void) {
    static const char want[] = "mlkem768x25519plus.random.0rtt.100-111-1111.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    char link[512];
    vl_server_t s;
    snprintf(link, sizeof link,
             "vless://11111111-1111-4111-8111-111111111111@example.com:443"
             "?type=tcp&security=tls&sni=cdn.example.com&encryption=%s#node", want);
    assert(cfg_parse_link(link, &s) == CFG_OK && s.venc != 0);
    cJSON *root = render(&s, NULL, NULL);
    assert(root != NULL);
    cJSON *settings = get(first_outbound(root), "settings");
    assert(strcmp(text(settings, "encryption"), want) == 0);
    cJSON_Delete(root);

    s.venc = 4000;
    assert(senko_core_config_render_rules(&s, "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) != 0);
}

int main(int argc, char **argv) {
    test_vless_encryption_render();
    test_frame();
    test_transports();
    test_protocols();
    test_rules();
    test_geo_dat();
    test_refusals();
    /* with a directory argument the rendered profiles are written there for the go core to validate */
    if (argc > 1) {
        const char *names[] = { "reality", "ws", "trojan", "hysteria2" };
        vl_server_t list[4];
        list[0] = server(VL_PROTO_VLESS, VL_NET_TCP, VL_SEC_REALITY);
        snprintf(list[0].pbk, sizeof list[0].pbk, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        snprintf(list[0].sid, sizeof list[0].sid, "ab12");
        snprintf(list[0].flow, sizeof list[0].flow, "xtls-rprx-vision");
        list[1] = server(VL_PROTO_VLESS, VL_NET_WS, VL_SEC_TLS);
        snprintf(list[1].path, sizeof list[1].path, "/ws");
        list[2] = server(VL_PROTO_TROJAN, VL_NET_TCP, VL_SEC_TLS);
        snprintf(list[2].pass, sizeof list[2].pass, "secret");
        list[3] = server(VL_PROTO_HYSTERIA2, VL_NET_UNKNOWN, VL_SEC_TLS);
        snprintf(list[3].pass, sizeof list[3].pass, "auth");
        for (int i = 0; i < 4; ++i) {
            char path[512];
            snprintf(path, sizeof path, "%s/%s.json", argv[1], names[i]);
            if (senko_core_config_render_rules(&list[i], "203.0.113.7", "tun0", 1500, NULL, NULL, NULL, out, sizeof out) != 0) {
                fprintf(stderr, "cannot render %s\n", names[i]);
                return 1;
            }
            FILE *f = fopen(path, "wb");
            if (!f) return 1;
            fwrite(out, 1, strlen(out), f);
            fclose(f);
        }
    }
    if (failures) return 1;
    printf("test_senko_core_config ok\n");
    return 0;
}
