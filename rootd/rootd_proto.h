#ifndef SENKO_ROOTD_PROTO_H
#define SENKO_ROOTD_PROTO_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* the wire protocol of senko_rootd, the root helper that android 2.3 to 3.x needs because
   those versions have no VpnService. one request per connection, one line each way:

     TUN <address4> <prefix> <mtu> <dns4>\n   reply "OK <ifname>\n" with the tun descriptor attached
     PROTECT\n                                with a socket descriptor attached; reply "OK <iface>\n"
     PING\n                                   reply "OK\n"
     QUIT\n                                   reply "OK\n", then the helper restores and exits

   a failure replies "ERR <reason>\n". nothing in a request reaches a shell */

#define ROOTD_LINE_MAX 256
#define ROOTD_IFNAME_MAX 16

typedef enum {
    ROOTD_REQ_TUN = 1,
    ROOTD_REQ_PROTECT,
    ROOTD_REQ_PING,
    ROOTD_REQ_QUIT
} rootd_req_kind_t;

typedef struct {
    rootd_req_kind_t kind;
    char             address4[16];
    unsigned         prefix4;
    unsigned         mtu;
    char             dns4[16];
} rootd_req_t;

typedef enum {
    ROOTD_PARSE_OK        =  0,
    ROOTD_PARSE_EMPTY     = -1,
    ROOTD_PARSE_UNKNOWN   = -2, /* a verb the helper does not have */
    ROOTD_PARSE_MALFORMED = -3, /* wrong word count or a field that is not what it should be */
    ROOTD_PARSE_RANGE     = -4  /* a number outside what a tunnel can use */
} rootd_parse_t;

/* line may end with a newline. out is fully initialised on every return */
rootd_parse_t rootd_parse_request(const char *line, rootd_req_t *out);

/* the interface that carries the default route, read from the text of /proc/net/route.
   interfaces whose name starts with skip_prefix (the tunnel itself) are passed over, and
   the lowest metric wins. 0 and the name in out, or -1 when no route qualifies */
int rootd_default_iface(const char *route_table, const char *skip_prefix, char *out, size_t cap);

#ifdef __cplusplus
}
#endif

#endif
