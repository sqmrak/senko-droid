/* drives senko_rootd through the engine's own client: ping, a tun device, a protected socket.
   usage: root_probe <socket path> */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "engine/root_client.h"

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    char why[200] = "";
    printf("ping: %d %s\n", root_client_ping(argv[1], why, sizeof why), why);
    tun_params_t p;
    memset(&p, 0, sizeof p);
    snprintf(p.address4, sizeof p.address4, "198.18.0.1");
    p.prefix4 = 32;
    snprintf(p.dns4, sizeof p.dns4, "1.1.1.1");
    p.mtu = 1500;
    why[0] = 0;
    int tun = root_client_open_tun(argv[1], &p, why, sizeof why);
    printf("tun fd: %d %s\n", tun, why);
    int udp = socket(AF_INET, SOCK_DGRAM, 0);
    why[0] = 0;
    printf("protect: %d %s\n", root_client_protect(argv[1], udp, why, sizeof why), why);
    fflush(stdout);
    /* the interface exists for as long as this process holds the descriptor */
    if (argc > 2) sleep(atoi(argv[2]));
    return tun >= 0 ? 0 : 1;
}
