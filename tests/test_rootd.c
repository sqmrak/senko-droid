#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include "core/fdpass.h"
#include "rootd/rootd_proto.h"
#include "rootd/rootd_serve.h"
#include "engine/root_client.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); ++failures; } } while (0)

static void test_parse(void) {
    rootd_req_t r;
    CHECK(rootd_parse_request("PING\n", &r) == ROOTD_PARSE_OK && r.kind == ROOTD_REQ_PING);
    CHECK(rootd_parse_request("QUIT", &r) == ROOTD_PARSE_OK && r.kind == ROOTD_REQ_QUIT);
    CHECK(rootd_parse_request("PROTECT\r\n", &r) == ROOTD_PARSE_OK && r.kind == ROOTD_REQ_PROTECT);
    CHECK(rootd_parse_request("TUN 198.18.0.1 32 1500 1.1.1.1\n", &r) == ROOTD_PARSE_OK);
    CHECK(r.kind == ROOTD_REQ_TUN && r.prefix4 == 32 && r.mtu == 1500);
    CHECK(strcmp(r.address4, "198.18.0.1") == 0 && strcmp(r.dns4, "1.1.1.1") == 0);

    CHECK(rootd_parse_request("", &r) == ROOTD_PARSE_EMPTY);
    CHECK(rootd_parse_request("\n", &r) == ROOTD_PARSE_EMPTY);
    CHECK(rootd_parse_request(NULL, &r) == ROOTD_PARSE_EMPTY);
    CHECK(rootd_parse_request("ROUTE add\n", &r) == ROOTD_PARSE_UNKNOWN);
    CHECK(rootd_parse_request("PING extra\n", &r) == ROOTD_PARSE_MALFORMED);
    CHECK(rootd_parse_request("TUN 198.18.0.1 32 1500\n", &r) == ROOTD_PARSE_MALFORMED);
    CHECK(rootd_parse_request("TUN 198.18.0.1 32 1500 1.1.1.1 x\n", &r) == ROOTD_PARSE_MALFORMED);
    CHECK(rootd_parse_request("TUN 198.18.0.300 32 1500 1.1.1.1\n", &r) == ROOTD_PARSE_MALFORMED);
    CHECK(rootd_parse_request("TUN 198.18.0.1 32 1500 1.1.1.1;reboot\n", &r) == ROOTD_PARSE_MALFORMED);
    CHECK(rootd_parse_request("TUN 198.18.0.1 -1 1500 1.1.1.1\n", &r) == ROOTD_PARSE_MALFORMED);
    CHECK(rootd_parse_request("TUN 198.18.0.1 0 1500 1.1.1.1\n", &r) == ROOTD_PARSE_RANGE);
    CHECK(rootd_parse_request("TUN 198.18.0.1 33 1500 1.1.1.1\n", &r) == ROOTD_PARSE_RANGE);
    CHECK(rootd_parse_request("TUN 198.18.0.1 32 100 1.1.1.1\n", &r) == ROOTD_PARSE_RANGE);
    CHECK(rootd_parse_request("TUN 198.18.0.1 32 99999 1.1.1.1\n", &r) == ROOTD_PARSE_RANGE);
    CHECK(r.kind == 0);
    CHECK(rootd_parse_request("TUN 198.18.0.1 99999999999999999999 1500 1.1.1.1\n", &r) == ROOTD_PARSE_RANGE ||
          rootd_parse_request("TUN 198.18.0.1 99999999999999999999 1500 1.1.1.1\n", &r) == ROOTD_PARSE_MALFORMED);
    CHECK(rootd_parse_request("PI\x01NG\n", &r) == ROOTD_PARSE_MALFORMED);
    char big[ROOTD_LINE_MAX + 8];
    memset(big, 'A', sizeof big - 1);
    big[sizeof big - 1] = '\0';
    CHECK(rootd_parse_request(big, &r) == ROOTD_PARSE_MALFORMED);
}

static const char ROUTES[] =
    "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n"
    "senko0\t00000000\t00000000\t0001\t0\t0\t0\t00000000\t0\t0\t0\n"
    "rmnet0\t00000000\t0100000A\t0003\t0\t0\t50\t00000000\t0\t0\t0\n"
    "wlan0\t00000000\t0101A8C0\t0003\t0\t0\t10\t00000000\t0\t0\t0\n"
    "wlan0\t0001A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n";

