#ifndef SENKO_AWG_BACKEND_H
#define SENKO_AWG_BACKEND_H

#include <pthread.h>
#include <stddef.h>
#include <stdint.h>

#include "core/awg_config.h"
#include "core/rules.h"
#include "core/tun_policy.h"
#include "awg_link.h"
#include "awg_split.h"
#include "tun_source.h"
#include "utun_device.h"

#ifdef __cplusplus
extern "C" {
#endif

/* the amneziawg tunnel, run by the engine on its own thread. it shares the
   tun device machinery with the vless tunnel, and the two never run at the
   same time. the profile is read once at start; the app keeps what must
   survive a service restart */

typedef enum {
    AWG_BACKEND_IDLE = 0,
    AWG_BACKEND_CONNECTING,
    AWG_BACKEND_CONNECTED,
    AWG_BACKEND_ERROR
} awg_backend_state_t;

typedef struct {
    pthread_mutex_t lock;
    int initialized;
    int thread_started;
    int thread_done;
    pthread_t thread;
    int wake[2];
    tun_source_t source;

    awg_backend_state_t state; /* guarded by lock, like everything below */
    char detail[224];          /* why it failed */
    char config_path[256];
    char dns_fallback[64];
    char endpoint[64];
    char dns_state[192];
    awg_link_t *link;          /* while connected; its counters use lock */
    awg_split_t *split;        /* while connected with rules that send traffic out; uses lock */
    ruleset_t *rules;          /* borrowed from start until the thread is joined */
    tun_policy_app_fn app_of;  /* how app rules learn a flow's owner, may be NULL */
    void *app_ctx;
    uint64_t bytes_up;         /* totals of links that already ended */
    uint64_t bytes_down;
    uint64_t connected_ms;     /* senko_now_ms() when the link came up */

    /* owned by the tunnel thread */
    awg_config_t cfg;
    utun_device_t device;
    int udp_fd;
} awg_backend_t;

/* source is copied and must stay callable until awg_backend_destroy */
int awg_backend_init(awg_backend_t *backend, const tun_source_t *source);
void awg_backend_destroy(awg_backend_t *backend);

/* checks that path names a readable profile senko can run, then connects in
   the background. dns_fallback is used when the profile names no ipv4 dns.
   rules may be NULL; when they send anything out of the tunnel the link sorts
   packets by them (awg_split.h). rules and the owner lookup must stay valid
   until awg_backend_stop returns */
int awg_backend_start(awg_backend_t *backend, const char *path, const char *dns_fallback,
                      ruleset_t *rules, tun_policy_app_fn app_of, void *app_ctx,
                      char *reason, size_t reason_cap);

/* takes the tunnel down and waits for it */
void awg_backend_stop(awg_backend_t *backend);

/* "idle", "connecting", "connected" or "error <why>", as the app expects */
void awg_backend_status(awg_backend_t *backend, char *out, size_t cap);

/* connecting or connected */
int awg_backend_busy(awg_backend_t *backend);

/* seconds the link has been up, -1 while it is not connected */
long awg_backend_uptime(awg_backend_t *backend);
void awg_backend_stats(awg_backend_t *backend, uint64_t *up, uint64_t *down);

/* one "key value" pair per call for the diagnostics; returns how many exist */
int awg_backend_describe(awg_backend_t *backend, int index, char *key, size_t key_cap,
                         char *value, size_t value_cap);

#ifdef __cplusplus
}
#endif

#endif
