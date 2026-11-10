#ifndef SENKO_AWG_PARAMS_H
#define SENKO_AWG_PARAMS_H

#include <stddef.h>

#include "core/awg_config.h"
#include "tun_source.h"

#ifdef __cplusplus
extern "C" {
#endif

/* the interface a profile asks for: its addresses, mtu and the first ipv4 dns
   it names, or fallback_dns when it names none. -1 with a sentence in why when
   the profile has no ipv4 address or the dns is not numeric */
int awg_tun_params(const awg_config_t *cfg, const char *fallback_dns,
                   tun_params_t *params, char *why, size_t why_cap);

#ifdef __cplusplus
}
#endif

#endif
