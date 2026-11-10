#ifndef SENKO_PATHS_H
#define SENKO_PATHS_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* every file the engine reads or writes lives under one directory the app
   owns, which android only reveals at run time (/data/data/<package>/files,
   or /data/user/<id>/ for a secondary user). the app passes it once before it
   starts the engine. the names below are relative to it */

#define SENKO_REL_CA_BUNDLE   "cacert.pem"
#define SENKO_REL_STORE       "senko.cfg"
#define SENKO_REL_HWID        "hwid"
#define SENKO_REL_GEO_DIR     "geo"
#define SENKO_REL_AWG_DIR     "awg"
#define SENKO_REL_REPLAY_DIR  "replay"
#define SENKO_REL_UPLOAD_DIR  "upload"
#define SENKO_REL_IMPORT      "import.dat"
#define SENKO_REL_LOG         "senko.log"
#define SENKO_REL_BACKUP_EXPORT "backup.senko"
#define SENKO_REL_BACKUP_IMPORT "import.senko"
#define SENKO_PATHS_ROOT_MAX  200

typedef enum {
    SENKO_PATHS_OK         =  0,
    SENKO_PATHS_ERR_ARG    = -1, /* not an absolute path, or has a .. component */
    SENKO_PATHS_ERR_LENGTH = -2, /* root longer than SENKO_PATHS_ROOT_MAX, or no room in out */
    SENKO_PATHS_ERR_UNSET  = -3  /* senko_paths_init has not run */
} senko_paths_status_t;

/* root must be absolute and free of ".." components. a trailing slash is
   dropped. calling it again replaces the root, which only the tests do */
senko_paths_status_t senko_paths_init(const char *root);

/* "<root>/<rel>". rel must be relative and free of ".." components */
senko_paths_status_t senko_path_join(char *out, size_t cap, const char *rel);

/* 1 when path is "<root>/..." with no ".." component and something after the
   slash, so a caller that takes a path from outside can refuse the rest */
int senko_path_inside(const char *path);

#ifdef __cplusplus
}
#endif

#endif
