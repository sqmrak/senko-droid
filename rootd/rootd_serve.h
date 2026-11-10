#ifndef SENKO_ROOTD_SERVE_H
#define SENKO_ROOTD_SERVE_H

#include <stddef.h>
#include <sys/types.h>

#include "rootd_proto.h"

#ifdef __cplusplus
extern "C" {
#endif

/* what the helper does to the system, so the request loop can be tested on a host without
   root. every callback returns 0 or a descriptor and puts a sentence in why on failure */
typedef struct {
    /* creates and configures the tun device, returns its descriptor and the interface name */
    int (*open_tun)(void *ctx, const rootd_req_t *req, char *ifname, size_t ifname_cap,
                    char *why, size_t why_cap);
    /* keeps the socket on the interface that carries the default route, returns its name */
    int (*protect)(void *ctx, int fd, char *iface, size_t iface_cap, char *why, size_t why_cap);
    /* undoes what open_tun set up outside the device: it is safe to call twice */
    void (*restore)(void *ctx);
    /* runs about once a second while idle, so settings can follow the device away: the
       client closing its tun descriptor ends the interface without telling the helper */
    void (*tick)(void *ctx);
    void *ctx;
} rootd_ops_t;

typedef enum {
    ROOTD_SERVE_QUIT   = 0, /* the client asked for it */
    ROOTD_SERVE_HANGUP = 1, /* stop_fd closed: the app is gone */
    ROOTD_SERVE_ERROR  = -1
} rootd_serve_t;

/* answers connections on listen_fd until QUIT, until stop_fd reports end of file, or until the
   listening socket fails. only a peer whose uid is allowed_uid is served. restore runs before
   the call returns */
rootd_serve_t rootd_serve(int listen_fd, int stop_fd, uid_t allowed_uid, const rootd_ops_t *ops);

#ifdef __cplusplus
}
#endif

#endif
