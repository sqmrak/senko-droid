#define _DEFAULT_SOURCE /* expose getaddrinfo */

#include "daemon_ctl.h"
#include "storefile.h"

#include "awg_probe.h"
#include "core/blake2b256.h"
#include "core/control.h"
#include "core/dns_cache.h"
#include "core/dns_msg.h"
#include "core/net_diag.h"
#include "core/net_protect.h"
#include "core/net_safe.h"
#include "core/real_probe.h"
#include "core/routing_profile.h"
#include "core/senko_clock.h"
#include "core/senko_paths.h"
#include "core/senko_trace.h"
#include "core/subfetch.h"
#include "core/tls_clienthello.h"
#include "core/transport.h"
#include "core/transport_pick.h"
#include "core/url.h"
#include "core/vless.h"
#include "core/vlenc_conf.h"
#include "direct_dns.h"
#include "direct_socket.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/tcp.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <openssl/crypto.h>
#include <openssl/rand.h>

void daemon_ctl_init(daemon_ctl_t *d, const char *config_path, const tun_source_t *source) {
    if (!d) return;
    memset(d, 0, sizeof *d);
    if (utun_backend_runtime_init(&d->tunnel, source) != 0)
        fprintf(stderr, "senko: tunnel initialization failed\n");
    if (awg_backend_init(&d->awg, source) != 0)
        fprintf(stderr, "senko: amneziawg initialization failed\n");
    xray_backend_init(&d->xray, NULL);
    d->source = source ? *source : (tun_source_t){ 0 };
    d->started_at = ctl_engine_now();
    if (config_path) {
        size_t l = strlen(config_path);
        if (l >= sizeof d->config_path) l = sizeof d->config_path - 1;
        memcpy(d->config_path, config_path, l);
        d->config_path[l] = '\0';
    }
}

void daemon_ctl_set_settings(daemon_ctl_t *d, const daemon_settings_t *s) {
    if (!d || !s) return;
    d->settings = *s;
    senko_trace_set_enabled(d->settings.trace);
}

void daemon_ctl_set_rules(daemon_ctl_t *d, ruleset_t *rules) {
    if (d) d->rules = rules;
}

void daemon_ctl_set_store(daemon_ctl_t *d, store_t *store, const char *geo_dir) {
    if (!d) return;
    d->store = store;
    snprintf(d->geo_dir, sizeof d->geo_dir, "%s", geo_dir ? geo_dir : "");
}

/* what the running tunnel reads out of the ruleset: the loaded geo sets. they
   go only after the tunnel that used them has stopped */
static void release_routing(daemon_ctl_t *d) {
    /* a running amneziawg tunnel reads the same ruleset, and its own stop
       releases it */
    if (awg_backend_busy(&d->awg)) return;
    if (d->rules) ruleset_release_geo(d->rules);
}

/* rebuilds the routing profile's rules from the catalog. runs with no tunnel up, because a
   running one reads the ruleset */
static void prepare_routing(daemon_ctl_t *d) {
    routing_info_t info;
    if (!d->rules) return;
    if (d->store) {
        store_status_t status = store_apply_routing(d->store, &info);
        if (status != STORE_OK)
            fprintf(stderr, "senko: the routing profile could not be applied (%d)\n", (int)status);
        else if (d->store->routing_active >= 0)
            fprintf(stderr, "senko: routing profile \"%s\": %zu rule(s), %zu entr%s senko cannot match,"
                            " unmatched traffic goes %s\n",
                    info.name, info.rules, info.unsupported, info.unsupported == 1 ? "y" : "ies",
                    d->rules->fallback == RULE_ACTION_DIRECT ? "straight out" : "through the tunnel");
    }
    /* the engine cannot name the app behind a flow. android 5 and later route
       whole apps through the vpn service instead */
    if (ruleset_has_type(d->rules, RULE_TYPE_APP))
        fprintf(stderr, "senko: app rules are decided by the vpn service, the engine"
                        " leaves them unmatched\n");
}

/* loads the geo sets the rules name, for the backends that match flows themselves. senko-core
   reads the dat files on its own, so a tunnel it carries skips this and its start is shorter
   by the time the sets take to parse */
static void bind_routing_geo(daemon_ctl_t *d) {
    size_t missing = 0;
    if (!d->rules || !d->geo_dir[0]) return;
    if (ruleset_bind_geo(d->rules, d->geo_dir, &missing) != RULES_OK)
        fprintf(stderr, "senko: the geo data could not be loaded\n");
    if (missing)
        fprintf(stderr, "senko: %zu geo rule(s) have no data yet and match nothing until"
                        " the geo files are downloaded\n", missing);
}

void daemon_ctl_set_core_library(daemon_ctl_t *d, const char *path) {
    if (!d) return;
    xray_backend_init(&d->xray, path);
    /* the profiles only senko-core carries are listed as runnable only while it can be loaded */
    cfg_set_core_available(path && path[0] && access(path, R_OK) == 0);
}

void daemon_ctl_shutdown(daemon_ctl_t *d) {
    if (!d) return;
    xray_backend_stop(&d->xray);
    utun_backend_destroy(&d->tunnel);
    /* the tunnel goes first because it reads the rules release_routing frees */
    awg_backend_destroy(&d->awg);
    release_routing(d);
    OPENSSL_cleanse(&d->started, sizeof d->started);
    d->started_set = 0;
}

int daemon_ctl_wait_fd(const daemon_ctl_t *d) {
    return d ? utun_backend_ended_fd(&d->tunnel) : -1;
}

int daemon_ctl_maintain(daemon_ctl_t *d) {
    if (!d) return 0;
    utun_backend_drain_ended(&d->tunnel);
    if (d->xray.active && !xray_backend_running(&d->xray)) {
        fprintf(stderr, "senko: senko-core stopped on its own\n");
        xray_backend_stop(&d->xray);
        release_routing(d);
        return -1;
    }
    if (d->tunnel.active && !utun_backend_running(&d->tunnel)) {
        fprintf(stderr, "senko: the tunnel packet loop exited: %s\n",
                d->tunnel.result.message);
        utun_backend_stop(&d->tunnel);
        release_routing(d);
        return -1;
    }
    return 0;
}

int daemon_ctl_stats(void *ctx, uint64_t *up, uint64_t *down) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    if (up) *up = 0;
    if (down) *down = 0;
    if (!d || !up || !down) return -1;
    if (awg_backend_busy(&d->awg)) {
        awg_backend_stats(&d->awg, up, down);
        return 0;
    }
    if (d->xray.active) {
        xray_backend_stats(&d->xray, up, down);
        return 0;
    }
    if (d->tunnel.active) {
        utun_backend_stats(&d->tunnel, up, down);
        return 0;
    }
    return 0;
}

static int ipv4_list_contains(const char *list, const char *ip) {
    size_t ip_len = strlen(ip);
    const char *p = list;
    while (p && *p) {
        while (*p == ' ' || *p == ',') ++p;
        const char *item_end = strchr(p, ',');
        if (!item_end) item_end = p + strlen(p);
        while (item_end > p && item_end[-1] == ' ') --item_end;
        if ((size_t)(item_end - p) == ip_len && memcmp(p, ip, ip_len) == 0)
            return 1;
        p = *item_end ? item_end + 1 : item_end;
    }
    return 0;
}

static int ipv4_list_append(char *list, size_t cap, const char *ip) {
    if (!list || cap == 0 || ipv4_list_contains(list, ip)) return 0;
    size_t used = strlen(list);
    int n = snprintf(list + used, cap - used, "%s%s", used ? ", " : "", ip);
    return n < 0 || (size_t)n >= cap - used ? -1 : 0;
}

static int resolve_ipv4_addresses(const char *host, char *first_ip, size_t first_cap,
                                  char *ip_list, size_t list_cap,
                                  int reject_unsafe) {
    if (!host || !first_ip || first_cap == 0) return -1;
    first_ip[0] = '\0';
    if (ip_list && list_cap) ip_list[0] = '\0';

    struct in_addr literal;
    if (inet_aton(host, &literal)) {
        struct sockaddr_in addr;
        memset(&addr, 0, sizeof addr);
        addr.sin_family = AF_INET;
        addr.sin_addr = literal;
        if (reject_unsafe && !net_addr_allowed((struct sockaddr *)&addr)) return -1;
        char ip[INET_ADDRSTRLEN];
        if (!inet_ntop(AF_INET, &literal, ip, sizeof ip)) return -1;
        int n = snprintf(first_ip, first_cap, "%s", ip);
        if (n < 0 || (size_t)n >= first_cap) return -1;
        return ipv4_list_append(ip_list, list_cap, ip);
    }

    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_INET; /* use ipv4 for routing */
    hints.ai_socktype = SOCK_STREAM;
    if (net_getaddrinfo_timed(host, NULL, &hints, &res, 2000) != 0 || !res) return -1;

    int ok = 0;
    for (struct addrinfo *ai = res; ai; ai = ai->ai_next) {
        if (ai->ai_family != AF_INET) continue;
        if (reject_unsafe && !net_addr_allowed(ai->ai_addr)) {
            ok = -1;
            break;
        }
        char ip[INET_ADDRSTRLEN];
        struct sockaddr_in *sin = (struct sockaddr_in *)ai->ai_addr;
        if (!inet_ntop(AF_INET, &sin->sin_addr, ip, sizeof ip)) continue;
        if (!first_ip[0]) {
            int n = snprintf(first_ip, first_cap, "%s", ip);
            if (n < 0 || (size_t)n >= first_cap) {
                ok = -1;
                break;
            }
        }
        if (ipv4_list_append(ip_list, list_cap, ip) != 0) {
            ok = -1;
            break;
        }
        ok = 1;
    }
    freeaddrinfo(res);
    return ok > 0 && first_ip[0] ? 0 : -1;
}

