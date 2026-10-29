#!/usr/bin/env bash
# Cross-compile the Rust library for the ABIs the app ships, and drop the
# results where Gradle looks for them. Requires ANDROID_NDK_HOME and cargo-ndk.
set -euo pipefail
cd "$(dirname "$0")/.."

# Android 15+ wants 16 KB page alignment from every shared object.
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'

cargo ndk -t arm64-v8a --platform 30 build --release --lib
cargo ndk -t x86_64 --platform 30 build --release --lib

mkdir -p android/app/src/main/jniLibs/arm64-v8a android/app/src/main/jniLibs/x86_64
cp target/aarch64-linux-android/release/libpocket.so android/app/src/main/jniLibs/arm64-v8a/
cp target/x86_64-linux-android/release/libpocket.so android/app/src/main/jniLibs/x86_64/

ls -la android/app/src/main/jniLibs/*/libpocket.so
