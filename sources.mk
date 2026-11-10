# the c sources of the engine, shared by the host tests and the android build.
# names are relative to core/ and engine/ and the including makefile adds the
# directory, so a file added here reaches both builds

SENKO_CORE_FILES = vless.c b64.c config.c share_link.c rules.c geo.c routing_profile.c \
    routing_sync.c geo_update.c dns_msg.c dns_cache.c profiles.c happ.c happ_crypt5.c incy.c \
    third_party/cJSON.c net_safe.c socks5.c senko_trace.c senko_replay.c senko_upload.c \
    senko_time.c real_probe.c net_diag.c senko_clock.c grpc_core.c h2_core.c vless_conn.c \
    session.c store.c control.c ctl_engine.c http.c url.c subfetch.c transport_tcp.c \
    transport_tls.c transport_ws.c transport_xhttp.c transport_pick.c transport_enc.c \
    vlenc_conf.c vlenc_conn.c blake3.c hpack.c \
    reality_crypto.c reality_auth.c tls_clienthello.c tls13_kdf.c tls13_keysched.c \
    tls13_record.c tls13_transcript.c tls13_handshake.c reality_handshake.c socks5_client.c \
    http_client.c vision.c awg_config.c awg_handshake.c awg_tunnel.c trojan_client.c \
    shadowsocks_client.c vmess_client.c blake2b256.c hy2_proto.c backend_state.c \
    endpoint_pool.c probe_stats.c tun_flow.c tun_nat.c utun_frame.c vless_udp.c \
    dns_policy.c tun_policy.c flow_split.c senko_paths.c net_protect.c errno_text.c \
    amnezia_bundle.c amnezia_qr.c fdpass.c senko_core_config.c

# vendored PQClean ml-kem-768 "clean" backend, plus its shared fips202.c and a
# local randombytes.c shim over senko's own openssl rng
SENKO_MLKEM_FILES = third_party/mlkem768/kem.c third_party/mlkem768/indcpa.c \
    third_party/mlkem768/poly.c third_party/mlkem768/polyvec.c third_party/mlkem768/ntt.c \
    third_party/mlkem768/cbd.c third_party/mlkem768/reduce.c third_party/mlkem768/verify.c \
    third_party/mlkem768/symmetric-shake.c third_party/mlkem768/fips202.c \
    third_party/mlkem768/randombytes.c

SENKO_ENGINE_FILES = engine_main.c daemon_ctl.c ctl_server.c helper_jobs.c awg_probe.c \
    awg_params.c awg_backend.c awg_link.c awg_split.c utun_backend.c utun_device.c \
    tun_loop.c tun_stack.c tun_tcp.c tun_udp.c tun_dialer.c tun_opener.c direct_socket.c \
    direct_dns.c hy2_engine.c settings.c storefile.c root_client.c xray_backend.c

# the userspace tcp/ip stack and senko's port of it
SENKO_LWIP_DIR = third_party/lwip/src
SENKO_LWIP_PORT_FILES = engine/lwip_port/lwip_sys.c
