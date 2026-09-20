#!/bin/bash
# Rebuilds tun2socks, pdnsd and libsystem with ndk-build and installs them into
# jniLibs, next to the OpenFlux binaries from build-openflux.sh.
# On Windows run it with NDK_BUILD=/path/to/ndk-build.cmd.

set -e
cd "$(dirname "$0")"

"${NDK_BUILD:-ndk-build}"

for p in armeabi-v7a arm64-v8a x86 x86_64; do
	mkdir -p jniLibs/$p
	mv libs/$p/tun2socks jniLibs/$p/libtun2socks.so
	mv libs/$p/pdnsd jniLibs/$p/libpdnsd.so
	mv libs/$p/libsystem.so jniLibs/$p/libsystem.so
done

rm -rf libs
