#define _DEFAULT_SOURCE

#include "engine_main.h"

#include "core/senko_paths.h"
#include "core/store.h"
#include "ctl_server.h"
#include "daemon_ctl.h"
#include "settings.h"
#include "storefile.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <openssl/crypto.h>

/* the log is kept small: the app shows its tail, and a phone has no logrotate */
#define ENGINE_LOG_MAX (256 * 1024)

/* a store is larger than a small thread stack, so the engine's state is static.
   there is one engine per process, which engine_run enforces */
static daemon_ctl_t g_ctl;
static ctl_server_t g_server;
static store_t g_preload;

static int g_saved_stderr = -1;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_running;
static int g_stop_pipe[2] = { -1, -1 };

static void set_error(char *error, size_t cap, const char *text) {
    if (error && cap) snprintf(error, cap, "%s", text);
}

static int set_nonblock_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) != 0) return -1;
    return fcntl(fd, F_SETFD, FD_CLOEXEC);
}

/* app processes have no terminal, so stderr goes to a file the app can read
   until the engine stops */
static void open_log(void) {
    char path[SENKO_PATHS_ROOT_MAX + 32];
    if (senko_path_join(path, sizeof path, SENKO_REL_LOG) != SENKO_PATHS_OK) return;
    struct stat st;
    if (stat(path, &st) == 0 && st.st_size > ENGINE_LOG_MAX) {
        char old[SENKO_PATHS_ROOT_MAX + 40];
        snprintf(old, sizeof old, "%s.1", path);
        (void)rename(path, old);
    }
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) return;
    int saved = dup(STDERR_FILENO);
    if (dup2(fd, STDERR_FILENO) >= 0) g_saved_stderr = saved;
    else if (saved >= 0) close(saved);
    close(fd);
}

static void close_log(void) {
    if (g_saved_stderr < 0) return;
    fflush(stderr);
    (void)dup2(g_saved_stderr, STDERR_FILENO);
    close(g_saved_stderr);
    g_saved_stderr = -1;
}

static int make_dir(const char *rel) {
    char path[SENKO_PATHS_ROOT_MAX + 32];
    if (senko_path_join(path, sizeof path, rel) != SENKO_PATHS_OK) return -1;
    if (mkdir(path, 0700) != 0 && errno != EEXIST) return -1;
    return 0;
}

