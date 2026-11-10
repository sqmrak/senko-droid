#include "xray_backend.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "core/net_protect.h"
#include "core/senko_core_config.h"

#define CONFIG_MAX (256 * 1024)
#define TUN_MTU 1500

static void say(char *why, size_t cap, const char *text) {
    if (why && cap) snprintf(why, cap, "%s", text);
}

/* the profile holds secrets, so the buffer is wiped before it is freed */
static void wipe(void *data, size_t len) {
    volatile unsigned char *p = data;
    while (len--) *p++ = 0;
}

void xray_backend_init(xray_backend_t *backend, const char *library) {
    memset(backend, 0, sizeof *backend);
    backend->tun_fd = -1;
    if (library) snprintf(backend->library, sizeof backend->library, "%s", library);
}

/* checks run on threads of their own, and the first of them loads the library for all */
static pthread_mutex_t g_load_lock = PTHREAD_MUTEX_INITIALIZER;

static int load_locked(xray_backend_t *backend, char *why, size_t why_cap);

int xray_backend_load(xray_backend_t *backend, char *why, size_t why_cap) {
    pthread_mutex_lock(&g_load_lock);
    int rc = load_locked(backend, why, why_cap);
    pthread_mutex_unlock(&g_load_lock);
    return rc;
}

static int load_locked(xray_backend_t *backend, char *why, size_t why_cap) {
    if (backend->handle) return 0;
    if (backend->load_tried) {
        say(why, why_cap, backend->load_error);
        return -1;
    }
    backend->load_tried = 1;
    if (!backend->library[0]) {
        snprintf(backend->load_error, sizeof backend->load_error, "senko-core is not enabled on this android or in this build");
        say(why, why_cap, backend->load_error);
        return -1;
    }
    void *handle = dlopen(backend->library, RTLD_NOW);
    if (!handle) {
        const char *reason = dlerror();
        snprintf(backend->load_error, sizeof backend->load_error, "senko-core did not load: %s",
                 reason ? reason : "unknown loader error");
        say(why, why_cap, backend->load_error);
        return -1;
    }
    xray_protect_fn protect = (xray_protect_fn)dlsym(handle, "SenkoCoreSetProtect");
    backend->start = (xray_start_fn)dlsym(handle, "SenkoCoreStart");
    backend->stop = (xray_stop_fn)dlsym(handle, "SenkoCoreStop");
    backend->running = (xray_running_fn)dlsym(handle, "SenkoCoreRunning");
    backend->stats = (xray_stats_fn)dlsym(handle, "SenkoCoreStats");
    backend->probe = (xray_probe_fn)dlsym(handle, "SenkoCoreProbe");
    backend->assets = (xray_assets_fn)dlsym(handle, "SenkoCoreSetAssets");
    xray_version_fn version = (xray_version_fn)dlsym(handle, "SenkoCoreVersion");
    if (!protect || !backend->start || !backend->stop || !backend->running || !backend->stats ||
        !backend->probe || !backend->assets || !version) {
        snprintf(backend->load_error, sizeof backend->load_error,
                 "senko-core is missing entry points: this library is from another build");
        dlclose(handle);
        say(why, why_cap, backend->load_error);
        return -1;
    }
    /* every socket toward a server goes through the same hook as the engine's own */
    if (protect((void *)net_protect_socket) != 0) {
        snprintf(backend->load_error, sizeof backend->load_error, "senko-core refused the protect hook");
        dlclose(handle);
        say(why, why_cap, backend->load_error);
        return -1;
    }
    backend->version = version();
    backend->handle = handle;
    return 0;
}

int xray_backend_can_carry(const vl_server_t *server, const ruleset_t *rules, const char *geo_dir,
                           char *why, size_t why_cap) {
    if (!server) {
        say(why, why_cap, "no server");
        return 0;
    }
    switch (server->proto) {
        case VL_PROTO_VLESS:
        case VL_PROTO_VMESS:
        case VL_PROTO_TROJAN:
        case VL_PROTO_SHADOWSOCKS:
        case VL_PROTO_HYSTERIA2:
            break;
        default:
            say(why, why_cap, "senko-core does not speak this protocol");
            return 0;
    }
    /* senko-core removed allowInsecure, so a node that relies on skipping verification cannot use it */
    if (server->insecure) {
        say(why, why_cap, "senko-core always verifies the server certificate");
        return 0;
    }
    if (rules && senko_core_config_unsupported(rules, geo_dir) != 0) {
        say(why, why_cap, "a geo rule has no data senko-core can read");
        return 0;
    }
    return 1;
}

