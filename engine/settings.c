#include "settings.h"


#include <arpa/inet.h>
#include <stdio.h>
#include <string.h>

void daemon_settings_defaults(daemon_settings_t *s) {
    if (!s) return;
    memset(s, 0, sizeof *s);
    snprintf(s->dns_upstream, sizeof s->dns_upstream, "8.8.8.8");
    s->block_response = DNS_BLOCK_ZERO;
    s->auto_connect = 0;
    /* a tunnel that died with nobody watching used to stay dead until the user
       opened the app, which on a phone is the wrong default */
    s->auto_reconnect = 1;
    s->reconnect_max_attempts = SENKO_DEFAULT_RECONNECT_ATTEMPTS;
    s->sub_refresh_hours = 0;
    s->failover = 0;
    s->sub_ignore_gating = 0;
    s->trace = 0;
    s->core_pref = SETTINGS_CORE_AUTO;
}

static int parse_bool01(const char *s, const char *end, int *out) {
    if ((size_t)(end - s) == 1 && (*s == '0' || *s == '1')) {
        *out = (*s == '1');
        return 0;
    }
    return -1;
}

static int parse_bounded_int(const char *s, const char *end, int lo, int hi, int *out) {
    long v = 0;
    int any = 0;
    for (; s < end; ++s) {
        if (*s < '0' || *s > '9') return -1;
        v = v * 10 + (*s - '0');
        any = 1;
        if (v > (long)hi) return -1;
    }
    if (!any || v < (long)lo) return -1;
    *out = (int)v;
    return 0;
}

static int ipv4_ok(const char *s, size_t len) {
    char tmp[SETTINGS_DNS_UPSTREAM_MAX];
    if (len == 0 || len >= sizeof tmp) return 0;
    memcpy(tmp, s, len);
    tmp[len] = '\0';
    struct in_addr a;
    return inet_pton(AF_INET, tmp, &a) == 1;
}

static int key_is(const char *key, size_t key_len, const char *name) {
    size_t n = strlen(name);
    return key_len == n && memcmp(key, name, n) == 0;
}

settings_status_t daemon_settings_set(daemon_settings_t *s,
                                      const char *key, size_t key_len,
                                      const char *val, size_t val_len) {
    if (!s || !key || !val || key_len == 0) return SETTINGS_ERR_KEY;
    const char *ve = val + val_len;

    /* keys the ios daemon had: a socks listener, a pf backend and a pinned
       backend. a backup exported from it still carries them, and refusing
       them would fail the whole import */
    if (key_is(key, key_len, "socks_port") || key_is(key, key_len, "socks_public") ||
        key_is(key, key_len, "force_backend") || key_is(key, key_len, "dns_local_port") ||
        key_is(key, key_len, "force_pf_mode"))
        return SETTINGS_OK;
    if (key_is(key, key_len, "dns_upstream")) {
        if (!ipv4_ok(val, val_len)) return SETTINGS_ERR_VALUE;
        memcpy(s->dns_upstream, val, val_len);
        s->dns_upstream[val_len] = '\0';
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "core")) {
        if (val_len == 4 && memcmp(val, "auto", 4) == 0) s->core_pref = SETTINGS_CORE_AUTO;
        else if (val_len == 4 && memcmp(val, "xray", 4) == 0) s->core_pref = SETTINGS_CORE_XRAY;
        else if (val_len == 6 && memcmp(val, "engine", 6) == 0) s->core_pref = SETTINGS_CORE_ENGINE;
        else return SETTINGS_ERR_VALUE;
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "block_response")) {
        if (val_len == 4 && memcmp(val, "zero", 4) == 0)
            s->block_response = DNS_BLOCK_ZERO;
        else if (val_len == 8 && memcmp(val, "nxdomain", 8) == 0)
            s->block_response = DNS_BLOCK_NXDOMAIN;
        else if (val_len == 7 && memcmp(val, "refused", 7) == 0)
            s->block_response = DNS_BLOCK_REFUSED;
        else
            return SETTINGS_ERR_VALUE;
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "auto_connect")) {
        int b;
        if (parse_bool01(val, ve, &b) != 0) return SETTINGS_ERR_VALUE;
        s->auto_connect = b;
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "auto_reconnect")) {
        int b;
        if (parse_bool01(val, ve, &b) != 0) return SETTINGS_ERR_VALUE;
        s->auto_reconnect = b;
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "reconnect_max_attempts")) {
        int v;
        if (parse_bounded_int(val, ve, 0, 100, &v) != 0) return SETTINGS_ERR_VALUE;
        s->reconnect_max_attempts = v;
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "sub_refresh_hours")) {
        int v;
        /* a week is the longest schedule a panel expiry makes any sense at */
        if (parse_bounded_int(val, ve, 0, 168, &v) != 0) return SETTINGS_ERR_VALUE;
        s->sub_refresh_hours = v;
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "failover")) {
        int b;
        if (parse_bool01(val, ve, &b) != 0) return SETTINGS_ERR_VALUE;
        s->failover = b;
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "sub_ignore_gating")) {
        int b;
        if (parse_bool01(val, ve, &b) != 0) return SETTINGS_ERR_VALUE;
        s->sub_ignore_gating = b;
        return SETTINGS_OK;
    }
    if (key_is(key, key_len, "trace")) {
        int b;
        if (parse_bool01(val, ve, &b) != 0) return SETTINGS_ERR_VALUE;
        s->trace = b;
        return SETTINGS_OK;
    }
    return SETTINGS_ERR_KEY;
}

