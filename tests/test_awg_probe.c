#include "awg_probe.h"

#include "senko_paths.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int failures;

static void ok(const char *name, int condition) {
    if (condition) return;
    ++failures;
    fprintf(stderr, "FAIL %s\n", name);
}

static void write_file(const char *path, const char *text) {
    FILE *file = fopen(path, "w");
    if (!file) {
        perror("fopen");
        _exit(2);
    }
    fputs(text, file);
    fclose(file);
}

int main(void) {
    const char *root = "/tmp/senko-awg-probe-root";
    const char *dir = "/tmp/senko-awg-probe-root/awg";
    if (senko_paths_init(root) != SENKO_PATHS_OK) return 2;
    if ((mkdir(root, 0700) != 0 && errno != EEXIST) || (mkdir(dir, 0700) != 0 && errno != EEXIST)) {
        perror("mkdir");
        return 2;
    }
    char out[256];

    ok("no path", awg_probe_job(NULL, out, sizeof out) == 1 && strncmp(out, "error ", 6) == 0);
    ok("path outside the awg directory",
       awg_probe_job("/etc/passwd", out, sizeof out) == 1 &&
       strstr(out, "not a .conf file") != NULL);
    ok("traversal", awg_probe_job("/tmp/senko-awg-probe-root/awg/../x.conf", out, sizeof out) == 1);

    write_file("/tmp/senko-awg-probe-root/awg/bad.conf", "[Interface]\nPrivateKey = nothing\n");
    ok("rejected profile", awg_probe_job("/tmp/senko-awg-probe-root/awg/bad.conf", out,
                                         sizeof out) == 1 &&
                           strstr(out, "config rejected") != NULL);
    ok("missing profile", awg_probe_job("/tmp/senko-awg-probe-root/awg/none.conf", out,
                                        sizeof out) == 1 &&
                          strstr(out, "config rejected") != NULL);
    ok("output cap of one byte", awg_probe_job("/etc/passwd", out, 1) == 1 && out[0] == '\0');
    ok("no output buffer", awg_probe_job("/etc/passwd", NULL, 0) == 1);

    unlink("/tmp/senko-awg-probe-root/awg/bad.conf");
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("ok");
    return 0;
}
