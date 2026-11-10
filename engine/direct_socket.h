#ifndef SENKO_DIRECT_SOCKET_H
#define SENKO_DIRECT_SOCKET_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* a socket for a flow the rules send straight out of the physical network.
   the tunnel route covers every destination, so a plain socket would be
   routed back into the tunnel; net_protect_socket keeps it outside.

   returns a nonblocking, close-on-exec descriptor, or -1 with the reason in
   error. type is SOCK_STREAM or SOCK_DGRAM. ctx is unused */
typedef int (*direct_socket_fn)(void *ctx, int family, int type,
                                char *error, size_t error_cap);

int direct_socket_protected(void *ctx, int family, int type, char *error, size_t error_cap);

#ifdef __cplusplus
}
#endif

#endif
