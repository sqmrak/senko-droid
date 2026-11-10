package main

/*
#include <stdint.h>
#include <string.h>

typedef int (*senko_protect_fn)(int fd);

// cgo cannot call a C function pointer held in a Go variable, so the call goes through here
static int senko_call_protect(void *fn, int fd) {
    return ((senko_protect_fn)fn)(fd);
}
*/
import "C"

import (
	"context"
	"errors"
	"net/http"
	"os"
	"runtime/debug"
	"strconv"
	stdnet "net"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"
	"github.com/xtls/xray-core/transport/internet"

	// the two apps that count the tunnel's bytes. the distro itself is chosen by the build tag, see
	// distro_full.go and distro_trim.go
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/stats"
)

const probeURL = "http://www.gstatic.com/generate_204"

const (
	uplinkCounter   = "inbound>>>senko-tun>>>traffic>>>uplink"
	downlinkCounter = "inbound>>>senko-tun>>>traffic>>>downlink"
)

var state struct {
	sync.Mutex
	assets   string // the directory holding geoip.dat and geosite.dat
	instance *core.Instance
	protect  unsafe.Pointer // senko_protect_fn, set by SenkoCoreSetProtect
	hooked   bool
}

var lifecycle sync.Mutex

// useAssets points the core at the geo dat files. xray reads this variable while it builds the router
func useAssets() {
	state.Lock()
	dir := state.assets
	state.Unlock()
	if dir != "" {
		_ = os.Setenv("XRAY_LOCATION_ASSET", dir)
	}
}

// SenkoCoreSetAssets names the directory with geoip.dat and geosite.dat, which geo rules read
//
//export SenkoCoreSetAssets
func SenkoCoreSetAssets(dir *C.char) {
	state.Lock()
	state.assets = C.GoString(dir)
	state.Unlock()
}

func fail(err error, out *C.char, capacity C.int) C.int {
	if out == nil || capacity <= 0 {
		return -1
	}
	text := []byte(err.Error())
	if len(text) > int(capacity)-1 {
		text = text[:int(capacity)-1]
	}
	if len(text) > 0 {
		C.memcpy(unsafe.Pointer(out), unsafe.Pointer(&text[0]), C.size_t(len(text)))
	}
	*(*C.char)(unsafe.Add(unsafe.Pointer(out), len(text))) = 0
	return -1
}

// protectSocket keeps every socket the core opens toward a server outside the tunnel: the vpn route
// covers every destination, so an unprotected one would carry its own packets back into the tun
func protectSocket(network, address string, conn syscall.RawConn) error {
	state.Lock()
	fn := state.protect
	state.Unlock()
	if fn == nil {
		return errors.New("no protect hook: refusing to open a socket that would loop into the tunnel")
	}
	var result C.int = -1
	if err := conn.Control(func(fd uintptr) {
		result = C.senko_call_protect(fn, C.int(fd))
	}); err != nil {
		return err
	}
	if result != 0 {
		return errors.New("the vpn service refused to protect a socket to " + address)
	}
	return nil
}

// SenkoCoreSetProtect installs the hook that takes a socket out of the tunnel. it must be set
// before the first start
//
//export SenkoCoreSetProtect
func SenkoCoreSetProtect(fn unsafe.Pointer) C.int {
	state.Lock()
	defer state.Unlock()
	state.protect = fn
	if state.hooked {
		return 0
	}
	if err := internet.RegisterDialerController(protectSocket); err != nil {
		return -1
	}
	if err := internet.RegisterListenerController(protectSocket); err != nil {
		return -1
	}
	state.hooked = true
	return 0
}

// SenkoCoreValidate builds the instance without starting it, so a config the core would refuse is
// reported before a tunnel is opened
//
//export SenkoCoreValidate
func SenkoCoreValidate(config *C.char, length C.int, errorOut *C.char, errorCap C.int) C.int {
	if config == nil || length <= 0 {
		return fail(errors.New("empty configuration"), errorOut, errorCap)
	}
	useAssets()
	parsed, err := serial.LoadJSONConfig(bytesReader(C.GoBytes(unsafe.Pointer(config), length)))
	if err != nil {
		return fail(err, errorOut, errorCap)
	}
	instance, err := core.New(parsed)
	if err != nil {
		return fail(err, errorOut, errorCap)
	}
	_ = instance.Close()
	return 0
}

// SenkoCoreStart runs the core on the tun descriptor. the core never closes the descriptor, the
// caller does after SenkoCoreStop
//
//export SenkoCoreStart
func SenkoCoreStart(config *C.char, length C.int, tunFD C.int, errorOut *C.char, errorCap C.int) C.int {
	if config == nil || length <= 0 || tunFD < 0 {
		return fail(errors.New("invalid configuration or tun descriptor"), errorOut, errorCap)
	}
	data := C.GoBytes(unsafe.Pointer(config), length)
	if err := startCore(data, int(tunFD)); err != nil {
		return fail(err, errorOut, errorCap)
	}
	return 0
}

