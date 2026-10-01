#!/usr/bin/env bash
# Embeds Gemma 4 E2B (LiteRT-LM runtime) in the assets of the `beta` flavor (APK
# handed out manually to testers). Same model as the play flavor, different
# delivery channel. Chunked because AGP doesn't package single assets >2 GB.
#
# Usage: ./scripts/prepare_beta_model.sh path/to/gemma-4-E2B-it.litertlm
set -euo pipefail

MODEL="${1:?Uso: $0 path/to/gemma4-e2b-it.litertlm}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/beta/assets/llm"
PARTS=3

SIZE=$(stat -f%z "$MODEL" 2>/dev/null || stat -c%s "$MODEL")
CHUNK=$(( (SIZE + PARTS - 1) / PARTS ))

echo "Modello: $MODEL ($SIZE byte) → $PARTS chunk da max $CHUNK byte"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
split -b "$CHUNK" "$MODEL" "$TMP/chunk_"

rm -rf "$ASSETS"
mkdir -p "$ASSETS"
i=0
for f in "$TMP"/chunk_*; do
  cp "$f" "$ASSETS/model.part$i.litertlm"
  echo "  → app/src/beta/assets/llm/model.part$i.litertlm ($(stat -f%z "$ASSETS/model.part$i.litertlm" 2>/dev/null || stat -c%s "$ASSETS/model.part$i.litertlm") byte)"
  i=$((i+1))
done

echo "Fatto. Ora: ./gradlew assembleBetaDebug (o assembleBetaRelease)"
