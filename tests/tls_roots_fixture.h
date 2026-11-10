#ifndef SENKO_TLS_ROOTS_FIXTURE_H
#define SENKO_TLS_ROOTS_FIXTURE_H

#include "senko_paths.h"

#include <errno.h>
#include <stdio.h>
#include <sys/stat.h>

/* the engine loads its trust roots from the app directory. a verifying tls
   context needs the file to exist and hold a certificate; the sockets in these
   tests never reach a real server. this is a throwaway self signed root, its
   key was discarded */
static const char tls_roots_fixture_pem[] =
    "-----BEGIN CERTIFICATE-----\n"
    "MIIBizCCATGgAwIBAgIUd337WlYrHhaXW49ow2euCeuQj9EwCgYIKoZIzj0EAwIw\n"
    "GjEYMBYGA1UEAwwPc2Vua28gdGVzdCByb290MCAXDTI2MTAyNzAzMjEwNloYDzIx\n"
    "MjYxMDAzMDMyMTA2WjAaMRgwFgYDVQQDDA9zZW5rbyB0ZXN0IHJvb3QwWTATBgcq\n"
    "hkjOPQIBBggqhkjOPQMBBwNCAAS29T1q8trrO8CRV69C+K0l7WYMYE/ayG//qkqq\n"
    "NywVFXTjZc3hHYxgmjbbEscC4Y9/dENgLd1v0OSr0GEeEWcno1MwUTAdBgNVHQ4E\n"
    "FgQUHo2HYkROn2YyY2m7S0BawwuV0rEwHwYDVR0jBBgwFoAUHo2HYkROn2YyY2m7\n"
    "S0BawwuV0rEwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiBubtI2\n"
    "o6uEJb+tm3h2tRItqBM9kZxNq0rsQzUHijq9SwIhAL4EpbkAOH5169Q4U8+CZCMc\n"
    "RvotbmqRN4EjJKTnjs1u\n"
    "-----END CERTIFICATE-----\n";

static int tls_roots_fixture_install(const char *root) {
    char path[SENKO_PATHS_ROOT_MAX + 32];
    if (senko_paths_init(root) != SENKO_PATHS_OK) return -1;
    if (mkdir(root, 0700) != 0 && errno != EEXIST) return -1;
    if (senko_path_join(path, sizeof path, SENKO_REL_CA_BUNDLE) != SENKO_PATHS_OK) return -1;
    FILE *file = fopen(path, "w");
    if (!file) return -1;
    int ok = fputs(tls_roots_fixture_pem, file) >= 0;
    if (fclose(file) != 0) ok = 0;
    return ok ? 0 : -1;
}

#endif
