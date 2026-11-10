#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "rootd_linux.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if_tun.h>
#include <net/if.h>
#include <net/route.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

#define IPTABLES_PATH "/system/bin/iptables"
#define ROUTE_TABLE_CAP 8192
#define TUN_PREFIX "senko"

/* one tunnel at a time: the app opens a new one only after the old descriptor is closed */
typedef struct {
    char ifname[ROOTD_IFNAME_MAX + 1];
    char dns4[16];
    int  dns_redirected;
} state_t;

static state_t g_state;

static void fail(char *why, size_t cap, const char *what) {
    snprintf(why, cap, "%s: %s", what, strerror(errno));
}

static int open_tun_node(void) {
    static const char *const nodes[] = { "/dev/tun", "/dev/net/tun" };
    int fd = -1;
    for (size_t i = 0; i < sizeof nodes / sizeof nodes[0] && fd < 0; ++i)
        fd = open(nodes[i], O_RDWR);
    return fd;
}

static void set_addr(struct sockaddr *target, const char *text) {
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family = AF_INET;
    inet_pton(AF_INET, text, &addr.sin_addr);
    memcpy(target, &addr, sizeof addr);
}

static void prefix_mask(unsigned prefix, char *out, size_t cap) {
    uint32_t mask = prefix >= 32 ? 0xffffffffu : ~(0xffffffffu >> prefix);
    struct in_addr addr;
    addr.s_addr = htonl(mask);
    inet_ntop(AF_INET, &addr, out, (socklen_t)cap);
}

static int add_route(int sock, const char *dest, const char *mask, const char *ifname) {
    struct rtentry route;
    memset(&route, 0, sizeof route);
    set_addr(&route.rt_dst, dest);
    set_addr(&route.rt_genmask, mask);
    route.rt_flags = RTF_UP;
    route.rt_dev = (char *)ifname;
    return ioctl(sock, SIOCADDRT, &route);
}

/* argv only, never a shell. returns the exit status, or -1 when it could not run */
static int run(char *const argv[]) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        int null = open("/dev/null", O_RDWR);
        if (null >= 0) {
            dup2(null, 0);
            dup2(null, 1);
            dup2(null, 2);
        }
        execv(argv[0], argv);
        _exit(127);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/* the lan resolver that dhcp hands out sits on a connected route, more specific than the two
   tunnel routes, so its queries would leave around the tunnel. every dns packet from this
   device is pointed at the resolver the engine answers for, which the tunnel routes cover */
static int dns_rules(const char *action, const char *proto, const char *dns4) {
    char target[32];
    snprintf(target, sizeof target, "%s:53", dns4);
    char *argv[] = { (char *)IPTABLES_PATH, "-t", "nat", (char *)action, "OUTPUT", "-p", (char *)proto,
                     "--dport", "53", "-j", "DNAT", "--to-destination", target, NULL };
    return run(argv);
}

static void restore_dns(state_t *state) {
    if (!state->dns_redirected) return;
    (void)dns_rules("-D", "udp", state->dns4);
    (void)dns_rules("-D", "tcp", state->dns4);
    state->dns_redirected = 0;
}

static void restore_state(void *ctx) {
    (void)ctx;
    restore_dns(&g_state);
    g_state.ifname[0] = '\0';
}

static void tick_state(void *ctx) {
    (void)ctx;
    if (!g_state.ifname[0]) return;
    if (if_nametoindex(g_state.ifname) == 0) restore_state(NULL);
}