func startCore(data []byte, tunFD int) error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	state.Lock()
	if state.instance != nil {
		state.Unlock()
		return errors.New("the core is already running")
	}
	if state.protect == nil {
		state.Unlock()
		return errors.New("the protect hook is not set")
	}
	state.Unlock()
	useAssets()
	if err := os.Setenv("XRAY_TUN_FD", strconv.Itoa(tunFD)); err != nil {
		return err
	}
	instance, err := core.StartInstance("json", data)
	_ = os.Unsetenv("XRAY_TUN_FD")
	if err != nil {
		return err
	}
	state.Lock()
	state.instance = instance
	state.Unlock()
	return nil
}

const probeParallel = 6

var probeSlots = make(chan struct{}, probeParallel)

// SenkoCoreProbe times what a request through the server takes: it builds a core with the
// configuration's outbound and no inbound, fetches the connectivity URL through it and returns the
// milliseconds. the sockets it opens go through the protect hook, so a probe never rides a running
// tunnel. -1 with the reason in errorOut when the server did not answer in time. a probe holds a
// whole core in memory, so only probeParallel run at once and the rest wait their turn. the wait
// does not count against the probe's own timeout: it used to, and in a sweep the servers queued
// behind dead ones failed with "too many probes" without ever being tried
//
//export SenkoCoreProbe
func SenkoCoreProbe(config *C.char, length C.int, timeoutMs C.int, errorOut *C.char, errorCap C.int) C.int {
	if config == nil || length <= 0 || timeoutMs <= 0 {
		return fail(errors.New("invalid probe request"), errorOut, errorCap)
	}
	data := C.GoBytes(unsafe.Pointer(config), length)
	timeout := time.Duration(timeoutMs) * time.Millisecond
	waiting := time.NewTimer(timeout)
	select {
	case probeSlots <- struct{}{}:
		waiting.Stop()
		defer func() { <-probeSlots }()
	case <-waiting.C:
		return fail(errors.New("too many probes at once"), errorOut, errorCap)
	}
	deadline := time.Now().Add(timeout)
	useAssets()
	parsed, err := serial.LoadJSONConfig(bytesReader(data))
	if err != nil {
		return fail(err, errorOut, errorCap)
	}
	instance, err := core.New(parsed)
	if err != nil {
		return fail(err, errorOut, errorCap)
	}
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		return fail(err, errorOut, errorCap)
	}
	defer instance.Close()

	ctx, cancel := context.WithDeadline(context.Background(), deadline)
	defer cancel()
	client := &http.Client{
		Transport: &http.Transport{
			DisableKeepAlives: true,
			DialContext: func(ctx context.Context, network, address string) (stdnet.Conn, error) {
				destination, err := net.ParseDestination(network + ":" + address)
				if err != nil {
					return nil, err
				}
				return core.Dial(ctx, instance, destination)
			},
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, probeURL, nil)
	if err != nil {
		return fail(err, errorOut, errorCap)
	}
	started := time.Now()
	response, err := client.Do(request)
	if err != nil {
		return fail(err, errorOut, errorCap)
	}
	_ = response.Body.Close()
	if response.StatusCode >= 500 {
		return fail(errors.New("the test address answered with an error"), errorOut, errorCap)
	}
	return C.int(time.Since(started).Milliseconds())
}

//export SenkoCoreStop
func SenkoCoreStop(errorOut *C.char, errorCap C.int) C.int {
	if err := stopCore(); err != nil {
		return fail(err, errorOut, errorCap)
	}
	return 0
}

func stopCore() error {
	lifecycle.Lock()
	defer lifecycle.Unlock()
	state.Lock()
	instance := state.instance
	state.instance = nil
	state.Unlock()
	if instance == nil {
		return nil
	}
	if err := instance.Close(); err != nil {
		return err
	}
	// the stopped core leaves a heap the phone has no use for. the forced collection takes long on
	// a slow cpu and the caller waits for this function to tear the tunnel down
	go debug.FreeOSMemory()
	return nil
}

//export SenkoCoreRunning
func SenkoCoreRunning() C.int {
	state.Lock()
	defer state.Unlock()
	if state.instance != nil {
		return 1
	}
	return 0
}

// SenkoCoreStats reports the bytes the tun inbound has carried since the core started
//
//export SenkoCoreStats
func SenkoCoreStats(up *C.uint64_t, down *C.uint64_t) C.int {
	if up == nil || down == nil {
		return -1
	}
	state.Lock()
	instance := state.instance
	state.Unlock()
	if instance == nil {
		return -1
	}
	manager, ok := instance.GetFeature(stats.ManagerType()).(stats.Manager)
	if !ok || manager == nil {
		return -1
	}
	*up, *down = 0, 0
	if counter := manager.GetCounter(uplinkCounter); counter != nil {
		*up = C.uint64_t(counter.Value())
	}
	if counter := manager.GetCounter(downlinkCounter); counter != nil {
		*down = C.uint64_t(counter.Value())
	}
	return 0
}

var versionOnce sync.Once
var versionText *C.char

// SenkoCoreVersion returns a string that lives as long as the library, the caller does not free it
//
//export SenkoCoreVersion
func SenkoCoreVersion() *C.char {
	versionOnce.Do(func() { versionText = C.CString(core.Version()) })
	return versionText
}

func main() {}
