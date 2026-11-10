#include <dlfcn.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "core/net_protect.h"
#include "engine/xray_backend.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); ++failures; } } while (0)

static const char *g_lib;
static int g_open_calls;
static int g_last_fd = -1;
static tun_params_t g_params;
static int g_open_fail;

static int open_tun(void *ctx, const tun_params_t *params, char *error, size_t cap) {
    (void)ctx;
    ++g_open_calls;
    g_params = *params;
    if (g_open_fail) {
        snprintf(error, cap, "the vpn permission was revoked");
        return -1;
    }
    g_last_fd = open("/dev/null", O_RDWR);
    return g_last_fd;
}

static int fd_open(int fd) { return fd >= 0 && fcntl(fd, F_GETFD) != -1; }

static vl_server_t server(vl_proto_t proto) {
    vl_server_t s;
    memset(&s, 0, sizeof s);
    s.proto = proto;
    s.net = VL_NET_TCP;
    s.security = VL_SEC_TLS;
    s.port = 443;
    snprintf(s.host, sizeof s.host, "node.example.com");
    snprintf(s.uuid, sizeof s.uuid, "11111111-1111-4111-8111-111111111111");
    snprintf(s.pass, sizeof s.pass, "secret");
    snprintf(s.encryption, sizeof s.encryption, "aes-256-gcm");
    return s;
}

static int hook_calls;
static int hook(void *ctx, int fd) {
    (void)ctx;
    (void)fd;
    ++hook_calls;
    return 0;
}

static void test_can_carry(void) {
    char why[160];
    vl_server_t s = server(VL_PROTO_VLESS);
    CHECK(xray_backend_can_carry(&s, NULL, NULL, why, sizeof why) == 1);
    s = server(VL_PROTO_VMESS);
    CHECK(xray_backend_can_carry(&s, NULL, NULL, why, sizeof why) == 1);
    s = server(VL_PROTO_TROJAN);
    CHECK(xray_backend_can_carry(&s, NULL, NULL, why, sizeof why) == 1);
    s = server(VL_PROTO_SHADOWSOCKS);
    CHECK(xray_backend_can_carry(&s, NULL, NULL, why, sizeof why) == 1);
    s = server(VL_PROTO_HYSTERIA2);
    CHECK(xray_backend_can_carry(&s, NULL, NULL, why, sizeof why) == 1);
    s = server(VL_PROTO_SOCKS5);
    CHECK(xray_backend_can_carry(&s, NULL, NULL, why, sizeof why) == 0 && strstr(why, "protocol") != NULL);
    s = server(VL_PROTO_UNSUPPORTED);
    CHECK(xray_backend_can_carry(&s, NULL, NULL, why, sizeof why) == 0);
    /* a node that relies on skipped verification cannot run, senko-core has no allowInsecure */
    s = server(VL_PROTO_VLESS);
    s.insecure = 1;
    CHECK(xray_backend_can_carry(&s, NULL, NULL, why, sizeof why) == 0 && strstr(why, "verifies") != NULL);
    CHECK(xray_backend_can_carry(NULL, NULL, NULL, why, sizeof why) == 0);

    static ruleset_t rules;
    memset(&rules, 0, sizeof rules);
    ruleset_init(&rules);
    s = server(VL_PROTO_VLESS);
    rule_t *r = &rules.entries[rules.count++];
    memset(r, 0, sizeof *r);
    r->type = RULE_TYPE_GEOSITE;
    r->action = RULE_ACTION_DIRECT;
    snprintf(r->value, sizeof r->value, "google");
    CHECK(xray_backend_can_carry(&s, &rules, NULL, why, sizeof why) == 0 && strstr(why, "geo") != NULL);
    r->type = RULE_TYPE_APP;
    CHECK(xray_backend_can_carry(&s, &rules, NULL, why, sizeof why) == 1);
}

static void test_load(void) {
    xray_backend_t b;
    char why[200] = "";
    xray_backend_init(&b, NULL);
    CHECK(xray_backend_load(&b, why, sizeof why) == -1 && strstr(why, "not enabled") != NULL);
    xray_backend_init(&b, "/nonexistent/libsenkocore.so");
    CHECK(xray_backend_load(&b, why, sizeof why) == -1 && strstr(why, "did not load") != NULL);
    /* the failure is remembered, a second try does not hit the loader again */
    why[0] = '\0';
    CHECK(xray_backend_load(&b, why, sizeof why) == -1 && strstr(why, "did not load") != NULL);
    /* a library that is not senko-core: dlopen works, the entry points do not exist */
    xray_backend_init(&b, "libm.so.6");
    CHECK(xray_backend_load(&b, why, sizeof why) == -1 && strstr(why, "missing entry points") != NULL);
    setenv("FAKE_CORE_REFUSE_PROTECT", "1", 1);
    xray_backend_init(&b, g_lib);
    CHECK(xray_backend_load(&b, why, sizeof why) == -1 && strstr(why, "protect hook") != NULL);
    unsetenv("FAKE_CORE_REFUSE_PROTECT");
}

