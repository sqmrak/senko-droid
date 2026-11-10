#ifndef SENKO_CORE_CONFIG_H
#define SENKO_CORE_CONFIG_H

#include "core/config.h"
#include "core/rules.h"

#include <stddef.h>

/* writes the senko-core configuration of one server: a tun inbound on the descriptor the host
   passes at start, one outbound to endpoint_ip (an address, so the core resolves nothing), a direct
   and a block outbound, and the field rules. secrets stay out of argv and logs because the text
   goes to the core in memory. -1 when it does not fit or the server cannot be written */
int senko_core_config_render_rules(const vl_server_t *server, const char *endpoint_ip,
                           const char *ifname, unsigned mtu, const ruleset_t *rules, const char *geo_dir,
                           const char *ca_file, char *out, size_t out_cap);

/* geo_dir holds the geosite.dat and geoip.dat xray reads, NULL when the caller
   cannot give senko-core one (the packet tunnel extension has no such path) */

/* how many rules senko-core cannot take: geo rules when it has no dat to read. the engine's own
   backend decides those itself, so a profile that uses them prefers it */
size_t senko_core_config_unsupported(const ruleset_t *rules, const char *geo_dir);

#endif
