#!/usr/bin/env bash
# Splits the Gemma 4 E2B model (LiteRT-LM runtime) into the 3 AI packs (Play for
# On-device AI).
#
# The .litertlm (gemma-4-E2B-it.litertlm, Apache 2.0 license, no HF login
# needed) has to be downloaded once by whoever builds:
#   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
#
# Usage: ./scripts/prepare_model_packs.sh path/to/gemma-4-E2B-it.litertlm
set -euo pipefail

MODEL="${1:?Uso: $0 path/to/gemma-4-E2B-it.litertlm}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PARTS=3

SIZE=$(stat -f%z "$MODEL" 2>/dev/null || stat -c%s "$MODEL")
CHUNK=$(( (SIZE + PARTS - 1) / PARTS ))

echo "Modello: $MODEL ($SIZE byte) → $PARTS chunk da max $CHUNK byte"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
split -b "$CHUNK" "$MODEL" "$TMP/chunk_"

i=0
for f in "$TMP"/chunk_*; do
  # Unique name per pack: bundletool rejects same-name entries with different content
  DEST="$ROOT/llm_pack_$i/src/main/assets/model.part$i"
  mkdir -p "$(dirname "$DEST")"
  rm -f "$ROOT/llm_pack_$i/src/main/assets/model.part"*
  cp "$f" "$DEST"
  echo "  → ${DEST#"$ROOT"/} ($(stat -f%z "$DEST" 2>/dev/null || stat -c%s "$DEST") byte)"
  i=$((i+1))
done

echo "Fatto. Ora: ./gradlew bundlePlayRelease (o bundlePlayDebug per il test locale con bundletool)"
