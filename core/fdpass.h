#ifndef SENKO_FDPASS_H
#define SENKO_FDPASS_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* one descriptor travels beside a short message over a unix stream socket
   (SCM_RIGHTS). the root helper and its client in the engine both use it */

/* sends all of data, and fd (when it is not -1) with the first byte. 0 or -1 with errno */
int fdpass_send(int sock, const void *data, size_t len, int fd);

/* reads one line of at most cap - 1 bytes, up to and including its newline, into buf and
   terminates it. a descriptor that arrives with it goes to *fd_out, which is -1 when none
   came; a second descriptor is closed. timeout_ms bounds each wait.
   returns the line length, 0 on a clean end before any byte, -1 with errno on failure
   (ETIMEDOUT, EMSGSIZE for a line that does not fit, or a socket error) */
int fdpass_recv_line(int sock, char *buf, size_t cap, int *fd_out, int timeout_ms);

#ifdef __cplusplus
}
#endif

#endif
