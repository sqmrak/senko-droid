#define _DEFAULT_SOURCE

#include "engine_main.h"
#include "daemon_ctl.h"
#include "packet_fixture.h"

#include "net_protect.h"
#include "senko_paths.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

static int failures;

static void ok(const char *name, int condition) {
    if (condition) return;
    ++failures;
    printf("FAIL %s\n", name);
}

static const char *root = "/tmp/senko-engine-root";
static const char *sock_path = "/tmp/senko-engine-root/ctl.sock";
static const char *token_path = "/tmp/senko-engine-root/ctl.token";
static const char *store_path = "/tmp/senko-engine-root/senko.cfg";

static int g_tun_calls;
static tun_params_t g_tun_params;
/* when set, the next tunnel gets one end of a datagram socketpair as its tun
   device, the way the kernel hands the engine bare ip packets */
static volatile int g_tun_peer = -1;
static int g_tun_wired;

static int fake_tun(void *ctx, const tun_params_t *params, char *error, size_t cap) {
    (void)ctx;
    ++g_tun_calls;
    g_tun_params = *params;
    if (g_tun_wired) {
        int pair[2];
        if (socketpair(AF_UNIX, SOCK_DGRAM, 0, pair) != 0) {
            snprintf(error, cap, "no socketpair");
            return -1;
        }
        g_tun_peer = pair[1];
        return pair[0];
    }
    snprintf(error, cap, "fake tun refused");
    return -1;
}

static int g_protect_calls;

static int fake_protect(void *ctx, int fd) {
    (void)ctx;
    (void)fd;
    ++g_protect_calls;
    return 0;
}

extern int daemon_ctl_test_subscription_connect(const struct sockaddr *, socklen_t, int, char *, size_t);

static int refuse_protect(void *ctx, int fd) {
    (void)ctx;
    (void)fd;
    return -1;
}

static void subscription_routes(void) {
    int server = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    socklen_t len = sizeof addr;
    if (server < 0 || bind(server, (struct sockaddr *)&addr, len) != 0 ||
        listen(server, 1) != 0 || getsockname(server, (struct sockaddr *)&addr, &len) != 0) {
        ok("subscription test listener", 0);
        if (server >= 0) close(server);
        return;
    }
    char error[128];
    net_protect_install(refuse_protect, NULL);
    int fd = daemon_ctl_test_subscription_connect((struct sockaddr *)&addr, len, 0, error, sizeof error);
    ok("subscription default route works without vpn permission", fd >= 0);
    if (fd >= 0) close(fd);
    fd = daemon_ctl_test_subscription_connect((struct sockaddr *)&addr, len, 1, error, sizeof error);
    ok("direct subscription fallback requires protect", fd < 0 && strstr(error, "protect") != NULL);
    if (fd >= 0) close(fd);
    net_protect_install(NULL, NULL);
    close(server);
}

/* opt-in integration probe, the url and response are never written to the test log */
static void subscription_live_probe(void) {
    const char *url = getenv("SENKODROID_SUBSCRIPTION_PROBE_URL");
    const char *roots = getenv("SENKODROID_SUBSCRIPTION_PROBE_ROOT");
    if (!url || !url[0]) return;
    if (!roots || senko_paths_init(roots) != SENKO_PATHS_OK) {
        ok("subscription probe roots", 0);
        return;
    }
    static unsigned char body[512 * 1024];
    size_t len = 0;
    ctl_fetch_meta_t meta;
    memset(&meta, 0, sizeof meta);
    net_protect_install(refuse_protect, NULL);
    int rc = daemon_ctl_fetch(NULL, url, NULL, body, sizeof body, &len, &meta);
    net_protect_install(NULL, NULL);
    ok("subscription loads over the default network without vpn permission", rc == 0 && len > 0);
    if (rc == 0 && len > 0) {
        static vl_server_t servers[STORE_MAX_SERVERS];
        cfg_import_stats_t stats;
        size_t count = 0;
        memset(&stats, 0, sizeof stats);
        cfg_status_t parsed = cfg_parse_subscription_rows((const char *)body, len, servers,
                                                           STORE_MAX_SERVERS, &count, &stats);
        ok("subscription response can be parsed", parsed == CFG_OK && count > 0);
        fprintf(stderr, "subscription integration: %zu rows, gated %d\n", count, meta.gated);
    }
}

typedef struct {
    engine_config_t config;
    engine_status_t status;
    char error[160];
} run_t;

static void *run_engine(void *arg) {
    run_t *run = arg;
    run->status = engine_run(&run->config, run->error, sizeof run->error);
    return NULL;
}

