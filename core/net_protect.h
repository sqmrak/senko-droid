#ifndef SENKO_NET_PROTECT_H
#define SENKO_NET_PROTECT_H

#ifdef __cplusplus
extern "C" {
#endif

/* every socket the engine opens toward a server or a direct destination goes
   through net_protect_socket before connect or the first send. on android the
   hook is VpnService.protect(), which keeps the socket out of the tunnel: the
   tun route covers every destination, so an unprotected socket would carry
   its own packets back into the tunnel and loop.

   the hook is installed while no engine thread runs and removed after they
   have been joined, so a socket never meets a half installed hook. a build
   without a hook (host tests) has no tunnel to avoid, and protect succeeds */

typedef int (*net_protect_fn)(void *ctx, int fd);

/* fn NULL removes the hook */
void net_protect_install(net_protect_fn fn, void *ctx);

/* 1 when a hook is installed */
int net_protect_installed(void);

/* 0 when the socket is out of the tunnel's reach, -1 with errno EPERM when the
   hook refused it. the caller closes the descriptor on failure */
int net_protect_socket(int fd);

#ifdef __cplusplus
}
#endif

#endif
