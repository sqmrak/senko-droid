#ifndef SENKO_AWG_PROBE_H
#define SENKO_AWG_PROBE_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* a handshake with the profile's server and nothing else: no tunnel device.
   it runs as a helper job, so the control loop never waits on the server.
   out gets "PING <ms>" or "error <why>", the lines the app reads. returns 0 on
   success, 1 on any failure */
int awg_probe_job(const char *path, char *out, size_t out_cap);

#ifdef __cplusplus
}
#endif

#endif
