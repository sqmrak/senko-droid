#define _DEFAULT_SOURCE

#include "awg_backend.h"

#include "core/senko_time.h"
#include "awg_params.h"
#include "direct_socket.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <openssl/crypto.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define AWG_HANDSHAKE_TIMEOUT_MS 5000
/* the stock datagram buffers drop the tail of a burst from the server while the
   loop thread is busy decrypting, and each lost datagram is a tcp retransmit.
   hysteria2 sizes its socket the same way */
#define AWG_UDP_BUFFER (256 * 1024)

int awg_backend_init(awg_backend_t *backend, const tun_source_t *source) {
    if (!backend || !source || !source->open) return -1;
    memset(backend, 0, sizeof *backend);
    backend->source = *source;
    backend->wake[0] = backend->wake[1] = -1;
    backend->udp_fd = -1;
    utun_device_init(&backend->device);
    if (pthread_mutex_init(&backend->lock, NULL) != 0) return -1;
    backend->initialized = 1;
    return 0;
}

void awg_backend_destroy(awg_backend_t *backend) {
    if (!backend || !backend->initialized) return;
    awg_backend_stop(backend);
    pthread_mutex_destroy(&backend->lock);
    backend->initialized = 0;
}

static void set_detail(awg_backend_t *backend, awg_backend_state_t state, const char *why) {
    pthread_mutex_lock(&backend->lock);
    backend->state = state;
    snprintf(backend->detail, sizeof backend->detail, "%s", why ? why : "");
    pthread_mutex_unlock(&backend->lock);
}

static int resolve_endpoint(const awg_config_t *cfg, char *out, size_t cap,
                            char *why, size_t why_cap) {
    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    int rc = getaddrinfo(cfg->endpoint_host, NULL, &hints, &res);
    if (rc != 0 || !res) {
        snprintf(why, why_cap, "cannot resolve the server %.100s: %s",
                 cfg->endpoint_host, gai_strerror(rc));
        return -1;
    }
    const struct sockaddr_in *sin = (const struct sockaddr_in *)res->ai_addr;
    int ok = inet_ntop(AF_INET, &sin->sin_addr, out, (socklen_t)cap) != NULL;
    freeaddrinfo(res);
    if (!ok) snprintf(why, why_cap, "the server address does not fit");
    return ok ? 0 : -1;
}

static void size_udp_buffers(int fd) {
    int size = AWG_UDP_BUFFER;
    if (setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &size, sizeof size) != 0 ||
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &size, sizeof size) != 0)
        fprintf(stderr, "senko: amneziawg keeps the stock udp buffers (errno %d)\n", errno);
}

