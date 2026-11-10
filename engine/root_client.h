#ifndef SENKO_ROOT_CLIENT_H
#define SENKO_ROOT_CLIENT_H

#include <stddef.h>

#include "tun_source.h"

#ifdef __cplusplus
extern "C" {
#endif

/* the engine's side of senko_rootd (see rootd/rootd_proto.h), for android 2.3 to 3.x where
   VpnService does not exist. socket_path is the helper's unix socket */

/* asks the helper for a tun device and returns its descriptor, or -1 with a sentence in error */
int root_client_open_tun(const char *socket_path, const tun_params_t *params,
                         char *error, size_t error_cap);

/* keeps a socket out of the tunnel. 0, or -1 with a sentence in error. fd stays the caller's */
int root_client_protect(const char *socket_path, int fd, char *error, size_t error_cap);

/* 0 when the helper answers, -1 with a sentence in error */
int root_client_ping(const char *socket_path, char *error, size_t error_cap);

#ifdef __cplusplus
}
#endif

#endif
