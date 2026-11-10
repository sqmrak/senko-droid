#ifndef SENKO_ENGINE_MAIN_H
#define SENKO_ENGINE_MAIN_H

#include <stddef.h>

#include "tun_source.h"

#ifdef __cplusplus
extern "C" {
#endif

/* the whole engine on the calling thread: the control server, the stored
   catalog and the tunnels. the app starts it on a thread of its own and
   talks to it over the control socket. there is one engine per process */

typedef struct {
    const char  *files_dir;   /* the app directory, see core/senko_paths.h */
    const char  *socket_path; /* the control socket, inside files_dir */
    tun_source_t source;      /* how a tunnel gets its tun device */
    const char  *core_library; /* libsenkocore.so, NULL or empty when this build has no senko-core */
} engine_config_t;

typedef enum {
    ENGINE_OK            =  0,
    ENGINE_ERR_ARG       = -1,
    ENGINE_ERR_RUNNING   = -2, /* one is running already */
    ENGINE_ERR_PATHS     = -3, /* files_dir is unusable */
    ENGINE_ERR_SOCKET    = -4, /* the control socket could not be bound */
    ENGINE_ERR_SYSTEM    = -5
} engine_status_t;

/* returns once engine_stop was called, or at once with a reason in error */
engine_status_t engine_run(const engine_config_t *config, char *error, size_t error_cap);

/* asks the running engine to return. safe from any thread, and a no-op when
   no engine runs */
void engine_stop(void);

#ifdef __cplusplus
}
#endif

#endif