static int wait_for_socket(void) {
    for (int i = 0; i < 300; ++i) {
        struct stat st;
        if (stat(token_path, &st) == 0 && stat(sock_path, &st) == 0) return 1;
        usleep(10000);
    }
    return 0;
}

/* one connection, optionally authenticated, one request, all the reply */
static void request(int authenticate, const char *line, char *out, size_t cap) {
    out[0] = '\0';
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof addr.sun_path, "%s", sock_path);
    if (fd < 0 || connect(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
        if (fd >= 0) close(fd);
        snprintf(out, cap, "connect failed (errno %d)", errno);
        return;
    }
    char sent[600];
    sent[0] = '\0';
    if (authenticate) {
        char token[64] = "";
        FILE *file = fopen(token_path, "r");
        if (file) {
            if (fgets(token, sizeof token, file)) token[strcspn(token, "\r\n")] = '\0';
            fclose(file);
        }
        snprintf(sent, sizeof sent, "AUTH %s\n", token);
    }
    strncat(sent, line, sizeof sent - strlen(sent) - 1);
    ssize_t wrote = write(fd, sent, strlen(sent));
    (void)wrote;
    size_t have = 0;
    int quiet = 0;
    while (quiet < 3 && have + 1 < cap) {
        struct pollfd pfd = { fd, POLLIN, 0 };
        int r = poll(&pfd, 1, 200);
        if (r <= 0) {
            ++quiet;
            continue;
        }
        ssize_t got = read(fd, out + have, cap - have - 1);
        if (got <= 0) break;
        have += (size_t)got;
        out[have] = '\0';
        quiet = 0;
    }
    close(fd);
}

typedef struct {
    char reply[2048];
} connect_t;

static void *connect_async(void *arg) {
    connect_t *call = arg;
    request(1, "CONNECT 1\n", call->reply, sizeof call->reply);
    return NULL;
}

/* a packet an app would write into the tun: tcp from the interface address to a
   public host, with nothing listening at the server behind the tunnel */
static void packets_cross_the_tunnel(void) {
    char out[2048];
    request(1, "ADDSRV vless://22222222-2222-4222-8222-222222222222@127.0.0.1:1"
               "?security=none&type=tcp#dead\n", out, sizeof out);
    ok("a second server is added", strstr(out, "OK ") != NULL);

    g_tun_wired = 1;
    g_tun_peer = -1;
    connect_t call;
    memset(&call, 0, sizeof call);
    pthread_t thread;
    pthread_create(&thread, NULL, connect_async, &call);
    for (int i = 0; i < 500 && g_tun_peer < 0; ++i) usleep(10000);
    ok("the tunnel asked for its interface", g_tun_peer >= 0);

    static const uint8_t app[4] = { 198, 18, 0, 1 };
    static const uint8_t host[4] = { 93, 184, 216, 34 };
    uint8_t syn[128];
    size_t syn_len = pkt_tcp4(syn, app, 40000, host, 80, 0x02, 1000, 0, 0);

    int saw_reply = 0, saw_bare_ip = 0, from_host = 0, tcp = 0;
    uint8_t flags = 0;
    /* the loop thread may not be reading yet, so the syn is repeated, which is
       what a real tcp stack does too */
    for (int attempt = 0; attempt < 40 && !saw_reply && g_tun_peer >= 0; ++attempt) {
        ssize_t sent = write(g_tun_peer, syn, syn_len);
        ok("the syn is written", sent == (ssize_t)syn_len);
        struct pollfd pfd = { g_tun_peer, POLLIN, 0 };
        if (poll(&pfd, 1, 250) > 0) {
            uint8_t packet[2048];
            ssize_t got = read(g_tun_peer, packet, sizeof packet);
            if (got >= 40) {
                saw_reply = 1;
                saw_bare_ip = (packet[0] >> 4) == 4 && pkt_ip4_valid(packet) &&
                              pkt_rd16(packet + 2) == (uint16_t)got;
                tcp = packet[9] == 6;
                from_host = memcmp(pkt_src(packet), host, 4) == 0 &&
                            memcmp(pkt_dst(packet), app, 4) == 0;
                flags = packet[20 + 13];
            }
        }
    }
    printf("the tunnel answered the syn with tcp flags 0x%02x\n", flags);
    ok("the engine answered the syn", saw_reply);
    ok("the answer is a bare ip packet, with no family header", saw_bare_ip);
    ok("it is tcp from the destination to the app address", tcp && from_host);
    ok("it accepts or refuses the connection", (flags & 0x12) == 0x12 || (flags & 0x04));

    ok("the dial toward the server was offered to the vpn service", g_protect_calls >= 1);

    pthread_join(thread, NULL);
    ok("a started tunnel connects even when the server is unavailable",
       strstr(call.reply, "STATE connected") != NULL);
    request(1, "DISCONNECT\n", out, sizeof out);
    ok("the first tunnel disconnects", strstr(out, "STATE idle") != NULL);
    close(g_tun_peer);
    g_tun_peer = -1;
    request(1, "CONNECT 1\n", out, sizeof out);
    ok("the same tunnel can start again without waiting for server traffic",
       strstr(out, "STATE connected") != NULL);
    request(1, "DISCONNECT\n", out, sizeof out);
    ok("the second tunnel disconnects", strstr(out, "STATE idle") != NULL);
    if (g_tun_peer >= 0) close(g_tun_peer);
    g_tun_peer = -1;
    g_tun_wired = 0;
}

