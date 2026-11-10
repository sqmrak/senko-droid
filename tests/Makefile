# build platform-free host tests with plain clang
# keep the aggregate list explicit so missing tests fail at build time
CC      ?= cc
CFLAGS  ?= -std=c99 -Wall -Wextra -O2 -I.. -I../core -I../core/third_party
THREAD_CFLAGS  ?= -pthread
THREAD_LDFLAGS ?= -pthread
CORE     = ../core
DAEMON   = ../engine
# reality_crypto.c's mlkem768 wrappers need these wherever it is linked
MLKEM768_SRC = $(CORE)/third_party/mlkem768/kem.c $(CORE)/third_party/mlkem768/indcpa.c \
               $(CORE)/third_party/mlkem768/poly.c $(CORE)/third_party/mlkem768/polyvec.c \
               $(CORE)/third_party/mlkem768/ntt.c $(CORE)/third_party/mlkem768/cbd.c \
               $(CORE)/third_party/mlkem768/reduce.c $(CORE)/third_party/mlkem768/verify.c \
               $(CORE)/third_party/mlkem768/symmetric-shake.c $(CORE)/third_party/mlkem768/fips202.c \
               $(CORE)/third_party/mlkem768/randombytes.c
# the vendored stack and senko's port of it, see daemon/third_party/lwip/VENDOR
LWIP      = ../third_party/lwip/src
LWIP_PORT = ../engine/lwip_port
LWIP_CFLAGS = -I$(LWIP_PORT) -I$(LWIP)/include
LWIP_SRC  = $(wildcard $(LWIP)/core/*.c) $(wildcard $(LWIP)/core/ipv4/*.c) \
            $(wildcard $(LWIP)/core/ipv6/*.c) $(LWIP_PORT)/lwip_sys.c
TUN_SESSION_SRC = $(CORE)/session.c $(CORE)/socks5.c $(NET_SAFE) \
                  $(CORE)/vless.c $(CORE)/vless_conn.c $(CORE)/socks5_client.c \
                  $(CORE)/http_client.c $(CORE)/vision.c $(CORE)/trojan_client.c \
                  $(CORE)/hy2_proto.c $(CORE)/blake2b256.c \
                  $(CORE)/shadowsocks_client.c $(CORE)/vmess_client.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) \
                  $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) $(CORE)/senko_paths.c
TRACE    = $(CORE)/senko_trace.c
# the utun rule, dns cache and direct socket modules the udp and tcp relays share
TUN_POLICY_SRC = $(CORE)/tun_policy.c $(CORE)/dns_policy.c $(CORE)/dns_cache.c \
                 $(DAEMON)/direct_socket.c $(CORE)/errno_text.c
REPLAY   = $(CORE)/senko_replay.c
UPLOAD   = $(CORE)/senko_upload.c
TIME     = $(CORE)/senko_time.c
NET_SAFE = $(CORE)/net_safe.c
CJSON    = $(CORE)/third_party/cJSON.c
CFG_SRC  = $(CORE)/vlenc_conf.c $(CORE)/incy.c $(CORE)/config.c $(CORE)/share_link.c $(CORE)/rules.c $(CORE)/geo.c $(CORE)/routing_profile.c $(CORE)/geo_update.c $(CORE)/happ.c $(CORE)/happ_crypt5.c $(CORE)/b64.c $(CORE)/profiles.c $(CJSON)
# the vless encryption transport and its record layer; reality_crypto.c, the
# ml-kem sources and senko_time.c come with every target that links a transport
ENC_SRC  = $(CORE)/transport_enc.c $(CORE)/vlenc_conn.c $(CORE)/blake3.c
TLS_CFLAGS  := $(shell pkg-config --cflags openssl 2>/dev/null)
TLS_LDFLAGS := $(shell pkg-config --libs openssl 2>/dev/null || echo -lssl -lcrypto)

.PHONY: all test clean sanitizers fuzz fuzz-smoke host-jni

HOST_TESTS =   test_session_direct test_config_compat \
             test_transport_ws test_store_contract test_storefile \
             test_reality_crypto test_tls13_kdf \
             test_tls13_keysched test_tls13_record test_tls_clienthello test_vision \
             test_transport_pick test_awg_config test_awg_handshake test_awg_tunnel  \
             test_xray_json test_url_headers test_grpc_core test_transport_grpc test_hpack \
   test_net_safe  \
             test_subinfo test_profiles test_amnezia_bundle test_amnezia_qr test_happ_links \
             test_b64 test_traffic test_rules
HOST_TESTS += test_dns_msg
HOST_TESTS += test_rootd
HOST_TESTS += test_config_core
HOST_TESTS += test_xray_backend
HOST_TESTS += test_senko_core_config
HOST_TESTS += test_dns_cache
HOST_TESTS += test_trojan test_trojan_combos test_trojan_exchange
HOST_TESTS += test_blake3 test_vlenc_conn test_transport_enc test_chacha_poly
HOST_TESTS += test_shadowsocks
HOST_TESTS += test_vmess
HOST_TESTS += test_subfetch_budget
HOST_TESTS += test_blake2b256
HOST_TESTS += test_hy2_proto
HOST_TESTS += test_session_hy2
HOST_TESTS += test_endpoint_pool
HOST_TESTS += test_utun_framing
HOST_TESTS += test_utun_device_raw
HOST_TESTS += test_net_protect
HOST_TESTS += test_senko_paths
HOST_TESTS += test_awg_params
HOST_TESTS += test_errno_text
HOST_TESTS += test_helper_jobs
HOST_TESTS += test_awg_probe
HOST_TESTS += test_ctl_server
HOST_TESTS += test_engine
HOST_TESTS += test_autoconnect
HOST_TESTS += test_tun_flow
HOST_TESTS += test_backend_state
HOST_TESTS += test_handshake_latency
HOST_TESTS += test_failover_health
HOST_TESTS += test_lwip_smoke
HOST_TESTS += test_tun_stack
HOST_TESTS += test_tun_nat
HOST_TESTS += test_tun_loop
HOST_TESTS += test_tun_tcp
HOST_TESTS += test_session_reset
HOST_TESTS += test_transport_tls_shared
HOST_TESTS += test_tun_dialer
HOST_TESTS += test_vless_udp
HOST_TESTS += test_tun_udp
HOST_TESTS += test_transport_tls_eof
HOST_TESTS += test_dns_policy
HOST_TESTS += test_tun_policy
HOST_TESTS += test_tun_udp_policy
HOST_TESTS += test_tun_udp_wait
HOST_TESTS += test_awg_link
HOST_TESTS += test_flow_split
HOST_TESTS += test_awg_split
HOST_TESTS += test_sub_links
HOST_TESTS += test_net_diag test_senko_clock
HOST_TESTS += test_real_probe
HOST_TESTS += test_direct_dns
HOST_TESTS += test_geo test_routing_profile test_geo_update test_routing_sync  

all: test

.PHONY: test-native
test-native:
	bash ../scripts/test_native_core.sh

test: $(HOST_TESTS)
	./test_blake2b256
	./test_rootd
	./test_config_core
	./test_xray_backend ./libfake_core.so
	./test_senko_core_config
	./test_hy2_proto
	./test_session_hy2
	./test_net_diag
	./test_senko_clock
	./test_real_probe
	./test_trojan
	./test_trojan_combos
	./test_trojan_exchange
	./test_blake3
	./test_vlenc_conn
	./test_transport_enc
	./test_chacha_poly
	./test_shadowsocks
	./test_vmess
	./test_traffic
	./test_rules
	./test_geo
	./test_routing_profile
	./test_geo_update
	./test_routing_sync
	./test_dns_msg
	./test_direct_dns
	./test_dns_cache
	./test_session_direct
	./test_config_compat
	./test_transport_ws
	./test_store_contract
	./test_storefile
	./test_reality_crypto
	./test_tls13_kdf
	./test_tls13_keysched
	./test_tls13_record
	./test_tls_clienthello
	./test_vision
	./test_transport_pick
	./test_awg_config
	./test_awg_handshake
	./test_awg_tunnel
	./test_xray_json
	./test_b64
	./test_url_headers
	./test_grpc_core
	./test_transport_grpc
	./test_hpack
	./test_net_safe
	./test_subinfo
	./test_subfetch_budget
	./test_profiles
	./test_happ_links
	./test_amnezia_bundle
	./test_amnezia_qr
	./test_endpoint_pool
	./test_utun_framing
	./test_tun_flow
	./test_backend_state
	./test_handshake_latency
	./test_failover_health
	./test_lwip_smoke
	./test_tun_stack
	./test_tun_nat
	./test_tun_loop
	./test_tun_tcp
	./test_session_reset
	./test_transport_tls_shared
	./test_tun_dialer
	./test_vless_udp
	./test_tun_udp
	./test_transport_tls_eof
	./test_dns_policy
	./test_tun_policy
	./test_tun_udp_policy
	./test_tun_udp_wait
	./test_awg_link
	./test_flow_split
	./test_awg_split
	./test_sub_links

test_tun_udp: test_tun_udp.c $(CORE)/net_protect.c packet_fixture.h $(DAEMON)/tun_udp.c \
              $(DAEMON)/tun_opener.c $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c \
              $(DAEMON)/tun_stack.c $(CORE)/tun_nat.c $(CORE)/utun_frame.c \
              $(CORE)/vless_udp.c $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c $(TUN_POLICY_SRC) \
              $(TUN_SESSION_SRC) $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(LWIP_SRC)
	$(CC) $(CFLAGS) $(LWIP_CFLAGS) $(THREAD_CFLAGS) -DSENKO_RELEASE -I$(DAEMON) \
	      test_tun_udp.c $(CORE)/net_protect.c $(DAEMON)/tun_udp.c $(DAEMON)/tun_opener.c \
	      $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c $(DAEMON)/tun_stack.c \
	      $(CORE)/tun_nat.c $(CORE)/utun_frame.c $(CORE)/vless_udp.c \
	      $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c $(TUN_POLICY_SRC) $(TUN_SESSION_SRC) \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(LWIP_SRC) -o $@ \
	      $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_tun_udp_policy: test_tun_udp_policy.c $(CORE)/net_protect.c packet_fixture.h $(DAEMON)/tun_udp.c \
              $(DAEMON)/tun_opener.c $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c \
              $(DAEMON)/tun_stack.c $(CORE)/tun_nat.c $(CORE)/utun_frame.c \
              $(CORE)/vless_udp.c $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c $(TUN_POLICY_SRC) \
              $(TUN_SESSION_SRC) $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(LWIP_SRC)
	$(CC) $(CFLAGS) $(LWIP_CFLAGS) $(THREAD_CFLAGS) -DSENKO_RELEASE -I$(DAEMON) \
	      test_tun_udp_policy.c $(CORE)/net_protect.c $(DAEMON)/tun_udp.c $(DAEMON)/tun_opener.c \
	      $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c $(DAEMON)/tun_stack.c \
	      $(CORE)/tun_nat.c $(CORE)/utun_frame.c $(CORE)/vless_udp.c \
	      $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c $(TUN_POLICY_SRC) $(TUN_SESSION_SRC) \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(LWIP_SRC) -o $@ \
	      $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_tun_udp_wait: test_tun_udp_wait.c $(CORE)/net_protect.c $(DAEMON)/tun_udp.c \
              $(DAEMON)/tun_opener.c $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c \
              $(DAEMON)/tun_stack.c $(CORE)/tun_nat.c $(CORE)/utun_frame.c \
              $(CORE)/vless_udp.c $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c $(TUN_POLICY_SRC) \
              $(TUN_SESSION_SRC) $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(LWIP_SRC)
	$(CC) $(CFLAGS) $(LWIP_CFLAGS) $(THREAD_CFLAGS) -DSENKO_RELEASE -I$(DAEMON) \
	      test_tun_udp_wait.c $(CORE)/net_protect.c $(DAEMON)/tun_opener.c \
	      $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c $(DAEMON)/tun_stack.c \
	      $(CORE)/tun_nat.c $(CORE)/utun_frame.c $(CORE)/vless_udp.c \
	      $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c $(TUN_POLICY_SRC) $(TUN_SESSION_SRC) \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(LWIP_SRC) -o $@ \
	      $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_vless_udp: test_vless_udp.c $(CORE)/vless_udp.c $(CORE)/vless_udp.h \
                $(CORE)/vless.c $(CORE)/vless.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_vless_udp.c $(CORE)/vless_udp.c $(CORE)/vless.c -o $@ $(TLS_LDFLAGS)

test_traffic: test_traffic.c $(CORE)/control.c $(CORE)/traffic.h
	$(CC) $(CFLAGS) test_traffic.c $(CORE)/control.c -o $@

test_routing_profile: test_routing_profile.c $(CORE)/routing_profile.c $(CORE)/routing_profile.h $(CFG_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_routing_profile.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_geo_update: test_geo_update.c $(CORE)/geo_update.c $(CORE)/geo_update.h $(CORE)/geo.c $(CFG_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_geo_update.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_routing_sync: test_routing_sync.c $(CORE)/routing_sync.c $(CORE)/routing_sync.h $(CORE)/store.c $(CFG_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_routing_sync.c $(CORE)/routing_sync.c $(CORE)/store.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_geo: test_geo.c $(CORE)/geo.c $(CORE)/geo.h
	$(CC) $(CFLAGS) test_geo.c $(CORE)/geo.c -o $@

test_rules: test_rules.c $(CORE)/rules.c $(CORE)/geo.c $(CORE)/rules.h
	$(CC) $(CFLAGS) test_rules.c $(CORE)/rules.c $(CORE)/geo.c -o $@

test_dns_msg: test_dns_msg.c $(CORE)/dns_msg.c $(CORE)/dns_msg.h
	$(CC) $(CFLAGS) test_dns_msg.c $(CORE)/dns_msg.c -o $@

test_direct_dns: test_direct_dns.c $(DAEMON)/direct_dns.c $(DAEMON)/direct_socket.c $(CORE)/net_protect.c $(CORE)/dns_msg.c $(NET_SAFE)
	$(CC) $(CFLAGS) $(THREAD_CFLAGS) -I$(DAEMON) test_direct_dns.c \
	      $(DAEMON)/direct_dns.c $(DAEMON)/direct_socket.c $(CORE)/net_protect.c \
	      $(CORE)/dns_msg.c $(NET_SAFE) -o $@ $(THREAD_LDFLAGS)

test_dns_cache: test_dns_cache.c $(CORE)/dns_cache.c $(CORE)/dns_cache.h \
                $(CORE)/dns_msg.c $(CORE)/dns_msg.h $(CORE)/rules.h
	$(CC) $(CFLAGS) test_dns_cache.c $(CORE)/dns_cache.c $(CORE)/dns_msg.c -o $@

test_url_headers: test_url_headers.c $(CORE)/senko_paths.c $(CORE)/url.c $(CORE)/net_safe.c $(CORE)/url.h
	$(CC) $(CFLAGS) test_url_headers.c $(CORE)/senko_paths.c $(CORE)/url.c $(CORE)/net_safe.c -o $@

test_net_safe: test_net_safe.c $(NET_SAFE) $(CORE)/net_safe.h
	$(CC) $(CFLAGS) test_net_safe.c $(NET_SAFE) -o $@

test_subinfo: test_subinfo.c $(CORE)/senko_paths.c $(CORE)/subfetch.c $(CORE)/subfetch.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) -DSENKO_HOST_TEST -I$(DAEMON) test_subinfo.c $(CORE)/senko_paths.c \
	      $(CORE)/subfetch.c $(CORE)/http.c $(CORE)/url.c $(CORE)/net_safe.c \
	      $(CORE)/b64.c $(TIME) -o $@ $(TLS_LDFLAGS) -lz

test_subfetch_budget: test_subfetch_budget.c $(CORE)/senko_paths.c $(CORE)/subfetch.c $(CORE)/subfetch.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) -DSENKO_HOST_TEST -I$(DAEMON) test_subfetch_budget.c $(CORE)/senko_paths.c \
	      $(CORE)/subfetch.c $(CORE)/http.c $(CORE)/url.c $(CORE)/net_safe.c \
	      $(CORE)/b64.c $(CORE)/transport_tcp.c $(CORE)/transport_tls.c \
	      $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) -o $@ $(TLS_LDFLAGS) -lz -lpthread

test_grpc_core: test_grpc_core.c $(CORE)/grpc_core.c $(CORE)/grpc_core.h
	$(CC) $(CFLAGS) test_grpc_core.c $(CORE)/grpc_core.c -o $@

test_awg_config: test_awg_config.c $(CORE)/senko_paths.c $(CORE)/awg_config.c $(CORE)/awg_config.h $(CORE)/b64.c $(CORE)/b64.h
	$(CC) $(CFLAGS) test_awg_config.c $(CORE)/senko_paths.c $(CORE)/awg_config.c $(CORE)/b64.c -o $@ -lcrypto

test_amnezia_bundle: test_amnezia_bundle.c $(CORE)/senko_paths.c amnezia_fixture.inc $(CORE)/amnezia_bundle.c \
                     $(CORE)/amnezia_bundle.h $(CORE)/awg_config.c $(CORE)/b64.c $(CJSON)
	$(CC) $(CFLAGS) test_amnezia_bundle.c $(CORE)/senko_paths.c $(CORE)/amnezia_bundle.c $(CORE)/awg_config.c \
	  $(CORE)/b64.c $(CJSON) -o $@ -lz -lcrypto

test_amnezia_qr: test_amnezia_qr.c $(CORE)/senko_paths.c amnezia_fixture.inc $(CORE)/amnezia_qr.c $(CORE)/amnezia_qr.h \
                 $(CORE)/amnezia_bundle.c $(CORE)/awg_config.c $(CORE)/b64.c $(CJSON)
	$(CC) $(CFLAGS) test_amnezia_qr.c $(CORE)/senko_paths.c $(CORE)/amnezia_qr.c $(CORE)/amnezia_bundle.c \
	  $(CORE)/awg_config.c $(CORE)/b64.c $(CJSON) -o $@ -lz -lcrypto

test_awg_handshake: test_awg_handshake.c $(CORE)/senko_paths.c $(CORE)/net_protect.c awg_response_fixture.inc $(CORE)/awg_handshake.c $(CORE)/awg_handshake.h \
                    $(CORE)/awg_config.c $(CORE)/b64.c $(CORE)/reality_crypto.c $(MLKEM768_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_awg_handshake.c $(CORE)/senko_paths.c $(CORE)/net_protect.c $(CORE)/awg_handshake.c \
	      $(CORE)/awg_config.c $(CORE)/b64.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME) -o $@ $(TLS_LDFLAGS)

test_awg_link: test_awg_link.c $(CORE)/senko_paths.c $(CORE)/net_protect.c $(DAEMON)/awg_link.c $(DAEMON)/awg_link.h $(CORE)/awg_tunnel.c \
               $(CORE)/awg_handshake.c $(DAEMON)/utun_device.c $(CORE)/utun_frame.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) $(THREAD_CFLAGS) -I$(DAEMON) test_awg_link.c $(CORE)/senko_paths.c $(CORE)/net_protect.c $(DAEMON)/awg_link.c \
	      $(DAEMON)/utun_device.c $(CORE)/utun_frame.c $(CORE)/awg_tunnel.c $(CORE)/awg_handshake.c \
	      $(CORE)/awg_config.c $(CORE)/b64.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME) \
	      -o $@ $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_flow_split: test_flow_split.c packet_fixture.h $(CORE)/flow_split.c $(CORE)/flow_split.h
	$(CC) $(CFLAGS) -I. test_flow_split.c $(CORE)/flow_split.c -o $@

AWG_SPLIT_SRC = $(DAEMON)/awg_split.c $(DAEMON)/awg_link.c $(DAEMON)/tun_tcp.c $(DAEMON)/tun_udp.c \
                $(DAEMON)/tun_opener.c $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c \
                $(DAEMON)/tun_stack.c $(CORE)/tun_nat.c $(CORE)/utun_frame.c $(CORE)/vless_udp.c \
                $(CORE)/flow_split.c $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c \
                $(CORE)/awg_tunnel.c $(CORE)/awg_handshake.c $(CORE)/awg_config.c $(CORE)/b64.c \
                $(TUN_POLICY_SRC) $(TUN_SESSION_SRC) $(CORE)/transport_tcp.c $(CORE)/transport_tls.c

test_awg_split: test_awg_split.c $(CORE)/net_protect.c packet_fixture.h $(AWG_SPLIT_SRC) $(DAEMON)/awg_split.h $(LWIP_SRC)
	$(CC) $(CFLAGS) $(LWIP_CFLAGS) $(TLS_CFLAGS) $(THREAD_CFLAGS) -DSENKO_RELEASE -I$(DAEMON) -I. \
	      test_awg_split.c $(CORE)/net_protect.c $(AWG_SPLIT_SRC) $(LWIP_SRC) -o $@ $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_awg_tunnel: test_awg_tunnel.c $(CORE)/senko_paths.c $(CORE)/net_protect.c $(CORE)/awg_tunnel.c $(CORE)/awg_tunnel.h \
                 $(CORE)/awg_handshake.c $(CORE)/awg_config.c $(CORE)/b64.c $(CORE)/reality_crypto.c $(MLKEM768_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_awg_tunnel.c $(CORE)/senko_paths.c $(CORE)/net_protect.c $(CORE)/awg_tunnel.c \
	      $(CORE)/awg_handshake.c $(CORE)/awg_config.c $(CORE)/b64.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) \
	      $(TIME) -o $@ $(TLS_LDFLAGS)

test_config_compat: test_config_compat.c $(CFG_SRC) $(CORE)/config.h $(CORE)/b64.h $(CORE)/happ.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_config_compat.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_sub_links: test_sub_links.c $(CFG_SRC) $(CORE)/vless.c $(CORE)/store.c $(CORE)/config.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) -I$(CORE) test_sub_links.c $(CFG_SRC) $(CORE)/vless.c $(CORE)/store.c \
	      -o $@ $(TLS_LDFLAGS)

libfake_core.so: fake_core.c
	$(CC) -std=c99 -Wall -Wextra -O2 -fPIC -shared fake_core.c -o $@

test_xray_backend: test_xray_backend.c libfake_core.so ../engine/xray_backend.c $(CORE)/senko_core_config.c $(CORE)/net_protect.c $(CORE)/rules.c $(CORE)/geo.c $(CORE)/config.c $(CORE)/share_link.c $(CJSON) $(CORE)/b64.c $(CORE)/happ.c $(CORE)/happ_crypt5.c $(CORE)/routing_profile.c $(CORE)/geo_update.c $(CORE)/profiles.c
	$(CC) $(CFLAGS) -D_GNU_SOURCE $(TLS_CFLAGS) -I../engine test_xray_backend.c ../engine/xray_backend.c $(CORE)/senko_core_config.c $(CORE)/net_protect.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS) -ldl

test_config_core: test_config_core.c $(CORE)/senko_core_config.c $(CFG_SRC) $(CORE)/store.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_config_core.c $(CORE)/senko_core_config.c $(CORE)/store.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_senko_core_config: test_senko_core_config.c $(CORE)/senko_core_config.c $(CORE)/config.c $(CORE)/share_link.c $(CORE)/rules.c $(CORE)/geo.c $(CJSON) $(CORE)/b64.c $(CORE)/happ.c $(CORE)/happ_crypt5.c $(CORE)/routing_profile.c $(CORE)/geo_update.c $(CORE)/profiles.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_senko_core_config.c $(CORE)/senko_core_config.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

ROOTD_SRC = ../core/fdpass.c ../rootd/rootd_proto.c ../rootd/rootd_serve.c ../engine/root_client.c
test_rootd: test_rootd.c $(ROOTD_SRC)
	$(CC) $(CFLAGS) $(THREAD_CFLAGS) -I../engine test_rootd.c $(ROOTD_SRC) -o $@ $(THREAD_LDFLAGS)

test_xray_json: test_xray_json.c $(CFG_SRC) $(CORE)/config.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_xray_json.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_b64: test_b64.c $(CORE)/b64.c $(CORE)/b64.h
	$(CC) $(CFLAGS) test_b64.c $(CORE)/b64.c -o $@

test_blake2b256: test_blake2b256.c $(CORE)/blake2b256.c $(CORE)/blake2b256.h
	$(CC) $(CFLAGS) test_blake2b256.c $(CORE)/blake2b256.c -o $@

test_hy2_proto: test_hy2_proto.c $(CORE)/hy2_proto.c $(CORE)/hy2_proto.h $(CORE)/blake2b256.c
	$(CC) $(CFLAGS) test_hy2_proto.c $(CORE)/hy2_proto.c $(CORE)/blake2b256.c -o $@

test_session_hy2: test_session_hy2.c fake_transport.c $(TUN_SESSION_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) -I. test_session_hy2.c fake_transport.c $(TUN_SESSION_SRC) -o $@ $(TLS_LDFLAGS)

test_endpoint_pool: test_endpoint_pool.c $(CORE)/endpoint_pool.c $(CORE)/endpoint_pool.h
	$(CC) $(CFLAGS) test_endpoint_pool.c $(CORE)/endpoint_pool.c -o $@

test_utun_framing: test_utun_framing.c $(CORE)/utun_frame.c $(CORE)/utun_frame.h
	$(CC) $(CFLAGS) test_utun_framing.c $(CORE)/utun_frame.c -o $@

test_utun_device_raw: test_utun_device_raw.c $(DAEMON)/utun_device.c $(DAEMON)/utun_device.h \
                     $(CORE)/utun_frame.c $(CORE)/utun_frame.h
	$(CC) $(CFLAGS) -I$(DAEMON) test_utun_device_raw.c $(DAEMON)/utun_device.c $(CORE)/utun_frame.c -o $@

test_ctl_server: test_ctl_server.c $(DAEMON)/ctl_server.c $(DAEMON)/helper_jobs.c $(DAEMON)/awg_probe.c $(DAEMON)/settings.c \
        $(CORE)/ctl_engine.c $(CORE)/control.c $(CORE)/store.c $(CORE)/routing_sync.c $(CORE)/url.c \
        $(CORE)/senko_paths.c $(CORE)/net_protect.c $(CORE)/errno_text.c $(CORE)/awg_handshake.c \
        $(CORE)/awg_config.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME) \
        $(NET_SAFE) $(CFG_SRC) $(CORE)/dns_msg.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) $(THREAD_CFLAGS) -I$(DAEMON) test_ctl_server.c \
	      $(DAEMON)/ctl_server.c $(DAEMON)/helper_jobs.c $(DAEMON)/awg_probe.c $(DAEMON)/settings.c \
	      $(CORE)/ctl_engine.c $(CORE)/control.c $(CORE)/store.c $(CORE)/routing_sync.c $(CORE)/url.c \
	      $(CORE)/senko_paths.c $(CORE)/net_protect.c $(CORE)/errno_text.c $(CORE)/awg_handshake.c \
	      $(CORE)/awg_config.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME) \
	      $(NET_SAFE) $(CFG_SRC) $(CORE)/dns_msg.c \
	      -o $@ $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_autoconnect: test_autoconnect.c $(DAEMON)/ctl_server.c $(DAEMON)/helper_jobs.c $(DAEMON)/awg_probe.c $(DAEMON)/settings.c \
        $(CORE)/ctl_engine.c $(CORE)/control.c $(CORE)/store.c $(CORE)/routing_sync.c $(CORE)/url.c \
        $(CORE)/senko_paths.c $(CORE)/net_protect.c $(CORE)/errno_text.c $(CORE)/awg_handshake.c \
        $(CORE)/awg_config.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME) \
        $(NET_SAFE) $(CFG_SRC) $(CORE)/dns_msg.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) $(THREAD_CFLAGS) -I$(DAEMON) test_autoconnect.c \
	      $(DAEMON)/ctl_server.c $(DAEMON)/helper_jobs.c $(DAEMON)/awg_probe.c $(DAEMON)/settings.c \
	      $(CORE)/ctl_engine.c $(CORE)/control.c $(CORE)/store.c $(CORE)/routing_sync.c $(CORE)/url.c \
	      $(CORE)/senko_paths.c $(CORE)/net_protect.c $(CORE)/errno_text.c $(CORE)/awg_handshake.c \
	      $(CORE)/awg_config.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME) \
	      $(NET_SAFE) $(CFG_SRC) $(CORE)/dns_msg.c \
	      -o $@ $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

# the hysteria2 client needs ngtcp2 with its openssl backend, and nghttp3
QUIC_PKGS    = libngtcp2 libngtcp2_crypto_ossl libnghttp3
QUIC_CFLAGS := $(shell pkg-config --cflags $(QUIC_PKGS) 2>/dev/null)
QUIC_LDFLAGS := $(shell pkg-config --libs $(QUIC_PKGS) 2>/dev/null || echo -lngtcp2 -lngtcp2_crypto_ossl -lnghttp3)

include ../sources.mk
ENGINE_CORE_SRC = $(addprefix $(CORE)/,$(SENKO_CORE_FILES) $(SENKO_MLKEM_FILES))
ENGINE_SRC = $(addprefix $(DAEMON)/,$(SENKO_ENGINE_FILES)) $(LWIP_SRC)

# the engine and its jni layer as a host library, for the jvm test that drives
# the engine through the same entry points the android app uses
HOST_JNI_DIR ?= ../build/host
host-jni: $(HOST_JNI_DIR)/libsenko.so

$(HOST_JNI_DIR)/libsenko.so: ../jni/senko_jni.c $(ENGINE_SRC) $(ENGINE_CORE_SRC)
	mkdir -p $(HOST_JNI_DIR)
	$(CC) $(CFLAGS) -fPIC -shared $(LWIP_CFLAGS) $(THREAD_CFLAGS) $(TLS_CFLAGS) $(QUIC_CFLAGS) -I$(DAEMON) \
	      -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/linux ../jni/senko_jni.c \
	      $(ENGINE_SRC) $(ENGINE_CORE_SRC) -o $@ $(QUIC_LDFLAGS) $(TLS_LDFLAGS) -lz $(THREAD_LDFLAGS)

test_engine: test_engine.c $(ENGINE_SRC) $(ENGINE_CORE_SRC)
	$(CC) $(CFLAGS) -DSENKO_HOST_TEST $(LWIP_CFLAGS) $(THREAD_CFLAGS) $(TLS_CFLAGS) $(QUIC_CFLAGS) -I$(DAEMON) \
	      test_engine.c $(ENGINE_SRC) $(ENGINE_CORE_SRC) -o $@ \
	      $(QUIC_LDFLAGS) $(TLS_LDFLAGS) -lz $(THREAD_LDFLAGS)

test_awg_probe: test_awg_probe.c $(DAEMON)/awg_probe.c $(DAEMON)/awg_probe.h $(CORE)/senko_paths.c \
                $(CORE)/net_protect.c $(CORE)/awg_handshake.c $(CORE)/awg_config.c $(CORE)/b64.c \
                $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME)
	$(CC) $(CFLAGS) -I$(DAEMON) $(TLS_CFLAGS) test_awg_probe.c $(DAEMON)/awg_probe.c \
	      $(CORE)/senko_paths.c $(CORE)/net_protect.c $(CORE)/awg_handshake.c $(CORE)/awg_config.c \
	      $(CORE)/b64.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME) -o $@ $(TLS_LDFLAGS)

test_helper_jobs: test_helper_jobs.c $(DAEMON)/helper_jobs.c $(DAEMON)/helper_jobs.h $(CORE)/errno_text.c
	$(CC) $(CFLAGS) $(THREAD_CFLAGS) -I$(DAEMON) test_helper_jobs.c $(DAEMON)/helper_jobs.c $(CORE)/errno_text.c -o $@ $(THREAD_LDFLAGS)

test_errno_text: test_errno_text.c $(CORE)/errno_text.c $(CORE)/errno_text.h
	$(CC) $(CFLAGS) test_errno_text.c $(CORE)/errno_text.c -o $@

test_awg_params: test_awg_params.c $(DAEMON)/awg_params.c $(DAEMON)/awg_params.h $(CORE)/awg_config.c $(CORE)/senko_paths.c $(CORE)/b64.c
	$(CC) $(CFLAGS) -I$(DAEMON) test_awg_params.c $(DAEMON)/awg_params.c $(CORE)/awg_config.c $(CORE)/senko_paths.c $(CORE)/b64.c -o $@ -lcrypto

test_senko_paths: test_senko_paths.c $(CORE)/senko_paths.c $(CORE)/senko_paths.h
	$(CC) $(CFLAGS) test_senko_paths.c $(CORE)/senko_paths.c -o $@

test_net_protect: test_net_protect.c $(CORE)/net_protect.c $(CORE)/net_protect.h
	$(CC) $(CFLAGS) $(THREAD_CFLAGS) test_net_protect.c $(CORE)/net_protect.c -o $@ $(THREAD_LDFLAGS)

test_lwip_smoke: test_lwip_smoke.c $(LWIP_SRC) $(LWIP_PORT)/lwipopts.h
	$(CC) $(CFLAGS) $(LWIP_CFLAGS) -I$(DAEMON) test_lwip_smoke.c $(LWIP_SRC) \
	      $(TIME) -o $@

test_tun_stack: test_tun_stack.c packet_fixture.h $(DAEMON)/tun_stack.c $(DAEMON)/tun_stack.h \
                $(CORE)/tun_nat.c $(CORE)/tun_nat.h $(CORE)/utun_frame.c \
                $(LWIP_SRC) $(LWIP_PORT)/lwipopts.h
	$(CC) $(CFLAGS) $(LWIP_CFLAGS) $(THREAD_CFLAGS) -I$(DAEMON) test_tun_stack.c \
	      $(DAEMON)/tun_stack.c $(CORE)/tun_nat.c $(CORE)/utun_frame.c $(LWIP_SRC) $(TIME) \
	      -o $@ $(THREAD_LDFLAGS)

test_tun_nat: test_tun_nat.c packet_fixture.h $(CORE)/tun_nat.c $(CORE)/tun_nat.h
	$(CC) $(CFLAGS) test_tun_nat.c $(CORE)/tun_nat.c -o $@

test_tun_loop: test_tun_loop.c packet_fixture.h $(DAEMON)/tun_loop.c $(DAEMON)/tun_loop.h \
               $(DAEMON)/utun_device.c $(DAEMON)/tun_stack.c $(CORE)/tun_nat.c \
               $(CORE)/utun_frame.c $(LWIP_SRC) $(LWIP_PORT)/lwipopts.h
	$(CC) $(CFLAGS) $(LWIP_CFLAGS) $(THREAD_CFLAGS) -I$(DAEMON) test_tun_loop.c \
	      $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c $(DAEMON)/tun_stack.c \
	      $(CORE)/tun_nat.c $(CORE)/utun_frame.c $(LWIP_SRC) $(TIME) \
	      -o $@ $(THREAD_LDFLAGS)

test_tun_tcp: test_tun_tcp.c $(CORE)/net_protect.c packet_fixture.h $(DAEMON)/tun_tcp.c \
              $(DAEMON)/tun_opener.c $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c \
              $(DAEMON)/tun_stack.c $(CORE)/tun_nat.c $(CORE)/utun_frame.c \
              $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c $(TUN_POLICY_SRC) $(TUN_SESSION_SRC) $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(LWIP_SRC)
	$(CC) $(CFLAGS) $(LWIP_CFLAGS) $(THREAD_CFLAGS) -DSENKO_RELEASE -I$(DAEMON) \
	      test_tun_tcp.c $(CORE)/net_protect.c $(DAEMON)/tun_tcp.c $(DAEMON)/tun_opener.c \
	      $(DAEMON)/tun_loop.c $(DAEMON)/utun_device.c $(DAEMON)/tun_stack.c \
	      $(CORE)/tun_nat.c $(CORE)/utun_frame.c $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c \
	      $(TUN_POLICY_SRC) $(TUN_SESSION_SRC) \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(LWIP_SRC) -o $@ \
	      $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_session_reset: test_session_reset.c $(TUN_SESSION_SRC) $(CORE)/transport_tcp.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) -DSENKO_RELEASE test_session_reset.c \
	      $(TUN_SESSION_SRC) $(CORE)/transport_tcp.c -o $@ $(TLS_LDFLAGS)

test_tun_policy: test_tun_policy.c $(CORE)/tun_policy.c $(CORE)/dns_policy.c \
                 $(CORE)/dns_cache.c $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c
	$(CC) $(CFLAGS) test_tun_policy.c $(CORE)/tun_policy.c $(CORE)/dns_policy.c \
	      $(CORE)/dns_cache.c $(CORE)/dns_msg.c $(CORE)/rules.c $(CORE)/geo.c -o $@

test_dns_policy: test_dns_policy.c $(CORE)/dns_policy.c $(CORE)/dns_policy.h
	$(CC) $(CFLAGS) test_dns_policy.c $(CORE)/dns_policy.c -o $@

test_transport_tls_eof: test_transport_tls_eof.c $(CORE)/senko_paths.c $(CORE)/transport_tls.c
	$(CC) $(CFLAGS) $(THREAD_CFLAGS) $(TLS_CFLAGS) test_transport_tls_eof.c $(CORE)/senko_paths.c \
	      $(CORE)/transport_tls.c -o $@ $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_transport_tls_shared: test_transport_tls_shared.c $(CORE)/senko_paths.c $(CORE)/transport_tls.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_transport_tls_shared.c $(CORE)/senko_paths.c \
	      $(CORE)/transport_tls.c -o $@ $(TLS_LDFLAGS)

test_tun_dialer: test_tun_dialer.c $(CORE)/net_protect.c $(DAEMON)/tun_dialer.c $(CORE)/endpoint_pool.c $(TIME) \
                 $(CORE)/errno_text.c
	$(CC) $(CFLAGS) $(THREAD_CFLAGS) -I$(DAEMON) test_tun_dialer.c $(CORE)/net_protect.c \
	      $(DAEMON)/tun_dialer.c $(CORE)/endpoint_pool.c $(TIME) \
	      $(CORE)/errno_text.c \
	      -o $@ $(THREAD_LDFLAGS)

test_tun_flow: test_tun_flow.c $(CORE)/tun_flow.c $(CORE)/tun_flow.h $(CORE)/rules.c $(CORE)/geo.c
	$(CC) $(CFLAGS) test_tun_flow.c $(CORE)/tun_flow.c $(CORE)/rules.c $(CORE)/geo.c -o $@

test_backend_state: test_backend_state.c $(CORE)/backend_state.c $(CORE)/backend_state.h
	$(CC) $(CFLAGS) test_backend_state.c $(CORE)/backend_state.c -o $@

test_handshake_latency: test_handshake_latency.c $(CORE)/probe_stats.c $(CORE)/probe_stats.h
	$(CC) $(CFLAGS) test_handshake_latency.c $(CORE)/probe_stats.c -o $@

test_failover_health: test_failover_health.c $(CORE)/probe_stats.c $(CORE)/probe_stats.h
	$(CC) $(CFLAGS) test_failover_health.c $(CORE)/probe_stats.c -o $@

sub_audit: sub_audit.c $(CFG_SRC) $(CORE)/config.h $(CORE)/b64.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) sub_audit.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_transport_ws: test_transport_ws.c $(CORE)/senko_paths.c $(CORE)/transport_ws.c $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(CORE)/transport.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_transport_ws.c $(CORE)/senko_paths.c $(CORE)/transport_ws.c \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c -o $@ $(TLS_LDFLAGS)

test_store_contract: test_store_contract.c $(CORE)/store.c $(CFG_SRC) $(CORE)/store.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_store_contract.c $(CORE)/store.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_storefile: test_storefile.c $(CORE)/senko_paths.c $(DAEMON)/storefile.c $(DAEMON)/settings.c \
                $(CORE)/store.c $(CFG_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) -I$(DAEMON) test_storefile.c $(CORE)/senko_paths.c $(DAEMON)/storefile.c \
	      $(DAEMON)/settings.c $(CORE)/store.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_session_direct: test_session_direct.c $(CORE)/senko_paths.c vmess_fixture.inc vmess_udp_fixture.inc $(CORE)/session.c $(CORE)/socks5.c $(NET_SAFE) \
              $(CORE)/vless.c $(CORE)/vless_conn.c $(CORE)/socks5_client.c $(CORE)/http_client.c $(CORE)/vision.c \
              $(CORE)/trojan_client.c $(CORE)/hy2_proto.c $(CORE)/blake2b256.c $(CORE)/shadowsocks_client.c $(CORE)/vmess_client.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TRACE) $(REPLAY) $(UPLOAD) $(TIME)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_session_direct.c $(CORE)/senko_paths.c $(CORE)/session.c \
	      $(CORE)/socks5.c $(NET_SAFE) $(CORE)/vless.c $(CORE)/vless_conn.c \
	      $(CORE)/socks5_client.c $(CORE)/http_client.c $(CORE)/vision.c \
	      $(CORE)/trojan_client.c $(CORE)/hy2_proto.c $(CORE)/blake2b256.c $(CORE)/shadowsocks_client.c $(CORE)/vmess_client.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) \
	      $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) -o $@ $(TLS_LDFLAGS)

# the probe drives a whole protocol session over the real transports
REAL_PROBE_SRC = $(CORE)/real_probe.c $(CORE)/net_diag.c $(CORE)/senko_clock.c $(CORE)/transport_pick.c $(ENC_SRC) $(TUN_SESSION_SRC) \
                 $(CFG_SRC) $(CORE)/transport_tcp.c $(CORE)/transport_tls.c \
                 $(CORE)/transport_ws.c $(CORE)/transport_xhttp.c $(CORE)/hpack.c \
                 $(CORE)/reality_handshake.c $(CORE)/reality_auth.c \
                 $(CORE)/tls_clienthello.c $(CORE)/tls13_kdf.c $(CORE)/tls13_keysched.c \
                 $(CORE)/tls13_record.c $(CORE)/tls13_transcript.c $(CORE)/tls13_handshake.c \
                 $(CORE)/grpc_core.c
test_real_probe: test_real_probe.c $(CORE)/net_protect.c $(REAL_PROBE_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_real_probe.c $(CORE)/net_protect.c $(REAL_PROBE_SRC) -o $@ $(TLS_LDFLAGS) -lpthread

test_chacha_poly: test_chacha_poly.c $(CORE)/chacha_poly.inc
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_chacha_poly.c -o $@ $(TLS_LDFLAGS)

test_trojan_exchange: test_trojan_exchange.c $(CORE)/session.c $(CORE)/socks5.c $(NET_SAFE) \
              $(CORE)/vless.c $(CORE)/vless_conn.c $(CORE)/socks5_client.c $(CORE)/http_client.c $(CORE)/vision.c \
              $(CORE)/trojan_client.c $(CORE)/hy2_proto.c $(CORE)/blake2b256.c $(CORE)/shadowsocks_client.c $(CORE)/vmess_client.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) \
              $(CORE)/transport_tcp.c $(CORE)/transport_tls.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_trojan_exchange.c $(CORE)/session.c \
	      $(CORE)/socks5.c $(NET_SAFE) $(CORE)/vless.c $(CORE)/vless_conn.c \
	      $(CORE)/socks5_client.c $(CORE)/http_client.c $(CORE)/vision.c \
	      $(CORE)/trojan_client.c $(CORE)/hy2_proto.c $(CORE)/blake2b256.c $(CORE)/shadowsocks_client.c $(CORE)/vmess_client.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) \
	      $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) $(CORE)/senko_paths.c $(CORE)/transport_tcp.c $(CORE)/transport_tls.c -o $@ $(TLS_LDFLAGS)

test_trojan_combos: test_trojan_combos.c $(CORE)/store.c $(CFG_SRC) $(CORE)/store.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_trojan_combos.c $(CORE)/store.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

# vless encryption: the hash, the profile parser and record layer against the
# in-tree test server, and the transport over a socketpair
test_blake3: test_blake3.c blake3_vectors.inc $(CORE)/blake3.c $(CORE)/blake3.h
	$(CC) $(CFLAGS) test_blake3.c $(CORE)/blake3.c -o $@

VLENC_SRC = $(CORE)/vlenc_conf.c $(CORE)/vlenc_conn.c $(CORE)/blake3.c $(CORE)/b64.c \
            $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME)

test_vlenc_conn: test_vlenc_conn.c vlenc_server.c vlenc_server.h $(VLENC_SRC) $(CORE)/vlenc_conn.h $(CORE)/vlenc_conf.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) $(THREAD_CFLAGS) test_vlenc_conn.c vlenc_server.c \
	      $(filter-out $(CORE)/vlenc_conn.c,$(VLENC_SRC)) -o $@ $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_transport_enc: test_transport_enc.c $(CORE)/senko_paths.c vlenc_server.c vlenc_server.h $(CORE)/transport_enc.c $(VLENC_SRC) \
                    $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(CORE)/transport_ws.c \
                    $(CORE)/transport_xhttp.c $(CORE)/hpack.c $(CORE)/grpc_core.c $(CORE)/h2_core.c \
                    $(CORE)/reality_handshake.c $(CORE)/reality_auth.c $(CORE)/tls_clienthello.c \
                    $(CORE)/tls13_kdf.c $(CORE)/tls13_keysched.c $(CORE)/tls13_record.c \
                    $(CORE)/tls13_transcript.c $(CORE)/tls13_handshake.c $(TRACE) $(REPLAY) $(UPLOAD)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) $(THREAD_CFLAGS) test_transport_enc.c $(CORE)/senko_paths.c vlenc_server.c \
	      $(filter-out $(CORE)/vlenc_conn.c,$(VLENC_SRC)) $(CORE)/vlenc_conn.c \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(CORE)/transport_ws.c \
	      $(CORE)/transport_xhttp.c $(CORE)/hpack.c $(CORE)/grpc_core.c $(CORE)/h2_core.c \
	      $(CORE)/reality_handshake.c $(CORE)/reality_auth.c $(CORE)/tls_clienthello.c \
	      $(CORE)/tls13_kdf.c $(CORE)/tls13_keysched.c $(CORE)/tls13_record.c \
	      $(CORE)/tls13_transcript.c $(CORE)/tls13_handshake.c $(TRACE) $(REPLAY) $(UPLOAD) \
	      -o $@ $(TLS_LDFLAGS) $(THREAD_LDFLAGS)

test_trojan: test_trojan.c $(CORE)/trojan_client.c $(CFG_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_trojan.c $(CORE)/trojan_client.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_shadowsocks: test_shadowsocks.c $(CORE)/shadowsocks_client.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(CFG_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_shadowsocks.c $(CORE)/shadowsocks_client.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_vmess: test_vmess.c vmess_fixture.inc vmess_udp_fixture.inc $(CORE)/vmess_client.c $(CORE)/vmess_client.h $(CORE)/vless.c $(CORE)/third_party/mlkem768/fips202.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_vmess.c $(CORE)/vmess_client.c $(CORE)/vless.c $(CORE)/third_party/mlkem768/fips202.c -o $@ $(TLS_LDFLAGS)

# zbar's fixed-point qr math shifts negative values left on purpose; clang and
# gcc define that as multiplication, so only that check is off for its sources.
# it follows CFLAGS so a sanitizer build cannot turn it back on
ZBAR_NO_SHIFT_CHECK = -fno-sanitize=shift-base

test_reality_crypto: test_reality_crypto.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(CORE)/reality_crypto.h
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_reality_crypto.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) \
	      -o $@ $(TLS_LDFLAGS)

test_tls13_kdf: test_tls13_kdf.c $(CORE)/tls13_kdf.c $(CORE)/reality_crypto.c $(MLKEM768_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_tls13_kdf.c $(CORE)/tls13_kdf.c \
	      $(CORE)/reality_crypto.c $(MLKEM768_SRC) -o $@ $(TLS_LDFLAGS)

test_tls13_keysched: test_tls13_keysched.c $(CORE)/tls13_keysched.c $(CORE)/tls13_kdf.c \
                     $(CORE)/reality_crypto.c $(MLKEM768_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_tls13_keysched.c $(CORE)/tls13_keysched.c \
	      $(CORE)/tls13_kdf.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) -o $@ $(TLS_LDFLAGS)

test_tls13_record: test_tls13_record.c $(CORE)/tls13_record.c $(CORE)/reality_crypto.c $(MLKEM768_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_tls13_record.c $(CORE)/tls13_record.c \
	      $(CORE)/reality_crypto.c $(MLKEM768_SRC) -o $@ $(TLS_LDFLAGS)

test_tls_clienthello: test_tls_clienthello.c $(CORE)/tls_clienthello.c $(CORE)/tls_clienthello.h
	$(CC) $(CFLAGS) test_tls_clienthello.c $(CORE)/tls_clienthello.c -o $@

test_vision: test_vision.c $(CORE)/vision.c $(CORE)/vision.h
	$(CC) $(CFLAGS) test_vision.c $(CORE)/vision.c -o $@

test_transport_grpc: test_transport_grpc.c $(CORE)/senko_paths.c $(CORE)/transport_pick.c $(ENC_SRC) $(CFG_SRC) \
                     $(CORE)/transport_tcp.c $(CORE)/transport_tls.c \
                     $(CORE)/transport_ws.c $(CORE)/transport_xhttp.c $(CORE)/hpack.c $(CORE)/reality_handshake.c \
                     $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(CORE)/reality_auth.c \
                     $(CORE)/tls_clienthello.c $(CORE)/tls13_kdf.c \
                     $(CORE)/tls13_keysched.c $(CORE)/tls13_record.c \
                     $(CORE)/tls13_transcript.c $(CORE)/tls13_handshake.c $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) $(CORE)/grpc_core.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_transport_grpc.c $(CORE)/senko_paths.c $(CORE)/transport_pick.c $(ENC_SRC) \
	      $(CFG_SRC) \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(CORE)/transport_ws.c \
	      $(CORE)/transport_xhttp.c $(CORE)/hpack.c \
	      $(CORE)/reality_handshake.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(CORE)/reality_auth.c \
	      $(CORE)/tls_clienthello.c $(CORE)/tls13_kdf.c $(CORE)/tls13_keysched.c \
	      $(CORE)/tls13_record.c $(CORE)/tls13_transcript.c $(CORE)/tls13_handshake.c \
	      $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) $(CORE)/grpc_core.c $(TLS_LDFLAGS) -o $@

test_transport_pick: test_transport_pick.c $(CORE)/senko_paths.c $(CORE)/transport_pick.c $(ENC_SRC) $(CFG_SRC) \
                     $(CORE)/transport_tcp.c $(CORE)/transport_tls.c \
                     $(CORE)/transport_ws.c $(CORE)/transport_xhttp.c $(CORE)/hpack.c $(CORE)/reality_handshake.c \
                     $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(CORE)/reality_auth.c \
                     $(CORE)/tls_clienthello.c $(CORE)/tls13_kdf.c \
                     $(CORE)/tls13_keysched.c $(CORE)/tls13_record.c \
                      $(CORE)/tls13_transcript.c $(CORE)/tls13_handshake.c $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) $(CORE)/grpc_core.c
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_transport_pick.c $(CORE)/senko_paths.c $(CORE)/transport_pick.c $(ENC_SRC) \
	      $(CFG_SRC) \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c $(CORE)/transport_ws.c \
	      $(CORE)/transport_xhttp.c $(CORE)/hpack.c \
	      $(CORE)/reality_handshake.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(CORE)/reality_auth.c \
	      $(CORE)/tls_clienthello.c $(CORE)/tls13_kdf.c $(CORE)/tls13_keysched.c \
	      $(CORE)/tls13_record.c $(CORE)/tls13_transcript.c $(CORE)/tls13_handshake.c \
      $(TRACE) $(REPLAY) $(UPLOAD) $(TIME) $(CORE)/grpc_core.c -o $@ $(TLS_LDFLAGS)

# senkod's amneziawg and update helper children
HELPER_SRC = $(DAEMON)/helper_jobs.c $(DAEMON)/senkod_helper.c \
             $(CORE)/errno_text.c \
             $(CORE)/awg_config.c $(CORE)/awg_handshake.c $(CORE)/reality_crypto.c $(MLKEM768_SRC) $(TIME)

test_profiles: test_profiles.c $(CFG_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_profiles.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

test_happ_links: test_happ_links.c $(CFG_SRC)
	$(CC) $(CFLAGS) $(TLS_CFLAGS) test_happ_links.c $(CFG_SRC) -o $@ $(TLS_LDFLAGS)

clean:
	rm -f $(HOST_TESTS) sub_audit fuzz_uri fuzz_json fuzz_http fuzz_h2_grpc fuzz_wire fuzz_ws fuzz_vision fuzz_dns fuzz_utun fuzz_tun_nat

sanitizers:
	$(MAKE) clean
	ASAN_OPTIONS=detect_leaks=1 UBSAN_OPTIONS=halt_on_error=1 \
	$(MAKE) test CC=/usr/bin/clang CFLAGS='-std=c99 -Wall -Wextra -O1 -g -fno-omit-frame-pointer -fsanitize=address,undefined -I../core -I../core/third_party' \
	TLS_LDFLAGS='-lssl -lcrypto -fsanitize=address,undefined'

FUZZ_FLAGS = -std=c99 -O1 -g -fno-omit-frame-pointer -fsanitize=fuzzer,address,undefined \
             -I../core -I../core/third_party

fuzz: fuzz_uri fuzz_json fuzz_http fuzz_h2_grpc fuzz_wire fuzz_ws fuzz_vision fuzz_dns fuzz_utun fuzz_tun_nat

fuzz_uri: fuzz_uri.c $(CFG_SRC)
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_uri.c $(CFG_SRC) -o $@ -lssl -lcrypto

fuzz_json: fuzz_json.c $(CFG_SRC)
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_json.c $(CFG_SRC) -o $@ -lssl -lcrypto

fuzz_http: fuzz_http.c $(CORE)/http.c
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_http.c $(CORE)/http.c -o $@

fuzz_h2_grpc: fuzz_h2_grpc.c $(CORE)/h2_core.c $(CORE)/grpc_core.c $(CORE)/hpack.c
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_h2_grpc.c $(CORE)/h2_core.c $(CORE)/grpc_core.c $(CORE)/hpack.c -o $@

fuzz_wire: fuzz_wire.c $(CORE)/socks5.c $(CORE)/vless.c $(CORE)/url.c $(NET_SAFE)
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_wire.c $(CORE)/socks5.c $(CORE)/vless.c \
	      $(CORE)/url.c $(NET_SAFE) -o $@ $(TLS_LDFLAGS)

fuzz_ws: fuzz_ws.c $(CORE)/transport_ws.c $(CORE)/transport_tcp.c $(CORE)/transport_tls.c
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_ws.c $(CORE)/transport_ws.c \
	      $(CORE)/transport_tcp.c $(CORE)/transport_tls.c -o $@ -lssl -lcrypto

fuzz_vision: fuzz_vision.c $(CORE)/vision.c
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_vision.c $(CORE)/vision.c -o $@

fuzz_dns: fuzz_dns.c $(CORE)/dns_msg.c $(CORE)/dns_msg.h
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_dns.c $(CORE)/dns_msg.c -o $@

fuzz_utun: fuzz_utun.c $(CORE)/utun_frame.c $(CORE)/utun_frame.h
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_utun.c $(CORE)/utun_frame.c -o $@

fuzz_tun_nat: fuzz_tun_nat.c $(CORE)/tun_nat.c $(CORE)/tun_nat.h
	/usr/bin/clang $(FUZZ_FLAGS) fuzz_tun_nat.c $(CORE)/tun_nat.c -o $@

fuzz-smoke: fuzz
	./fuzz_uri -runs=1000
	./fuzz_json -runs=1000
	./fuzz_http -runs=1000
	./fuzz_h2_grpc -runs=1000
	./fuzz_wire -runs=1000
	./fuzz_ws -runs=1000
	./fuzz_vision -runs=1000
	./fuzz_dns -runs=1000
	./fuzz_utun -runs=1000
	./fuzz_tun_nat -runs=1000

test_hpack: test_hpack.c $(CORE)/hpack.c
	$(CC) $(CFLAGS) test_hpack.c $(CORE)/hpack.c -o $@

test_net_diag: test_net_diag.c $(CORE)/net_diag.c
	$(CC) $(CFLAGS) test_net_diag.c $(CORE)/net_diag.c -o $@

test_senko_clock: test_senko_clock.c $(CORE)/senko_clock.c
	$(CC) $(CFLAGS) $(THREAD_CFLAGS) test_senko_clock.c $(CORE)/senko_clock.c -o $@ $(THREAD_LDFLAGS)
