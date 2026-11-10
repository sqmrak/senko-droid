#include "errno_text.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>

static int failures;

static void ok(const char *name, int condition) {
    if (condition) return;
    ++failures;
    fprintf(stderr, "FAIL %s\n", name);
}

int main(void) {
    ok("named code", strcmp(senko_errno_name(ECONNREFUSED), "ECONNREFUSED") == 0);
    ok("zero", strcmp(senko_errno_name(0), "0") == 0);
    ok("unnamed code", senko_errno_name(9999)[0] == '\0');

    char text[64] = "cannot connect";
    senko_errno_append(text, sizeof text, ETIMEDOUT);
    char want[64];
    snprintf(want, sizeof want, "cannot connect (errno %d ETIMEDOUT)", ETIMEDOUT);
    ok("named append", strcmp(text, want) == 0);

    snprintf(text, sizeof text, "failed");
    senko_errno_append(text, sizeof text, 9999);
    ok("unnamed append keeps the number", strcmp(text, "failed (errno 9999)") == 0);

    char tiny[12] = "cannot";
    senko_errno_append(tiny, sizeof tiny, EPERM);
    ok("append is cut, not overrun", strlen(tiny) < sizeof tiny && strncmp(tiny, "cannot", 6) == 0);

    char full[4] = "abc";
    senko_errno_append(full, sizeof full, EIO);
    ok("full buffer is untouched", strcmp(full, "abc") == 0);

    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("ok");
    return 0;
}