static void start(run_t *run, pthread_t *thread) {
    memset(run, 0, sizeof *run);
    run->config.files_dir = root;
    run->config.socket_path = sock_path;
    run->config.source.open = fake_tun;
    run->status = ENGINE_ERR_SYSTEM;
    pthread_create(thread, NULL, run_engine, run);
}

int main(void) {
    subscription_routes();
    subscription_live_probe();
    mkdir(root, 0700);
    unlink(sock_path);
    unlink(token_path);
    unlink(store_path);
    net_protect_install(fake_protect, NULL);

    char out[4096];
    run_t run;
    pthread_t thread;

    /* an incomplete configuration is refused at once */
    engine_config_t bad;
    memset(&bad, 0, sizeof bad);
    char error[160];
    ok("an empty configuration is refused", engine_run(&bad, error, sizeof error) == ENGINE_ERR_ARG &&
                                            error[0] != '\0');
    engine_stop();

    start(&run, &thread);
    ok("the control socket appears", wait_for_socket());

    request(0, "STATUS\n", out, sizeof out);
    ok("a request without the token is refused", strstr(out, "ERR auth required") != NULL);

    request(1, "STATUS\n", out, sizeof out);
    ok("an authenticated status says idle", strstr(out, "STATE idle") != NULL);

    request(1, "ADDSRV vless://11111111-1111-4111-8111-111111111111@1.2.3.4:443?security=none&type=tcp#first\n",
            out, sizeof out);
    ok("a server is added", strstr(out, "OK ") != NULL);
    request(1, "LIST\n", out, sizeof out);
    ok("the list shows it", strstr(out, "1.2.3.4") != NULL || strstr(out, "first") != NULL);

    request(1, "CONNECT 0\n", out, sizeof out);
    ok("a failed tunnel says why", strstr(out, "fake tun refused") != NULL);
    ok("the vpn service was asked for the interface", g_tun_calls == 1);
    ok("the interface asks for the stack address", strcmp(g_tun_params.address4, "198.18.0.1") == 0 &&
                                                   g_tun_params.prefix4 == 32 &&
                                                   g_tun_params.mtu == 1500 &&
                                                   strcmp(g_tun_params.dns4, "8.8.8.8") == 0);

    request(1, "DIAG\n", out, sizeof out);
    ok("diagnostics come from the engine", strstr(out, "DIAG engine.uptime") != NULL &&
                                           strstr(out, "DIAG path.roots") != NULL);

    request(1, "NETWORK\n", out, sizeof out);
    ok("a network change is accepted while idle", strstr(out, "OK network change noted") != NULL);

    request(1, "FWCONF\n", out, sizeof out);
    ok("an ios verb is refused", strstr(out, "ERR ") != NULL);

    packets_cross_the_tunnel();

    engine_stop();
    pthread_join(thread, NULL);
    ok("the engine returns on stop", run.status == ENGINE_OK);

    struct stat st;
    ok("the catalog was saved", stat(store_path, &st) == 0 && st.st_size > 0);
    ok("the log exists", stat("/tmp/senko-engine-root/senko.log", &st) == 0);

    /* a second run loads what the first one saved */
    unlink(sock_path);
    unlink(token_path);
    start(&run, &thread);
    ok("the socket is back after a restart", wait_for_socket());
    request(1, "LIST\n", out, sizeof out);
    ok("the saved server survives a restart", strstr(out, "1.2.3.4") != NULL ||
                                              strstr(out, "first") != NULL);

    /* only one engine at a time */
    engine_config_t again = run.config;
    ok("a second engine is refused", engine_run(&again, error, sizeof error) == ENGINE_ERR_RUNNING);

    engine_stop();
    pthread_join(thread, NULL);
    ok("the second run returns on stop", run.status == ENGINE_OK);

    net_protect_install(NULL, NULL);
    if (failures) {
        printf("%d check(s) failed\n", failures);
        return 1;
    }
    puts("ok");
    return 0;
}
