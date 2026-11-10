#include "awg_probe.h"

#include "core/awg_config.h"
#include "core/awg_handshake.h"
#include "core/senko_time.h"

#include <openssl/crypto.h>
#include <stdint.h>
#include <stdio.h>

#define AWG_PROBE_TIMEOUT_MS 5000

int awg_probe_job(const char *path, char *out, size_t out_cap) {
    if (!out || !out_cap) return 1;
    out[0] = '\0';
    if (!path || !awg_config_path_ok(path)) {
        snprintf(out, out_cap, "error the config is not a .conf file in the app awg directory: %.120s",
                 path ? path : "(none)");
        return 1;
    }
    awg_config_t cfg;
    char why[160];
    if (awg_config_load_file(path, &cfg, why, sizeof why) != AWG_CFG_OK) {
        snprintf(out, out_cap, "error config rejected: %s", why);
        return 1;
    }
    int64_t started = senko_now_ms();
    awg_hs_status_t result = awg_handshake_probe(&cfg, AWG_PROBE_TIMEOUT_MS, why, sizeof why);
    int64_t took = senko_now_ms() - started;
    OPENSSL_cleanse(&cfg, sizeof cfg);
    if (result != AWG_HS_OK) {
        snprintf(out, out_cap, "error %s", why);
        return 1;
    }
    snprintf(out, out_cap, "PING %lld", (long long)took);
    return 0;
}
