#!/usr/bin/env bash
# N8 Kotlin (H4): compila las homebrew de gba/tests con la toolchain de referencia de los hashes de GbaNativeTest:
# el clang del sistema (Apple clang) y, al FINAL del PATH, solo ld.lld/llvm-objcopy del NDK. Con el clang del NDK
# delante las ROMs salen con otros bytes y el hash acumulado de ppu_scene_11 no coincide.
set -euo pipefail
ndk="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.3.13750724}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/usr/bin:$PATH:$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin"
echo "clang: $(command -v clang)"
rm -rf "$here/gba/build/hb"
make -C "$here/gba" homebrew
