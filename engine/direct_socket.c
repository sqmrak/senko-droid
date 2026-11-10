#include "direct_socket.h"

#include "core/net_protect.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int fail(int fd, char *error, size_t cap, const char *what) {
    int saved = errno;
    if (fd >= 0) close(fd);
    if (error && cap) snprintf(error, cap, "%s (errno %d %s)", what, saved, strerror(saved));
    return -1;
}

int direct_socket_protected(void *ctx, int family, int type, char *error, size_t error_cap) {
    (void)ctx;
    if (error && error_cap) error[0] = '\0';
    int fd = socket(family, type, 0);
    if (fd < 0) return fail(-1, error, error_cap, "cannot open a direct socket");
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0 ||
        fcntl(fd, F_SETFD, FD_CLOEXEC) != 0)
        return fail(fd, error, error_cap, "cannot make the direct socket nonblocking");
    if (net_protect_socket(fd) != 0)
        return fail(fd, error, error_cap, "the vpn service refused to protect the direct socket");
    return fd;
}
