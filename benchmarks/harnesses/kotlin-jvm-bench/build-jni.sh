#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUST_DIR="$SCRIPT_DIR/../../generated/boltffi"
JNI_SRC="$RUST_DIR/dist/android/kotlin/jni/jni_glue.c"
HEADER_DIR="$RUST_DIR/dist/android/include"
OUTPUT_DIR="$RUST_DIR/target/release"

if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || echo "")
fi

if [ -z "$JAVA_HOME" ]; then
    echo "Error: JAVA_HOME not set and cannot be determined"
    exit 1
fi

if [ ! -f "$JNI_SRC" ]; then
    echo "Error: JNI glue not found at $JNI_SRC"
    echo "Run benchmarks/generated/boltffi/build.sh --platform android --skip-bench first"
    exit 1
fi

if [ "$(uname)" == "Darwin" ]; then
    # Mac
    PLATFORM_INCLUDE="$JAVA_HOME/include/darwin"
    LIBRARY_FILE=libdemo_jni.dylib
    RPATH=-Wl,-rpath,@loader_path
elif [ "$(expr substr $(uname -s) 1 5)" == "Linux" ]; then
    # Linux
    PLATFORM_INCLUDE="$JAVA_HOME/include/linux"
    LIBRARY_FILE=libdemo_jni.so
    RPATH=-Wl,-rpath,'$ORIGIN'
else 
    echo "Can't determine system platform"
    exit 1
fi

echo "Compiling JNI glue..."
cd "$OUTPUT_DIR"
cc -c -fPIC \
    -I"$HEADER_DIR" \
    -I"$JAVA_HOME/include" \
    -I"$PLATFORM_INCLUDE" \
    -o jni_glue.o \
    "$JNI_SRC"

echo "Linking final library..."
cc -shared \
    -o "$LIBRARY_FILE" \
    jni_glue.o \
    -L. -ldemo \
    $RPATH

rm -f jni_glue.o
echo "Built: $OUTPUT_DIR/$LIBRARY_FILE"