static void *run(void *arg) {
    awg_backend_t *b = arg;
    char why[224];
    why[0] = '\0';
    int stopped = 0;
    awg_link_t *link = NULL;
    awg_split_t *split = NULL;
    awg_handshake_t hs;
    memset(&hs, 0, sizeof hs);

    char endpoint[64];
    if (resolve_endpoint(&b->cfg, endpoint, sizeof endpoint, why, sizeof why) != 0)
        goto done;
    pthread_mutex_lock(&b->lock);
    snprintf(b->endpoint, sizeof b->endpoint, "%s", endpoint);
    pthread_mutex_unlock(&b->lock);

    /* protected, so the server keeps being reached outside the tunnel whatever
       the routing table says */
    b->udp_fd = direct_socket_protected(NULL, AF_INET, SOCK_DGRAM, why, sizeof why);
    if (b->udp_fd < 0) goto done;
    size_udp_buffers(b->udp_fd);
    struct sockaddr_in server;
    memset(&server, 0, sizeof server);
    server.sin_family = AF_INET;
    server.sin_port = htons(b->cfg.endpoint_port);
    (void)inet_pton(AF_INET, endpoint, &server.sin_addr);
    if (connect(b->udp_fd, (struct sockaddr *)&server, sizeof server) != 0) {
        snprintf(why, sizeof why, "cannot reach %s:%u (errno %d)", endpoint,
                 (unsigned)b->cfg.endpoint_port, errno);
        goto done;
    }

    char hs_reason[160];
    hs_reason[0] = '\0';
    awg_hs_status_t hr = awg_handshake_establish_fd(b->udp_fd, b->wake[0], &b->cfg,
                                                    AWG_HANDSHAKE_TIMEOUT_MS, &hs,
                                                    hs_reason, sizeof hs_reason);
    if (hr == AWG_HS_CANCELLED) {
        stopped = 1;
        goto done;
    }
    if (hr != AWG_HS_OK) {
        snprintf(why, sizeof why, "handshake with %s:%u failed: %s", endpoint,
                 (unsigned)b->cfg.endpoint_port, hs_reason);
        goto done;
    }

    tun_params_t params;
    if (awg_tun_params(&b->cfg, b->dns_fallback, &params, why, sizeof why) != 0)
        goto done;
    char open_error[160];
    open_error[0] = '\0';
    int tun_fd = b->source.open(b->source.ctx, &params, open_error, sizeof open_error);
    if (tun_fd < 0) {
        snprintf(why, sizeof why, "cannot open the tunnel: %s",
                 open_error[0] ? open_error : "the vpn service refused");
        goto done;
    }
    if (utun_device_adopt_ip(&b->device, tun_fd, "tun") != UTUN_DEVICE_OK) {
        close(tun_fd);
        snprintf(why, sizeof why, "cannot use the tunnel descriptor (errno %d)",
                 b->device.last_errno);
        goto done;
    }
    const char *dns = params.dns4;
    char dns_state[192];
    snprintf(dns_state, sizeof dns_state, "system dns is %s through the tunnel", dns);

    link = calloc(1, sizeof *link);
    if (!link) {
        snprintf(why, sizeof why, "not enough memory for the packet buffers");
        goto done;
    }
    awg_link_init(link, &b->cfg, &b->device, b->udp_fd, b->wake[0], &hs, senko_now_ms());
    link->stats_lock = &b->lock;
    size_t rule_count = b->rules ? b->rules->count : 0;
    if (!awg_split_wanted(b->rules))
        fprintf(stderr, "senko: amneziawg carries everything through the tunnel, %zu rule(s)"
                        " and none sends traffic out of it\n", rule_count);
    if (awg_split_wanted(b->rules)) {
        awg_split_config_t sc;
        memset(&sc, 0, sizeof sc);
        sc.link = link;
        sc.device = &b->device;
        sc.rules = b->rules;
        sc.app_of = b->app_of;
        sc.app_ctx = b->app_ctx;
        sc.wake_fd = b->wake[0];
        sc.mtu = params.mtu;
        (void)inet_pton(AF_INET, dns, sc.dns);
        split = awg_split_open(&sc, why, sizeof why);
        if (!split) goto done;
        fprintf(stderr, "senko: amneziawg sorts packets by %zu rule(s), unmatched traffic goes %s,"
                        " app rules %s\n", rule_count,
                b->rules->fallback == RULE_ACTION_PROXY ? "through the tunnel" : "straight out",
                b->app_of ? "can be decided" : "cannot be decided");
        size_t blocks = awg_split_unenforced(b->rules);
        if (blocks)
            fprintf(stderr, "senko: amneziawg cannot enforce %zu domain block rule(s),"
                            " the lookup travels the tunnel\n", blocks);
    }
    pthread_mutex_lock(&b->lock);
    snprintf(b->dns_state, sizeof b->dns_state, "%s", dns_state);
    b->link = link;
    b->split = split;
    b->state = AWG_BACKEND_CONNECTED;
    b->connected_ms = senko_now_ms();
    pthread_mutex_unlock(&b->lock);
    fprintf(stderr, "senko: amneziawg connected to %s:%u\n", endpoint,
            (unsigned)b->cfg.endpoint_port);

    awg_link_status_t ended = split ? awg_split_run(split, why, sizeof why)
                                    : awg_link_run(link, why, sizeof why);
    if (ended == AWG_LINK_STOPPED) stopped = 1;

done:
    /* describe reads the split under the lock, so it is hidden before it goes */
    pthread_mutex_lock(&b->lock);
    b->split = NULL;
    pthread_mutex_unlock(&b->lock);
    awg_split_close(split);
    utun_device_close(&b->device);
    if (b->udp_fd >= 0) close(b->udp_fd);
    b->udp_fd = -1;
    OPENSSL_cleanse(&hs, sizeof hs);

    pthread_mutex_lock(&b->lock);
    if (link) {
        b->bytes_up += link->bytes_up;
        b->bytes_down += link->bytes_down;
    }
    b->link = NULL;
    b->state = stopped ? AWG_BACKEND_IDLE : AWG_BACKEND_ERROR;
    snprintf(b->detail, sizeof b->detail, "%s", stopped ? "" : why);
    b->dns_state[0] = '\0';
    b->thread_done = 1;
    pthread_mutex_unlock(&b->lock);
    if (link) {
        awg_link_clear(link);
        free(link);
    }
    if (stopped)
        fprintf(stderr, "senko: amneziawg stopped\n");
    else
        fprintf(stderr, "senko: amneziawg failed: %s\n", why);
    return NULL;
}

