#include "senko_paths.h"

#include <stdio.h>
#include <string.h>

static int failures;

static void ok(const char *name, int condition) {
    if (condition) return;
    ++failures;
    fprintf(stderr, "FAIL %s\n", name);
}

int main(void) {
    char out[64];

    ok("unset root", senko_path_join(out, sizeof out, "x") == SENKO_PATHS_ERR_UNSET);
    ok("unset root is not inside", !senko_path_inside("/data/x/y"));

    ok("relative root", senko_paths_init("data/files") == SENKO_PATHS_ERR_ARG);
    ok("null root", senko_paths_init(NULL) == SENKO_PATHS_ERR_ARG);
    ok("slash root", senko_paths_init("/") == SENKO_PATHS_ERR_ARG);
    ok("dotdot root", senko_paths_init("/data/../files") == SENKO_PATHS_ERR_ARG);
    char long_root[SENKO_PATHS_ROOT_MAX + 8];
    memset(long_root, 'a', sizeof long_root);
    long_root[0] = '/';
    long_root[sizeof long_root - 1] = '\0';
    ok("long root", senko_paths_init(long_root) == SENKO_PATHS_ERR_LENGTH);

    ok("init", senko_paths_init("/data/data/app/files//") == SENKO_PATHS_OK);
    ok("join", senko_path_join(out, sizeof out, SENKO_REL_CA_BUNDLE) == SENKO_PATHS_OK &&
               strcmp(out, "/data/data/app/files/cacert.pem") == 0);
    ok("join nested", senko_path_join(out, sizeof out, "awg/a.conf") == SENKO_PATHS_OK &&
                      strcmp(out, "/data/data/app/files/awg/a.conf") == 0);
    ok("join absolute", senko_path_join(out, sizeof out, "/etc/passwd") == SENKO_PATHS_ERR_ARG);
    ok("join dotdot", senko_path_join(out, sizeof out, "a/../b") == SENKO_PATHS_ERR_ARG);
    ok("join empty", senko_path_join(out, sizeof out, "") == SENKO_PATHS_ERR_ARG);
    ok("join keeps dotted names", senko_path_join(out, sizeof out, "a..b") == SENKO_PATHS_OK);
    char tiny[16];
    ok("join truncation", senko_path_join(tiny, sizeof tiny, "geo") == SENKO_PATHS_ERR_LENGTH &&
                          tiny[0] == '\0');
    ok("join without room", senko_path_join(out, 0, "geo") == SENKO_PATHS_ERR_ARG);

    ok("inside", senko_path_inside("/data/data/app/files/senko.cfg"));
    ok("inside nested", senko_path_inside("/data/data/app/files/awg/a.conf"));
    ok("the root itself is not inside", !senko_path_inside("/data/data/app/files"));
    ok("root with slash only", !senko_path_inside("/data/data/app/files/"));
    ok("sibling with the same prefix", !senko_path_inside("/data/data/app/files2/x"));
    ok("other tree", !senko_path_inside("/sdcard/x"));
    ok("dotdot escape", !senko_path_inside("/data/data/app/files/../../x"));
    ok("null path", !senko_path_inside(NULL));

    ok("replace root", senko_paths_init("/tmp/t") == SENKO_PATHS_OK);
    ok("old root no longer inside", !senko_path_inside("/data/data/app/files/x"));

    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("ok");
    return 0;
}
