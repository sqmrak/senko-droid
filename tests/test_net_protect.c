#include "net_protect.h"

#include <errno.h>
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>

static int failures;

static void ok(const char *name, int condition) {
    if (condition) return;
    ++failures;
    fprintf(stderr, "FAIL %s\n", name);
}

static int seen_fd = -1;
static int seen_ctx;

static int accept_all(void *ctx, int fd) {
    seen_fd = fd;
    seen_ctx = *(int *)ctx;
    return 0;
}

static int refuse_all(void *ctx, int fd) {
    (void)ctx;
    (void)fd;
    return -1;
}

int main(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    ok("socket", fd >= 0);

    ok("nothing installed", !net_protect_installed());
    ok("no hook is a pass", net_protect_socket(fd) == 0);

    int marker = 42;
    net_protect_install(accept_all, &marker);
    ok("installed", net_protect_installed());
    ok("hook accepts", net_protect_socket(fd) == 0);
    ok("hook got the descriptor", seen_fd == fd);
    ok("hook got its context", seen_ctx == 42);

    errno = 0;
    ok("bad descriptor is refused", net_protect_socket(-1) == -1 && errno == EPERM);

    net_protect_install(refuse_all, NULL);
    errno = 0;
    ok("refusal is reported", net_protect_socket(fd) == -1 && errno == EPERM);

    net_protect_install(NULL, &marker);
    ok("removed", !net_protect_installed());
    ok("removed hook is a pass", net_protect_socket(fd) == 0);

    close(fd);
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("ok");
    return 0;
}
