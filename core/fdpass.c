#include "core/fdpass.h"

#include <errno.h>
#include <poll.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <unistd.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

int fdpass_send(int sock, const void *data, size_t len, int fd) {
    const char *bytes = data;
    int pending_fd = fd;
    while (len > 0) {
        struct iovec iov = { (void *)bytes, len };
        struct msghdr msg;
        union {
            struct cmsghdr align;
            char buf[CMSG_SPACE(sizeof(int))];
        } control;
        memset(&msg, 0, sizeof msg);
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        if (pending_fd >= 0) {
            memset(&control, 0, sizeof control);
            msg.msg_control = control.buf;
            msg.msg_controllen = sizeof control.buf;
            struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
            cmsg->cmsg_level = SOL_SOCKET;
            cmsg->cmsg_type = SCM_RIGHTS;
            cmsg->cmsg_len = CMSG_LEN(sizeof(int));
            memcpy(CMSG_DATA(cmsg), &pending_fd, sizeof(int));
        }
        ssize_t sent = sendmsg(sock, &msg, MSG_NOSIGNAL);
        if (sent < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (sent == 0) {
            errno = EPIPE;
            return -1;
        }
        pending_fd = -1;
        bytes += sent;
        len -= (size_t)sent;
    }
    return 0;
}

static int wait_readable(int sock, int timeout_ms) {
    struct pollfd pfd = { sock, POLLIN, 0 };
    for (;;) {
        int ready = poll(&pfd, 1, timeout_ms);
        if (ready > 0) return 0;
        if (ready == 0) {
            errno = ETIMEDOUT;
            return -1;
        }
        if (errno != EINTR) return -1;
    }
}

int fdpass_recv_line(int sock, char *buf, size_t cap, int *fd_out, int timeout_ms) {
    if (!buf || cap < 2 || !fd_out) {
        errno = EINVAL;
        return -1;
    }
    *fd_out = -1;
    size_t have = 0;
    for (;;) {
        if (wait_readable(sock, timeout_ms) != 0) goto fail;
        /* one byte at a time would be slow but it keeps bytes of the next line out of this one */
        struct iovec iov = { buf + have, 1 };
        struct msghdr msg;
        union {
            struct cmsghdr align;
            char buf[CMSG_SPACE(sizeof(int))];
        } control;
        memset(&msg, 0, sizeof msg);
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control.buf;
        msg.msg_controllen = sizeof control.buf;
        ssize_t got = recvmsg(sock, &msg, 0);
        if (got < 0) {
            if (errno == EINTR) continue;
            goto fail;
        }
        for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
            if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) continue;
            size_t count = (cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            for (size_t i = 0; i < count; ++i) {
                int received;
                memcpy(&received, CMSG_DATA(cmsg) + i * sizeof(int), sizeof received);
                if (*fd_out < 0) *fd_out = received;
                else close(received);
            }
        }
        if (msg.msg_flags & MSG_CTRUNC) {
            errno = EMSGSIZE;
            goto fail;
        }
        if (got == 0) {
            if (have == 0) return 0;
            errno = ECONNRESET;
            goto fail;
        }
        ++have;
        if (buf[have - 1] == '\n') {
            buf[have] = '\0';
            return (int)have;
        }
        if (have + 1 >= cap) {
            errno = EMSGSIZE;
            goto fail;
        }
    }
fail: {
        int saved = errno;
        if (*fd_out >= 0) close(*fd_out);
        *fd_out = -1;
        errno = saved;
        return -1;
    }
}
