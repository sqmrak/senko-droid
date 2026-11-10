#ifndef SENKO_UTUN_BACKEND_H
#define SENKO_UTUN_BACKEND_H

#include <pthread.h>
#include <stddef.h>
#include <stdint.h>

#include "core/config.h"
#include "core/endpoint_pool.h"
#include "core/senko_paths.h"
#include "core/rules.h"
#include "hy2_engine.h"
#include "tun_dialer.h"
#include "tun_source.h"
#include "tun_loop.h"
#include "tun_tcp.h"
#include "tun_udp.h"
#include "utun_device.h"
#include "direct_socket.h"
#include "core/tun_policy.h"

/* what the diagnostics show, copied on the tunnel's own thread */
typedef struct {
    tun_tcp_stats_t    tcp;
    tun_udp_stats_t    udp;
    tun_policy_stats_t policy;
    tun_stack_stats_t  stack;
    uint64_t           nat_dropped[TUN_NAT_DROP_REASON_COUNT];
    uint64_t           dns_cache_hits;
    uint64_t           dns_cache_misses;
    uint64_t           dns_cache_stale;
    size_t             dns_cache_entries;
    int                direct_map_enabled; /* some rule sends a domain direct */
    dns_policy_counts_t direct_map;
    uint64_t           direct_map_evicted;
} utun_backend_snapshot_t;

typedef struct {
    pthread_mutex_t lock;
    pthread_cond_t changed;
    int initialized;
    int active;
    int thread_started;
    int loop_initialized; /* the loop owns the device only after tun_loop_init */
    int ready;
    int ended;
    pthread_t thread;
    uint64_t generation;
    vl_server_t server;
    utun_device_t device;
    tun_dialer_t dialer;
    hy2_engine_t *hy2; /* hysteria2 dials its quic streams here instead of the dialer */
    tun_loop_t *loop;
    tun_tcp_t *tcp;
    tun_udp_t *udp;
    tun_loop_result_t result;
    tun_policy_t policy;
    int policy_ready;
    tun_policy_app_fn app_of; /* how app rules learn a flow's owner; NULL when they cannot */
    void *app_ctx;
    tun_loop_command_fn previous_command;
    void *previous_command_ctx;
    utun_backend_snapshot_t snapshot; /* guarded by lock */
    uint64_t snapshot_seq;
    char error[256];
    char ca_file[SENKO_PATHS_ROOT_MAX + 32]; /* trust roots for the hysteria2 client */
    tun_source_t source;
/* the tunnel thread writes a byte here as it returns, so the engine's poll
   wakes for a dead tunnel instead of checking on a timer */
    int ended_pipe[2];
} utun_backend_t;

#define UTUN_BACKEND_MAX_ENDPOINTS ENDPOINT_POOL_MAX

/* source is copied and must stay callable until utun_backend_destroy */
int utun_backend_runtime_init(utun_backend_t *backend, const tun_source_t *source);
/* names the owner lookup the next start gives its policy. the lookup must stay
   valid until the backend is stopped */
void utun_backend_set_app_owner(utun_backend_t *backend, tun_policy_app_fn fn, void *ctx);
int utun_backend_start(utun_backend_t *backend, const vl_server_t *server,
                       const char *resolved_ipv4, ruleset_t *rules,
                       const char *dns_upstream, dns_block_response_t block_response,
                       char *reason, size_t reason_cap);
void utun_backend_stop(utun_backend_t *backend);
void utun_backend_destroy(utun_backend_t *backend);
int utun_backend_running(utun_backend_t *backend);
/* readable once the tunnel thread has returned, -1 without a runtime; the
   owner drains it with utun_backend_drain_ended before looking again */
int utun_backend_ended_fd(const utun_backend_t *backend);
void utun_backend_drain_ended(utun_backend_t *backend);
void utun_backend_stats(utun_backend_t *backend, uint64_t *up, uint64_t *down);

/* 0 with the counters copied, -1 when the tunnel is down or did not answer */
int utun_backend_snapshot(utun_backend_t *backend, utun_backend_snapshot_t *out);

/* asks the tunnel thread to forget cached answers or the direct addresses;
   0 when the request was queued */
int utun_backend_flush_dns(utun_backend_t *backend);
int utun_backend_flush_direct(utun_backend_t *backend);

#endif
