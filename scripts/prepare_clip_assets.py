#!/usr/bin/env python3
"""Precomputa gli embedding testuali per il detector zero-shot MobileCLIP-S2.

Legge scripts/ingredient_labels.txt, calcola l'embedding di ogni label con il
text encoder open_clip (stesso spazio dell'image encoder TFLite bundlato negli
assets) e scrive app/src/main/assets/clip/label_embeddings.json.

Con --verify-image controlla anche la parità tra l'image encoder TFLite e
quello open_clip su un'immagine di test (cosine > 0.99 atteso).

Uso:
  python scripts/prepare_clip_assets.py [--verify-image path/to/test.jpg]

Dipendenze: torch, open_clip_torch, ai-edge-litert, pillow, numpy
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch
import open_clip

ROOT = Path(__file__).resolve().parent.parent
LABELS_FILE = ROOT / "scripts" / "ingredient_labels.txt"
OUT_FILE = ROOT / "app" / "src" / "main" / "assets" / "clip" / "label_embeddings.json"
TFLITE_FILE = ROOT / "app" / "src" / "main" / "assets" / "clip" / "mobileclip_s2_image.tflite"

MODEL = "MobileCLIP-S2"
PRETRAINED = "datacompdr"

# L'image encoder TFLite non sta in git (137MB > limite GitHub): si scarica
# da Hugging Face al primo run
TFLITE_URL = "https://huggingface.co/plainhub/mobileclip-s2-tflite/resolve/main/mobileclip_s2_image.tflite"

# Più template mediati migliorano lo zero-shot (pratica standard CLIP)
TEMPLATES = [
    "a photo of {}",
    "a photo of {} in a refrigerator",
    "a photo of {} on a pantry shelf",
    "a close-up photo of {}",
]


def load_labels() -> tuple[list[str], list[str], list[bool]]:
    """Ritorna (labels, display_names, distractor_flags).

    Il prefisso ~ marca i distrattori; la sintassi "label|display" fa
    matchare la label specifica ("lactose-free milk") ma riporta in UI il
    nome semplice ("milk").
    """
    labels, displays, distractors = [], [], []
    for line in LABELS_FILE.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        is_distractor = line.startswith("~")
        label, _, display = line.lstrip("~").partition("|")
        labels.append(label.strip())
        displays.append(display.strip() or label.strip())
        distractors.append(is_distractor)
    return labels, displays, distractors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-image", type=Path, default=None)
    args = parser.parse_args()

    ensure_tflite_model()

    labels, displays, distractors = load_labels()
    print(f"{len(labels)} label ({sum(distractors)} distrattori) da {LABELS_FILE.name}")

    model, _, preprocess = open_clip.create_model_and_transforms(MODEL, pretrained=PRETRAINED)
    model.eval()
    tokenizer = open_clip.get_tokenizer(MODEL)

    with torch.no_grad():
        embeddings = []
        for label in labels:
            tokens = tokenizer([t.format(label) for t in TEMPLATES])
            text = model.encode_text(tokens)
            text = text / text.norm(dim=-1, keepdim=True)
            mean = text.mean(dim=0)
            mean = mean / mean.norm()
            embeddings.append(mean.tolist())

    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUT_FILE.write_text(json.dumps({
        "model": f"{MODEL}/{PRETRAINED}",
        "dim": len(embeddings[0]),
        "labels": labels,
        "display": displays,
        "distractors": distractors,
        "embeddings": [[round(v, 6) for v in e] for e in embeddings],
    }))
    size_kb = OUT_FILE.stat().st_size // 1024
    print(f"→ {OUT_FILE.relative_to(ROOT)} ({size_kb} KB, dim={len(embeddings[0])})")

    if args.verify_image:
        verify(model, preprocess, args.verify_image)


def ensure_tflite_model() -> None:
    if TFLITE_FILE.exists():
        return
    import urllib.request
    print(f"Scarico l'image encoder ({TFLITE_URL}) …")
    TFLITE_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = TFLITE_FILE.with_suffix(".tflite.part")
    urllib.request.urlretrieve(TFLITE_URL, tmp)
    tmp.rename(TFLITE_FILE)
    print(f"→ {TFLITE_FILE.relative_to(ROOT)} ({TFLITE_FILE.stat().st_size // (1024 * 1024)} MB)")


def verify(model, preprocess, image_path: Path) -> None:
    """Parità open_clip ↔ TFLite sull'image encoder."""
    from PIL import Image
    from ai_edge_litert.interpreter import Interpreter

    image = Image.open(image_path).convert("RGB")
    with torch.no_grad():
        ref = model.encode_image(preprocess(image).unsqueeze(0))
        ref = (ref / ref.norm(dim=-1, keepdim=True)).numpy()[0]

    interpreter = Interpreter(model_path=str(TFLITE_FILE))
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]
    out = interpreter.get_output_details()[0]
    print(f"TFLite input shape={inp['shape'].tolist()} dtype={inp['dtype'].__name__}")

    # Stesso preprocessing del training: shortest-edge 256 bilineare + center
    # crop, [0,1] senza normalizzazione (config ufficiale open_clip)
    w, h = image.size
    scale = 256 / min(w, h)
    image = image.resize((round(w * scale), round(h * scale)), Image.BILINEAR)
    w, h = image.size
    left, top = (w - 256) // 2, (h - 256) // 2
    image = image.crop((left, top, left + 256, top + 256))
    arr = np.asarray(image, dtype=np.float32) / 255.0

    shape = inp["shape"].tolist()
    if shape[1] == 3:  # NCHW
        arr = arr.transpose(2, 0, 1)
    interpreter.set_tensor(inp["index"], arr[np.newaxis, ...])
    interpreter.invoke()
    emb = interpreter.get_tensor(out["index"])[0].astype(np.float32)
    emb = emb / np.linalg.norm(emb)

    cos = float(np.dot(ref, emb))
    print(f"cosine(open_clip, tflite) = {cos:.4f}")
    if cos < 0.99:
        print("ATTENZIONE: parità sotto 0.99 — verificare preprocessing/export", file=sys.stderr)
        sys.exit(1)
    print("Parità OK")


if __name__ == "__main__":
    main()