engine_status_t engine_run(const engine_config_t *config, char *error, size_t error_cap) {
    if (error && error_cap) error[0] = '\0';
    if (!config || !config->files_dir || !config->socket_path ||
        !config->source.open) {
        set_error(error, error_cap, "the engine configuration is incomplete");
        return ENGINE_ERR_ARG;
    }
    pthread_mutex_lock(&g_lock);
    if (g_running) {
        pthread_mutex_unlock(&g_lock);
        set_error(error, error_cap, "the engine is already running");
        return ENGINE_ERR_RUNNING;
    }
    if (pipe(g_stop_pipe) != 0 || set_nonblock_cloexec(g_stop_pipe[0]) != 0 ||
        set_nonblock_cloexec(g_stop_pipe[1]) != 0) {
        if (g_stop_pipe[0] >= 0) close(g_stop_pipe[0]);
        if (g_stop_pipe[1] >= 0) close(g_stop_pipe[1]);
        g_stop_pipe[0] = g_stop_pipe[1] = -1;
        pthread_mutex_unlock(&g_lock);
        set_error(error, error_cap, "cannot create the stop pipe");
        return ENGINE_ERR_SYSTEM;
    }
    g_running = 1;
    pthread_mutex_unlock(&g_lock);

    engine_status_t status = ENGINE_ERR_PATHS;
    char geo_dir[SENKO_PATHS_ROOT_MAX + 32];
    char config_path[SENKO_PATHS_ROOT_MAX + 32];

    if (senko_paths_init(config->files_dir) != SENKO_PATHS_OK ||
        make_dir(SENKO_REL_GEO_DIR) != 0 || make_dir(SENKO_REL_AWG_DIR) != 0 ||
        senko_path_join(geo_dir, sizeof geo_dir, SENKO_REL_GEO_DIR) != SENKO_PATHS_OK ||
        senko_path_join(config_path, sizeof config_path, SENKO_REL_STORE) != SENKO_PATHS_OK ||
        !senko_path_inside(config->socket_path)) {
        set_error(error, error_cap, "the app directory is unusable");
        goto out;
    }
    open_log();

    /* before the first openssl call: a teardown at process exit must not run
       into state this engine already freed */
    OPENSSL_init_crypto(OPENSSL_INIT_NO_ATEXIT, NULL);
    signal(SIGPIPE, SIG_IGN);
    fprintf(stderr, "senko: starting, pid %d\n", (int)getpid());

    /* before the catalog is read: a saved profile only senko-core carries is runnable only if it loads */
    cfg_set_core_available(config->core_library && config->core_library[0] && access(config->core_library, R_OK) == 0);

    daemon_settings_t settings;
    daemon_settings_defaults(&settings);
    store_init(&g_preload);
    storefile_status_t loaded = storefile_load(&g_preload, &settings, config_path);
    if (loaded != STOREFILE_OK)
        fprintf(stderr, "senko: saved configuration could not be read (rc=%d)\n", (int)loaded);

    daemon_ctl_init(&g_ctl, config_path, &config->source);
    if (config->core_library && config->core_library[0]) daemon_ctl_set_core_library(&g_ctl, config->core_library);
    daemon_ctl_set_settings(&g_ctl, &settings);

    status = ENGINE_ERR_SOCKET;
    if (ctl_server_init(&g_server, config->socket_path, daemon_ctl_apply, &g_ctl) != CTLS_OK) {
        set_error(error, error_cap, "the control socket could not be bound");
        daemon_ctl_shutdown(&g_ctl);
        goto out;
    }
    ctl_server_set_persist(&g_server, daemon_ctl_persist);
    ctl_server_set_fetch(&g_server, daemon_ctl_fetch);
    ctl_server_set_probe(&g_server, daemon_ctl_probe);
    ctl_server_set_server_probe(&g_server, daemon_ctl_probe_server);
    ctl_server_set_tunnel_probe(&g_server, daemon_ctl_ping_tunnel);
    ctl_server_set_backup(&g_server, daemon_ctl_backup);
    ctl_server_set_check(&g_server, daemon_ctl_check);
    ctl_server_set_reason(&g_server, daemon_ctl_last_reason);
/* one live copy of the settings from here on: the engine writes it, the control
   server reads it for the SETTINGS dump and for the schedules it runs */
    ctl_server_set_settings(&g_server, &g_ctl.settings);
    ctl_server_set_diag(&g_server, daemon_ctl_diag);
    ctl_server_set_flush(&g_server, daemon_ctl_flush);
    ctl_server_set_awg(&g_server, daemon_ctl_awg, daemon_ctl_awg_busy);
    ctl_server_set_stats(&g_server, daemon_ctl_stats);
    daemon_ctl_set_rules(&g_ctl, &g_server.engine.store.rules);
    daemon_ctl_set_store(&g_ctl, &g_server.engine.store, geo_dir);
    ctl_server_set_geo(&g_server, daemon_ctl_geo_fetch, geo_dir);

    if (loaded == STOREFILE_OK) {
        /* the settings were merged above, and reading the file again here
           would only repeat the work */
        if (storefile_load(&g_server.engine.store, NULL, config_path) == STOREFILE_OK)
            fprintf(stderr, "senko: loaded %zu server(s) from %s\n",
                    g_server.engine.store.n, config_path);
    }
    fprintf(stderr, "senko: engine ready, control at %s\n", config->socket_path);

    /* a start after a reboot has no client to ask for the tunnel, so the
       stored selection is what brings it back */
    if (g_ctl.settings.auto_connect) {
        if (ctl_server_restore_tunnel(&g_server) == 0)
            fprintf(stderr, "senko: auto-connected to server %d\n",
                    g_server.engine.store.selected);
        else
            fprintf(stderr, "senko: auto-connect found no server to start\n");
    }

/* one poll for every owner on this thread, waiting only as long as the nearest
   deadline any of them has */
    int stopping = 0;
    while (!stopping) {
        struct pollfd pfd[2 + CTL_SERVER_POLL_MAX];
        int timeout_ms = -1;
        size_t n = 0;
        pfd[n].fd = g_stop_pipe[0];
        pfd[n].events = POLLIN;
        pfd[n].revents = 0;
        n++;
        int ended_fd = daemon_ctl_wait_fd(&g_ctl);
        if (ended_fd >= 0) {
            pfd[n].fd = ended_fd;
            pfd[n].events = POLLIN;
            pfd[n].revents = 0;
            n++;
        }
        size_t ctl_at = n;
        size_t ctl_n = ctl_server_prepare(&g_server, pfd + ctl_at, CTL_SERVER_POLL_MAX,
                                          &timeout_ms);
        if (ctl_n == 0) {
            fprintf(stderr, "senko: the control server has nothing to poll\n");
            break;
        }
        int r = poll(pfd, (nfds_t)(ctl_at + ctl_n), timeout_ms);
        if (r < 0 && errno != EINTR) {
            fprintf(stderr, "senko: poll failed (errno %d)\n", errno);
            break;
        }
        if (r > 0 && (pfd[0].revents & POLLIN)) stopping = 1;
        /* helper jobs time out from dispatch, so it runs on a timeout too */
        if (r >= 0) ctl_server_dispatch(&g_server, pfd + ctl_at, ctl_n);
/* the tunnel can die between two control commands, and the redial schedule
   lives with the store that knows which server to dial */
        if (daemon_ctl_maintain(&g_ctl) != 0)
            ctl_server_tunnel_lost(&g_server);
        ctl_server_tick(&g_server);
    }

    fprintf(stderr, "senko: shutting down\n");
    daemon_ctl_shutdown(&g_ctl);
    storefile_save(&g_server.engine.store, &g_ctl.settings, config_path);
    ctl_server_close(&g_server);
    status = ENGINE_OK;

out:
    close_log();
    pthread_mutex_lock(&g_lock);
    close(g_stop_pipe[0]);
    close(g_stop_pipe[1]);
    g_stop_pipe[0] = g_stop_pipe[1] = -1;
    g_running = 0;
    pthread_mutex_unlock(&g_lock);
    return status;
}

void engine_stop(void) {
    pthread_mutex_lock(&g_lock);
    if (g_running && g_stop_pipe[1] >= 0) {
        ssize_t wrote;
        do {
            wrote = write(g_stop_pipe[1], "s", 1);
        } while (wrote < 0 && errno == EINTR);
        /* a full pipe already holds the wakeup */
    }
    pthread_mutex_unlock(&g_lock);
}