static void test_default_iface(void) {
    char name[ROOTD_IFNAME_MAX + 1];
    CHECK(rootd_default_iface(ROUTES, "senko", name, sizeof name) == 0 && strcmp(name, "wlan0") == 0);
    CHECK(rootd_default_iface(ROUTES, NULL, name, sizeof name) == 0 && strcmp(name, "senko0") == 0);
    CHECK(rootd_default_iface("Iface\tDestination\n", "senko", name, sizeof name) == -1);
    CHECK(rootd_default_iface("", "senko", name, sizeof name) == -1);
    /* a route that is down, or not the default, never qualifies */
    CHECK(rootd_default_iface("wlan0\t00000000\t0101A8C0\t0002\t0\t0\t0\t00000000\t0\t0\t0\n", "senko", name, sizeof name) == -1);
    CHECK(rootd_default_iface("wlan0\t0001A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n", "senko", name, sizeof name) == -1);
    /* an interface name longer than the caller's buffer is refused, not cut */
    char tiny[4];
    CHECK(rootd_default_iface(ROUTES, "senko", tiny, sizeof tiny) == -1);
}

static void test_fdpass(void) {
    int pair[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    int pipefd[2];
    CHECK(pipe(pipefd) == 0);
    CHECK(fdpass_send(pair[0], "OK senko0\n", 10, pipefd[0]) == 0);
    char line[64];
    int got_fd = -2;
    CHECK(fdpass_recv_line(pair[1], line, sizeof line, &got_fd, 1000) == 10);
    CHECK(strcmp(line, "OK senko0\n") == 0 && got_fd >= 0);
    /* the received descriptor is the same pipe */
    CHECK(write(pipefd[1], "x", 1) == 1);
    char c = 0;
    CHECK(read(got_fd, &c, 1) == 1 && c == 'x');
    close(got_fd);

    /* a line without a descriptor reports -1, and the next line stays unread */
    CHECK(fdpass_send(pair[0], "PING\nQUIT\n", 10, -1) == 0);
    CHECK(fdpass_recv_line(pair[1], line, sizeof line, &got_fd, 1000) == 5 && got_fd == -1);
    CHECK(strcmp(line, "PING\n") == 0);
    CHECK(fdpass_recv_line(pair[1], line, sizeof line, &got_fd, 1000) == 5 && strcmp(line, "QUIT\n") == 0);
    /* nothing to read times out */
    CHECK(fdpass_recv_line(pair[1], line, sizeof line, &got_fd, 50) == -1 && errno == ETIMEDOUT);
    /* a line longer than the buffer is an error */
    char longline[100];
    memset(longline, 'A', sizeof longline);
    CHECK(fdpass_send(pair[0], longline, sizeof longline, -1) == 0);
    CHECK(fdpass_recv_line(pair[1], line, 16, &got_fd, 1000) == -1 && errno == EMSGSIZE);
    /* end of stream before a byte */
    int pair2[2];
    CHECK(socketpair(AF_UNIX, SOCK_STREAM, 0, pair2) == 0);
    close(pair2[0]);
    CHECK(fdpass_recv_line(pair2[1], line, sizeof line, &got_fd, 1000) == 0);
    close(pair2[1]);
    close(pair[0]);
    close(pair[1]);
    close(pipefd[0]);
    close(pipefd[1]);
}

typedef struct {
    int  tun_calls;
    int  protect_calls;
    int  restore_calls;
    int  tick_calls;
    int  fail_tun;
    int  last_mtu;
    char last_dns[16];
    int  protected_flags;
} fake_t;

static int fake_open_tun(void *ctx, const rootd_req_t *req, char *ifname, size_t ifname_cap, char *why, size_t why_cap) {
    fake_t *fake = ctx;
    ++fake->tun_calls;
    if (fake->fail_tun) {
        snprintf(why, why_cap, "cannot open /dev/tun: Permission denied");
        return -1;
    }
    fake->last_mtu = (int)req->mtu;
    snprintf(fake->last_dns, sizeof fake->last_dns, "%s", req->dns4);
    snprintf(ifname, ifname_cap, "senko0");
    return open("/dev/null", O_RDWR);
}

static int fake_protect(void *ctx, int fd, char *iface, size_t iface_cap, char *why, size_t why_cap) {
    fake_t *fake = ctx;
    ++fake->protect_calls;
    (void)why;
    (void)why_cap;
    /* proves the descriptor that arrived is a live socket of the client */
    int type = 0;
    socklen_t len = sizeof type;
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &len) != 0) return -1;
    fake->protected_flags = type;
    snprintf(iface, iface_cap, "wlan0");
    return 0;
}

