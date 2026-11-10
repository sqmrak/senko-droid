package main

import (
	"testing"
	"unsafe"
)

func TestStartRejectsInvalidConfigWithoutDeadlock(t *testing.T) {
	state.Lock()
	oldProtect, oldAssets := state.protect, state.assets
	var marker byte
	state.protect = unsafe.Pointer(&marker)
	state.assets = t.TempDir()
	state.Unlock()
	t.Cleanup(func() {
		state.Lock()
		state.protect, state.assets = oldProtect, oldAssets
		state.Unlock()
	})
	for i := 0; i < 2; i++ {
		if err := startCore([]byte("{"), 0); err == nil {
			t.Fatal("invalid configuration was accepted")
		}
	}
}

func TestCoreStartsStopsAndRestarts(t *testing.T) {
	state.Lock()
	oldProtect := state.protect
	var marker byte
	state.protect = unsafe.Pointer(&marker)
	state.Unlock()
	t.Cleanup(func() {
		_ = stopCore()
		state.Lock()
		state.protect = oldProtect
		state.Unlock()
	})
	config := []byte(`{"log":{"loglevel":"none"},"outbounds":[{"protocol":"freedom","tag":"direct"}]}`)
	for i := 0; i < 2; i++ {
		if err := startCore(config, 0); err != nil {
			t.Fatalf("start: %v", err)
		}
		if err := startCore(config, 0); err == nil {
			t.Fatal("second start was accepted")
		}
		if err := stopCore(); err != nil {
			t.Fatalf("stop: %v", err)
		}
	}
}

func TestCoreAcceptsVisionUDP443(t *testing.T) {
	state.Lock()
	oldProtect := state.protect
	var marker byte
	state.protect = unsafe.Pointer(&marker)
	state.Unlock()
	t.Cleanup(func() {
		_ = stopCore()
		state.Lock()
		state.protect = oldProtect
		state.Unlock()
	})
	config := []byte(`{"log":{"loglevel":"none"},"outbounds":[{"protocol":"vless","settings":{"address":"127.0.0.1","port":443,"id":"11111111-1111-4111-8111-111111111111","encryption":"none","flow":"xtls-rprx-vision-udp443"},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"example.com"}}}]}`)
	if err := startCore(config, 0); err != nil {
		t.Fatalf("start: %v", err)
	}
}