static void test_lifecycle(void) {
    xray_backend_t b;
    char why[200] = "";
    xray_backend_init(&b, g_lib);
    CHECK(xray_backend_load(&b, why, sizeof why) == 0 && strcmp(b.version, "fake-1.0") == 0);
    void *lib = dlopen(g_lib, RTLD_NOW);
    const char *(*config_of)(void) = (const char *(*)(void))dlsym(lib, "FakeCoreConfig");
    int (*fd_of)(void) = (int (*)(void))dlsym(lib, "FakeCoreFd");
    int (*stops)(void) = (int (*)(void))dlsym(lib, "FakeCoreStops");
    int (*through_hook)(int) = (int (*)(int))dlsym(lib, "FakeCoreProtect");
    CHECK(config_of && fd_of && stops && through_hook);

    /* the core reaches the engine's own protect hook, which is what keeps its sockets out of the tun */
    net_protect_install(hook, NULL);
    CHECK(through_hook(7) == 0 && hook_calls == 1);
    net_protect_install(NULL, NULL);

    tun_source_t source = { open_tun, NULL };
    vl_server_t s = server(VL_PROTO_TROJAN);
    CHECK(xray_backend_running(&b) == 0);
    uint64_t up = 5, down = 5;
    CHECK(xray_backend_stats(&b, &up, &down) == -1 && up == 0 && down == 0);

    CHECK(xray_backend_start(&b, &s, "203.0.113.7", NULL, NULL, "/files/cacert.pem", "1.1.1.1", &source, why, sizeof why) == 0);
    CHECK(b.active && xray_backend_running(&b) == 1);
    /* the core got the rendered profile and the very descriptor the host opened */
    CHECK(strstr(config_of(), "\"protocol\":\"trojan\"") != NULL && strstr(config_of(), "203.0.113.7") != NULL);
    CHECK(strstr(config_of(), "cacert.pem") != NULL);
    CHECK(fd_of() == g_last_fd && fd_open(g_last_fd));
    CHECK(strcmp(g_params.address4, "198.18.0.1") == 0 && g_params.prefix4 == 32 && g_params.mtu == 1500);
    CHECK(strcmp(g_params.dns4, "1.1.1.1") == 0);
    CHECK(xray_backend_stats(&b, &up, &down) == 0 && up == 11 && down == 222);

    /* a probe does not touch the running tunnel and carries no tun inbound */
    xray_backend_set_assets(&b, "/files/geo");
    const char *(*assets_of)(void) = (const char *(*)(void))dlsym(lib, "FakeCoreAssets");
    CHECK(assets_of && strcmp(assets_of(), "/files/geo") == 0);
    CHECK(xray_backend_probe(&b, &s, "203.0.113.7", NULL, 3000, why, sizeof why) == 42);
    CHECK(xray_backend_running(&b) == 1);
    setenv("FAKE_CORE_PROBE_FAIL", "1", 1);
    CHECK(xray_backend_probe(&b, &s, "203.0.113.7", NULL, 3000, why, sizeof why) == -1 && strcmp(why, "i/o timeout") == 0);
    unsetenv("FAKE_CORE_PROBE_FAIL");
    vl_server_t unwritable = server(VL_PROTO_VLESS);
    unwritable.net = VL_NET_HTTP;
    CHECK(xray_backend_probe(&b, &unwritable, "203.0.113.7", NULL, 3000, why, sizeof why) == -1);

    /* a second start while it runs is refused and does not open another device */
    int calls = g_open_calls;
    CHECK(xray_backend_start(&b, &s, "203.0.113.7", NULL, NULL, NULL, "1.1.1.1", &source, why, sizeof why) == -1);
    CHECK(g_open_calls == calls);

    int fd = g_last_fd;
    xray_backend_stop(&b);
    CHECK(!b.active && stops() == 1 && !fd_open(fd) && xray_backend_running(&b) == 0);
    xray_backend_stop(&b);
    CHECK(stops() == 1);

    /* a profile the core refuses is reported by the one start that builds it, and the device it was given is closed */
    setenv("FAKE_CORE_BAD_CONFIG", "1", 1);
    CHECK(xray_backend_start(&b, &s, "203.0.113.7", NULL, NULL, NULL, "1.1.1.1", &source, why, sizeof why) == -1);
    CHECK(strstr(why, "did not start: unknown outbound protocol") != NULL && !b.active && !fd_open(g_last_fd));
    unsetenv("FAKE_CORE_BAD_CONFIG");

    /* the host refusing the device is passed on as it said it */
    g_open_fail = 1;
    CHECK(xray_backend_start(&b, &s, "203.0.113.7", NULL, NULL, NULL, "1.1.1.1", &source, why, sizeof why) == -1);
    CHECK(strcmp(why, "the vpn permission was revoked") == 0 && !b.active);
    g_open_fail = 0;

    /* a core that fails to start gives the descriptor back to nobody: it is closed here */
    setenv("FAKE_CORE_FAIL_START", "1", 1);
    CHECK(xray_backend_start(&b, &s, "203.0.113.7", NULL, NULL, NULL, "1.1.1.1", &source, why, sizeof why) == -1);
    CHECK(strstr(why, "did not start: tun inbound could not start") != NULL && !b.active && !fd_open(g_last_fd));
    unsetenv("FAKE_CORE_FAIL_START");

    /* a profile that cannot be written is refused with no device opened */
    vl_server_t bad = server(VL_PROTO_VLESS);
    bad.net = VL_NET_HTTP;
    calls = g_open_calls;
    CHECK(xray_backend_start(&b, &bad, "203.0.113.7", NULL, NULL, NULL, "1.1.1.1", &source, why, sizeof why) == -1);
    CHECK(g_open_calls == calls);

    /* and it starts again after all that */
    CHECK(xray_backend_start(&b, &s, "203.0.113.7", NULL, NULL, NULL, "1.1.1.1", &source, why, sizeof why) == 0);
    xray_backend_stop(&b);
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: test_xray_backend <path of the fake core library>\n");
        return 2;
    }
    g_lib = argv[1];
    test_can_carry();
    test_load();
    test_lifecycle();
    if (failures) return 1;
    printf("test_xray_backend ok\n");
    return 0;
}