/* a config written by a newer build carries keys this one has no field for, and
   dropping the whole file over one of them would lose every server in it */
int daemon_settings_apply_line(daemon_settings_t *s, const char *line, size_t len) {
    if (!s || !line || len < 5 || memcmp(line, "SET ", 4) != 0) return 0;

    const char *rest = line + 4;
    const char *sp = memchr(rest, ' ', (size_t)(line + len - rest));
    if (!sp) return 1;

    size_t key_len = (size_t)(sp - rest);
    const char *val = sp + 1;
    size_t val_len = (size_t)(line + len - val);
    while (val_len > 0 && (val[val_len - 1] == '\n' || val[val_len - 1] == '\r'
                           || val[val_len - 1] == ' '))
        val_len--;

    (void)daemon_settings_set(s, rest, key_len, val, val_len);
    return 1;
}

void daemon_settings_apply_buf(daemon_settings_t *s, const char *buf, size_t len) {
    if (!s || !buf) return;
    const char *p = buf;
    const char *end = buf + len;
    while (p < end) {
        const char *nl = memchr(p, '\n', (size_t)(end - p));
        const char *le = nl ? nl : end;
        daemon_settings_apply_line(s, p, (size_t)(le - p));
        if (!nl) break;
        p = nl + 1;
    }
}

int daemon_settings_serialize(const daemon_settings_t *s, char *buf, size_t cap,
                              size_t *out_len) {
    if (!s || !buf) return -1;
    size_t off = 0;
    int n;

#define SETTINGS_EMIT(fmt, value)                                     \
    do {                                                              \
        n = snprintf(buf + off, cap - off, fmt, value);               \
        if (n < 0 || (size_t)n >= cap - off) return -1;               \
        off += (size_t)n;                                             \
    } while (0)

    SETTINGS_EMIT("SET dns_upstream %s\n", s->dns_upstream);
    SETTINGS_EMIT("SET block_response %s\n",
                  s->block_response == DNS_BLOCK_NXDOMAIN ? "nxdomain" :
                  s->block_response == DNS_BLOCK_REFUSED ? "refused" : "zero");
    SETTINGS_EMIT("SET auto_connect %d\n", s->auto_connect ? 1 : 0);
    SETTINGS_EMIT("SET auto_reconnect %d\n", s->auto_reconnect ? 1 : 0);
    SETTINGS_EMIT("SET reconnect_max_attempts %d\n", s->reconnect_max_attempts);
    SETTINGS_EMIT("SET sub_refresh_hours %d\n", s->sub_refresh_hours);
    SETTINGS_EMIT("SET failover %d\n", s->failover ? 1 : 0);
    SETTINGS_EMIT("SET sub_ignore_gating %d\n", s->sub_ignore_gating ? 1 : 0);
    SETTINGS_EMIT("SET trace %d\n", s->trace ? 1 : 0);
    SETTINGS_EMIT("SET core %s\n", s->core_pref == SETTINGS_CORE_XRAY ? "xray" :
                                    s->core_pref == SETTINGS_CORE_ENGINE ? "engine" : "auto");

#undef SETTINGS_EMIT

    if (out_len) *out_len = off;
    return 0;
}