static int open_tun(void *ctx, const rootd_req_t *req, char *ifname, size_t ifname_cap,
                    char *why, size_t why_cap) {
    (void)ctx;
    if (g_state.ifname[0] && if_nametoindex(g_state.ifname) != 0) {
        snprintf(why, why_cap, "a tunnel is already up on %s", g_state.ifname);
        return -1;
    }
    restore_state(NULL);

    int tun = open_tun_node();
    if (tun < 0) {
        fail(why, why_cap, "cannot open /dev/tun");
        return -1;
    }
    struct ifreq ifr;
    memset(&ifr, 0, sizeof ifr);
    snprintf(ifr.ifr_name, sizeof ifr.ifr_name, "%s%%d", TUN_PREFIX);
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    if (ioctl(tun, TUNSETIFF, &ifr) != 0) {
        fail(why, why_cap, "TUNSETIFF failed");
        close(tun);
        return -1;
    }
    snprintf(g_state.ifname, sizeof g_state.ifname, "%s", ifr.ifr_name);

    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) {
        fail(why, why_cap, "no control socket");
        close(tun);
        return -1;
    }
    char mask[16];
    prefix_mask(req->prefix4, mask, sizeof mask);
    struct ifreq cfg;
    memset(&cfg, 0, sizeof cfg);
    snprintf(cfg.ifr_name, sizeof cfg.ifr_name, "%s", g_state.ifname);
    set_addr(&cfg.ifr_addr, req->address4);
    int bad = ioctl(sock, SIOCSIFADDR, &cfg) != 0 ? 1 : 0;
    if (!bad) {
        set_addr(&cfg.ifr_netmask, mask);
        bad = ioctl(sock, SIOCSIFNETMASK, &cfg) != 0;
    }
    if (!bad) {
        cfg.ifr_mtu = (int)req->mtu;
        bad = ioctl(sock, SIOCSIFMTU, &cfg) != 0;
    }
    if (!bad) {
        bad = ioctl(sock, SIOCGIFFLAGS, &cfg) != 0;
        cfg.ifr_flags |= IFF_UP | IFF_RUNNING;
        if (!bad) bad = ioctl(sock, SIOCSIFFLAGS, &cfg) != 0;
    }
    if (bad) {
        fail(why, why_cap, "cannot configure the interface");
        goto undo;
    }
    /* two halves instead of a default route: the original default route stays, and protect
       sockets use it through their bound interface */
    if (add_route(sock, "0.0.0.0", "128.0.0.0", g_state.ifname) != 0 ||
        add_route(sock, "128.0.0.0", "128.0.0.0", g_state.ifname) != 0) {
        fail(why, why_cap, "cannot add the tunnel routes");
        goto undo;
    }
    close(sock);

    snprintf(g_state.dns4, sizeof g_state.dns4, "%s", req->dns4);
    if (dns_rules("-I", "udp", req->dns4) != 0 || dns_rules("-I", "tcp", req->dns4) != 0) {
        snprintf(why, why_cap, "cannot redirect dns: iptables nat is missing or refused");
        g_state.dns_redirected = 1;
        restore_dns(&g_state);
        g_state.ifname[0] = '\0';
        close(tun);
        return -1;
    }
    g_state.dns_redirected = 1;
    snprintf(ifname, ifname_cap, "%s", g_state.ifname);
    return tun;

undo:
    close(sock);
    close(tun);
    g_state.ifname[0] = '\0';
    return -1;
}

static int read_route_table(char *out, size_t cap) {
    int fd = open("/proc/net/route", O_RDONLY);
    if (fd < 0) return -1;
    size_t have = 0;
    for (;;) {
        ssize_t got = read(fd, out + have, cap - 1 - have);
        if (got < 0 && errno == EINTR) continue;
        if (got <= 0) break;
        have += (size_t)got;
        if (have + 1 >= cap) break;
    }
    close(fd);
    out[have] = '\0';
    return 0;
}

static int protect_socket(void *ctx, int fd, char *iface, size_t iface_cap, char *why, size_t why_cap) {
    (void)ctx;
    char table[ROUTE_TABLE_CAP];
    if (read_route_table(table, sizeof table) != 0) {
        fail(why, why_cap, "cannot read /proc/net/route");
        return -1;
    }
    if (rootd_default_iface(table, TUN_PREFIX, iface, iface_cap) != 0) {
        snprintf(why, why_cap, "no network has a default route");
        return -1;
    }
    if (setsockopt(fd, SOL_SOCKET, SO_BINDTODEVICE, iface, (socklen_t)strlen(iface) + 1) != 0) {
        fail(why, why_cap, "SO_BINDTODEVICE failed");
        return -1;
    }
    return 0;
}

void rootd_linux_ops(rootd_ops_t *ops) {
    memset(ops, 0, sizeof *ops);
    ops->open_tun = open_tun;
    ops->protect = protect_socket;
    ops->restore = restore_state;
    ops->tick = tick_state;
}
