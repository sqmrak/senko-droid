#ifndef SETTINGS_H
#define SETTINGS_H

#include <stddef.h>
#include <stdint.h>

#include "core/dns_msg.h"

#ifdef __cplusplus
extern "C" {
#endif

#define SETTINGS_DNS_UPSTREAM_MAX 64
/* a phone that lost its carrier for good must stop redialling it, and five
   attempts already cover the 31 seconds of backoff a handover needs */
#define SENKO_DEFAULT_RECONNECT_ATTEMPTS 5

/* the longest key and value the control protocol carries in one SET */
#define SETTINGS_KEY_MAX   32
#define SETTINGS_VALUE_MAX 64

typedef struct {
    char     dns_upstream[SETTINGS_DNS_UPSTREAM_MAX];
    dns_block_response_t block_response;
    /* what the daemon does on its own, with no client attached */
    int      auto_connect; /* redial the stored selection at startup */
    int      auto_reconnect; /* redial after the data path drops */
    int      reconnect_max_attempts; /* 0 retries until it works */
    int      sub_refresh_hours; /* 0 turns scheduled refreshes off */
    int      failover; /* walk the current section when a server will not come up */

/* the developer overrides */
    int      sub_ignore_gating; /* take the panel's placeholder feed anyway */
    int      trace; /* per connection event lines on the daemon log */
    int      core_pref; /* which backend carries a tunnel, a SETTINGS_CORE_ value */
} daemon_settings_t;

/* auto takes senko-core (xray) when it loads and can carry the profile, and the engine's own
   backend otherwise. xray insists on senko-core and refuses to connect without it; engine never
   loads it */
enum { SETTINGS_CORE_AUTO = 0, SETTINGS_CORE_XRAY = 1, SETTINGS_CORE_ENGINE = 2 };

typedef enum {
    SETTINGS_OK        =  0,
    SETTINGS_ERR_KEY   = -1, /* a key this build does not know */
    SETTINGS_ERR_VALUE = -2  /* a known key with a value it cannot take */
} settings_status_t;

void daemon_settings_defaults(daemon_settings_t *s);

/* the one place that maps a key to a field, shared by the config file and the
   control protocol so both accept exactly the same set */
settings_status_t daemon_settings_set(daemon_settings_t *s,
                                      const char *key, size_t key_len,
                                      const char *val, size_t val_len);

int daemon_settings_apply_line(daemon_settings_t *s, const char *line, size_t len);
void daemon_settings_apply_buf(daemon_settings_t *s, const char *buf, size_t len);
int daemon_settings_serialize(const daemon_settings_t *s, char *buf, size_t cap,
                              size_t *out_len);

#ifdef __cplusplus
}
#endif

#endif /* settings_h */
