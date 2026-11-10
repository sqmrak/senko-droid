#ifndef SENKO_ROOTD_LINUX_H
#define SENKO_ROOTD_LINUX_H

#include "rootd_serve.h"

#ifdef __cplusplus
extern "C" {
#endif

/* the real operations: a tun device through /dev/tun, its address and routes through ioctl,
   the dns redirect through iptables, protect through SO_BINDTODEVICE. needs root */
void rootd_linux_ops(rootd_ops_t *ops);

#ifdef __cplusplus
}
#endif

#endif
