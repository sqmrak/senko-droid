# libsenko.so for one android abi: the engine plus its jni layer.
#   make -f android.mk ABI=armeabi-v7a   (or armeabi, arm64-v8a, x86, x86_64)
# needs SENKODROID_LLVM and SENKODROID_SYSROOT (see scripts/android-env.sh), and
# the openssl and quic archives from scripts/build_openssl_android.sh and
# scripts/build_quic_android.sh for the same abi

include sources.mk

ABI ?= armeabi-v7a
SENKODROID_API ?= 9
export SENKODROID_API
BUILD ?= build
SENKODROID_LLVM ?= $(error set SENKODROID_LLVM to the ndk llvm prebuilt directory)
SENKODROID_SYSROOT ?= $(error set SENKODROID_SYSROOT to the ndk sysroot)

TRIPLE  := $(shell scripts/android-abi.sh $(ABI) triple)
ABIFLAGS := $(shell scripts/android-abi.sh $(ABI) cflags)
PROFILE := $(shell scripts/android-abi.sh $(ABI) profile)

OSSL := $(BUILD)/openssl-$(ABI)
QUIC := $(BUILD)/quic-$(ABI)
OUT  := $(BUILD)/android/$(ABI)
OBJ  := $(OUT)/obj

SYSROOT := $(shell SENKODROID_BUILD=$(abspath $(BUILD)) scripts/android-sysroot.sh $(ABI))
CC := $(SENKODROID_LLVM)/bin/clang --target=$(TRIPLE) --sysroot=$(SYSROOT)
# SENKO_RELEASE drops the traffic dump hooks of the debug builds
# -MMD keeps the header dependencies, so editing a header rebuilds what includes it
CFLAGS := -MMD -MP -std=c99 -D_GNU_SOURCE -DSENKO_RELEASE $(PROFILE) $(ABIFLAGS) -O2 -fPIC \
    -fvisibility=hidden -ffunction-sections -fdata-sections -Wall -Wextra \
    -Werror=implicit-function-declaration -I. -Icore -Icore/third_party -Iengine \
    -Iengine/lwip_port -Irootd -I$(SENKO_LWIP_DIR)/include -I$(OSSL)/include -I$(QUIC)/include
LDFLAGS := -shared -Wl,--gc-sections -Wl,--no-undefined -Wl,-z,relro -Wl,-z,now \
    -Wl,--exclude-libs,ALL
ifneq ($(filter arm64-v8a x86_64,$(ABI)),)
# android devices with 16 kb pages reject segments aligned to 4 kb
LDFLAGS += -Wl,-z,max-page-size=16384
endif

CORE_SRC   := $(addprefix core/,$(SENKO_CORE_FILES) $(SENKO_MLKEM_FILES))
ENGINE_SRC := $(addprefix engine/,$(SENKO_ENGINE_FILES))
LWIP_SRC   := $(wildcard $(SENKO_LWIP_DIR)/core/*.c) $(wildcard $(SENKO_LWIP_DIR)/core/ipv4/*.c) \
    $(wildcard $(SENKO_LWIP_DIR)/core/ipv6/*.c) $(SENKO_LWIP_PORT_FILES)
# the armv5te slice needs the barrier that compiler-rt lacks for it
ARM5_SRC := $(if $(filter armeabi,$(ABI)),engine/armv5_compat.c)
SRC := $(CORE_SRC) $(ENGINE_SRC) $(LWIP_SRC) jni/senko_jni.c $(ARM5_SRC)
OBJS := $(patsubst %.c,$(OBJ)/%.o,$(SRC))

# the root helper of android 2.3 to 3.x, see rootd/. it is an executable that ships as a lib*.so so
# the package installer extracts it, and it is not position independent: android before 4.1 cannot
# start a pie executable. the objects are position independent all the same: the x86 linker of
# android 4.4 stops with "unknown reloc type 5" on the copy relocation that code against libc data
# (stdout) otherwise needs
ROOTD_SRC := rootd/senko_rootd.c rootd/rootd_linux.c rootd/rootd_serve.c rootd/rootd_proto.c core/fdpass.c
ROOTD_OBJS := $(patsubst %.c,$(OBJ)/%.o,$(ROOTD_SRC))
ROOTD_CFLAGS := $(CFLAGS) -Irootd

.PHONY: all clean
all: $(OUT)/libsenko.so $(OUT)/libsenkorootd.so

$(OBJ)/%.o: %.c
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -c $< -o $@

$(OUT)/libsenko.so: android.mk $(OBJS) $(OSSL)/lib/libssl.a $(OSSL)/lib/libcrypto.a \
    $(QUIC)/lib/libngtcp2.a $(QUIC)/lib/libnghttp3.a
	$(CC) $(ABIFLAGS) $(LDFLAGS) $(OBJS) \
	    $(QUIC)/lib/libngtcp2.a $(QUIC)/lib/libnghttp3.a \
	    $(OSSL)/lib/libssl.a $(OSSL)/lib/libcrypto.a -llog -lz -ldl -lm -o $@

$(OBJ)/rootd/%.o: rootd/%.c
	@mkdir -p $(dir $@)
	$(CC) $(ROOTD_CFLAGS) -c $< -o $@

$(OUT)/libsenkorootd.so: android.mk $(ROOTD_OBJS)
	$(CC) $(ABIFLAGS) -no-pie -Wl,--gc-sections -Wl,-z,relro -Wl,-z,now $(ROOTD_OBJS) -o $@

clean:
	rm -rf $(BUILD)/android/$(ABI)

-include $(OBJS:.o=.d)
