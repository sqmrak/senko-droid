#ifndef SUBFETCH_H
#define SUBFETCH_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#include "http.h"
#include "transport.h"

#ifdef __cplusplus
extern "C" {
#endif

/* budget_ms is what is left of the fetch timeout, not a deadline, because the
   daemon's dialer times itself on a different clock. a dialer that keeps its
   own budget instead spends the whole fetch on the dial and leaves nothing
   for the request, which is how one dead tunnel timed out every refresh */
typedef int (*subfetch_dial_fn)(void *ctx, const char *host, uint16_t port,
                                int budget_ms);

typedef enum {
    SUBFETCH_OK         =  0,
    SUBFETCH_ERR_ARG    = -1,
    SUBFETCH_ERR_URL    = -2, /* the url text itself does not parse */
    SUBFETCH_ERR_DIAL   = -3, /* dial failed */
    SUBFETCH_ERR_TRANSPORT = -4,/* transport failed */
    SUBFETCH_ERR_HTTP   = -5, /* bad response */
    SUBFETCH_ERR_TOOBIG = -6, /* body is too large */
    SUBFETCH_ERR_REDIRECT = -7 /* redirect failed */
} subfetch_status_t;

/* the connection is up but the server has not answered: during the tls handshake and the request, or
   after the request while the response is awaited */
#define SUBFETCH_PHASE_HANDSHAKE 1
#define SUBFETCH_PHASE_RESPONSE 2

typedef struct {
    subfetch_dial_fn dial;
    void            *dial_ctx;
    const transport_vt_t *tcp;
    const transport_vt_t *tls;
    const char *request_header; /* only for the subscription HTTP request */
/* ask for gzip and unpack it. a subscription body of 65 KB crosses the wire as
   about 5 KB, and a mobile path that stalls a connection after roughly 20 KB
   of a response never gets to cut it. a file sink must leave this zero */
    int accept_gzip;
    int max_redirects; /* zero uses five hops */
/* extra time a body that keeps arriving may take past the fetch timeout, zero
   keeps the timeout hard. the caller sets it only when it can afford the wait */
    int progress_extend_ms;
/* set by subfetch_get_file only: the body goes to the sink and body_cap is its limit */
    http_body_sink_fn sink;
    void *sink_ctx;
} subfetch_cfg_t;

typedef struct {
    uint64_t expire;
    uint64_t upload;
    uint64_t download;
    uint64_t total;
/* the panel's suggested name for the subscription, from profile-title; kept
   apart from description, which is prose about it, not a name for it */
    char title[256];
    char description[256];
    char support_url[512];
/* the panel's account page (profile-web-page-url) and its notice (announce, base64: decoded), the
   refresh pace it asks for in hours and the second the traffic counter resets. 0 or empty when the
   header was missing */
    char web_page_url[512];
    char announce[512];
    int  update_hours;
    uint64_t refill_date;
/* set when the panel answered with a device gated placeholder profile instead
   of the real node list; gate_reason carries the panel's own wording */
    int  gated;
    char gate_reason[256];
/* the routing response header of a happ panel, empty when there was none.
   routing_too_long means the header did not fit and was dropped */
    char routing[20 * 1024];
    int  routing_too_long;
/* HTTP status of a failed response, including a stalled 2xx body, 0 on success */
    int  http_status;
/* retain progress when the response stalls before its framing is complete */
    int timed_out;
    /* where the wait ran out: SUBFETCH_PHASE_*, 0 when nothing timed out */
    int timeout_phase;
    size_t received;
/* the body did not fit body_cap: the buffer holds its first body_cap bytes
   for diagnostics. only reported through
   subfetch_get_info; subfetch_get still fails such a body with TOOBIG */
    int  body_cut;
} subfetch_info_t;

subfetch_status_t subfetch_get(const subfetch_cfg_t *cfg, const char *url,
                               uint8_t *body_buf, size_t body_cap,
                               size_t *body_len, int timeout_ms);

subfetch_status_t subfetch_get_info(const subfetch_cfg_t *cfg, const char *url,
                                    uint8_t *body_buf, size_t body_cap,
                                    size_t *body_len, int timeout_ms,
                                    subfetch_info_t *info);

/* the body of a large download goes to out as it arrives, at most max_bytes of it.
   nothing is unpacked: the bytes written are the bytes the server sent. a
   failure leaves a partial file, which is the caller's to remove */
subfetch_status_t subfetch_get_file(const subfetch_cfg_t *cfg, const char *url,
                                    FILE *out, size_t max_bytes, size_t *written,
                                    int timeout_ms, subfetch_info_t *info);

#ifdef __cplusplus
}
#endif

#ifdef SENKO_HOST_TEST
/* the userinfo header parser is worth pinning: an off by one here silently
   turns a valid expiry into a date in the past */
uint64_t subfetch_userinfo_expire_for_test(const char *value);
uint64_t subfetch_userinfo_value_for_test(const char *value, const char *wanted);

/* device gating is decided from response headers alone, so the whole decision
   is reachable from a fixture without a socket */
#include "http.h"
void subfetch_parser_info_for_test(const http_parser_t *hp, subfetch_info_t *info);
#endif

#endif /* subfetch_h */
