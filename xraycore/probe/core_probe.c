/* loads libsenkocore.so the way the engine does and exercises what needs no tun device: the go
   runtime starting inside a dlopen'ed library, the version string, and config validation.
   usage: core_probe <path to libsenkocore.so> [config.json] */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef const char *(*version_fn)(void);
typedef int (*validate_fn)(const char *, int, char *, int);
typedef int (*running_fn)(void);

static int protect_stub(int fd) {
    (void)fd;
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: core_probe <libsenkocore.so> [config.json]\n");
        return 2;
    }
    void *lib = dlopen(argv[1], RTLD_NOW);
    if (!lib) {
        printf("FAIL dlopen: %s\n", dlerror());
        return 1;
    }
    version_fn version = (version_fn)dlsym(lib, "SenkoCoreVersion");
    validate_fn validate = (validate_fn)dlsym(lib, "SenkoCoreValidate");
    running_fn running = (running_fn)dlsym(lib, "SenkoCoreRunning");
    int (*set_protect)(void *) = (int (*)(void *))dlsym(lib, "SenkoCoreSetProtect");
    if (!version || !validate || !running || !set_protect) {
        printf("FAIL dlsym: %s\n", dlerror());
        return 1;
    }
    printf("OK loaded, core %s, running=%d\n", version(), running());
    printf("OK set_protect=%d\n", set_protect((void *)protect_stub));
    if (argc > 2) {
        FILE *f = fopen(argv[2], "rb");
        if (!f) {
            printf("FAIL cannot read %s\n", argv[2]);
            return 1;
        }
        static char config[1 << 16];
        int n = (int)fread(config, 1, sizeof config - 1, f);
        fclose(f);
        char error[512] = "";
        int rc = validate(config, n, error, sizeof error);
        printf("%s validate rc=%d %s\n", rc == 0 ? "OK" : "FAIL", rc, error);
        if (rc != 0) return 1;
    }
    /* a request through the configuration's outbound, timed by the core */
    if (argc > 3 && strcmp(argv[3], "probe") == 0) {
        int (*probe)(const char *, int, int, char *, int) = (int (*)(const char *, int, int, char *, int))dlsym(lib, "SenkoCoreProbe");
        if (!probe) { printf("FAIL no SenkoCoreProbe\n"); return 1; }
        FILE *f = fopen(argv[2], "rb");
        static char config[1 << 16];
        int n = f ? (int)fread(config, 1, sizeof config - 1, f) : 0;
        if (f) fclose(f);
        char error[512] = "";
        int ms = probe(config, n, 8000, error, sizeof error);
        printf("%s probe=%d %s\n", ms >= 0 ? "OK" : "FAIL", ms, error);
        return ms >= 0 ? 0 : 1;
    }
    return 0;
}