/* resolve the diagnostic target outside the tunnel being checked */
static void resolve_probe_upstream(daemon_ctl_t *d) {
    uint32_t addresses[DNS_MSG_MAX_IPV4];
    size_t count = 0;
    if (direct_dns_ipv4(d->settings.dns_upstream, 53, "example.com", 2000,
                        addresses, DNS_MSG_MAX_IPV4, &count) != 0)
        return;
    for (size_t i = 0; i < count; ++i) {
        struct sockaddr_in sin;
        memset(&sin, 0, sizeof sin);
        sin.sin_family = AF_INET;
        sin.sin_addr.s_addr = addresses[i];
        if (!net_addr_allowed((struct sockaddr *)&sin)) continue;
        if (inet_ntop(AF_INET, &sin.sin_addr, d->probe_ip, sizeof d->probe_ip)) {
            fprintf(stderr, "senko: probe address %s came from the configured resolver\n",
                    d->probe_ip);
            return;
        }
    }
}

/* 0 when senko-core carries the tunnel, 1 when the engine's own backend must, -1 when the user
   insisted on senko-core and it cannot. in auto the reason for a fallback is logged and kept for
   the diagnostics, so a profile that quietly ran on the other backend can be explained */
static int start_core(daemon_ctl_t *d, const vl_server_t *s, const char *endpoint_ip, int needs_core,
                      char *reason, size_t reason_cap) {
    int pref = d->settings.core_pref;
    /* the engine's own backend cannot carry this profile, so the user's choice of it cannot be kept */
    if (needs_core && pref == SETTINGS_CORE_ENGINE) pref = SETTINGS_CORE_AUTO;
    if (pref == SETTINGS_CORE_ENGINE) return 1;
    char why[160] = "";
    int usable = 0;
    /* geoip.dat and geosite.dat sit in the geo directory beside the per tag files, see geo_update */
    if (xray_backend_can_carry(s, d->rules, d->geo_dir, why, sizeof why) && xray_backend_load(&d->xray, why, sizeof why) == 0) {
        char ca[320];
        xray_backend_set_assets(&d->xray, d->geo_dir);
        if (senko_path_join(ca, sizeof ca, SENKO_REL_CA_BUNDLE) != SENKO_PATHS_OK) {
            snprintf(why, sizeof why, "the roots file path is unusable");
        } else if (xray_backend_start(&d->xray, s, endpoint_ip, d->rules, d->geo_dir, ca,
                                      d->settings.dns_upstream, &d->source, why, sizeof why) == 0) {
            return 0;
        }
        usable = 1;
    }
    (void)usable;
    if (pref == SETTINGS_CORE_XRAY || needs_core) {
        snprintf(reason, reason_cap, "senko-core: %s", why[0] ? why : "unavailable");
        return -1;
    }
    snprintf(d->core_note, sizeof d->core_note, "%s", why);
    fprintf(stderr, "senko: not using senko-core: %s\n", why[0] ? why : "unavailable");
    return 1;
}

int daemon_ctl_apply(void *ctx, const ctl_action_t *action) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    if (!d || !action) return -1;

    switch (action->kind) {
        case CTL_ACT_START: {
            const vl_server_t *s = &action->server;
            d->started = *s;
            d->started_set = 1;
            /* quic only: no senko transport carries it, the tunnel has its own
               client (hy2_engine) for it */
            int quic_only = (s->proto == VL_PROTO_HYSTERIA2);

            /* a profile only senko-core can carry has no transport of the engine's own */
            int needs_core = cfg_server_needs_core(s, NULL, 0);
            const transport_vt_t *vt = quic_only || needs_core ? NULL : transport_for_server(s);
            if (!vt && !quic_only && !needs_core) {
                fprintf(stderr, "senko: unsupported transport/security for server\n");
                return DCTL_ERR_TRANSPORT;
            }

            uint8_t uuid[VLESS_UUID_LEN];
            memset(uuid, 0, sizeof uuid);
            if (cfg_proto_uses_uuid(s->proto) && !needs_core) {
                if (vless_uuid_parse(s->uuid, uuid) != VLESS_OK) {
                    fprintf(stderr, "senko: bad uuid in server link\n");
                    return DCTL_ERR_UUID;
                }
            }

            utun_backend_stop(&d->tunnel);
            xray_backend_stop(&d->xray);
            release_routing(d);
            prepare_routing(d);

            char first_ip[64], ip_list[4096];
            first_ip[0] = '\0';
            ip_list[0] = '\0';

            if (resolve_ipv4_addresses(s->host, first_ip, sizeof first_ip,
                                       ip_list, sizeof ip_list, 0) != 0) {
                fprintf(stderr, "senko: dns resolution failed for %s\n", s->host);
                release_routing(d);
                return DCTL_ERR_DNS;
            }

            char backend_reason[160];
            backend_reason[0] = '\0';
            d->last_reason[0] = '\0';
            d->core_note[0] = '\0';
            int core_result = start_core(d, s, first_ip, needs_core, backend_reason, sizeof backend_reason);
            if (core_result == 0) return 0;
            if (core_result < 0) {
                fprintf(stderr, "senko: tunnel failed: %s\n", backend_reason);
                snprintf(d->last_reason, sizeof d->last_reason, "%s", backend_reason);
                release_routing(d);
                return DCTL_ERR_UTUN;
            }
            bind_routing_geo(d);
            int started = utun_backend_start(&d->tunnel, s, ip_list,
                d->rules, d->settings.dns_upstream,
                d->settings.block_response, backend_reason,
                sizeof backend_reason) == 0;
            if (!started) {
                fprintf(stderr, "senko: tunnel failed: %s\n",
                        backend_reason[0] ? backend_reason : "unknown error");
                snprintf(d->last_reason, sizeof d->last_reason, "%s", backend_reason);
                release_routing(d);
                return DCTL_ERR_UTUN;
            }
            return 0;
        }

        case CTL_ACT_STOP:
            OPENSSL_cleanse(&d->started, sizeof d->started);
            d->started_set = 0;
            utun_backend_stop(&d->tunnel);
            xray_backend_stop(&d->xray);
            release_routing(d);
            return 0;

        case CTL_ACT_PING:
            return 0;

        case CTL_ACT_REFRESH:
            return -1;

        case CTL_ACT_SET: {
/* the engine holds the only writable copy, so the control server hands the
   pair over instead of keeping settings the two could disagree about */
            settings_status_t r = daemon_settings_set(&d->settings,
                                                      action->key,
                                                      strlen(action->key),
                                                      action->value,
                                                      strlen(action->value));
            if (r == SETTINGS_ERR_KEY) return DCTL_ERR_SETTING_KEY;
            if (r != SETTINGS_OK) return DCTL_ERR_SETTING_VALUE;
            senko_trace_set_enabled(d->settings.trace);
            return 0;
        }

        case CTL_ACT_NONE:
        default:
            return 0;
    }
}

const char *daemon_ctl_last_reason(void *ctx) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    return d && d->last_reason[0] ? d->last_reason : NULL;
}

/* every fact is appended through one helper so a full buffer stops the report
   instead of writing a half line the reader would show as a fact */