static void fake_restore(void *ctx) { ++((fake_t *)ctx)->restore_calls; }
static void fake_tick(void *ctx) { ++((fake_t *)ctx)->tick_calls; }

typedef struct {
    int         listen_fd;
    int         stop_fd;
    uid_t       uid;
    rootd_ops_t ops;
    rootd_serve_t result;
} server_t;

static void *server_main(void *arg) {
    server_t *server = arg;
    server->result = rootd_serve(server->listen_fd, server->stop_fd, server->uid, &server->ops);
    return NULL;
}

static int connect_to(const char *path) {
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof addr.sun_path, "%s", path);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0 || connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) return -1;
    return fd;
}

static int ask(const char *path, const char *request, int send_fd, char *answer, size_t cap, int *got_fd) {
    int conn = connect_to(path);
    if (conn < 0) return -1;
    int ok = fdpass_send(conn, request, strlen(request), send_fd);
    int n = ok == 0 ? fdpass_recv_line(conn, answer, cap, got_fd, 2000) : -1;
    close(conn);
    return n;
}

static void test_serve(void) {
    char path[] = "/tmp/senko-rootd-test-XXXXXX";
    int tmp = mkstemp(path);
    CHECK(tmp >= 0);
    close(tmp);
    unlink(path);

    server_t server;
    memset(&server, 0, sizeof server);
    fake_t fake;
    memset(&fake, 0, sizeof fake);
    server.ops.open_tun = fake_open_tun;
    server.ops.protect = fake_protect;
    server.ops.restore = fake_restore;
    server.ops.tick = fake_tick;
    server.ops.ctx = &fake;
    server.uid = geteuid();

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof addr.sun_path, "%s", path);
    server.listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    CHECK(server.listen_fd >= 0);
    CHECK(bind(server.listen_fd, (struct sockaddr *)&addr, sizeof addr) == 0);
    CHECK(listen(server.listen_fd, 4) == 0);
    int stop[2];
    CHECK(pipe(stop) == 0);
    server.stop_fd = stop[0];

    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, server_main, &server) == 0);

    char answer[ROOTD_LINE_MAX];
    int fd = -1;
    CHECK(ask(path, "PING\n", -1, answer, sizeof answer, &fd) > 0 && strcmp(answer, "OK\n") == 0 && fd == -1);

    CHECK(ask(path, "TUN 198.18.0.1 32 1400 8.8.8.8\n", -1, answer, sizeof answer, &fd) > 0);
    CHECK(strcmp(answer, "OK senko0\n") == 0 && fd >= 0);
    CHECK(fake.tun_calls == 1 && fake.last_mtu == 1400 && strcmp(fake.last_dns, "8.8.8.8") == 0);
    if (fd >= 0) close(fd);

    fd = -1;
    fake.fail_tun = 1;
    CHECK(ask(path, "TUN 198.18.0.1 32 1400 8.8.8.8\n", -1, answer, sizeof answer, &fd) > 0);
    CHECK(strncmp(answer, "ERR cannot open /dev/tun", 24) == 0 && fd == -1);

    CHECK(ask(path, "TUN 198.18.0.1 32 99 8.8.8.8\n", -1, answer, sizeof answer, &fd) > 0);
    CHECK(strncmp(answer, "ERR ", 4) == 0 && fake.tun_calls == 2);
    CHECK(ask(path, "RM -rf\n", -1, answer, sizeof answer, &fd) > 0 && strncmp(answer, "ERR unknown", 11) == 0);

    CHECK(ask(path, "PROTECT\n", -1, answer, sizeof answer, &fd) > 0 && strncmp(answer, "ERR no socket", 13) == 0);
    int udp = socket(AF_INET, SOCK_DGRAM, 0); /* reused below */
    CHECK(ask(path, "PROTECT\n", udp, answer, sizeof answer, &fd) > 0 && strcmp(answer, "OK wlan0\n") == 0);
    CHECK(fake.protect_calls == 1 && fake.protected_flags == SOCK_DGRAM);
    close(udp);

    /* the engine's own client against the same loop */
    char why[160];
    CHECK(root_client_ping(path, why, sizeof why) == 0);
    tun_params_t params;
    memset(&params, 0, sizeof params);
    snprintf(params.address4, sizeof params.address4, "198.18.0.1");
    params.prefix4 = 32;
    snprintf(params.dns4, sizeof params.dns4, "1.1.1.1");
    params.mtu = 1500;
    fake.fail_tun = 0;
    int tun = root_client_open_tun(path, &params, why, sizeof why);
    CHECK(tun >= 0 && fake.last_mtu == 1500 && strcmp(fake.last_dns, "1.1.1.1") == 0);
    if (tun >= 0) close(tun);
    fake.fail_tun = 1;
    CHECK(root_client_open_tun(path, &params, why, sizeof why) == -1);
    CHECK(strstr(why, "Permission denied") != NULL);
    fake.fail_tun = 0;
    udp = socket(AF_INET, SOCK_DGRAM, 0);
    int before = fake.protect_calls;
    CHECK(root_client_protect(path, udp, why, sizeof why) == 0 && fake.protect_calls == before + 1);
    /* the socket stays usable and open in the caller */
    int type = 0;
    socklen_t type_len = sizeof type;
    CHECK(getsockopt(udp, SOL_SOCKET, SO_TYPE, &type, &type_len) == 0 && type == SOCK_DGRAM);
    close(udp);
    CHECK(root_client_protect(path, -1, why, sizeof why) == -1);
    params.dns4[0] = '\0';
    CHECK(root_client_open_tun(path, &params, why, sizeof why) == -1);
    CHECK(root_client_ping("/tmp/senko-rootd-no-such-socket", why, sizeof why) == -1 &&
          strstr(why, "not running") != NULL);
    char too_long[200];
    memset(too_long, 'a', sizeof too_long - 1);
    too_long[sizeof too_long - 1] = '\0';
    CHECK(root_client_ping(too_long, why, sizeof why) == -1);

    CHECK(ask(path, "QUIT\n", -1, answer, sizeof answer, &fd) > 0 && strcmp(answer, "OK\n") == 0);
    pthread_join(thread, NULL);
    CHECK(server.result == ROOTD_SERVE_QUIT && fake.restore_calls == 1);
    close(server.listen_fd);
    unlink(path);

    /* the parent closing its end of stdin stops the loop and restores */
    memset(&fake, 0, sizeof fake);
    server.listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    CHECK(bind(server.listen_fd, (struct sockaddr *)&addr, sizeof addr) == 0);
    CHECK(listen(server.listen_fd, 4) == 0);
    /* a peer that is not the allowed uid gets no answer, the connection just ends */
    server.uid = geteuid() + 1;
    CHECK(pthread_create(&thread, NULL, server_main, &server) == 0);
    fd = -1;
    CHECK(ask(path, "TUN 198.18.0.1 32 1400 8.8.8.8\n", -1, answer, sizeof answer, &fd) <= 0 && fd == -1);
    CHECK(fake.tun_calls == 0);
    close(stop[1]);
    pthread_join(thread, NULL);
    CHECK(server.result == ROOTD_SERVE_HANGUP && fake.restore_calls == 1);
    close(server.listen_fd);
    close(stop[0]);
    unlink(path);
}

int main(void) {
    test_parse();
    test_default_iface();
    test_fdpass();
    test_serve();
    if (failures) {
        fprintf(stderr, "%d failures\n", failures);
        return 1;
    }
    printf("test_rootd ok\n");
    return 0;
}