void xray_backend_set_assets(xray_backend_t *backend, const char *dir) {
    if (backend->handle && backend->assets && dir && dir[0]) backend->assets(dir);
}

int xray_backend_probe(xray_backend_t *backend, const vl_server_t *server, const char *endpoint_ip,
                       const char *ca_file, int timeout_ms, char *why, size_t why_cap) {
    if (xray_backend_load(backend, why, why_cap) != 0) return -1;
    char *config = malloc(CONFIG_MAX);
    if (!config) {
        say(why, why_cap, "not enough memory for the probe configuration");
        return -1;
    }
    int ms = -1;
    if (senko_core_config_render_rules(server, endpoint_ip, NULL, 0, NULL, NULL, ca_file, config, CONFIG_MAX) != 0) {
        say(why, why_cap, "senko-core cannot be configured for this profile");
    } else {
        char error[256] = "";
        ms = backend->probe(config, (int)strlen(config), timeout_ms, error, sizeof error);
        if (ms < 0) snprintf(why, why_cap, "%s", error[0] ? error : "no answer through the server");
    }
    wipe(config, CONFIG_MAX);
    free(config);
    return ms;
}

int xray_backend_start(xray_backend_t *backend, const vl_server_t *server, const char *endpoint_ip,
                       const ruleset_t *rules, const char *geo_dir, const char *ca_file,
                       const char *dns_upstream, const tun_source_t *source,
                       char *why, size_t why_cap) {
    if (backend->active) {
        say(why, why_cap, "senko-core is already running");
        return -1;
    }
    if (xray_backend_load(backend, why, why_cap) != 0) return -1;
    if (!source || !source->open || !dns_upstream || !endpoint_ip) {
        say(why, why_cap, "senko-core needs a tun source, a resolver and a server address");
        return -1;
    }
    char *config = malloc(CONFIG_MAX);
    if (!config) {
        say(why, why_cap, "not enough memory for the senko-core configuration");
        return -1;
    }
    int result = -1;
    if (senko_core_config_render_rules(server, endpoint_ip, "tun0", TUN_MTU, rules, geo_dir, ca_file,
                                       config, CONFIG_MAX) != 0) {
        say(why, why_cap, "senko-core cannot be configured for this profile");
        goto done;
    }
    int length = (int)strlen(config);
    char error[256] = "";

    tun_params_t params;
    memset(&params, 0, sizeof params);
    snprintf(params.address4, sizeof params.address4, "198.18.0.1");
    params.prefix4 = 32;
    snprintf(params.address6, sizeof params.address6, "fd00:5e4b::1");
    params.prefix6 = 128;
    snprintf(params.dns4, sizeof params.dns4, "%s", dns_upstream);
    params.mtu = TUN_MTU;
    char open_error[160] = "";
    int fd = source->open(source->ctx, &params, open_error, sizeof open_error);
    if (fd < 0) {
        say(why, why_cap, open_error[0] ? open_error : "the tun device could not be opened");
        goto done;
    }
    error[0] = '\0';
    if (backend->start(config, length, fd, error, sizeof error) != 0) {
        close(fd);
        snprintf(why, why_cap, "senko-core did not start: %s", error[0] ? error : "unknown reason");
        goto done;
    }
    backend->tun_fd = fd;
    backend->active = 1;
    result = 0;
done:
    wipe(config, CONFIG_MAX);
    free(config);
    return result;
}

void xray_backend_stop(xray_backend_t *backend) {
    if (!backend->active) return;
    char error[160] = "";
    if (backend->stop(error, sizeof error) != 0)
        fprintf(stderr, "senko: senko-core did not stop cleanly: %s\n", error);
    /* the core leaves the descriptor to the caller, and closing it takes the interface away */
    if (backend->tun_fd >= 0) close(backend->tun_fd);
    backend->tun_fd = -1;
    backend->active = 0;
}

int xray_backend_running(const xray_backend_t *backend) {
    return backend->active && backend->running() == 1;
}

int xray_backend_stats(const xray_backend_t *backend, uint64_t *up, uint64_t *down) {
    if (up) *up = 0;
    if (down) *down = 0;
    if (!backend->active || !up || !down) return -1;
    return backend->stats(up, down) == 0 ? 0 : -1;
}