static void diag_add(char *buf, size_t cap, size_t *off, const char *key,
                     const char *fmt, ...) {
    char value[192];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(value, sizeof value, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    size_t written = 0;
    if (ctl_build_diag(key, value, buf + *off, cap - *off, &written) == CTL_OK)
        *off += written;
}

/* the rules that are doing something. a rule list nobody can see hit counts for
   is a list of guesses */
static void diag_tunnel(daemon_ctl_t *d, char *buf, size_t cap, size_t *off) {
    utun_backend_t *u = &d->tunnel;
    utun_backend_snapshot_t snap;
    if (utun_backend_snapshot(u, &snap) != 0) {
        diag_add(buf, cap, off, "tunnel.counters", "the tunnel thread did not answer in 1 s");
        return;
    }
    diag_add(buf, cap, off, "tunnel.tcp",
             "%llu opened, %llu refused, %llu reset, %llu finished, %llu direct, %llu blocked",
             (unsigned long long)snap.tcp.opened, (unsigned long long)snap.tcp.refused,
             (unsigned long long)snap.tcp.aborted, (unsigned long long)snap.tcp.finished,
             (unsigned long long)snap.tcp.direct, (unsigned long long)snap.tcp.blocked);
    diag_add(buf, cap, off, "tunnel.tcp.bytes", "%llu up, %llu down",
             (unsigned long long)snap.tcp.bytes_to_server,
             (unsigned long long)snap.tcp.bytes_to_app);
    if (snap.tcp.last_error[0])
        diag_add(buf, cap, off, "tunnel.tcp.last_error", "%s", snap.tcp.last_error);
    diag_add(buf, cap, off, "tunnel.udp",
             "%llu in, %llu out, %llu direct, %llu blocked, %llu refused, %llu expired",
             (unsigned long long)snap.udp.received, (unsigned long long)snap.udp.sent,
             (unsigned long long)snap.udp.direct, (unsigned long long)snap.udp.blocked,
             (unsigned long long)snap.udp.refused, (unsigned long long)snap.udp.expired);
    if (snap.udp.last_error[0])
        diag_add(buf, cap, off, "tunnel.udp.last_error", "%s", snap.udp.last_error);
    diag_add(buf, cap, off, "tunnel.packets",
             "%llu in, %llu out, %llu queued, %llu queue full, %llu write error(s)",
             (unsigned long long)snap.stack.frames_in, (unsigned long long)snap.stack.frames_out,
             (unsigned long long)snap.stack.queued, (unsigned long long)snap.stack.queue_full,
             (unsigned long long)snap.stack.write_errors);
    for (int i = 1; i < TUN_NAT_DROP_REASON_COUNT; ++i)
        if (snap.nat_dropped[i])
            diag_add(buf, cap, off, "tunnel.dropped", "%llu: %s",
                     (unsigned long long)snap.nat_dropped[i],
                     tun_nat_drop_text((tun_nat_drop_t)i));
    diag_add(buf, cap, off, "dns.queries",
             "%llu, %llu blocked, %llu cached, %llu stale, %llu unanswered",
             (unsigned long long)snap.policy.dns_queries,
             (unsigned long long)snap.policy.dns_blocked,
             (unsigned long long)snap.policy.dns_cache_hits,
             (unsigned long long)snap.policy.dns_stale_answers,
             (unsigned long long)snap.policy.dns_failed);
    diag_add(buf, cap, off, "dns.cache", "%llu hit, %llu miss, %llu stale, %zu of %d entries",
             (unsigned long long)snap.dns_cache_hits, (unsigned long long)snap.dns_cache_misses,
             (unsigned long long)snap.dns_cache_stale, snap.dns_cache_entries, DNS_CACHE_CAP);
    diag_add(buf, cap, off, "rules.flows", "%llu proxy, %llu direct, %llu blocked",
             (unsigned long long)snap.policy.flows_proxy,
             (unsigned long long)snap.policy.flows_direct,
             (unsigned long long)snap.policy.flows_blocked);
    if (snap.direct_map_enabled)
        diag_add(buf, cap, off, "rules.direct_addresses",
                 "%zu known, %zu direct, %llu dropped by capacity",
                 snap.direct_map.addresses, snap.direct_map.direct,
                 (unsigned long long)snap.direct_map_evicted);
}

static void diag_top_rules(const ruleset_t *rules, char *buf, size_t cap,
                           size_t *off) {
    size_t best[3];
    size_t found = 0;
    if (!rules || rules->count == 0) return;
    for (size_t round = 0; round < 3; ++round) {
        size_t pick = rules->count;
        uint64_t best_hits = 0;
        for (size_t i = 0; i < rules->count; ++i) {
            uint64_t hits = rule_hit_count(&rules->entries[i]);
            if (hits == 0) continue;
            int taken = 0;
            for (size_t j = 0; j < found; ++j) if (best[j] == i) taken = 1;
            if (taken) continue;
            if (pick == rules->count || hits > best_hits) {
                pick = i;
                best_hits = hits;
            }
        }
        if (pick == rules->count) break;
        best[found++] = pick;
    }
    if (found == 0) {
        diag_add(buf, cap, off, "rules.top", "no rule has matched yet");
        return;
    }
    for (size_t i = 0; i < found; ++i) {
        const rule_t *rule = &rules->entries[best[i]];
        char key[24];
        snprintf(key, sizeof key, "rules.top%zu", i + 1);
        diag_add(buf, cap, off, key, "%llu hit(s), %s %s %s",
                 (unsigned long long)rule_hit_count(rule),
                 rule_action_name(rule->action), rule_type_name(rule->type),
                 rule->value);
    }
}

/* which profile the rules came from */
static void diag_routing(const daemon_ctl_t *d, char *buf, size_t cap, size_t *off) {
    if (d->store && d->store->routing_active >= 0)
        diag_add(buf, cap, off, "routing.profile", "subscription %d, unmatched traffic goes %s",
                 d->store->routing_active,
                 d->rules && d->rules->fallback == RULE_ACTION_DIRECT ? "straight out"
                                                                      : "through the tunnel");
}

int daemon_ctl_diag(void *ctx, char *buf, size_t cap, size_t *len) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    size_t off = 0;
    char path[SENKO_PATHS_ROOT_MAX + 32];
    if (!d || !buf || !len || cap == 0) return -1;
    *len = 0;

    diag_add(buf, cap, &off, "engine.pid", "%ld", (long)getpid());
    diag_add(buf, cap, &off, "engine.uptime", "%ld s",
             (long)(ctl_engine_now() - d->started_at));
    diag_add(buf, cap, &off, "engine.config", "%s",
             d->config_path[0] ? d->config_path : "none");
    diag_add(buf, cap, &off, "backend", "%s", d->xray.active ? "senko-core" : d->tunnel.active ? "tunnel" : "idle");
    if (d->xray.active && d->xray.version) diag_add(buf, cap, &off, "backend.core", "xray %s", d->xray.version);
    if (d->core_note[0]) diag_add(buf, cap, &off, "backend.core_skipped", "%s", d->core_note);
    if (d->tunnel.active) diag_tunnel(d, buf, cap, &off);
    diag_add(buf, cap, &off, "dns.upstream", "%s", d->settings.dns_upstream);
    diag_add(buf, cap, &off, "dns.block_response", "%s",
             d->settings.block_response == DNS_BLOCK_NXDOMAIN ? "nxdomain" :
             d->settings.block_response == DNS_BLOCK_REFUSED ? "refused" : "zero");
    diag_top_rules(d->rules, buf, cap, &off);
    diag_routing(d, buf, cap, &off);

    diag_add(buf, cap, &off, "sub.user_agent", "Happ/3.26.1/ios");
    if (d->settings.sub_ignore_gating)
        diag_add(buf, cap, &off, "sub.gating", "ignored, placeholder feeds accepted");
    diag_add(buf, cap, &off, "trace", "%s",
             d->settings.trace ? "on, one line per session event" : "off");

    if (senko_path_join(path, sizeof path, SENKO_REL_HWID) == SENKO_PATHS_OK)
        diag_add(buf, cap, &off, "path.hwid", "%s", path);
    if (senko_path_join(path, sizeof path, SENKO_REL_CA_BUNDLE) == SENKO_PATHS_OK)
        diag_add(buf, cap, &off, "path.roots", "%s", path);

    int awg_facts = awg_backend_describe(&d->awg, -1, NULL, 0, NULL, 0);
    for (int i = 0; i < awg_facts; ++i) {
        char key[32], value[192];
        awg_backend_describe(&d->awg, i, key, sizeof key, value, sizeof value);
        diag_add(buf, cap, &off, key, "%s", value);
    }
    if (d->last_reason[0])
        diag_add(buf, cap, &off, "backend.last_error", "%s", d->last_reason);

    *len = off;
    return 0;
}