int awg_backend_start(awg_backend_t *backend, const char *path, const char *dns_fallback,
                      ruleset_t *rules, tun_policy_app_fn app_of, void *app_ctx,
                      char *reason, size_t reason_cap) {
    if (reason && reason_cap) reason[0] = '\0';
    if (!backend || !backend->initialized || !path) return -1;
    if (!awg_config_path_ok(path)) {
        if (reason) snprintf(reason, reason_cap, "the config is not a .conf file in "
                             "the app awg directory: %.120s", path);
        return -1;
    }
    awg_backend_stop(backend);
    char why[128];
    if (awg_config_load_file(path, &backend->cfg, why, sizeof why) != AWG_CFG_OK) {
        if (reason) snprintf(reason, reason_cap, "config rejected: %s", why);
        return -1;
    }
    tun_params_t params;
    if (awg_tun_params(&backend->cfg, dns_fallback, &params, why, sizeof why) != 0) {
        if (reason) snprintf(reason, reason_cap, "%s", why);
        return -1;
    }
    if (pipe(backend->wake) != 0) {
        if (reason) snprintf(reason, reason_cap, "cannot create the stop pipe (errno %d)", errno);
        backend->wake[0] = backend->wake[1] = -1;
        return -1;
    }
    for (int i = 0; i < 2; ++i) (void)fcntl(backend->wake[i], F_SETFD, FD_CLOEXEC);
    pthread_mutex_lock(&backend->lock);
    backend->state = AWG_BACKEND_CONNECTING;
    backend->detail[0] = '\0';
    backend->endpoint[0] = '\0';
    snprintf(backend->config_path, sizeof backend->config_path, "%s", path);
    snprintf(backend->dns_fallback, sizeof backend->dns_fallback, "%s",
             dns_fallback ? dns_fallback : "");
    backend->thread_done = 0;
    backend->rules = rules;
    backend->app_of = app_of;
    backend->app_ctx = app_ctx;
    pthread_mutex_unlock(&backend->lock);
    if (pthread_create(&backend->thread, NULL, run, backend) != 0) {
        close(backend->wake[0]);
        close(backend->wake[1]);
        backend->wake[0] = backend->wake[1] = -1;
        set_detail(backend, AWG_BACKEND_ERROR, "cannot start the tunnel thread");
        if (reason) snprintf(reason, reason_cap, "cannot start the tunnel thread");
        return -1;
    }
    backend->thread_started = 1;
    fprintf(stderr, "senko: amneziawg connecting to %s:%u\n", backend->cfg.endpoint_host,
            (unsigned)backend->cfg.endpoint_port);
    return 0;
}

void awg_backend_stop(awg_backend_t *backend) {
    if (!backend || !backend->initialized) return;
    if (backend->thread_started) {
        char one = 1;
        while (write(backend->wake[1], &one, 1) < 0 && errno == EINTR) {}
        pthread_join(backend->thread, NULL);
        backend->thread_started = 0;
    }
    if (backend->wake[0] >= 0) close(backend->wake[0]);
    if (backend->wake[1] >= 0) close(backend->wake[1]);
    backend->wake[0] = backend->wake[1] = -1;
    OPENSSL_cleanse(&backend->cfg, sizeof backend->cfg);
}

