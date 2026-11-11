#!/usr/bin/env bash
# Compiles the pure-logic half of the engine with a plain Kotlin compiler (no Android SDK)
# and runs the self-test. Requires JAVA_HOME and KOTLIN_HOME (a kotlinc distribution).
#
#   JAVA_HOME=/path/to/jdk KOTLIN_HOME=/path/to/kotlinc bash tools/jvmtest/run_selftest.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${ROOT}/build/selftest"
mkdir -p "${OUT}"

PURE=$(cat <<'EOF'
app/src/main/java/ai/hindidub/core/Diag.kt
app/src/main/java/ai/hindidub/core/LanguageCatalog.kt
app/src/main/java/ai/hindidub/core/WavReader.kt
app/src/main/java/ai/hindidub/core/LoudnessNormalizer.kt
app/src/main/java/ai/hindidub/core/EngineRouter.kt
app/src/main/java/ai/hindidub/core/WaveformPeaks.kt
app/src/main/java/ai/hindidub/core/StorageManager.kt
app/src/main/java/ai/hindidub/cloud/JsonArrays.kt
app/src/main/java/ai/hindidub/engine/audio/FloatSink.kt
app/src/main/java/ai/hindidub/engine/audio/PcmStore.kt
app/src/main/java/ai/hindidub/engine/AudioUtils.kt
app/src/main/java/ai/hindidub/engine/AudioMixer.kt
app/src/main/java/ai/hindidub/engine/TimeStretcher.kt
app/src/main/java/ai/hindidub/engine/core/Vad.kt
app/src/main/java/ai/hindidub/engine/core/SegmentCache.kt
app/src/main/java/ai/hindidub/engine/core/ProsodyAnalyzer.kt
app/src/main/java/ai/hindidub/model/ModelCatalog.kt
EOF
)

echo "compiling pure engine sources…"
"${KOTLIN_HOME}/bin/kotlinc" -nowarn -jvm-target 17 \
    -include-runtime -d "${OUT}/selftest.jar" \
    $(for f in ${PURE}; do echo "${ROOT}/${f}"; done) \
    "${ROOT}/tools/jvmtest/EngineSelfTest.kt"

echo "running…"
"${JAVA_HOME}/bin/java" -Xmx512m -jar "${OUT}/selftest.jar"
