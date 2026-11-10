#include "net_protect.h"

#include <errno.h>
#include <pthread.h>

static pthread_mutex_t protect_lock = PTHREAD_MUTEX_INITIALIZER;
static net_protect_fn protect_fn;
static void *protect_ctx;

void net_protect_install(net_protect_fn fn, void *ctx) {
    pthread_mutex_lock(&protect_lock);
    protect_fn = fn;
    protect_ctx = fn ? ctx : NULL;
    pthread_mutex_unlock(&protect_lock);
}

int net_protect_installed(void) {
    pthread_mutex_lock(&protect_lock);
    int installed = protect_fn != NULL;
    pthread_mutex_unlock(&protect_lock);
    return installed;
}

int net_protect_socket(int fd) {
    pthread_mutex_lock(&protect_lock);
    net_protect_fn fn = protect_fn;
    void *ctx = protect_ctx;
    pthread_mutex_unlock(&protect_lock);
    if (!fn) return 0;
    if (fd < 0 || fn(ctx, fd) != 0) {
        errno = EPERM;
        return -1;
    }
    return 0;
}
