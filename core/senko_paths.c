#include "senko_paths.h"

#include <stdio.h>
#include <string.h>

static char root_path[SENKO_PATHS_ROOT_MAX + 1];

static int has_dotdot(const char *path) {
    const char *p = path;
    while (*p) {
        const char *end = strchr(p, '/');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len == 2 && p[0] == '.' && p[1] == '.') return 1;
        if (!end) break;
        p = end + 1;
    }
    return 0;
}

senko_paths_status_t senko_paths_init(const char *root) {
    if (!root || root[0] != '/' || has_dotdot(root)) return SENKO_PATHS_ERR_ARG;
    size_t len = strlen(root);
    while (len > 1 && root[len - 1] == '/') --len;
    if (len <= 1) return SENKO_PATHS_ERR_ARG;
    if (len > SENKO_PATHS_ROOT_MAX) return SENKO_PATHS_ERR_LENGTH;
    memcpy(root_path, root, len);
    root_path[len] = '\0';
    return SENKO_PATHS_OK;
}

senko_paths_status_t senko_path_join(char *out, size_t cap, const char *rel) {
    if (out && cap) out[0] = '\0';
    if (!out || !cap || !rel || !rel[0] || rel[0] == '/' || has_dotdot(rel))
        return SENKO_PATHS_ERR_ARG;
    if (!root_path[0]) return SENKO_PATHS_ERR_UNSET;
    int n = snprintf(out, cap, "%s/%s", root_path, rel);
    if (n < 0 || (size_t)n >= cap) {
        out[0] = '\0';
        return SENKO_PATHS_ERR_LENGTH;
    }
    return SENKO_PATHS_OK;
}

int senko_path_inside(const char *path) {
    if (!path || !root_path[0] || has_dotdot(path)) return 0;
    size_t root_len = strlen(root_path);
    return strncmp(path, root_path, root_len) == 0 && path[root_len] == '/' &&
           path[root_len + 1] != '\0';
}