int daemon_ctl_flush(void *ctx, const char *what, char *reason, size_t reason_cap) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    if (!d || !what) return -1;
    if (strcmp(what, "dns") == 0) {
        if (utun_backend_flush_dns(&d->tunnel) != 0) {
            if (reason && reason_cap)
                snprintf(reason, reason_cap, "no dns cache: the tunnel is not running");
            return -1;
        }
        return 0;
    }
    /* "bypass" is the name the ios pf table had; it is the direct address table now */
    if (strcmp(what, "bypass") == 0 || strcmp(what, "direct") == 0) {
        if (utun_backend_flush_direct(&d->tunnel) != 0) {
            if (reason && reason_cap)
                snprintf(reason, reason_cap,
                         "no direct address table: the tunnel is not running");
            return -1;
        }
        return 0;
    }
    if (strcmp(what, "config") == 0) {
/* only the knobs go back to their defaults. the catalog is the user's, and
   losing it to a settings reset is not something a confirmation covers */
        daemon_settings_defaults(&d->settings);
        senko_trace_set_enabled(d->settings.trace);
        return 0;
    }
    if (reason && reason_cap) snprintf(reason, reason_cap, "unknown flush target");
    return -1;
}

/* persist runs after a command finished, so no import is half done. the
   profiles the store and the running tunnel do not name are released, or a
   subscription that rotates its keys would fill the pool for good */
static void release_unused_vlenc(const daemon_ctl_t *d, const store_t *store) {
    uint16_t ids[STORE_MAX_SERVERS + 1];
    size_t n = 0;
    for (size_t i = 0; i < store->n && i < STORE_MAX_SERVERS; ++i)
        if (store->servers[i].venc) ids[n++] = store->servers[i].venc;
    if (utun_backend_running(&d->tunnel) && d->tunnel.server.venc) ids[n++] = d->tunnel.server.venc;
    vlenc_pool_retain(ids, n);
}

void daemon_ctl_persist(void *ctx, const store_t *store) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    if (!d || !store || !d->config_path[0]) return;
    storefile_save(store, &d->settings, d->config_path);
    release_unused_vlenc(d, store);
}

/* the app hands the exported file to the user and stages an imported one in
   the same directory */
int daemon_ctl_backup(void *ctx, int restore, store_t *store) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    char path[SENKO_PATHS_ROOT_MAX + 32];
    if (!d || !store || !d->config_path[0]) return -1;
    if (senko_path_join(path, sizeof path,
                        restore ? SENKO_REL_BACKUP_IMPORT : SENKO_REL_BACKUP_EXPORT) !=
        SENKO_PATHS_OK)
        return -1;
    if (!restore)
        return storefile_save(store, &d->settings, path) == STOREFILE_OK ? 0 : -1;
    struct stat staged;
    if (lstat(path, &staged) != 0 || !S_ISREG(staged.st_mode))
        return -1;
    /* a store is larger than a small thread stack, and control commands are
       handled one at a time, so one static staging copy is enough */
    static store_t candidate;
    daemon_settings_t candidate_settings;
    if (storefile_load(&candidate, &candidate_settings, path) != STOREFILE_OK)
        return -1;
    if (storefile_save(&candidate, &candidate_settings, d->config_path) != STOREFILE_OK)
        return -1;
    *store = candidate;
    d->settings = candidate_settings;
    (void)unlink(path);
    return 0;
}

/* wall-clock adjustments must not turn one tcp connect into a false latency
   spike */
static long probe_now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0)
        return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

/* record the step a check just finished. the slot is empty on every path but a
   check that asked for stages, so this is a branch the normal path pays and
   nothing more */
static pthread_key_t trace_key;
static pthread_once_t trace_key_once = PTHREAD_ONCE_INIT;
static int trace_key_error;

static void make_trace_key(void) {
    trace_key_error = pthread_key_create(&trace_key, NULL);
}

static void stage_mark(daemon_ctl_t *d, const char *name, int ok) {
    (void)d;
    pthread_once(&trace_key_once, make_trace_key);
    ctl_check_trace_t *trace = trace_key_error ? NULL : pthread_getspecific(trace_key);
    if (!trace || trace->count >= CTL_CHECK_STAGE_MAX) return;
    ctl_check_stage_t *stage = &trace->stages[trace->count++];
    snprintf(stage->name, sizeof stage->name, "%s", name ? name : "?");
    stage->ms = (int)(probe_now_ms() - trace->started_ms);
    stage->ok = ok ? 1 : 0;
}

static int write_all_until(int fd, const void *buf, size_t len, long deadline_ms) {
    size_t off = 0;
    while (off < len) {
        if (probe_now_ms() >= deadline_ms) return -1;
        ssize_t w = write(fd, (const uint8_t *)buf + off, len - off);
        if (w > 0) { off += (size_t)w; continue; }
        if (w < 0 && errno == EINTR) continue;
        if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pfd = { fd, POLLOUT, 0 };
            poll(&pfd, 1, 10);
            continue;
        }
        return -1;
    }
    return 0;
}

/* subscription sockets use the default route first, then bypass our vpn if it fails */
/* the segment size of a split connection: the tls hello leaves in several small packets */
#define SPLIT_MSS 96

static int connect_subscription(const struct sockaddr *addr, socklen_t addr_len, long deadline,
                                int bypass_vpn, int split, char *why, size_t why_cap) {
    char socket_error[96];
    int fd;
    if (bypass_vpn) {
        fd = direct_socket_protected(NULL, addr->sa_family, SOCK_STREAM,
                                     socket_error, sizeof socket_error);
    } else {
        fd = socket(addr->sa_family, SOCK_STREAM, 0);
        if (fd >= 0) {
            int flags = fcntl(fd, F_GETFL, 0);
            if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0 ||
                fcntl(fd, F_SETFD, FD_CLOEXEC) != 0) {
                int error = errno;
                close(fd);
                fd = -1;
                errno = error;
            }
        }
        if (fd < 0) snprintf(socket_error, sizeof socket_error, "cannot open socket (errno %d %s)",
                             errno, strerror(errno));
    }
    if (fd < 0) {
        snprintf(why, why_cap, "%s", socket_error);
        return -1;
    }
    /* set before connect so the handshake itself is cut: a middlebox that reads the server name from
       the first packet of the hello sees only a fragment of it */
    if (split) {
        int mss = SPLIT_MSS;
        if (setsockopt(fd, IPPROTO_TCP, TCP_MAXSEG, &mss, sizeof mss) != 0)
            fprintf(stderr, "senko: cannot shrink the segment size (errno %d)\n", errno);
    }
    int error = 0;
    if (connect(fd, addr, addr_len) == 0) return fd;
    error = errno;
    if (error == EINPROGRESS) {
        error = ETIMEDOUT;
        for (;;) {
            long left = deadline - probe_now_ms();
            if (left <= 0) break;
            struct pollfd pfd = { fd, POLLOUT, 0 };
            int pr = poll(&pfd, 1, (int)left);
            if (pr < 0 && errno == EINTR) continue;
            if (pr > 0) {
                int so_error = 0;
                socklen_t size = sizeof so_error;
                if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_error, &size) != 0)
                    error = errno;
                else if (so_error != 0)
                    error = so_error;
                else if (pfd.revents & POLLOUT && !(pfd.revents & (POLLERR | POLLHUP | POLLNVAL)))
                    return fd;
                else
                    error = ECONNABORTED;
            } else if (pr < 0) {
                error = errno;
            }
            break;
        }
    }
    snprintf(why, why_cap, "errno %d %s", error, strerror(error));
    close(fd);
    return -1;
}

#ifdef SENKO_HOST_TEST
int daemon_ctl_test_subscription_connect(const struct sockaddr *addr, socklen_t addr_len,
                                         int bypass_vpn, char *why, size_t cap) {
    return connect_subscription(addr, addr_len, probe_now_ms() + 1000, bypass_vpn, 0, why, cap);
}
#endif

