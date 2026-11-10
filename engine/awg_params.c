#include "awg_params.h"

#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *first_ipv4_dns(const awg_config_t *cfg, const char *fallback) {
    for (size_t i = 0; i < cfg->dns_count; ++i) {
        struct in_addr a;
        if (inet_pton(AF_INET, cfg->dns[i], &a) == 1) return cfg->dns[i];
    }
    return fallback;
}

/* "10.8.0.2/32" into the address and its prefix length. a bare address is a
   host address; -1 when the text is not an address of that family */
static int split_address(const char *entry, int family, char *out, size_t cap,
                         uint8_t *prefix) {
    out[0] = '\0';
    const char *slash = strchr(entry, '/');
    size_t len = slash ? (size_t)(slash - entry) : strlen(entry);
    if (!len || len >= cap) return -1;
    char address[46];
    if (len >= sizeof address) return -1;
    memcpy(address, entry, len);
    address[len] = '\0';
    unsigned char raw[16];
    if (inet_pton(family, address, raw) != 1) return -1;
    unsigned max = family == AF_INET ? 32u : 128u;
    unsigned value = max;
    if (slash) {
        char *end = NULL;
        unsigned long parsed = strtoul(slash + 1, &end, 10);
        if (!slash[1] || *end || parsed > max) return -1;
        value = (unsigned)parsed;
    }
    memcpy(out, address, len + 1);
    *prefix = (uint8_t)value;
    return 0;
}

int awg_tun_params(const awg_config_t *cfg, const char *fallback_dns,
                           tun_params_t *params, char *why, size_t why_cap) {
    memset(params, 0, sizeof *params);
    for (size_t i = 0; i < cfg->address_count; ++i) {
        if (!params->address4[0])
            (void)split_address(cfg->addresses[i], AF_INET, params->address4,
                                sizeof params->address4, &params->prefix4);
        if (!params->address6[0])
            (void)split_address(cfg->addresses[i], AF_INET6, params->address6,
                                sizeof params->address6, &params->prefix6);
    }
    if (!params->address4[0]) {
        snprintf(why, why_cap, "the profile has no IPv4 Address");
        return -1;
    }
    const char *dns = first_ipv4_dns(cfg, fallback_dns);
    struct in_addr check;
    if (!dns || strlen(dns) >= sizeof params->dns4 || inet_pton(AF_INET, dns, &check) != 1) {
        snprintf(why, why_cap, "the dns address %.60s is not a numeric IPv4 address",
                 dns ? dns : "(none)");
        return -1;
    }
    snprintf(params->dns4, sizeof params->dns4, "%s", dns);
    params->mtu = cfg->mtu;
    return 0;
}
