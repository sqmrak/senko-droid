/* a stand-in for libsenkocore.so with the same entry points, so the engine's loader and lifecycle
   can be tested on the host without the go runtime. it records what it was asked and what it
   returns is steered through the environment of the test */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int g_running;
static int g_fd = -1;
static int (*g_protect)(int);
static char g_config[8192];
static int g_stops;

int SenkoCoreSetProtect(void *hook) {
    g_protect = (int (*)(int))hook;
    return getenv("FAKE_CORE_REFUSE_PROTECT") ? -1 : 0;
}

int SenkoCoreStart(const char *config, int length, int tun_fd, char *error, int cap) {
    if (getenv("FAKE_CORE_BAD_CONFIG")) {
        snprintf(error, (size_t)cap, "unknown outbound protocol");
        return -1;
    }
    if (getenv("FAKE_CORE_FAIL_START")) {
        snprintf(error, (size_t)cap, "tun inbound could not start");
        return -1;
    }
    if (g_running) {
        snprintf(error, (size_t)cap, "the core is already running");
        return -1;
    }
    snprintf(g_config, sizeof g_config, "%.*s", length < (int)sizeof g_config ? length : (int)sizeof g_config - 1, config);
    g_fd = tun_fd;
    g_running = 1;
    return 0;
}

int SenkoCoreStop(char *error, int cap) {
    (void)error;
    (void)cap;
    g_running = 0;
    ++g_stops;
    return 0;
}

int SenkoCoreRunning(void) { return g_running; }

int SenkoCoreStats(uint64_t *up, uint64_t *down) {
    if (!g_running) return -1;
    *up = 11;
    *down = 222;
    return 0;
}

static char g_assets[256];
void SenkoCoreSetAssets(const char *dir) { snprintf(g_assets, sizeof g_assets, "%s", dir); }
const char *FakeCoreAssets(void) { return g_assets; }

int SenkoCoreProbe(const char *config, int length, int timeout_ms, char *error, int cap) {
    (void)length;
    (void)timeout_ms;
    /* a probe configuration has no tun inbound, which is what keeps it off a running tunnel */
    if (strstr(config, "\"protocol\":\"tun\"")) {
        snprintf(error, (size_t)cap, "a probe must not carry a tun inbound");
        return -1;
    }
    if (getenv("FAKE_CORE_PROBE_FAIL")) {
        snprintf(error, (size_t)cap, "i/o timeout");
        return -1;
    }
    return 42;
}

const char *SenkoCoreVersion(void) { return "fake-1.0"; }

/* what the test reads back */
const char *FakeCoreConfig(void) { return g_config; }
int FakeCoreFd(void) { return g_fd; }
int FakeCoreStops(void) { return g_stops; }
int FakeCoreProtect(int fd) { return g_protect ? g_protect(fd) : -2; }
