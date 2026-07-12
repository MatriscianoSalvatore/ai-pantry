# AI Pantry – Cook from Your Fridge 🥕📷

Demo Android (Droidcon) di una pipeline AI **completamente on-device**: fotografi frigo e dispensa, la computer vision riconosce gli ingredienti, un LLM locale genera ricette che usano solo ciò che hai.

**L'inferenza è 100% offline: le foto non lasciano mai il dispositivo.** La rete serve solo per il download one-time del modello, gestito in-app — nessun `adb push`, nessun setup manuale.

## Flusso demo

1. **Scan** → fotografa il frigorifero (chip *Fridge*), poi "Scan another" per la dispensa (*Pantry*).
2. Gli ingredienti rilevati (con quantità approssimative e confidenza) finiscono nell'inventario locale (Room).
3. **What can I cook?** → l'LLM genera 5 ricette ordinate per copertura degli ingredienti, con tempo, difficoltà, ingredienti usati/mancanti, istruzioni passo-passo e varianti.

## Architettura

```
CameraX → Image capture
        → Ingredient detection ── Gemma 3n vision (LiteRT)      [modello attivo scaricato]
        │                      └─ EfficientNet-Lite2 (bundlato) [fallback zero-setup]
        → Room Database
        → Recipe generation ──── Gemma 3n / Qwen (LiteRT)
        → Jetpack Compose UI
```

Ogni feature segue Clean Architecture + MVI (`data / domain / presentation`), DI con Koin:

- `capture/` — CameraX, `SmartIngredientDetector` (LLM vision → classificatore bundlato → demo)
- `inventory/` — Room, inventario ingredienti
- `recipes/` — `SmartRecipeGenerator` (LLM attivo → demo)
- `aisetup/` — schermata di provisioning modelli (download, resume, selezione)
- `core/data/ai/` — `LlmCatalog`, `ModelRepository` (downloader con resume), `LlmEngineHolder` (cache motore LiteRT)

## Provisioning dei modelli (in-app, senza setup manuale)

Dalla home → icona 🤖 → **On-device AI**. Catalogo:

| Modello | Dimensione | Vision | Autenticazione |
|---------|-----------|--------|----------------|
| **Gemma 3n E4B** (default) | 4.4 GB | ✅ detection ingredienti dalla foto | token HF + accettazione licenza Gemma |
| Gemma 3n E2B | 3.1 GB | ✅ | token HF + accettazione licenza Gemma |
| Qwen2.5 1.5B | 1.6 GB | ❌ (usa il classificatore bundlato) | nessuna |

- Il download è **in-app con resume** (si può mettere in pausa e riprendere).
- Per i Gemma: accetta la licenza su [huggingface.co](https://huggingface.co/google/gemma-3n-E4B-it-litert-preview), crea un token di lettura e incollalo nella schermata AI.
- La **detection ingredienti funziona out-of-the-box** anche senza download: EfficientNet-Lite2 (~24 MB) è bundlato nell'APK e riconosce frutta/verdura/cibi (classi ImageNet, mapping in `IngredientLabels`).
- Il demo mode (risultati dello script Droidcon, latenze simulate) resta come ultima rete di sicurezza: la demo non può fallire sul palco. La UI mostra sempre il motore attivo.
- Convenienza dev: un modello in `/data/local/tmp/llm/<filename>` ha priorità su quello scaricato.

## Pubblicazione su Play Store

Per distribuire con AI funzionante senza chiedere token agli utenti:

1. **Hosting proprio del modello** (consigliato): carica il `.task` su un tuo CDN (GCS/S3/R2) e cambia la `url` in `LlmCatalog`. La [Gemma Terms of Use](https://ai.google.dev/gemma/terms) consente la ridistribuzione con passthrough delle condizioni d'uso e notice; Qwen è Apache 2.0 senza vincoli.
2. **Google Play — Play for On-device AI** (beta): delivery dei modelli custom via AI pack di Play Asset Delivery, con download differenziale gestito dallo store.
3. Il classificatore bundlato garantisce comunque la feature di riconoscimento al primo avvio, prima ancora del download LLM.

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Richiede JDK 17+, Android SDK 36. `minSdk 31`, device con camera. Per i modelli Gemma: 6+ GB di RAM consigliati, accelerazione GPU/NPU dove disponibile.

## Stack

Kotlin 2.2 · Jetpack Compose (M3) · CameraX · MediaPipe Tasks (Vision + GenAI/LiteRT) · Room · Koin · Navigation Compose · kotlinx-serialization