/* validate every resolved address before opening a subscription or geo socket */
static int subfetch_dial_path(daemon_ctl_t *d, const char *host, uint16_t port,
                              int budget_ms, int bypass_vpn, int split) {
    if (budget_ms <= 0) return -1;
    long deadline = probe_now_ms() + budget_ms;

    char portstr[8];
    snprintf(portstr, sizeof portstr, "%u", port);

    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
/* leave the connect at least as much room as the name lookup gets */
    int dns_ms = budget_ms / 2;
    if (dns_ms > 3000) dns_ms = 3000;
    if (dns_ms <= 0) dns_ms = 1;
    int resolved = net_getaddrinfo_timed(host, portstr, &hints, &res, dns_ms) == 0 && res;
    if (!resolved)
        fprintf(stderr, "senko: system dns failed for %s; asking the configured resolver\n", host);

    int fd = -1;
    for (struct addrinfo *ai = res; ai; ai = ai->ai_next) {
        char shown[INET6_ADDRSTRLEN] = "?";
        if (ai->ai_family == AF_INET)
            inet_ntop(AF_INET, &((struct sockaddr_in *)ai->ai_addr)->sin_addr, shown, sizeof shown);
        else if (ai->ai_family == AF_INET6)
            inet_ntop(AF_INET6, &((struct sockaddr_in6 *)ai->ai_addr)->sin6_addr, shown, sizeof shown);
        if (!net_addr_allowed(ai->ai_addr)) {
            fprintf(stderr, "senko: direct dial to %s refused address %s\n", host, shown);
            continue;
        }
        char why[128];
        fd = connect_subscription(ai->ai_addr, ai->ai_addrlen, deadline, bypass_vpn, split, why, sizeof why);
        if (fd >= 0) break;
        fprintf(stderr, "senko: %s dial to %s could not connect to %s (%s)\n",
                bypass_vpn ? "direct" : "default route", host, shown, why);
    }
    if (res) freeaddrinfo(res);
    if (fd < 0 && !resolved && d && deadline > probe_now_ms()) {
        uint32_t addresses[DNS_MSG_MAX_IPV4];
        size_t count = 0;
        int remaining = (int)(deadline - probe_now_ms());
        if (direct_dns_ipv4(d->settings.dns_upstream, 53, host, remaining, addresses,
                            DNS_MSG_MAX_IPV4, &count) == 0) {
            for (size_t i = 0; i < count && fd < 0; ++i) {
                struct sockaddr_in addr;
                memset(&addr, 0, sizeof addr);
                addr.sin_family = AF_INET;
                addr.sin_port = htons(port);
                addr.sin_addr.s_addr = addresses[i];
                if (!net_addr_allowed((struct sockaddr *)&addr)) continue;
                char why[128];
                fd = connect_subscription((struct sockaddr *)&addr, sizeof addr, deadline,
                                          bypass_vpn, split, why, sizeof why);
                if (fd < 0)
                    fprintf(stderr, "senko: direct dial to %s could not connect (%s)\n", host, why);
            }
        }
    }
    return fd;
}

static int subfetch_dial(void *ctx, const char *host, uint16_t port, int budget_ms) {
    return subfetch_dial_path((daemon_ctl_t *)ctx, host, port, budget_ms, 1, 0);
}

static int subfetch_dial_default(void *ctx, const char *host, uint16_t port, int budget_ms) {
    return subfetch_dial_path((daemon_ctl_t *)ctx, host, port, budget_ms, 0, 0);
}

static int subfetch_dial_split(void *ctx, const char *host, uint16_t port, int budget_ms) {
    return subfetch_dial_path((daemon_ctl_t *)ctx, host, port, budget_ms, 0, 1);
}

/* the user's words for a failed download. what names the thing fetched */
static void fetch_error_text(subfetch_status_t r, const char *host, int http_status,
                             const char *what, char *e, size_t ec) {
    switch (r) {
        case SUBFETCH_ERR_DIAL:
            snprintf(e, ec, "cannot resolve or connect to %.96s", host); break;
        case SUBFETCH_ERR_TRANSPORT:
            snprintf(e, ec, "tls or connection to %.96s failed", host); break;
        case SUBFETCH_ERR_HTTP:
            if (http_status && (http_status < 200 || http_status >= 300))
                snprintf(e, ec, "%.96s answered HTTP %d", host, http_status);
            else
                snprintf(e, ec, "%.96s sent a response senko cannot read", host);
            break;
        case SUBFETCH_ERR_TOOBIG:
            snprintf(e, ec, "the %s from %.96s is too large", what, host); break;
        case SUBFETCH_ERR_REDIRECT:
            snprintf(e, ec, "%.96s redirected too often or to an unusable address", host); break;
        default:
            snprintf(e, ec, "the %s url is invalid", what); break;
    }
}

/* runs on a ctl_server fetch thread */
int daemon_ctl_fetch(void *ctx, const char *url,
                     const char *request_header,
                     unsigned char *buf, size_t cap, size_t *len,
                     ctl_fetch_meta_t *meta) {
    subfetch_cfg_t cfg;
    memset(&cfg, 0, sizeof cfg);
    cfg.dial = subfetch_dial_default;
    cfg.dial_ctx = ctx;
    cfg.tcp = &transport_tcp;
    cfg.tls = &transport_tls; /* use tls for https */
    cfg.request_header = request_header;
    cfg.accept_gzip = 1;
    cfg.max_redirects = 5;
/* a direct retry needs its own deadline within the app's request timeout */
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    int tunnel_active = d && (utun_backend_running(&d->tunnel) || xray_backend_running(&d->xray) ||
                              awg_backend_busy(&d->awg));
    cfg.progress_extend_ms = tunnel_active ? 0 : 10000;

    /* name lookup, connect, tls and the answer share one budget, and a phone on a mobile network
       needs more than eight seconds for all four */
    const int budget_ms = 15000;
    subfetch_info_t info;
    memset(&info, 0, sizeof info);
    subfetch_status_t r = subfetch_get_info(&cfg, url, buf, cap, len, budget_ms, &info);
    if (tunnel_active && (r == SUBFETCH_ERR_DIAL || r == SUBFETCH_ERR_TRANSPORT)) {
        fprintf(stderr, "senko: subscription through the vpn failed, retrying directly\n");
        cfg.dial = subfetch_dial;
        memset(&info, 0, sizeof info);
        r = subfetch_get_info(&cfg, url, buf, cap, len, budget_ms, &info);
    }
    /* the connection came up and the server then said nothing to the tls hello, which is what a
       network that filters by server name does. the same request goes out once more with the hello
       cut into small packets, a form such a filter does not read the name from */
    if (r != SUBFETCH_OK && info.timed_out && !info.http_status &&
        info.timeout_phase == SUBFETCH_PHASE_HANDSHAKE && !tunnel_active) {
        fprintf(stderr, "senko: no answer to the tls hello, retrying with a split hello\n");
        cfg.dial = subfetch_dial_split;
        memset(&info, 0, sizeof info);
        r = subfetch_get_info(&cfg, url, buf, cap, len, budget_ms, &info);
        if (r == SUBFETCH_OK) fprintf(stderr, "senko: the split hello was answered\n");
    }
    /* a panel that answers slowly also resets a tls session now and then, which a second attempt gets
       past: seen as "unexpected eof" on a repeated request to one subscription host. one more try, never a loop */
    if (r == SUBFETCH_ERR_TRANSPORT && !info.timed_out && !info.http_status) {
        fprintf(stderr, "senko: the connection was reset, trying the subscription once more\n");
        memset(&info, 0, sizeof info);
        r = subfetch_get_info(&cfg, url, buf, cap, len, budget_ms, &info);
    }
    if (r != SUBFETCH_OK) {
        url_t eu;
        const char *host = url && url_parse(url, &eu) == URL_OK ? eu.host : "the server";
        if (meta) fetch_error_text(r, host, info.http_status, "subscription",
                                   meta->error, sizeof meta->error);
        if (meta && info.timed_out) {
            if (info.http_status)
                snprintf(meta->error, sizeof meta->error,
                         "%.96s timed out after HTTP %d (%zu body bytes)",
                         host, info.http_status, info.received);
            else if (info.timeout_phase == SUBFETCH_PHASE_HANDSHAKE)
                snprintf(meta->error, sizeof meta->error,
                         "%.96s did not answer the tls handshake in time", host);
            else
                snprintf(meta->error, sizeof meta->error,
                         "%.96s took the request but did not answer in time", host);
        }
        const char *why = "unknown";
        switch (r) {
            case SUBFETCH_ERR_ARG:       why = "bad arg"; break;
            case SUBFETCH_ERR_URL:        why = "bad url"; break;
            case SUBFETCH_ERR_DIAL:       why = "dns/connect"; break;
            case SUBFETCH_ERR_TRANSPORT: why = "tls/io"; break;
            case SUBFETCH_ERR_HTTP:       why = "http status/body"; break;
            case SUBFETCH_ERR_TOOBIG:     why = "body too big"; break;
            case SUBFETCH_ERR_REDIRECT:   why = "redirect"; break;
            default: break;
        }
/* redaction prevents subscription credentials from reaching system logs */
        url_t u;
        if (url && url_parse(url, &u) == URL_OK)
            fprintf(stderr, "senko: subfetch failed: %s (rc=%d, http %d, body %zu, timeout %d) %s://%s:%u/...\n",
                    why, (int)r, info.http_status, info.received, info.timed_out,
                    u.is_https ? "https" : "http", u.host,
                    (unsigned)u.port);
        else
            fprintf(stderr, "senko: subfetch failed: %s (rc=%d)\n", why, (int)r);
        return -1;
    }
    if (meta) {
        meta->expire = info.expire;
        meta->upload = info.upload;
        meta->download = info.download;
        meta->total = info.total;
        snprintf(meta->title, sizeof meta->title, "%s", info.title);
        snprintf(meta->description, sizeof meta->description, "%s", info.description);
        snprintf(meta->support_url, sizeof meta->support_url, "%s", info.support_url);
        snprintf(meta->web_page_url, sizeof meta->web_page_url, "%s", info.web_page_url);
        snprintf(meta->announce, sizeof meta->announce, "%s", info.announce);
        meta->update_hours = info.update_hours;
        meta->refill_date = info.refill_date;
        meta->gated = info.gated;
        meta->body_cut = info.body_cut;
        snprintf(meta->routing, sizeof meta->routing, "%s", info.routing);
        meta->routing_too_long = info.routing_too_long;
        snprintf(meta->gate_reason, sizeof meta->gate_reason, "%s", info.gate_reason);
    }
    fprintf(stderr, "senko: subfetch ok %zu bytes\n", len ? *len : 0);
    return 0;
}

