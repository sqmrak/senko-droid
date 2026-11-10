/* the whole android path on a device with a root helper: senko_rootd makes the tun device, the go
   core runs on it with a freedom outbound whose sockets are kept out of the tunnel through the
   helper, and this process fetches a page over a plain tcp socket that the tun routes catch.
   usage: tun_probe <libsenkocore.so> <rootd socket> <host ip> */
#include <arpa/inet.h>
#include <dlfcn.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "engine/root_client.h"

static const char *g_sock;

static int protect(int fd) {
    char why[160] = "";
    int rc = root_client_protect(g_sock, fd, why, sizeof why);
    if (rc != 0) fprintf(stderr, "protect refused: %s\n", why);
    return rc;
}

static const char CONFIG[] =
    "{\"log\":{\"loglevel\":\"warning\"},\"stats\":{},"
    "\"policy\":{\"system\":{\"statsInboundUplink\":true,\"statsInboundDownlink\":true}},"
    "\"inbounds\":[{\"tag\":\"senko-tun\",\"protocol\":\"tun\",\"settings\":{\"name\":\"tun0\",\"mtu\":1500}}],"
    "\"outbounds\":[{\"tag\":\"direct\",\"protocol\":\"freedom\"}]}";

int main(int argc, char **argv) {
    if (argc < 4) return 2;
    g_sock = argv[2];
    void *lib = dlopen(argv[1], RTLD_NOW);
    if (!lib) { printf("FAIL dlopen %s\n", dlerror()); return 1; }
    int (*set_protect)(void *) = (int (*)(void *))dlsym(lib, "SenkoCoreSetProtect");
    int (*start)(const char *, int, int, char *, int) = (int (*)(const char *, int, int, char *, int))dlsym(lib, "SenkoCoreStart");
    int (*stop)(char *, int) = (int (*)(char *, int))dlsym(lib, "SenkoCoreStop");
    int (*stats)(uint64_t *, uint64_t *) = (int (*)(uint64_t *, uint64_t *))dlsym(lib, "SenkoCoreStats");
    if (!set_protect || !start || !stop || !stats) { printf("FAIL dlsym\n"); return 1; }
    printf("set_protect=%d\n", set_protect((void *)protect));

    tun_params_t p;
    memset(&p, 0, sizeof p);
    snprintf(p.address4, sizeof p.address4, "198.18.0.1");
    p.prefix4 = 32;
    snprintf(p.dns4, sizeof p.dns4, "1.1.1.1");
    p.mtu = 1500;
    char why[256] = "";
    int tun = root_client_open_tun(g_sock, &p, why, sizeof why);
    printf("tun=%d %s\n", tun, why);
    if (tun < 0) return 1;
    char error[256] = "";
    int rc = start(CONFIG, (int)strlen(CONFIG), tun, error, sizeof error);
    printf("start=%d %s\n", rc, error);
    if (rc != 0) return 1;
    sleep(1);

    /* a tcp connection that only the tunnel can carry: the default route is shadowed by the /1 halves */
    int s = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_port = htons(80);
    inet_pton(AF_INET, argv[3], &addr.sin_addr);
    struct timeval tv = { 8, 0 };
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    int c = connect(s, (struct sockaddr *)&addr, sizeof addr);
    printf("connect=%d\n", c);
    if (c == 0) {
        const char *req = "GET / HTTP/1.0\r\nHost: example.com\r\nConnection: close\r\n\r\n";
        write(s, req, strlen(req));
        char buf[256];
        ssize_t n = read(s, buf, sizeof buf - 1);
        if (n > 0) { buf[n] = 0; char *eol = strchr(buf, '\r'); if (eol) *eol = 0; printf("reply: %s\n", buf); }
        else printf("read=%zd\n", n);
    }
    close(s);
    uint64_t up = 0, down = 0;
    printf("stats=%d up=%llu down=%llu\n", stats(&up, &down), (unsigned long long)up, (unsigned long long)down);
    rc = stop(error, sizeof error);
    printf("stop=%d %s\n", rc, error);
    close(tun);
    return c == 0 ? 0 : 1;
}
