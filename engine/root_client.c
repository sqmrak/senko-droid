#include "root_client.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include "core/fdpass.h"
#include "rootd/rootd_proto.h"

#define ANSWER_TIMEOUT_MS 5000

static void say(char *error, size_t cap, const char *text, int err) {
    if (!error || cap == 0) return;
    if (err) snprintf(error, cap, "%s: %s", text, strerror(err));
    else snprintf(error, cap, "%s", text);
}

static int connect_helper(const char *socket_path, char *error, size_t error_cap) {
    struct sockaddr_un addr;
    if (!socket_path || strlen(socket_path) >= sizeof addr.sun_path) {
        say(error, error_cap, "the root helper socket path is unusable", 0);
        return -1;
    }
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path, socket_path, strlen(socket_path) + 1);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        say(error, error_cap, "no socket for the root helper", errno);
        return -1;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
        say(error, error_cap, "the root helper is not running", errno);
        close(fd);
        return -1;
    }
    return fd;
}

/* one request, one answer. the answer's descriptor, if any, goes to *fd_out. returns 0 for an
   OK answer (the text after it is copied to detail) and -1 otherwise */
static int transact(const char *socket_path, const char *request, int send_fd, int *fd_out,
                    char *detail, size_t detail_cap, char *error, size_t error_cap) {
    int local_fd = -1;
    if (!fd_out) fd_out = &local_fd;
    *fd_out = -1;
    if (detail && detail_cap) detail[0] = '\0';
    int conn = connect_helper(socket_path, error, error_cap);
    if (conn < 0) return -1;
    char answer[ROOTD_LINE_MAX];
    int result = -1;
    if (fdpass_send(conn, request, strlen(request), send_fd) != 0) {
        say(error, error_cap, "the root helper did not take the request", errno);
    } else {
        int got = fdpass_recv_line(conn, answer, sizeof answer, fd_out, ANSWER_TIMEOUT_MS);
        if (got < 0) {
            say(error, error_cap, "the root helper did not answer", errno);
        } else if (got == 0) {
            say(error, error_cap, "the root helper closed the connection", 0);
        } else {
            answer[got - 1] = '\0';
            if (strcmp(answer, "OK") == 0 || strncmp(answer, "OK ", 3) == 0) {
                if (detail && detail_cap) snprintf(detail, detail_cap, "%s", answer[2] ? answer + 3 : "");
                result = 0;
            } else {
                say(error, error_cap, strncmp(answer, "ERR ", 4) == 0 ? answer + 4 : answer, 0);
            }
        }
    }
    if (result != 0 && *fd_out >= 0) {
        close(*fd_out);
        *fd_out = -1;
    }
    if (local_fd >= 0) close(local_fd);
    close(conn);
    return result;
}

int root_client_open_tun(const char *socket_path, const tun_params_t *params,
                         char *error, size_t error_cap) {
    if (!params || params->address4[0] == '\0' || params->dns4[0] == '\0') {
        say(error, error_cap, "the tunnel needs an ipv4 address and a dns server", 0);
        return -1;
    }
    char request[ROOTD_LINE_MAX];
    int n = snprintf(request, sizeof request, "TUN %s %u %u %s\n", params->address4,
                     (unsigned)params->prefix4, (unsigned)params->mtu, params->dns4);
    if (n < 0 || (size_t)n >= sizeof request) {
        say(error, error_cap, "the tunnel request does not fit", 0);
        return -1;
    }
    int tun = -1;
    /* the interface name in the answer is not needed here, the descriptor is the device */
    char name[ROOTD_LINE_MAX];
    if (transact(socket_path, request, -1, &tun, name, sizeof name, error, error_cap) != 0) return -1;
    if (tun < 0) {
        say(error, error_cap, "the root helper answered without a tun device", 0);
        return -1;
    }
    return tun;
}

int root_client_protect(const char *socket_path, int fd, char *error, size_t error_cap) {
    if (fd < 0) {
        say(error, error_cap, "no socket to protect", 0);
        return -1;
    }
    return transact(socket_path, "PROTECT\n", fd, NULL, NULL, 0, error, error_cap);
}

int root_client_ping(const char *socket_path, char *error, size_t error_cap) {
    return transact(socket_path, "PING\n", -1, NULL, NULL, 0, error, error_cap);
}