/* a geo dat is tens of megabytes, so it goes to a file as it arrives. happ gives
   a download three minutes, and so does this */
int daemon_ctl_geo_fetch(void *ctx, const char *url, FILE *out, size_t max_bytes,
                         size_t *written, char *error, size_t error_cap) {
    subfetch_cfg_t cfg;
    subfetch_info_t info;
    memset(&cfg, 0, sizeof cfg);
    cfg.dial = subfetch_dial;
    cfg.dial_ctx = ctx;
    cfg.tcp = &transport_tcp;
    cfg.tls = &transport_tls;
    cfg.max_redirects = 5;
    subfetch_status_t r = subfetch_get_file(&cfg, url, out, max_bytes, written, 180000, &info);
    if (r == SUBFETCH_OK) {
        fprintf(stderr, "senko: geo download ok %zu bytes\n", *written);
        return 0;
    }
    {
        url_t u;
        const char *host = url && url_parse(url, &u) == URL_OK ? u.host : "the server";
        /* a sink that failed reads as an unreadable response; the disk is the cause */
        if (r == SUBFETCH_ERR_HTTP && !info.http_status && ferror(out))
            snprintf(error, error_cap, "the geo file could not be written, the disk may be full");
        else
            fetch_error_text(r, host, info.http_status, "geo file", error, error_cap);
        fprintf(stderr, "senko: geo download failed: %s\n", error);
    }
    return -1;
}

/* deliberately not protected: it is what proves the tunnel carries traffic */
static int dial_numeric_until(const char *ip, uint16_t port,
                              long deadline, int *elapsed_ms) {
    struct sockaddr_storage storage;
    struct sockaddr *addr = (struct sockaddr *)&storage;
    socklen_t addr_len;
    memset(&storage, 0, sizeof storage);
    if (inet_pton(AF_INET, ip, &((struct sockaddr_in *)addr)->sin_addr) == 1) {
        struct sockaddr_in *v4 = (struct sockaddr_in *)addr;
        v4->sin_family = AF_INET;
        v4->sin_port = htons(port);
        addr_len = sizeof *v4;
    } else if (inet_pton(AF_INET6, ip, &((struct sockaddr_in6 *)addr)->sin6_addr) == 1) {
        struct sockaddr_in6 *v6 = (struct sockaddr_in6 *)addr;
        v6->sin6_family = AF_INET6;
        v6->sin6_port = htons(port);
        addr_len = sizeof *v6;
    } else {
        return -1;
    }
    int fd = socket(addr->sa_family, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    int fl = fcntl(fd, F_GETFL, 0);
    if (fl < 0) { close(fd); return -1; }
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);

    long start = probe_now_ms();
    int r = connect(fd, addr, addr_len);
    if (r == 0) {
        if (elapsed_ms) *elapsed_ms = (int)(probe_now_ms() - start);
        return fd;
    }
    if (errno != EINPROGRESS) { close(fd); return -1; }

    for (;;) {
        long now = probe_now_ms();
        if (now >= deadline) { close(fd); return -1; }

        int remain = (int)(deadline - now);
        int slice = remain > 5 ? 5 : remain;
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLOUT;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, slice);
        if (pr < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (pr == 0) continue;
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            close(fd);
            return -1;
        }
        if (!(pfd.revents & POLLOUT)) continue;

        int soerr = 0;
        socklen_t sl = sizeof soerr;
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &sl) != 0 || soerr != 0) {
            close(fd);
            return -1;
        }
        if (elapsed_ms) *elapsed_ms = (int)(probe_now_ms() - start);
        return fd;
    }
}

/* measure one direct ipv4 handshake from connect until the socket reports its
   final error state. the socket is protected, so the number belongs to the
   server and not to the tunnel */
static int probe_tcp_ipv4(const char *ip, uint16_t port, int timeout_ms) {
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    if (!ip || inet_pton(AF_INET, ip, &addr.sin_addr) != 1) return -1;

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    if (net_protect_socket(fd) != 0) {
        close(fd);
        return -1;
    }
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) {
        close(fd);
        return -1;
    }

    long start = probe_now_ms();
    int rc = connect(fd, (struct sockaddr *)&addr, sizeof addr);
    if (rc != 0 && errno != EINPROGRESS) {
        close(fd);
        return -1;
    }

    fd_set writable;
    FD_ZERO(&writable);
    FD_SET(fd, &writable);
    struct timeval timeout;
    timeout.tv_sec = timeout_ms / 1000;
    timeout.tv_usec = (timeout_ms % 1000) * 1000;
    rc = select(fd + 1, NULL, &writable, NULL, &timeout);
    if (rc <= 0 || !FD_ISSET(fd, &writable)) {
        close(fd);
        return -1;
    }

    int soerr = 0;
    socklen_t soerr_len = sizeof soerr;
    if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &soerr_len) != 0 || soerr != 0) {
        close(fd);
        return -1;
    }
    close(fd);
    long elapsed = probe_now_ms() - start;
    return elapsed >= 0 && elapsed <= INT_MAX ? (int)elapsed : -1;
}

#define SENKO_QUIC_PROBE_LEN 1200

static void build_quic_probe_packet(unsigned char *out) {
    memset(out, 0, SENKO_QUIC_PROBE_LEN);
    out[0] = 0xc0;
    /* a reserved quic version makes a compliant server answer without auth */
    out[1] = 0x1a;
    out[2] = 0x2a;
    out[3] = 0x3a;
    out[4] = 0x4a;
    out[5] = 8;
    (void)RAND_bytes(out + 6, 8);
    out[14] = 0;
}

static int build_salamander_packet(const char *password,
                                   const unsigned char *plain, size_t plain_len,
                                   unsigned char *wire, size_t wire_cap,
                                   size_t *wire_len) {
    unsigned char keyed[136];
    unsigned char key[32];
    size_t pass_len;
    if (!password || !plain || !wire || !wire_len) return -1;
    pass_len = strlen(password);
    if (pass_len + 8 > sizeof keyed || wire_cap < plain_len + 8) return -1;
    if (RAND_bytes(wire, 8) != 1) return -1;
    memcpy(keyed, password, pass_len);
    memcpy(keyed + pass_len, wire, 8);
    blake2b256(keyed, pass_len + 8, key);
    for (size_t i = 0; i < plain_len; ++i)
        wire[8 + i] = plain[i] ^ key[i % sizeof key];
    *wire_len = plain_len + 8;
    return 0;
}

static int probe_udp_ipv4(const char *ip, uint16_t port,
                          const char *obfs, const char *obfs_password,
                          int timeout_ms) {
    unsigned char plain[SENKO_QUIC_PROBE_LEN];
    unsigned char wire[SENKO_QUIC_PROBE_LEN + 8];
    const unsigned char *packet = plain;
    size_t packet_len = sizeof plain;
    struct sockaddr_in addr;
    struct timeval timeout;
    fd_set readable;
    long started;
    int fd;

    if (!ip || !port) return -1;
    build_quic_probe_packet(plain);
    if (obfs && obfs[0] && strcmp(obfs, "none") != 0) {
        if (strcmp(obfs, "salamander") != 0 ||
            build_salamander_packet(obfs_password, plain, sizeof plain,
                                    wire, sizeof wire, &packet_len) != 0)
            return -1;
        packet = wire;
    }

    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    if (inet_pton(AF_INET, ip, &addr.sin_addr) != 1) return -1;
    fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    if (net_protect_socket(fd) != 0) {
        close(fd);
        return -1;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
        close(fd);
        return -1;
    }
    started = probe_now_ms();
    if (send(fd, packet, packet_len, 0) != (ssize_t)packet_len) {
        close(fd);
        return -1;
    }

    FD_ZERO(&readable);
    FD_SET(fd, &readable);
    timeout.tv_sec = timeout_ms / 1000;
    timeout.tv_usec = (timeout_ms % 1000) * 1000;
    int ready = select(fd + 1, &readable, NULL, NULL, &timeout);
    unsigned char response[64];
    ssize_t received = ready > 0 ? recv(fd, response, sizeof response, 0) : -1;
    close(fd);
    if (ready <= 0 || received <= 0) return -1;
    long elapsed = probe_now_ms() - started;
    return elapsed >= 0 && elapsed <= INT_MAX ? (int)elapsed : -1;
}

