# entry points:
#   make test       host tests of the c engine
#   make test-java  jvm tests, including the engine driven through jni
#   make native     libsenko.so, the root helper and senko-core for every abi in ABIS, into build/jniLibs
#   make apk        the debug apk, with the engine and the trust roots inside
#
# the ndk side needs SENKODROID_LLVM, SENKODROID_SYSROOT and the three source
# trees below, see README.md. the gradle side takes the toolchain checkout from
# SENKODROID_ANDROID, a sibling of this directory by default

ABIS ?= armeabi armeabi-v7a arm64-v8a x86 x86_64
# the go build of xray needs android 4.4 or later and an arm cpu of armv7, so armeabi (armv6) has none
CORE_ABIS = $(filter-out armeabi,$(ABIS))
# api 9 is android 2.3. arm64 never goes below api 21, see scripts/android-abi.sh. the build
# directory carries the api, so a build for another one never reuses these archives
SENKODROID_API ?= 9
BUILD ?= build/api$(SENKODROID_API)
export SENKODROID_API BUILD
# the host build of the engine does not depend on the android api
HOSTLIB ?= $(abspath build/host)
export SENKODROID_BUILD := $(abspath $(BUILD))

SENKODROID_ANDROID ?= $(abspath ../android/android-toolchain)
JAVA_HOME ?= $(firstword $(wildcard $(SENKODROID_ANDROID)/jdk-17*))
ANDROID_HOME ?= $(SENKODROID_ANDROID)/sdk
GRADLE_USER_HOME ?= $(SENKODROID_ANDROID)/gradle-home
# google ships aapt2 only for x86_64, leave empty on hosts where the maven aapt2 runs
SENKODROID_AAPT2 ?= $(wildcard $(SENKODROID_ANDROID)/bin/aapt2)
export JAVA_HOME ANDROID_HOME GRADLE_USER_HOME

GRADLE = ./gradlew --no-daemon -Psenko.jnilibs=$(abspath $(BUILD))/jniLibs $(if $(SENKODROID_AAPT2),-Pandroid.aapt2FromMavenOverride=$(SENKODROID_AAPT2))

.PHONY: test test-java native core roots apk clean

test:
	$(MAKE) -C tests test

# the jvm tests load the host build of the engine, which needs a jdk for jni.h
test-java:
	scripts/fetch_robolectric.sh
	$(MAKE) -C tests host-jni JAVA_HOME=$(JAVA_HOME) HOST_JNI_DIR=$(HOSTLIB)
	SENKODROID_HOST_LIB_DIR=$(HOSTLIB) $(GRADLE) :app:testDebugUnitTest

# the openssl and quic archives are rebuilt only when missing
$(BUILD)/openssl-%/lib/libssl.a:
	scripts/build_openssl_android.sh $*

$(BUILD)/quic-%/lib/libngtcp2.a: $(BUILD)/openssl-%/lib/libssl.a
	scripts/build_quic_android.sh $*

native: $(foreach abi,$(ABIS),$(BUILD)/jniLibs/$(abi)/libsenko.so) $(foreach abi,$(CORE_ABIS),$(BUILD)/jniLibs/$(abi)/libsenkocore.so)

# the engine library is rebuilt by android.mk, which tracks its own sources
.PHONY: FORCE
FORCE:

$(BUILD)/jniLibs/%/libsenko.so: $(BUILD)/quic-%/lib/libngtcp2.a FORCE
	$(MAKE) -f android.mk ABI=$* -j$$(nproc)
	mkdir -p $(dir $@)
	$(SENKODROID_LLVM)/bin/llvm-strip --strip-unneeded -o $@ $(BUILD)/android/$*/libsenko.so
	# the root helper exists only where android has no VpnService, so for the 32 bit abis
	if [ "$*" != arm64-v8a ] && [ "$*" != x86_64 ]; then \
	  $(SENKODROID_LLVM)/bin/llvm-strip --strip-unneeded -o $(dir $@)libsenkorootd.so $(BUILD)/android/$*/libsenkorootd.so; \
	fi

# senko-core: needs SENKODROID_GO and SENKODROID_CORE_SRC, see tools/android-ndk-env.sh
# go keeps its own cache, so running it every time costs little and never misses a change of the core source
$(BUILD)/jniLibs/%/libsenkocore.so: FORCE
	scripts/build_xray_android.sh $*
	mkdir -p $(dir $@)
	cp $(BUILD)/xray-api$${SENKODROID_CORE_API:-16}/$*/libsenkocore.so $@

core: $(foreach abi,$(CORE_ABIS),$(BUILD)/jniLibs/$(abi)/libsenkocore.so)

roots:
	scripts/fetch_roots.sh

apk: native roots
	$(GRADLE) :app:assembleDebug
	@ls -l app/build/outputs/apk/debug/app-debug.apk

clean:
	$(MAKE) -C tests clean
	rm -rf $(BUILD)/android $(BUILD)/jniLibs app/build
