#ifndef SENKO_XRAY_BACKEND_H
#define SENKO_XRAY_BACKEND_H

#include <stddef.h>
#include <stdint.h>

#include "core/config.h"
#include "core/rules.h"
#include "tun_source.h"

#ifdef __cplusplus
extern "C" {
#endif

/* senko-core: the xray build in libsenkocore.so (xraycore/), carrying a tunnel on the tun device
   the host opens. it is loaded at run time, so a device whose loader or runtime cannot run it
   keeps the engine's own backend. the core opens its server sockets through net_protect_socket */

typedef int (*xray_start_fn)(const char *config, int length, int tun_fd, char *error, int error_cap);
typedef int (*xray_stop_fn)(char *error, int error_cap);
typedef int (*xray_running_fn)(void);
typedef int (*xray_stats_fn)(uint64_t *up, uint64_t *down);
typedef int (*xray_protect_fn)(void *hook);
typedef const char *(*xray_version_fn)(void);
typedef int (*xray_probe_fn)(const char *config, int length, int timeout_ms, char *error, int error_cap);
typedef void (*xray_assets_fn)(const char *dir);

typedef struct {
    char             library[256]; /* path of libsenkocore.so, empty when this build has none */
    void            *handle;
    int              load_tried;
    char             load_error[192];
    xray_start_fn    start;
    xray_stop_fn     stop;
    xray_running_fn  running;
    xray_stats_fn    stats;
    xray_probe_fn    probe;
    xray_assets_fn   assets;
    const char      *version;
    int              active;
    int              tun_fd;
} xray_backend_t;

void xray_backend_init(xray_backend_t *backend, const char *library);

/* loads the library once. 0 when it is usable, -1 with the reason in why */
int xray_backend_load(xray_backend_t *backend, char *why, size_t why_cap);

/* whether senko-core can carry this profile: a protocol and transport it speaks, tls checked, and
   no geo rule it has no data for. 1 or 0 with the reason */
int xray_backend_can_carry(const vl_server_t *server, const ruleset_t *rules, const char *geo_dir,
                           char *why, size_t why_cap);

/* the directory with geoip.dat and geosite.dat that geo rules read, set before a start or a probe */
void xray_backend_set_assets(xray_backend_t *backend, const char *dir);

/* the milliseconds a request through the server takes, measured by the core with a configuration of
   its own that has no tun device and so rides no tunnel. -1 with the reason in why. safe to call from
   several threads: the core runs a few probes at once */
int xray_backend_probe(xray_backend_t *backend, const vl_server_t *server, const char *endpoint_ip,
                       const char *ca_file, int timeout_ms, char *why, size_t why_cap);

/* starts a tunnel for the server on a tun device from source. 0, or -1 with the reason. the
   device is closed again on failure; on success the backend owns it until stop */
int xray_backend_start(xray_backend_t *backend, const vl_server_t *server, const char *endpoint_ip,
                       const ruleset_t *rules, const char *geo_dir, const char *ca_file,
                       const char *dns_upstream, const tun_source_t *source,
                       char *why, size_t why_cap);

void xray_backend_stop(xray_backend_t *backend);
int xray_backend_running(const xray_backend_t *backend);
/* bytes the tunnel carried since it started, 0 when it is not running */
int xray_backend_stats(const xray_backend_t *backend, uint64_t *up, uint64_t *down);

#ifdef __cplusplus
}
#endif

#endif