int daemon_ctl_probe(void *ctx, const char *host, uint16_t port) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    const int timeout_ms = 5000;

    char ip[INET_ADDRSTRLEN];
    if (!net_resolve_public_ipv4(host, port, ip, sizeof ip)) {
        stage_mark(d, "resolve", 0);
        return -1;
    }
    stage_mark(d, "resolve", 1);

    int ms = probe_tcp_ipv4(ip, port, timeout_ms);
    stage_mark(d, "tcp connect", ms >= 0);
    return ms;
}

/* vmess, reality and certificate dates fail when the device clock is off, and
   look like a broken server when they do; a direct answer from the probe host
   estimates the difference, without changing the device clock */
static void probe_measure_clock(daemon_ctl_t *d) {
    char ip[INET_ADDRSTRLEN];
    if (!net_resolve_public_ipv4(REAL_PROBE_HOST, REAL_PROBE_PORT, ip, sizeof ip)) return;
    int measured = real_probe_clock(ip, 3000) == 0;
    stage_mark(d, "device clock", measured);
}

/* the list ping and the server checks. a bare tcp connect reported the nearest
   cdn edge for nodes behind one (2 to 8 ms for a server abroad) and whatever
   the wifi radio added while it dozed, so the number is the delay of a real
   request carried through the server instead. a failure leaves in reason what
   is wrong, in words the app translates */
static int diagnose_server(daemon_ctl_t *d, const vl_server_t *server, int timeout_ms, int tcp_only,
                           char *reason, size_t reason_cap) {
    net_diag_t diag;
    memset(&diag, 0, sizeof diag);
    diag.proto = server->proto;
    diag.port = server->port;
    char ip[INET_ADDRSTRLEN];
    if (!net_resolve_public_ipv4(server->host, server->port, ip, sizeof ip)) {
        stage_mark(d, "resolve", 0);
        diag.stage = NET_DIAG_DNS;
        if (reason && reason_cap) net_diag_text(&diag, reason, reason_cap);
        return -1;
    }
    stage_mark(d, "resolve", 1);

    char text[256];
    int ms = tcp_only
        ? real_probe_tcp(server, ip, timeout_ms, text, sizeof text)
        : real_probe_run(server, ip, timeout_ms, &diag, text, sizeof text);
    if (!tcp_only && ms < 0 && net_diag_wants_clock(&diag)) {
        probe_measure_clock(d);
        if (senko_clock_offset(&diag.clock_offset_s)) {
            diag.clock_known = 1;
            net_diag_text(&diag, text, sizeof text);
        }
    }
    stage_mark(d, "answer through the server", ms >= 0);
    if (ms < 0) fprintf(stderr, "senko: server %s:%u check failed: %s\n",
                        server->host, (unsigned)server->port, text);
    if (reason && reason_cap) snprintf(reason, reason_cap, "%s", ms >= 0 ? "" : text);
    return ms;
}

int daemon_ctl_probe_server(void *ctx, const vl_server_t *server) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    if (!d || !server) return -1;
    if (server->proto != VL_PROTO_HYSTERIA2)
        return diagnose_server(d, server, 6000, 0, NULL, 0);

    char ip[INET_ADDRSTRLEN];
    if (!net_resolve_public_ipv4(server->host, server->port,
                                 ip, sizeof ip)) {
        stage_mark(d, "resolve", 0);
        return -1;
    }
    stage_mark(d, "resolve", 1);

    int ms = probe_udp_ipv4(ip, server->port, server->obfs,
                            server->obfs_password, 2500);
    stage_mark(d, "udp quic probe", ms >= 0);
    return ms;
}

static int read_some_until(int fd, char *buf, size_t cap, size_t want_min,
                            long deadline, size_t *got_out) {
    size_t got = 0;
    while (got < cap && probe_now_ms() < deadline) {
        ssize_t r = read(fd, buf + got, cap - got);
        if (r > 0) {
            got += (size_t)r;
            if (got >= want_min) break;
            continue;
        }
        if (r == 0) break;
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            struct pollfd pfd = { fd, POLLIN, 0 };
            poll(&pfd, 1, 50);
            continue;
        }
        break;
    }
    if (got_out) *got_out = got;
    return (got >= want_min) ? 0 : -1;
}

static int build_probe_clienthello_record(uint8_t *out, size_t cap, size_t *out_len,
                                          const char *sni) {
    if (!out || !out_len || cap < 10) return -1;
    tls_ch_params_t chp;
    memset(&chp, 0, sizeof chp);
    if (RAND_bytes(chp.random, sizeof chp.random) != 1) return -1;
    if (RAND_bytes(chp.x25519_pub, sizeof chp.x25519_pub) != 1) return -1;
    chp.sni = sni;
    chp.fp = TLS_FP_CHROME;

    uint8_t hello[2048];
    size_t hello_len = 0;
    if (tls_build_clienthello(&chp, hello, sizeof hello, &hello_len) != TLS_CH_OK)
        return -1;
    if (5 + hello_len > cap) return -1;
    out[0] = 0x16; /* tls handshake */
    out[1] = 0x03;
    out[2] = 0x01;
    out[3] = (uint8_t)(hello_len >> 8);
    out[4] = (uint8_t)(hello_len & 0xff);
    memcpy(out + 5, hello, hello_len);
    *out_len = 5 + hello_len;
    return 0;
}

static int is_tls_record_prefix(const char *buf, size_t n) {
    if (n < 5) return 0;
    uint8_t t = (uint8_t)buf[0];
    if (t != 0x14 && t != 0x15 && t != 0x16 && t != 0x17) return 0;
    if ((uint8_t)buf[1] != 0x03) return 0;
    return 1;
}

/* require application data through the tunnel, a synthetic connect is not success */
static int tunnel_carry_probe(daemon_ctl_t *d, int timeout_ms, int *ms_out,
                          char *stage_out, size_t stage_cap) {
    long start = probe_now_ms();
    long deadline = start + timeout_ms;
    const char *stage = "the tunnel is not running";
    if (ms_out) *ms_out = -1;
    if (stage_out && stage_cap) stage_out[0] = '\0';
    if (!d || !(utun_backend_running(&d->tunnel) || xray_backend_running(&d->xray))) {
        stage_mark(d, "tunnel running", 0);
        goto fail;
    }
    stage_mark(d, "tunnel running", 1);
    if (!d->probe_ip[0]) {
        stage = "probe address unavailable";
        stage_mark(d, "probe resolve", 0);
        goto fail;
    }
    stage_mark(d, "probe resolve", 1);
    /* a working proxy can block destination port 80 while carrying TLS */
    long tls_deadline = start + (timeout_ms * 6) / 10;
    if (tls_deadline > deadline) tls_deadline = deadline;
    int fd = dial_numeric_until(d->probe_ip, 443, tls_deadline, NULL);
    stage_mark(d, "tunnel tls connect", fd >= 0);
    if (fd >= 0) {
        uint8_t hello[2100];
        size_t hello_len = 0;
        if (build_probe_clienthello_record(hello, sizeof hello, &hello_len,
                                           "example.com") == 0 &&
            write_all_until(fd, hello, hello_len, tls_deadline) == 0) {
            char response[64];
            size_t got = 0;
            if (read_some_until(fd, response, sizeof response, 5, tls_deadline, &got) == 0 &&
                is_tls_record_prefix(response, got)) {
                close(fd);
                stage_mark(d, "tunnel tls response", 1);
                if (ms_out) *ms_out = (int)(probe_now_ms() - start);
                return 0;
            }
        }
        close(fd);
        stage_mark(d, "tunnel tls response", 0);
    }

    fd = dial_numeric_until(d->probe_ip, 80, deadline, NULL);
    stage_mark(d, "tunnel http connect", fd >= 0);
    if (fd < 0) {
        stage = "tunnel connection timed out";
        goto fail;
    }
    static const char request[] =
        "GET / HTTP/1.0\r\nHost: example.com\r\nConnection: close\r\n\r\n";
    if (write_all_until(fd, request, sizeof request - 1, deadline) != 0) {
        close(fd);
        stage = "tunnel data write failed";
        goto fail;
    }
    char response[64];
    size_t got = 0;
    if (read_some_until(fd, response, sizeof response, 12, deadline, &got) != 0) {
        close(fd);
        stage = "no data returned through the tunnel";
        stage_mark(d, "tunnel http response", 0);
        goto fail;
    }
    close(fd);
    if (got < 5 || memcmp(response, "HTTP/", 5) != 0) {
        stage = "invalid data returned through the tunnel";
        stage_mark(d, "tunnel http response", 0);
        goto fail;
    }
    stage_mark(d, "tunnel http response", 1);
    if (ms_out) *ms_out = (int)(probe_now_ms() - start);
    return 0;

fail:
    if (stage_out && stage_cap) snprintf(stage_out, stage_cap, "%s", stage);
    return -1;
}

