#!/usr/bin/env bash
set -uo pipefail
T=/scratch/work/tools7
SRC=$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/java
CP="$T/android-35/android.jar:$(cat $T/classpath.txt)"
rm -rf "$T/out" && mkdir -p "$T/out"
"$T/kotlinc/bin/kotlinc" -classpath "$CP" -jvm-target 17 -nowarn -d "$T/out" $(find "$SRC" -name '*.kt') "$T/stubs/R.kt"
