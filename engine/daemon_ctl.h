#ifndef DAEMON_CTL_H
#define DAEMON_CTL_H

#include "core/ctl_engine.h"
#include "ctl_server.h"
#include "awg_backend.h"
#include "utun_backend.h"
#include "xray_backend.h"
#include "settings.h"
#include "tun_source.h"
#include "core/geo_update.h"

#include <pthread.h>

#define DCTL_OK             0
#define DCTL_ERR_TRANSPORT (-1)
#define DCTL_ERR_UUID      (-2)
#define DCTL_ERR_DNS       (-4)
#define DCTL_ERR_SETTING_KEY   (-7)
#define DCTL_ERR_SETTING_VALUE (-8)
#define DCTL_ERR_UTUN      (-9)

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    char           config_path[1024];
    ruleset_t      *rules;
    /* the catalog the rules belong to: a connect rebuilds the routing profile's
       share of them from it, while no tunnel reads them */
    store_t        *store;
    char           geo_dir[GEO_UPDATE_DIR_MAX]; /* per tag geo files of the active profile */

    daemon_settings_t settings;
    /* resolved through a protected socket for the tunnel diagnostic */
    char           probe_ip[16];
    utun_backend_t tunnel;
    awg_backend_t  awg; /* the amneziawg tunnel, never up together with vless */
    /* senko-core, the xray build that carries a tunnel in place of utun_backend when the profile
       allows it, see settings.core_pref. never up together with the engine's own tunnel */
    xray_backend_t xray;
    tun_source_t   source; /* copied from daemon_ctl_init, senko-core opens its own device through it */
    /* why the last start did not use senko-core, empty when it did or was never tried */
    char           core_note[160];
    /* what the backend actually complained about. the control protocol only
       carries a layer name */
    char           last_reason[192];
/* the server the last start brought up, so a failed tunnel check can probe it
   directly and say why */
    vl_server_t    started;
    int            started_set;
/* wall clock second the engine came up, so the diagnostics can report an age */
    long           started_at;
} daemon_ctl_t;

/* source opens the tun device when a tunnel needs it, see tun_source.h */
void daemon_ctl_init(daemon_ctl_t *d, const char *config_path, const tun_source_t *source);

/* where libsenkocore.so is, empty or never called when this build ships none */
void daemon_ctl_set_core_library(daemon_ctl_t *d, const char *path);

void daemon_ctl_set_settings(daemon_ctl_t *d, const daemon_settings_t *s);
void daemon_ctl_set_rules(daemon_ctl_t *d, ruleset_t *rules);
void daemon_ctl_set_store(daemon_ctl_t *d, store_t *store, const char *geo_dir);

void daemon_ctl_shutdown(daemon_ctl_t *d);

/* notices a tunnel thread that ended on its own. returns -1 when it did, so
   the caller can schedule a redial */
int daemon_ctl_maintain(daemon_ctl_t *d);
/* a descriptor that turns readable when the tunnel thread dies, -1 when there
   is none */
int daemon_ctl_wait_fd(const daemon_ctl_t *d);
int daemon_ctl_stats(void *ctx, uint64_t *up, uint64_t *down);

int daemon_ctl_apply(void *ctx, const ctl_action_t *action);

/* why the last backend start failed; empty when there is nothing to add */
const char *daemon_ctl_last_reason(void *ctx);

void daemon_ctl_persist(void *ctx, const store_t *store);

/* append "DIAG <key> <value>" lines describing what the engine is doing. the
   control server adds what only it knows and streams the result */
int daemon_ctl_diag(void *ctx, char *buf, size_t cap, size_t *len);

int daemon_ctl_fetch(void *ctx, const char *url,
                     const char *request_header,
                     unsigned char *buf, size_t cap, size_t *len,
                     ctl_fetch_meta_t *meta);

/* a download for the geo update job, over the same dialer a subscription uses */
int daemon_ctl_geo_fetch(void *ctx, const char *url, FILE *out, size_t max_bytes,
                         size_t *written, char *error, size_t error_cap);

int daemon_ctl_probe(void *ctx, const char *host, uint16_t port);
int daemon_ctl_probe_server(void *ctx, const vl_server_t *server);

int daemon_ctl_ping_tunnel(void *ctx);

int daemon_ctl_backup(void *ctx, int restore, store_t *store);
int daemon_ctl_check(void *ctx, const char *mode, const vl_server_t *server,
                     ctl_check_trace_t *trace, char *reason, size_t reason_cap);

/* drop the dns answer cache, the direct address table, or the settings */
int daemon_ctl_flush(void *ctx, const char *what, char *reason, size_t reason_cap);

/* amneziawg control for the AWG verb. flag is the verb's helper flag (--awg,
   --awg-stop, --awg-status, --awg-validate, --awg-restart); out gets the status
   line the app reads. returns 0 or 1 like an exit code, or DCTL_AWG_JOB when the
   request has to run as a helper job (the handshake probe) */
#define DCTL_AWG_JOB 2
int daemon_ctl_awg(void *ctx, const char *flag, const char *path, char *out, size_t cap);

/* 1 while the amneziawg tunnel is connecting or up */
int daemon_ctl_awg_busy(void *ctx);

#ifdef __cplusplus
}
#endif

#endif /* daemon_ctl_h */
