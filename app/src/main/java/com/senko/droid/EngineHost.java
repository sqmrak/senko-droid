package com.senko.droid;

/**
 * what the engine asks of the vpn service. both methods are called from
 * threads the engine owns, and neither may block for long
 */
interface EngineHost {
    /** keeps the socket out of the tunnel, VpnService.protect */
    boolean protect(int fd);

    /**
     * opens the tun interface and returns its descriptor, detached so the
     * engine owns it. a negative value means failure, and tunError says why
     */
    int openTun(String address4, int prefix4, String address6, int prefix6, String dns4, int mtu);

    String tunError();
}
