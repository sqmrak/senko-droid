#include "rootd_proto.h"

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MTU_MIN 576u
#define MTU_MAX 9000u
#define RTF_UP_BIT 0x0001u

static int split_words(char *line, char **words, size_t max) {
    size_t count = 0;
    char *cursor = line;
    while (*cursor) {
        while (*cursor == ' ') ++cursor;
        if (!*cursor) break;
        if (count == max) return -1;
        words[count++] = cursor;
        while (*cursor && *cursor != ' ') ++cursor;
        if (*cursor) *cursor++ = '\0';
    }
    return (int)count;
}

static int parse_uint(const char *text, unsigned long *out) {
    if (!*text) return -1;
    for (const char *p = text; *p; ++p)
        if (!isdigit((unsigned char)*p)) return -1;
    errno = 0;
    char *end = NULL;
    unsigned long value = strtoul(text, &end, 10);
    if (errno != 0 || *end != '\0') return -1;
    *out = value;
    return 0;
}

static int copy_ipv4(const char *text, char *out, size_t cap) {
    struct in_addr addr;
    if (strlen(text) >= cap || inet_pton(AF_INET, text, &addr) != 1) return -1;
    memcpy(out, text, strlen(text) + 1);
    return 0;
}

rootd_parse_t rootd_parse_request(const char *line, rootd_req_t *out) {
    char copy[ROOTD_LINE_MAX];
    char *words[6];
    memset(out, 0, sizeof *out);
    if (!line) return ROOTD_PARSE_EMPTY;
    size_t len = strlen(line);
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) --len;
    if (len == 0) return ROOTD_PARSE_EMPTY;
    if (len >= sizeof copy) return ROOTD_PARSE_MALFORMED;
    memcpy(copy, line, len);
    copy[len] = '\0';
    for (size_t i = 0; i < len; ++i)
        if ((unsigned char)copy[i] < 0x20 || (unsigned char)copy[i] > 0x7e) return ROOTD_PARSE_MALFORMED;

    int count = split_words(copy, words, 6);
    if (count < 1) return ROOTD_PARSE_MALFORMED;
    rootd_req_kind_t bare = 0;
    if (strcmp(words[0], "PING") == 0) bare = ROOTD_REQ_PING;
    else if (strcmp(words[0], "QUIT") == 0) bare = ROOTD_REQ_QUIT;
    else if (strcmp(words[0], "PROTECT") == 0) bare = ROOTD_REQ_PROTECT;
    if (bare) {
        if (count != 1) return ROOTD_PARSE_MALFORMED;
        out->kind = bare;
        return ROOTD_PARSE_OK;
    }
    if (strcmp(words[0], "TUN") != 0) return ROOTD_PARSE_UNKNOWN;
    if (count != 5) return ROOTD_PARSE_MALFORMED;
    unsigned long prefix, mtu;
    if (copy_ipv4(words[1], out->address4, sizeof out->address4) != 0 ||
        parse_uint(words[2], &prefix) != 0 || parse_uint(words[3], &mtu) != 0 ||
        copy_ipv4(words[4], out->dns4, sizeof out->dns4) != 0) {
        memset(out, 0, sizeof *out);
        return ROOTD_PARSE_MALFORMED;
    }
    if (prefix < 1 || prefix > 32 || mtu < MTU_MIN || mtu > MTU_MAX) {
        memset(out, 0, sizeof *out);
        return ROOTD_PARSE_RANGE;
    }
    out->kind = ROOTD_REQ_TUN;
    out->prefix4 = (unsigned)prefix;
    out->mtu = (unsigned)mtu;
    return ROOTD_PARSE_OK;
}

int rootd_default_iface(const char *route_table, const char *skip_prefix, char *out, size_t cap) {
    if (!route_table || !out || cap == 0) return -1;
    out[0] = '\0';
    size_t skip_len = skip_prefix ? strlen(skip_prefix) : 0;
    unsigned long best_metric = 0;
    int found = 0;
    const char *cursor = route_table;
    while (*cursor) {
        const char *eol = strchr(cursor, '\n');
        size_t line_len = eol ? (size_t)(eol - cursor) : strlen(cursor);
        char line[256];
        if (line_len < sizeof line) {
            memcpy(line, cursor, line_len);
            line[line_len] = '\0';
            char iface[ROOTD_IFNAME_MAX + 1];
            unsigned long dest, gateway, flags, refcnt, use, metric, mask;
            /* the header line starts with "Iface" and fails the numeric fields */
            if (sscanf(line, "%16s %lx %lx %lx %lu %lu %lu %lx", iface, &dest, &gateway, &flags,
                       &refcnt, &use, &metric, &mask) == 8 &&
                dest == 0 && mask == 0 && (flags & RTF_UP_BIT) &&
                !(skip_len && strncmp(iface, skip_prefix, skip_len) == 0) &&
                strlen(iface) < cap && (!found || metric < best_metric)) {
                memcpy(out, iface, strlen(iface) + 1);
                best_metric = metric;
                found = 1;
            }
        }
        if (!eol) break;
        cursor = eol + 1;
    }
    return found ? 0 : -1;
}
