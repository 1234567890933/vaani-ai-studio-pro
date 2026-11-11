#!/usr/bin/env bash
# Download a quantised Whisper ggml model for on-device ASR.
#   bash scripts/fetch_models.sh            -> small q5_1 (recommended default)
#   bash scripts/fetch_models.sh base-q5    -> base q5_1 (4 GB devices)
#   bash scripts/fetch_models.sh small-q8
set -euo pipefail

case "${1:-small-q5}" in
  small-q5) FILE="ggml-small-q5_1.bin" ;;
  base-q5)  FILE="ggml-base-q5_1.bin" ;;
  small-q8) FILE="ggml-small-q8_0.bin" ;;
  *) echo "usage: $0 [small-q5|base-q5|small-q8]"; exit 1 ;;
esac

OUT_DIR="${2:-models/whisper}"
mkdir -p "$OUT_DIR"
URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${FILE}"

echo "Downloading ${FILE} ..."
curl -L --retry 3 -C - -o "${OUT_DIR}/${FILE}" "$URL"
echo "Saved to ${OUT_DIR}/${FILE}"
echo
echo "Next: push it to the device"
echo "  adb push ${OUT_DIR}/${FILE} /sdcard/Download/"
echo "then use 'Import from device' in the app's Models screen."
