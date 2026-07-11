# AI Pantry – Cook from Your Fridge 🥕📷

Demo Android (Droidcon) di una pipeline AI **completamente on-device**: fotografi frigo e dispensa, la computer vision riconosce gli ingredienti, un LLM locale genera ricette che usano solo ciò che hai.

**Nessuna connessione richiesta. Le foto non lasciano mai il dispositivo.**

## Flusso demo

1. **Scan** → fotografa il frigorifero (chip *Fridge*), poi "Scan another" per la dispensa (*Pantry*).
2. Gli ingredienti rilevati (con quantità approssimative e confidenza) finiscono nell'inventario locale (Room).
3. **What can I cook?** → l'LLM genera 5 ricette ordinate per copertura degli ingredienti, con tempo, difficoltà, ingredienti usati/mancanti, istruzioni passo-passo e varianti.

## Architettura

```
CameraX → Image Analysis → Image Classifier (MediaPipe/LiteRT)
        → Detected ingredients → Room Database
        → Gemma 3n (LiteRT) → Recipe generation → Jetpack Compose UI
```

Ogni feature segue Clean Architecture + MVI (`data / domain / presentation`), DI con Koin:

- `capture/` — CameraX, `IngredientDetector` (MediaPipe → fallback demo)
- `inventory/` — Room, inventario ingredienti
- `recipes/` — `RecipeGenerator` (Gemma 3n via MediaPipe GenAI → fallback demo)

## Modalità demo vs modelli reali

L'app funziona **out-of-the-box in demo mode**: senza modelli sul device, detector e LLM restituiscono i risultati dello script demo (con latenze simulate). Se i modelli sono presenti, vengono usati automaticamente (`SmartIngredientDetector` / `SmartRecipeGenerator`), con fallback silenzioso alla demo in caso di errore — a prova di palco.

### Abilitare Gemma 3n reale

1. Scarica un modello Gemma 3n in formato `.task` per LiteRT (es. `gemma-3n-E2B-it-int4.task` da [Kaggle](https://www.kaggle.com/models/google/gemma-3n) o dalla collection [litert-community su Hugging Face](https://huggingface.co/litert-community)).
2. Push sul device:
   ```bash
   adb shell mkdir -p /data/local/tmp/llm
   adb push gemma-3n-E2B-it-int4.task /data/local/tmp/llm/gemma-3n.task
   ```
   (in alternativa: `files/models/gemma-3n.task` nella sandbox dell'app)

### Abilitare il classificatore ingredienti reale

1. Procurati un classificatore immagini `.tflite` (es. EfficientNet-Lite food/ImageNet dal [MediaPipe Model Zoo](https://ai.google.dev/edge/mediapipe/solutions/vision/image_classifier)).
2. Copialo in `files/models/ingredients.tflite` nella sandbox dell'app:
   ```bash
   adb shell mkdir -p /data/data/com.smatrisciano.aipantry/files/models
   adb push classifier.tflite /data/data/com.smatrisciano.aipantry/files/models/ingredients.tflite
   ```
3. La mappatura label → ingrediente è in `IngredientLabels` (`capture/data/MediaPipeIngredientDetector.kt`).

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Richiede JDK 17+, Android SDK 36. `minSdk 31`, device con camera (per Gemma reale: 6+ GB RAM consigliati, accelerazione GPU/NPU dove disponibile).

## Stack

Kotlin 2.2 · Jetpack Compose (M3) · CameraX · MediaPipe Tasks (Vision + GenAI/LiteRT) · Room · Koin · Navigation Compose · kotlinx-serialization
