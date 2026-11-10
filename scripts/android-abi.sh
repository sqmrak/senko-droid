#!/usr/bin/env bash
# prints one setting of an android abi: triple, ssl (the openssl target), cflags
# or profile (extra defines). the single place that maps an abi to compiler flags
set -euo pipefail

ABI="${1:?usage: android-abi.sh <armeabi|armeabi-v7a|arm64-v8a|x86|x86_64> <triple|ssl|cflags|profile|arch|libdir>}"
KEY="${2:?usage: android-abi.sh <abi> <triple|ssl|cflags|profile|arch|libdir>}"
API="${SENKODROID_API:-9}"

case "${ABI}" in
  armeabi)
    # android 2.3 phones with an ARM11 core (armv6) load only this abi, and ARM11 before the K revision has no
    # 64 bit exclusive loads (ldrexd), so the code is armv5te and takes its atomics from compiler-rt. soft float: part of them
    # have no vfp unit, and the abi itself is soft float. no senko-core here, see android.mk
    triple="armv5te-linux-androideabi${API}"
    ssl="linux-armv4"
    cflags="-march=armv5te -mfloat-abi=soft -marm"
    arch="arm"; libdir="arm-linux-androideabi"
    profile="" ;;
  armeabi-v7a)
    triple="armv7a-linux-androideabi${API}"
    ssl="linux-armv4"
    cflags="-march=armv7-a -mfloat-abi=softfp -mfpu=vfpv3-d16"
    arch="arm"; libdir="arm-linux-androideabi"
    profile="" ;;
  arm64-v8a)
    # no 64 bit android before api 21, so the arm64 slice never goes lower
    triple="aarch64-linux-android$(( API < 21 ? 21 : API ))"
    ssl="linux-aarch64"
    cflags=""
    # more lwip pcbs, as on the ios arm64 slice
    arch="arm64"; libdir="aarch64-linux-android"
    profile="-DSENKO_LWIP_PROFILE_LARGE" ;;
  x86_64)
    # no 64 bit android before api 21, as for arm64
    triple="x86_64-linux-android$(( API < 21 ? 21 : API ))"
    ssl="linux-x86_64"
    cflags=""
    arch="x86_64"; libdir="x86_64-linux-android"
    profile="-DSENKO_LWIP_PROFILE_LARGE" ;;
  x86)
    triple="i686-linux-android${API}"
    ssl="linux-x86"
    cflags="-mstackrealign"
    arch="x86"; libdir="i686-linux-android"
    profile="" ;;
  *) echo "unknown abi: ${ABI}" >&2; exit 1 ;;
esac

case "${KEY}" in
  triple) echo "${triple}" ;;
  ssl) echo "${ssl}" ;;
  cflags) echo "${cflags}" ;;
  profile) echo "${profile}" ;;
  arch) echo "${arch}" ;;
  libdir) echo "${libdir}" ;;
  *) echo "unknown key: ${KEY}" >&2; exit 1 ;;
esac
