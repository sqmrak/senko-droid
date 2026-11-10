#include "errno_text.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>

const char *senko_errno_name(int value) {
    /* compared by name so the armv7, arm64 and host builds each get their own
       platform's numbers right */
    switch (value) {
    case 0:             return "0";
    case EPERM:         return "EPERM";
    case ESRCH:         return "ESRCH";
    case EINTR:         return "EINTR";
    case EIO:           return "EIO";
    case EBADF:         return "EBADF";
    case EAGAIN:        return "EAGAIN";
    case EACCES:        return "EACCES";
    case EFAULT:        return "EFAULT";
    case EBUSY:         return "EBUSY";
    case EEXIST:        return "EEXIST";
    case EINVAL:        return "EINVAL";
    case EAFNOSUPPORT:  return "EAFNOSUPPORT";
    case EADDRINUSE:    return "EADDRINUSE";
    case ENETDOWN:      return "ENETDOWN";
    case ENETUNREACH:   return "ENETUNREACH";
    case ENOBUFS:       return "ENOBUFS";
    case EHOSTUNREACH:  return "EHOSTUNREACH";
    case ENOTSUP:       return "ENOTSUP";
    case ENOENT:        return "ENOENT";
    case ENXIO:         return "ENXIO";
    case EMFILE:        return "EMFILE";
    case ENFILE:        return "ENFILE";
    case ECONNREFUSED:  return "ECONNREFUSED";
    case ECONNRESET:    return "ECONNRESET";
    case ETIMEDOUT:     return "ETIMEDOUT";
    case EHOSTDOWN:     return "EHOSTDOWN";
    case EADDRNOTAVAIL: return "EADDRNOTAVAIL";
    default:            break;
    }
    return "";
}

void senko_errno_append(char *out, size_t cap, int value) {
    const char *name = senko_errno_name(value);
    size_t used = strlen(out);
    if (used >= cap) return;
    if (name[0])
        snprintf(out + used, cap - used, " (errno %d %s)", value, name);
    else
        snprintf(out + used, cap - used, " (errno %d)", value);
}
