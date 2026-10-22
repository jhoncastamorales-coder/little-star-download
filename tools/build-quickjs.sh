#!/usr/bin/env bash
# Compila QuickJS-NG como ejecutable estatico para Android arm64-v8a y lo
# empaqueta como android/app/src/main/jniLibs/arm64-v8a/libqjs.so, para que
# yt-dlp pueda resolver los retos de JavaScript de YouTube/TikTok a maxima
# calidad (ver --js-runtimes en https://github.com/yt-dlp/yt-dlp/wiki/EJS).
#
# Requisitos: Android NDK instalado (Android Studio > SDK Manager > SDK Tools
# > NDK). Definir ANDROID_NDK_HOME antes de correr este script, por ejemplo:
#   export ANDROID_NDK_HOME="$HOME/AppData/Local/Android/Sdk/ndk/<version>"
#
# Se corre a mano solo cuando se quiera (re)generar el binario -- no es parte
# del build normal de Gradle (ver Option 1 del plan: se vendoriza el binario
# ya compilado en vez de recompilarlo en cada build).
set -euo pipefail

QJS_TAG="v0.17.0"  # Verificar si hay un tag mas reciente antes de compilar.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
THIRD_PARTY_DIR="$SCRIPT_DIR/third_party"
SRC_DIR="$THIRD_PARTY_DIR/quickjs-ng"
BUILD_DIR="$SCRIPT_DIR/build-quickjs-arm64"
OUT_DIR="$SCRIPT_DIR/../android/app/src/main/jniLibs/arm64-v8a"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ANDROID_NDK_HOME no esta definida. Instala el NDK desde Android Studio y exportala." >&2
    exit 1
fi

NINJA_BIN="$(command -v ninja || true)"
if [ -z "$NINJA_BIN" ]; then
    NINJA_BIN="$HOME/AppData/Local/Microsoft/WinGet/Links/ninja.exe"
fi
if [ ! -x "$NINJA_BIN" ]; then
    echo "No se encontro ninja. Instalalo (winget install Ninja-build.Ninja) o definí NINJA_BIN." >&2
    exit 1
fi

mkdir -p "$THIRD_PARTY_DIR"
if [ ! -d "$SRC_DIR" ]; then
    git clone --branch "$QJS_TAG" --depth 1 https://github.com/quickjs-ng/quickjs "$SRC_DIR"
fi

cmake -S "$SRC_DIR" -B "$BUILD_DIR" \
    -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    -DQJS_BUILD_CLI_STATIC=ON \
    -DBUILD_SHARED_LIBS=OFF

cmake --build "$BUILD_DIR" -j

mkdir -p "$OUT_DIR"
# El nombre exacto del binario resultante puede variar segun la version
# (qjs vs qjs_exe); si esta copia falla, correr primero:
#   cmake --build "$BUILD_DIR" --target help
cp "$BUILD_DIR/qjs" "$OUT_DIR/libqjs.so"

echo "Listo: $OUT_DIR/libqjs.so"
echo ""
echo "Antes de integrarlo con yt-dlp, probalo aislado en un dispositivo/emulador real:"
echo "  adb push '$OUT_DIR/libqjs.so' /data/local/tmp/qjs"
echo "  adb shell chmod 755 /data/local/tmp/qjs"
echo "  adb shell /data/local/tmp/qjs --version"
