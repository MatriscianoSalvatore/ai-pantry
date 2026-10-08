#!/usr/bin/env python3
"""Precomputes the text embeddings for the EmbeddingGemma 2 zero-shot detector.

Same labels as MobileCLIP (scripts/ingredient_labels.txt, with the Italian names
of scripts/ingredient_names_it.txt and the same templates), embedded with
EmbeddingGemma 2 through LiteRT-LM, from the very .litertlm file the phone runs,
and written to app/src/main/assets/embeddinggemma/label_embeddings.json. Words
and photos meet as in a search: the label is the query ("task: search result |
query: a photo of …"), the crop of the photo the document.

The model isn't in git (388 MB) and the app doesn't deliver it: it is
downloaded from Hugging Face on the first run, into build/embeddinggemma/, and
copied onto the phone with adb (see the README).

With --check-image it runs the app's scan on a photo (same crops, scale and
threshold as ZeroShotScan.kt) and prints what it finds, to judge the labels.

Usage:
  python scripts/prepare_embeddinggemma_assets.py [--check-image fridge.png]

Dependencies: litert-lm-api (the app's LiteRT-LM version), numpy, pillow
"""

import argparse
import io
import json
import time
import urllib.request
from pathlib import Path

import numpy as np
import litert_lm
from litert_lm import EmbeddingEngine, EmbeddingOptions
from litert_lm._messages import ImageBytes

from prepare_clip_assets import TEMPLATES, load_italian_names, load_labels

ROOT = Path(__file__).resolve().parent.parent
OUT_FILE = ROOT / "app" / "src" / "main" / "assets" / "embeddinggemma" / "label_embeddings.json"
MODEL_FILE = ROOT / "build" / "embeddinggemma" / "embeddinggemma-2-text-vision-440m.litertlm"
MODEL_URL = (
    "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm"
    "/resolve/main/embeddinggemma-2-text-vision-440m.litertlm"
)

QUERY_PREFIX = "task: search result | query: "

# As in EmbeddingGemmaIngredientDetector.kt and ZeroShotScan.kt
VISION_TOKENS = 70
MAX_CROP_SIDE = 512
CROP_SCALES = (0.5, 0.33)
LOGIT_SCALE = 100
MIN_PROB = 0.22
TOP_PER_CROP = 3
MAX_RESULTS = 15


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-image", type=Path, action="append", default=[])
    args = parser.parse_args()

    ensure_model()
    labels, displays, distractors = load_labels()
    displays_it = load_italian_names(displays, distractors)
    print(f"{len(labels)} label ({sum(distractors)} distrattori)")

    engine = EmbeddingEngine(
        str(MODEL_FILE),
        backend=litert_lm.Backend.CPU(),
        cache_dir=str(MODEL_FILE.parent),
        vision_tokens_per_image=VISION_TOKENS,
    )
    with engine:
        start = time.time()
        embeddings = np.stack([embed_label(engine, label) for label in labels])
        print(f"Etichette calcolate in {time.time() - start:.0f} s")

        OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
        OUT_FILE.write_text(json.dumps({
            "model": "EmbeddingGemma 2 text-vision 440M",
            "dim": int(embeddings.shape[1]),
            "labels": labels,
            "display": displays,
            "display_it": displays_it,
            "distractors": distractors,
            "embeddings": [[round(float(v), 5) for v in e] for e in embeddings],
        }, separators=(",", ":")))
        print(f"→ {OUT_FILE.relative_to(ROOT)} ({OUT_FILE.stat().st_size // 1024} KB, dim={embeddings.shape[1]})")

        for image in args.check_image:
            check(engine, image, embeddings, displays, distractors)


def ensure_model() -> None:
    if MODEL_FILE.exists():
        return
    print(f"Scarico EmbeddingGemma 2 ({MODEL_URL}) …")
    MODEL_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = MODEL_FILE.with_suffix(".part")
    urllib.request.urlretrieve(MODEL_URL, tmp)
    tmp.rename(MODEL_FILE)
    print(f"→ {MODEL_FILE.relative_to(ROOT)} ({MODEL_FILE.stat().st_size // (1024 * 1024)} MB)")


def embed_label(engine: EmbeddingEngine, label: str) -> np.ndarray:
    """The mean of the templates' embeddings, as for CLIP, normalised again."""
    vectors = np.stack([
        np.array(engine.compute_embedding(QUERY_PREFIX + t.format(label)).embedding, dtype=np.float32)
        for t in TEMPLATES
    ])
    mean = vectors.mean(axis=0)
    return mean / np.linalg.norm(mean)


def check(engine, image_path: Path, embeddings, displays, distractors) -> None:
    """The app's scan of [image_path]: what it would find, with its scores."""
    from PIL import Image

    image = Image.open(image_path).convert("RGB")
    crops = list(crops_of(image))
    start = time.time()
    best = np.zeros(len(displays))
    for crop in crops:
        if crop.width > MAX_CROP_SIDE:
            crop = crop.resize((MAX_CROP_SIDE, MAX_CROP_SIDE), Image.BILINEAR)
        buffer = io.BytesIO()
        crop.save(buffer, format="JPEG", quality=90)
        result = engine.compute_embedding(
            ImageBytes(buffer.getvalue()),
            EmbeddingOptions(normalize=True, vision_tokens_per_image=VISION_TOKENS),
        )
        logits = embeddings @ np.array(result.embedding, dtype=np.float32) * LOGIT_SCALE
        probs = np.exp(logits - logits.max())
        probs /= probs.sum()
        for i in np.argsort(-probs)[:TOP_PER_CROP]:
            best[i] = max(best[i], probs[i])
    found = {}
    for i, score in enumerate(best):
        if not distractors[i] and score >= MIN_PROB:
            found[displays[i]] = max(found.get(displays[i], 0), score)
    ranked = sorted(found.items(), key=lambda kv: -kv[1])[:MAX_RESULTS]
    print(f"\n{image_path.name}: {len(crops)} ritagli in {time.time() - start:.1f} s")
    print(", ".join(f"{name} {score:.2f}" for name, score in ranked))


def crops_of(image):
    """The crops of ZeroShotScan.cropsOf: two scales, 25% overlap, the last tile on the edge."""
    width, height = image.size
    for scale in CROP_SCALES:
        side = int(min(width, height) * scale)
        step = side * 3 // 4
        for y in edge_anchored_steps(height, side, step):
            for x in edge_anchored_steps(width, side, step):
                yield image.crop((x, y, x + side, y + side))


def edge_anchored_steps(extent: int, side: int, step: int) -> list[int]:
    if side >= extent:
        return [0]
    offsets = list(range(0, extent - side + 1, step))
    if offsets[-1] != extent - side:
        offsets.append(extent - side)
    return offsets


if __name__ == "__main__":
    main()
