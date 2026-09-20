#!/usr/bin/env bash
# Builds the OpenFlux client binary (https://github.com/p1neappleXpress/OpenFlux)
# for every ABI the app ships and installs it as
# jniLibs/<abi>/libp1npplydtransport.so.
#
# Usage:   ANDROID_NDK_HOME=/path/to/ndk ./build-openflux.sh /path/to/OpenFlux
# Needs:   Go (version from OpenFlux go.mod), Android NDK r27+.
#
# CGO is required: without it Go's resolver reads /etc/resolv.conf, which does
# not exist on Android, and every transport hostname lookup fails.

set -euo pipefail

SRC="${1:?usage: $0 /path/to/OpenFlux}"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an NDK r27+ install}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$SCRIPT_DIR/jniLibs"
API=26 # app minSdk

case "$(uname -s)" in
    Darwin) HOST=darwin-x86_64 ;;
    Linux) HOST=linux-x86_64 ;;
    MINGW* | MSYS* | CYGWIN*) HOST=windows-x86_64 ;;
    *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
esac

CLANG="$NDK/toolchains/llvm/prebuilt/$HOST/bin/clang"
if [ "$HOST" = windows-x86_64 ]; then
    CLANG="$(cygpath -m "$CLANG.exe")"
fi

build() {
    local abi=$1 goarch=$2 target=$3
    local out="$OUT_DIR/$abi/libp1npplydtransport.so"
    echo "==> $abi"
    mkdir -p "$OUT_DIR/$abi"
    (
        cd "$SRC"
        export GOOS=android GOARCH="$goarch" CGO_ENABLED=1
        export CC="$CLANG --target=$target$API"
        if [ "$goarch" = arm ]; then export GOARM=7; fi
        # 16 KB page alignment keeps the binary runnable on 16K-page devices.
        go build -trimpath \
            -ldflags="-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
            -o "$out" .
    )
}

build arm64-v8a arm64 aarch64-linux-android
build armeabi-v7a arm armv7a-linux-androideabi
build x86_64 amd64 x86_64-linux-android

commit="$(git -C "$SRC" rev-parse HEAD)"
printf 'OpenFlux %s\n%s\n' "$commit" "$(go version)" > "$SCRIPT_DIR/openflux-version.txt"
echo "Built OpenFlux $commit"