void awg_backend_status(awg_backend_t *backend, char *out, size_t cap) {
    if (!out || !cap) return;
    if (!backend || !backend->initialized) {
        snprintf(out, cap, "idle");
        return;
    }
    pthread_mutex_lock(&backend->lock);
    switch (backend->state) {
    case AWG_BACKEND_CONNECTING: snprintf(out, cap, "connecting"); break;
    case AWG_BACKEND_CONNECTED:  snprintf(out, cap, "connected"); break;
    case AWG_BACKEND_ERROR:      snprintf(out, cap, "error %s", backend->detail); break;
    default:                     snprintf(out, cap, "idle"); break;
    }
    pthread_mutex_unlock(&backend->lock);
}

int awg_backend_busy(awg_backend_t *backend) {
    if (!backend || !backend->initialized) return 0;
    pthread_mutex_lock(&backend->lock);
    int busy = backend->state == AWG_BACKEND_CONNECTING ||
               backend->state == AWG_BACKEND_CONNECTED;
    pthread_mutex_unlock(&backend->lock);
    return busy;
}

long awg_backend_uptime(awg_backend_t *backend) {
    if (!backend || !backend->initialized) return -1;
    pthread_mutex_lock(&backend->lock);
    long seconds = -1;
    if (backend->state == AWG_BACKEND_CONNECTED)
        seconds = (long)((senko_now_ms() - backend->connected_ms) / 1000);
    pthread_mutex_unlock(&backend->lock);
    return seconds;
}

void awg_backend_stats(awg_backend_t *backend, uint64_t *up, uint64_t *down) {
    if (up) *up = 0;
    if (down) *down = 0;
    if (!backend || !backend->initialized) return;
    pthread_mutex_lock(&backend->lock);
    uint64_t u = backend->bytes_up, d = backend->bytes_down;
    if (backend->link) {
        u += backend->link->bytes_up;
        d += backend->link->bytes_down;
    }
    pthread_mutex_unlock(&backend->lock);
    if (up) *up = u;
    if (down) *down = d;
}

int awg_backend_describe(awg_backend_t *backend, int index, char *key, size_t key_cap,
                         char *value, size_t value_cap) {
    static const char *keys[] = { "awg.state", "awg.profile", "awg.endpoint", "awg.dns", "awg.link",
                                  "awg.rules" };
    const int count = (int)(sizeof keys / sizeof keys[0]);
    if (!backend || !backend->initialized || index < 0 || index >= count) return count;
    snprintf(key, key_cap, "%s", keys[index]);
    if (index == 0) {
        awg_backend_status(backend, value, value_cap);
        return count;
    }
    pthread_mutex_lock(&backend->lock);
    switch (index) {
    case 1:
        snprintf(value, value_cap, "%s", backend->config_path[0] ? backend->config_path : "none");
        break;
    case 2:
        snprintf(value, value_cap, "%s", backend->endpoint[0] ? backend->endpoint : "none");
        break;
    case 3:
        snprintf(value, value_cap, "%s", backend->dns_state[0] ? backend->dns_state : "untouched");
        break;
    case 5:
        if (backend->split) {
            awg_split_counts_t counts;
            awg_split_counts(backend->split, &counts);
            snprintf(value, value_cap, "%u flow(s) through the tunnel, %u sent out locally,"
                     " %u forgotten, direct tcp %u refused %u aborted",
                     (unsigned)counts.tunnel_flows, (unsigned)counts.local_flows,
                     (unsigned)counts.forgotten, (unsigned)counts.tcp_refused,
                     (unsigned)counts.tcp_aborted);
        } else {
            snprintf(value, value_cap, "no rule sends traffic out of the tunnel");
        }
        break;
    default:
        if (backend->link)
            snprintf(value, value_cap, "%u key renewal(s), %u packet(s) dropped",
                     (unsigned)backend->link->renewals, (unsigned)backend->link->dropped);
        else
            snprintf(value, value_cap, "down");
        break;
    }
    pthread_mutex_unlock(&backend->lock);
    return count;
}
