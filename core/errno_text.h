#ifndef SENKO_ERRNO_TEXT_H
#define SENKO_ERRNO_TEXT_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* the name of an errno, e.g. 17 -> "EEXIST", or "" for codes without a
   name here */
const char *senko_errno_name(int value);

/* appends " (errno 17 EEXIST)", or " (errno 99)" without a name, so a
   message never loses the number */
void senko_errno_append(char *out, size_t cap, int value);

#ifdef __cplusplus
}
#endif

#endif