int daemon_ctl_ping_tunnel(void *ctx) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    const int timeout_ms = 4000;
    int ms = -1;
    if (d && !d->probe_ip[0]) resolve_probe_upstream(d);
    return tunnel_carry_probe(d, timeout_ms, &ms, NULL, 0) == 0 ? ms : -1;
}

/* the time a request through the server takes, measured by senko-core, which is the measure of a url
   test and covers every profile the core carries. -2 when it is not applicable and the engine's own
   probe should answer. a profile only the core carries has no other probe, so its failure is final */
static int core_probe(daemon_ctl_t *d, const vl_server_t *s, char *reason, size_t reason_cap) {
    int needs = cfg_server_needs_core(s, NULL, 0);
    if (d->settings.core_pref == SETTINGS_CORE_ENGINE && !needs) return -2;
    char why[160] = "";
    if (!xray_backend_can_carry(s, NULL, NULL, why, sizeof why) ||
        xray_backend_load(&d->xray, why, sizeof why) != 0) {
        if (!needs) return -2;
        snprintf(reason, reason_cap, "%s", why);
        return -1;
    }
    char ip[INET_ADDRSTRLEN];
    if (!net_resolve_public_ipv4(s->host, s->port, ip, sizeof ip)) {
        stage_mark(d, "resolve", 0);
        snprintf(reason, reason_cap, "the server name does not resolve");
        return -1;
    }
    stage_mark(d, "resolve", 1);
    char ca[320];
    if (senko_path_join(ca, sizeof ca, SENKO_REL_CA_BUNDLE) != SENKO_PATHS_OK) ca[0] = '\0';
    xray_backend_set_assets(&d->xray, d->geo_dir);
    int ms = xray_backend_probe(&d->xray, s, ip, ca[0] ? ca : NULL, 6000, why, sizeof why);
    stage_mark(d, "request through senko-core", ms >= 0);
    if (ms < 0) snprintf(reason, reason_cap, "%s", why);
    return ms;
}

static int check_run(daemon_ctl_t *d, const char *mode, const vl_server_t *server,
                     char *reason, size_t reason_cap) {
    /* the tcp check stays a bare handshake: it tells a blocked port apart
       from a server that refuses the proxy, which the list ping cannot */
    if (strcmp(mode, "tcp") == 0) {
        /* the engine has no handshake of its own for a profile only the core carries */
        if (cfg_server_needs_core(server, NULL, 0)) return core_probe(d, server, reason, reason_cap);
        if (server->proto == VL_PROTO_HYSTERIA2) return daemon_ctl_probe_server(d, server);
        return diagnose_server(d, server, 6000, 1, reason, reason_cap);
    }
    if (strcmp(mode, "real") == 0) {
        int through_core = core_probe(d, server, reason, reason_cap);
        if (through_core != -2) return through_core;
        if (server->proto != VL_PROTO_HYSTERIA2)
            return diagnose_server(d, server, 6000, 0, reason, reason_cap);
        int ms = daemon_ctl_probe_server(d, server);
        if (ms < 0 && reason && reason_cap)
            snprintf(reason, reason_cap, "no answer through the server");
        return ms;
    }
    if (strcmp(mode, "tunnel") == 0) {
        int ms = daemon_ctl_ping_tunnel(d);
        if (ms < 0 && reason && reason_cap) snprintf(reason, reason_cap, "active tunnel check failed");
        return ms;
    }
    if (strcmp(mode, "handshake") == 0) {
        if (d->tunnel.active || d->xray.active || awg_backend_busy(&d->awg)) {
            stage_mark(d, "tunnel idle", 0);
            if (reason && reason_cap)
                snprintf(reason, reason_cap, "disconnect before checking another profile");
            return -1;
        }
        stage_mark(d, "tunnel idle", 1);
        if (server->proto == VL_PROTO_HYSTERIA2) {
            if (reason && reason_cap)
                snprintf(reason, reason_cap, "profile uses an unsupported transport or security mode");
            return -1;
        }
        return diagnose_server(d, server, 6000, 0, reason, reason_cap);
    }

    if (reason && reason_cap) snprintf(reason, reason_cap, "unknown check type");
    return -1;
}

int daemon_ctl_check(void *ctx, const char *mode, const vl_server_t *server,
                     ctl_check_trace_t *trace, char *reason, size_t reason_cap) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    if (!d || !mode || !server) return -1;
    pthread_once(&trace_key_once, make_trace_key);
    if (trace_key_error) {
        if (reason && reason_cap) snprintf(reason, reason_cap, "cannot initialize check trace");
        return -1;
    }
    if (trace) {
        memset(trace, 0, sizeof *trace);
        trace->started_ms = probe_now_ms();
    }
    ctl_check_trace_t *previous = pthread_getspecific(trace_key);
    if (pthread_setspecific(trace_key, trace) != 0) {
        if (reason && reason_cap) snprintf(reason, reason_cap, "cannot set check trace");
        return -1;
    }
    int ms = check_run(d, mode, server, reason, reason_cap);
    if (pthread_setspecific(trace_key, previous) != 0)
        fprintf(stderr, "senko: cannot restore check trace\n");
    return ms;
}

/* the tunnel reads the routing profile's rules until it is stopped, so the
   previous run is joined before they are rebuilt */
static int awg_begin(daemon_ctl_t *d, const char *path, char *why, size_t why_cap) {
    awg_backend_stop(&d->awg);
    release_routing(d);
    prepare_routing(d);
    bind_routing_geo(d);
    int started = awg_backend_start(&d->awg, path, d->settings.dns_upstream, d->rules,
                                    NULL, NULL, why, why_cap);
    if (started != 0) release_routing(d);
    return started;
}

int daemon_ctl_awg_busy(void *ctx) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    return d ? awg_backend_busy(&d->awg) : 0;
}

int daemon_ctl_awg(void *ctx, const char *flag, const char *path, char *out, size_t cap) {
    daemon_ctl_t *d = (daemon_ctl_t *)ctx;
    if (out && cap) out[0] = '\0';
    if (!d || !flag || !out || !cap) return 1;
    if (strcmp(flag, "--awg-probe") == 0) return DCTL_AWG_JOB;
    if (strcmp(flag, "--awg-status") == 0) {
        awg_backend_status(&d->awg, out, cap);
        /* the app reads the uptime from its own line, so the status word
           stays exactly "connected" for every caller that compares it */
        long up = awg_backend_uptime(&d->awg);
        size_t used = strlen(out);
        if (up >= 0 && used < cap)
            snprintf(out + used, cap - used, "\nAWGUP %ld", up);
        return 0;
    }
    if (strcmp(flag, "--awg-stop") == 0) {
        awg_backend_stop(&d->awg);
        release_routing(d);
        snprintf(out, cap, "idle");
        return 0;
    }
    /* the network changed under a running tunnel: its udp socket still leaves
       by the old interface, so the tunnel is rebuilt on the new one */
    char current[256];
    if (strcmp(flag, "--awg-restart") == 0) {
        pthread_mutex_lock(&d->awg.lock);
        snprintf(current, sizeof current, "%s", d->awg.config_path);
        pthread_mutex_unlock(&d->awg.lock);
        if (!awg_backend_busy(&d->awg) || !current[0]) {
            awg_backend_status(&d->awg, out, cap);
            return 0;
        }
        path = current;
        flag = "--awg";
    }
    if (!path || !path[0]) {
        snprintf(out, cap, "error no config path given");
        return 1;
    }
    char why[224];
    if (strcmp(flag, "--awg-validate") == 0) {
        awg_config_t cfg;
        int ok = awg_config_path_ok(path) &&
                 awg_config_load_file(path, &cfg, why, sizeof why) == AWG_CFG_OK;
        if (ok) snprintf(out, cap, "VALID AmneziaWG native config");
        else if (!awg_config_path_ok(path))
            snprintf(out, cap, "ERR the config is not a .conf file in the app awg directory");
        else snprintf(out, cap, "ERR config rejected: %s", why);
        if (ok) OPENSSL_cleanse(&cfg, sizeof cfg);
        return ok ? 0 : 1;
    }
    if (strcmp(flag, "--awg") == 0) {
        if (awg_begin(d, path, why, sizeof why) != 0) {
            fprintf(stderr, "senko: amneziawg not started: %s\n", why);
            snprintf(out, cap, "error %s", why);
            return 1;
        }
        snprintf(out, cap, "connecting");
        return 0;
    }
    snprintf(out, cap, "error unknown amneziawg request %s", flag);
    return 1;
}
