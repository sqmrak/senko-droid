# shared toolchain setup, sourced by the android build scripts
# every root is overrideable, nothing here names a home directory

: "${SENKODROID_LLVM:?set SENKODROID_LLVM to the ndk llvm prebuilt directory}"
: "${SENKODROID_SYSROOT:?set SENKODROID_SYSROOT to the ndk sysroot}"
SENKODROID_API="${SENKODROID_API:-9}"
export SENKODROID_API

[[ -x "${SENKODROID_LLVM}/bin/clang" ]] || { echo "no clang in ${SENKODROID_LLVM}/bin" >&2; return 1; }
[[ -d "${SENKODROID_SYSROOT}/usr/lib" ]] || { echo "no sysroot at ${SENKODROID_SYSROOT}" >&2; return 1; }

# abi name -> clang triple, openssl target, cpu flags
senkodroid_abi() {
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  ABI_TRIPLE="$("${here}/android-abi.sh" "$1" triple)"
  ABI_SSL="$("${here}/android-abi.sh" "$1" ssl)"
  ABI_CFLAGS="$("${here}/android-abi.sh" "$1" cflags)"
  ABI_SYSROOT="$("${here}/android-sysroot.sh" "$1")"
}
