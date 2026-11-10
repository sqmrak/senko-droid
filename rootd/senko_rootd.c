#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include "rootd_linux.h"
#include "rootd_serve.h"

/* senko_rootd <socket path> <uid>
   the root half of senko on android 2.3 to 3.x. the app starts it through su and keeps its
   stdin open: the helper answers on a unix socket that only that uid may use, prints READY
   once it listens, and cleans up when stdin closes or QUIT arrives */

static int listen_on(const char *path, uid_t uid) {
    struct sockaddr_un addr;
    if (strlen(path) >= sizeof addr.sun_path) {
        fprintf(stderr, "senko_rootd: socket path is too long\n");
        return -1;
    }
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path, path, strlen(path) + 1);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        perror("senko_rootd: socket");
        return -1;
    }
    (void)unlink(path);
    mode_t old = umask(0177);
    int bound = bind(fd, (struct sockaddr *)&addr, sizeof addr);
    umask(old);
    if (bound != 0 || chown(path, uid, (gid_t)-1) != 0 || chmod(path, 0600) != 0 || listen(fd, 4) != 0) {
        perror("senko_rootd: socket setup");
        close(fd);
        (void)unlink(path);
        return -1;
    }
    return fd;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: senko_rootd <socket path> <uid>\n");
        return 2;
    }
    if (geteuid() != 0) {
        fprintf(stderr, "senko_rootd: root access was not granted\n");
        return 1;
    }
    char *end = NULL;
    errno = 0;
    unsigned long uid = strtoul(argv[2], &end, 10);
    if (errno != 0 || !*argv[2] || *end != '\0' || uid == 0) {
        fprintf(stderr, "senko_rootd: the uid must be the app's, not root\n");
        return 2;
    }
    signal(SIGPIPE, SIG_IGN);
    int listener = listen_on(argv[1], (uid_t)uid);
    if (listener < 0) return 1;
    rootd_ops_t ops;
    rootd_linux_ops(&ops);
    printf("READY\n");
    fflush(stdout);
    rootd_serve_t result = rootd_serve(listener, STDIN_FILENO, (uid_t)uid, &ops);
    close(listener);
    (void)unlink(argv[1]);
    return result == ROOTD_SERVE_ERROR ? 1 : 0;
}
