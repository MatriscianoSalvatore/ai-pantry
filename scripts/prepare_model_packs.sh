#!/usr/bin/env bash
# Splitta il modello Gemma 3n E2B nei 3 AI pack (Play for On-device AI).
#
# Il .task va scaricato una tantum da chi builda (richiede account HF con
# licenza Gemma accettata):
#   https://huggingface.co/google/gemma-3n-E2B-it-litert-preview
#
# Uso: ./scripts/prepare_model_packs.sh path/to/gemma-3n-E2B-it-int4.task
set -euo pipefail

MODEL="${1:?Uso: $0 path/to/gemma-3n-E2B-it-int4.task}"
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
  # Nome unico per pack: bundletool rifiuta entry omonime con contenuto diverso
  DEST="$ROOT/llm_pack_$i/src/main/assets/model.part$i"
  mkdir -p "$(dirname "$DEST")"
  rm -f "$ROOT/llm_pack_$i/src/main/assets/model.part"*
  cp "$f" "$DEST"
  echo "  → ${DEST#"$ROOT"/} ($(stat -f%z "$DEST" 2>/dev/null || stat -c%s "$DEST") byte)"
  i=$((i+1))
done

echo "Fatto. Ora: ./gradlew bundleRelease (o bundleDebug per il test locale con bundletool)"
