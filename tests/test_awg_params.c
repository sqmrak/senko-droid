#include "awg_params.h"

#include <stdio.h>
#include <string.h>

#define K_PRIV "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
#define K_PUB  "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8="

static int failures;

static void ok(const char *name, int condition) {
    if (condition) return;
    ++failures;
    fprintf(stderr, "FAIL %s\n", name);
}

static int load(const char *address, const char *dns, const char *mtu, awg_config_t *cfg) {
    char text[1024];
    char reason[160];
    snprintf(text, sizeof text,
             "[Interface]\nPrivateKey = " K_PRIV "\n%s%s%s"
             "[Peer]\nPublicKey = " K_PUB "\nAllowedIPs = 0.0.0.0/0\nEndpoint = 192.0.2.1:51820\n",
             address, dns, mtu);
    return awg_config_parse(text, strlen(text), cfg, reason, sizeof reason) == AWG_CFG_OK;
}

int main(void) {
    awg_config_t cfg;
    tun_params_t params;
    char why[160];

    ok("dual stack profile", load("Address = 10.0.0.2/32, fd00::2/128\n",
                                  "DNS = 2606:4700:4700::1111, 1.1.1.1\n", "MTU = 1280\n", &cfg));
    ok("dual stack params", awg_tun_params(&cfg, "9.9.9.9", &params, why, sizeof why) == 0);
    ok("address4", strcmp(params.address4, "10.0.0.2") == 0 && params.prefix4 == 32);
    ok("address6", strcmp(params.address6, "fd00::2") == 0 && params.prefix6 == 128);
    ok("first ipv4 dns wins over an ipv6 one", strcmp(params.dns4, "1.1.1.1") == 0);
    ok("mtu", params.mtu == 1280);

    ok("subnet prefix kept", load("Address = 10.8.0.5/24\n", "DNS = 8.8.8.8\n", "", &cfg));
    ok("subnet params", awg_tun_params(&cfg, NULL, &params, why, sizeof why) == 0);
    ok("prefix4", params.prefix4 == 24 && params.address6[0] == '\0' && params.prefix6 == 0);

    ok("bare address is a host", load("Address = 10.8.0.9\n", "DNS = 8.8.8.8\n", "", &cfg));
    ok("bare params", awg_tun_params(&cfg, NULL, &params, why, sizeof why) == 0 &&
                      params.prefix4 == 32);

    ok("no dns uses the fallback", load("Address = 10.0.0.2/32\n", "", "", &cfg));
    ok("fallback params", awg_tun_params(&cfg, "9.9.9.9", &params, why, sizeof why) == 0 &&
                          strcmp(params.dns4, "9.9.9.9") == 0);
    ok("no dns and no fallback", awg_tun_params(&cfg, NULL, &params, why, sizeof why) == -1 &&
                                 strstr(why, "not a numeric IPv4") != NULL);
    ok("fallback must be numeric", awg_tun_params(&cfg, "dns.example", &params, why,
                                                  sizeof why) == -1);

    ok("ipv6 only profile", load("Address = fd00::2/128\n", "DNS = 1.1.1.1\n", "", &cfg));
    ok("ipv6 only is refused", awg_tun_params(&cfg, NULL, &params, why, sizeof why) == -1 &&
                               strstr(why, "no IPv4 Address") != NULL);
    ok("params are cleared on failure", params.address4[0] == '\0' && params.mtu == 0);

    ok("bad prefix", load("Address = 10.0.0.2/40, 10.0.0.3/32\n", "DNS = 1.1.1.1\n", "", &cfg));
    ok("bad prefix falls to the next address",
       awg_tun_params(&cfg, NULL, &params, why, sizeof why) == 0 &&
       strcmp(params.address4, "10.0.0.3") == 0);

    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("ok");
    return 0;
}
