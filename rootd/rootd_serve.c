#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "rootd_serve.h"

#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "core/fdpass.h"

#define REQUEST_TIMEOUT_MS 3000
#define TICK_MS 1000

static void reply(int conn, const char *text, int fd) {
    /* a client that went away is not the helper's problem, it will ask again */
    (void)fdpass_send(conn, text, strlen(text), fd);
}

static void reply_error(int conn, const char *why) {
    char line[ROOTD_LINE_MAX];
    snprintf(line, sizeof line, "ERR %s\n", why[0] ? why : "failed");
    reply(conn, line, -1);
}

static int peer_allowed(int conn, uid_t allowed) {
    struct ucred cred;
    socklen_t len = sizeof cred;
    if (getsockopt(conn, SOL_SOCKET, SO_PEERCRED, &cred, &len) != 0 || len != sizeof cred) return 0;
    return cred.uid == allowed;
}

static int handle(int conn, const rootd_ops_t *ops) {
    char line[ROOTD_LINE_MAX];
    int attached = -1;
    int got = fdpass_recv_line(conn, line, sizeof line, &attached, REQUEST_TIMEOUT_MS);
    if (got <= 0) {
        if (attached >= 0) close(attached);
        return 0;
    }
    rootd_req_t req;
    rootd_parse_t parsed = rootd_parse_request(line, &req);
    char why[128] = "";
    char name[ROOTD_IFNAME_MAX + 1] = "";
    char out[ROOTD_LINE_MAX];
    int quit = 0;

    if (parsed != ROOTD_PARSE_OK) {
        reply_error(conn, parsed == ROOTD_PARSE_UNKNOWN ? "unknown request" : "bad request");
    } else if (req.kind == ROOTD_REQ_PING) {
        reply(conn, "OK\n", -1);
    } else if (req.kind == ROOTD_REQ_QUIT) {
        reply(conn, "OK\n", -1);
        quit = 1;
    } else if (req.kind == ROOTD_REQ_PROTECT) {
        if (attached < 0) {
            reply_error(conn, "no socket attached");
        } else if (ops->protect(ops->ctx, attached, name, sizeof name, why, sizeof why) != 0) {
            reply_error(conn, why);
        } else {
            snprintf(out, sizeof out, "OK %s\n", name);
            reply(conn, out, -1);
        }
    } else {
        int tun = ops->open_tun(ops->ctx, &req, name, sizeof name, why, sizeof why);
        if (tun < 0) {
            reply_error(conn, why);
        } else {
            snprintf(out, sizeof out, "OK %s\n", name);
            reply(conn, out, tun);
            /* the device now lives in the client alone: when it closes its copy the interface and
               its routes disappear, which is the teardown for a crashed app too */
            close(tun);
        }
    }
    if (attached >= 0) close(attached);
    return quit;
}

rootd_serve_t rootd_serve(int listen_fd, int stop_fd, uid_t allowed_uid, const rootd_ops_t *ops) {
    rootd_serve_t result = ROOTD_SERVE_ERROR;
    for (;;) {
        struct pollfd fds[2] = { { listen_fd, POLLIN, 0 }, { stop_fd, POLLIN, 0 } };
        int ready = poll(fds, stop_fd >= 0 ? 2 : 1, ops->tick ? TICK_MS : -1);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (ready == 0) {
            if (ops->tick) ops->tick(ops->ctx);
            continue;
        }
        if (stop_fd >= 0 && (fds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            /* the parent writes nothing after its start line, so any wakeup means it closed */
            char drain[64];
            ssize_t n = read(stop_fd, drain, sizeof drain);
            if (n == 0 || (n < 0 && errno != EINTR && errno != EAGAIN)) {
                result = ROOTD_SERVE_HANGUP;
                break;
            }
        }
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (!(fds[0].revents & POLLIN)) continue;
        int conn = accept(listen_fd, NULL, NULL);
        if (conn < 0) {
            if (errno == EINTR || errno == ECONNABORTED || errno == EAGAIN) continue;
            break;
        }
        int quit = 0;
        if (peer_allowed(conn, allowed_uid)) quit = handle(conn, ops);
        close(conn);
        if (quit) {
            result = ROOTD_SERVE_QUIT;
            break;
        }
    }
    ops->restore(ops->ctx);
    return result;
}
