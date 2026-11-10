//go:build !senko_trim

package main

// every proxy, transport and app xray ships. what the app can write a configuration for is a
// separate matter, see engine/xray_backend.c
import _ "github.com/xtls/xray-core/main/distro/all"
