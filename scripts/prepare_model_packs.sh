#!/usr/bin/env bash
# Splitta il modello Gemma 4 E2B (runtime LiteRT-LM) nei 3 AI pack (Play for
# On-device AI).
#
# Il .litertlm (gemma-4-E2B-it.litertlm, licenza Apache 2.0, nessun login HF
# richiesto) va scaricato una tantum da chi builda:
#   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
#
# Uso: ./scripts/prepare_model_packs.sh path/to/gemma-4-E2B-it.litertlm
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
  # Nome unico per pack: bundletool rifiuta entry omonime con contenuto diverso
  DEST="$ROOT/llm_pack_$i/src/main/assets/model.part$i"
  mkdir -p "$(dirname "$DEST")"
  rm -f "$ROOT/llm_pack_$i/src/main/assets/model.part"*
  cp "$f" "$DEST"
  echo "  → ${DEST#"$ROOT"/} ($(stat -f%z "$DEST" 2>/dev/null || stat -c%s "$DEST") byte)"
  i=$((i+1))
done

echo "Fatto. Ora: ./gradlew bundlePlayRelease (o bundlePlayDebug per il test locale con bundletool)"
