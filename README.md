# senko-droid

<p align="center">
  <img src="assets/senko-phone.png" width="420" alt="senko-droid">
</p>

<p align="center"><b>vless, hysteria2 and amneziawg for the whole device, android 2.3 and later</b></p>

android port of [senko](https://github.com/sqmrak/senko), with its own app and build.

> [!NOTE]
> android 2.3 to 3.x needs root. android 4.0 and later uses `VpnService` without root.

## devices

- android 2.3 and later: `armeabi`, `armeabi-v7a`, `x86`
- android 5.0 and later: `arm64-v8a`, `x86_64`
- per app routing and ipv6 need android 5.0 or later
- `armeabi` is built for armv5te and runs on armv6 phones; it has no senko-core

## protocols

- vless: tcp, tls, reality, websocket, xhttp, grpc and vless encryption
- vmess, trojan, shadowsocks and hysteria2
- amneziawg
- httpupgrade, mkcp and profiles the engine cannot carry need senko-core

subscriptions: uri lists, base64, happ, xray/sing-box json, clash yaml and other formats the senko parser accepts. a json profile with multiple nodes stays one row; auto selection checks its nodes before other rows. `incy://add/` and `incy://import/` links are supported.

not carried: tuic, anytls, wireguard, a tcp http header disguise and ech. unsupported nodes stay visible and cannot be selected. xhttp `extra` needs senko-core; the h2 transport was removed from xray.

## features

- servers and subscriptions, paste, clipboard, qr and file import
- checks, automatic server selection, failover and reconnect
- routing profiles, geo data, domain rules and direct apps
- themes, statistics, logs and diagnostics
- russian, english and chinese

## android 2.3 to 3.x

`senko_rootd` opens the tun device, binds protected sockets to the default interface and redirects dns with iptables. the helper listens on `files/rootd.sock` with mode 0600 and checks the app uid. its stdin stays open while the app runs; when it closes the helper removes its dns rules and exits.

root is requested when connect is tapped. senko tries `su` from `PATH`, then `/system/xbin/su`, `/system/bin/su`, `/sbin/su`, `/su/bin/su` and `/system/bin/failsafe/su` if the executable could not be started. a refused prompt is reported, not retried through another path. the helper refuses to start unless it has uid 0. the device also needs a tun driver and iptables with the nat table.

below android 5.0 the vpn carries ipv4 only. on android 2.3 to 3.x the root helper does not follow a network change until the tunnel is rebuilt. connect at startup needs the app to be opened first.

## senko-core

`libsenkocore.so` is the xray build in `xraycore/`. automatic mode uses it when it can carry the profile; developer settings can select senko-core or the engine. a profile only the core carries needs the core even when the engine was selected.

senko-core loads on android 5.0 and later, and on x86 from android 4.4. arm devices below 5.0 use the c engine. the core reads `geoip.dat` and `geosite.dat` from the app's geo directory; the c engine reads its own geo sets.

the full distro is the default. `SENKODROID_CORE_DISTRO=trim` builds the smaller ios distro. xhttp `extra` is kept up to 1023 bytes and passed to the core unchanged. vless encryption has host coverage but has not been verified against a real server on android.

## paths

- app data: the app's private `files/` directory
- control socket and token: `files/ctl.sock`, `files/ctl.token`
- root helper socket: `files/rootd.sock`
- trust roots: `files/cacert.pem`
- amneziawg profile: `files/awg/`

## build

```bash
make test
make test-java
make apk
```

`make apk` writes `app/build/outputs/apk/debug/app-debug.apk`. `ABIS` selects architectures, default `armeabi armeabi-v7a arm64-v8a x86 x86_64`. `SENKODROID_API` selects the 32 bit api, default 9; arm64 and x86_64 use api 21 or later.

needs jdk 17, the android sdk and gradle (`SENKODROID_ANDROID`), ndk llvm and sysroot (`SENKODROID_LLVM`, `SENKODROID_SYSROOT`), ndk r15c api 9 files (`SENKODROID_NDK15`), openssl, ngtcp2 and nghttp3 sources (`SENKODROID_OPENSSL_SRC`, `SENKODROID_NGTCP2_SRC`, `SENKODROID_NGHTTP3_SRC`), go 1.27.1 and the pinned xray source (`SENKODROID_GO`, `SENKODROID_CORE_SRC`). `SENKODROID_AAPT2` selects a host-compatible aapt2. `SENKODROID_ROBOLECTRIC` selects the framework jar cache for `make test-java`.

## license

[gpl v2](LICENSE).
