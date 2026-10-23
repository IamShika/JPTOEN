#!/usr/bin/env bash
# Publishes ONE optional speech recognizer on your repo's Releases page so the app can
# download it. Usage: publish-asr-model.sh <id>
# It does nothing if that release already exists. Files are renamed to the simple names the
# app expects (see AsrModels.kt). If a model's files are not found, the script prints what
# it found and fails; the workflow step is allowed to fail without stopping the APK build.
set -euo pipefail

ID="$1"
BASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
REL="model-${ID}"

if gh release view "$REL" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "$REL already exists, nothing to do."
  exit 0
fi

# Pick the archive name for this model.
case "$ID" in
  sensevoice)     ARCHIVE="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17" ;;
  whisper-small)  ARCHIVE="sherpa-onnx-whisper-small" ;;
  qwen3asr)       ARCHIVE="sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25" ;;
  funasr)         ARCHIVE="sherpa-onnx-funasr-nano-int8-2025-12-30" ;;
  *) echo "Unknown model id: $ID"; exit 1 ;;
esac

WORK=$(mktemp -d)
cd "$WORK"
curl -fL --retry 3 -o model.tar.bz2 "$BASE/$ARCHIVE.tar.bz2"
tar xjf model.tar.bz2
cd "$ARCHIVE"
echo "Files found:"
find . -type f -not -path './test_wavs/*' -printf '%P  %s bytes\n'

OUT="$WORK/out"
mkdir -p "$OUT"

# Copy one file to the output under a new name. Fails loudly if it is missing.
take() { # take <path-or-glob> <new-name>
  local f
  f=$(ls $1 2>/dev/null | head -n 1 || true)
  if [ -z "$f" ]; then echo "MISSING: $1"; exit 1; fi
  cp -v "$f" "$OUT/$2"
}

case "$ID" in
  sensevoice)
    take "model.int8.onnx" model.int8.onnx
    take "tokens.txt" tokens.txt
    ;;
  whisper-small)
    take "*encoder.int8.onnx" encoder.onnx
    take "*decoder.int8.onnx" decoder.onnx
    take "*tokens.txt" tokens.txt
    ;;
  qwen3asr)
    take "conv_frontend.onnx" conv_frontend.onnx
    take "encoder.int8.onnx" encoder.int8.onnx
    take "decoder.int8.onnx" decoder.int8.onnx
    take "tokenizer/merges.txt" tokenizer_merges.txt
    take "tokenizer/vocab.json" tokenizer_vocab.json
    take "tokenizer/tokenizer_config.json" tokenizer_config.json
    ;;
  funasr)
    take "embedding.int8.onnx" embedding.int8.onnx
    take "encoder_adaptor.int8.onnx" encoder_adaptor.int8.onnx
    take "llm.int8.onnx" llm.int8.onnx
    take "Qwen3-0.6B/merges.txt" tokenizer_merges.txt
    take "Qwen3-0.6B/tokenizer.json" tokenizer_tokenizer.json
    take "Qwen3-0.6B/vocab.json" tokenizer_vocab.json
    ;;
esac

gh release create "$REL" \
  --repo "$GITHUB_REPOSITORY" \
  --prerelease \
  --title "Optional recognizer: $ID (the app downloads these, do not delete)" \
  --notes "Speech recognizer files repackaged from the sherpa-onnx project. The app downloads them when you choose this model in Settings." \
  "$OUT"/*
echo "Published $REL"
