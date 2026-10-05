# AI Pantry – Cucina con quello che hai in frigo 🥕📷

🇬🇧 [English](README.md) · 🇮🇹 Italiano

App Android dimostrativa del talk *AI On-Device*: una pipeline di intelligenza artificiale **interamente on-device**. Fotografi frigo e dispensa, l'app riconosce gli ingredienti e genera ricette che usano solo quello che hai. Tre motori AI collaborano, ognuno sul lavoro per cui è stato costruito:

- **Gemini Nano** (AICore, tramite ML Kit GenAI) — riconoscimento degli ingredienti sui dispositivi che lo supportano: zero MB nell'APK
- **MobileCLIP-S2** (LiteRT) — riconoscimento zero-shot su *qualsiasi* dispositivo Android 12+: il fallback universale e il motore di default
- **Gemma 4 E2B** (LiteRT-LM) — generazione delle ricette: l'unico compito davvero generativo

**L'inferenza è al 100% on-device: foto e prompt non lasciano mai il dispositivo.** Il codice dell'app non fa nessuna chiamata di rete; il permesso `INTERNET` che trovate nel manifest finale lo aggiunge la libreria di telemetria di ML Kit (`com.google.android.datatransport`), che invia metriche d'uso, non foto né prompt (se pubblicate su Play, dichiaratelo nel modulo Data safety). I modelli arrivano attraverso il canale di installazione: Google Play (AI pack) oppure incorporati nell'APK, nella build di debug beta.

## Il talk

Le slide (in italiano, con le note dello speaker) sono in questa repo: [AI On-Device - ita.pptx](AI%20On-Device%20-%20ita.pptx). Il talk ripercorre la strada che ha portato a questa architettura:

1. **Gemini Nano** era la scelta ovvia, ma gira solo sui flagship più recenti: un bonus, mai la base.
2. **Gemma 4 E2B per fare tutto** (riconoscimento e ricette) funzionava, ma una singola scansione richiedeva minuti su un Pixel 7: il prefill multimodale e il decode un token alla volta costano troppo su un telefono.
3. **MobileCLIP-S2 zero-shot**: per riconoscere non serve un modello che scrive, ma uno che confronta. Stesso telefono, stessa foto: da minuti a secondi.
4. **Gemma torna** per l'unico compito che ha davvero bisogno di un LLM: generare le ricette, solo testo.

La lezione: prima di chiedervi come rendere un modello più veloce, chiedetevi se è il modello giusto.

## Flusso della demo

Il flusso completo, con l'app in italiano, è registrato nel video [demo.mp4](demo.mp4).

1. **Scansione** → fotografi il frigo (chip *Frigo*), poi "Altra scansione" per la dispensa (*Dispensa*). Invece di scattare una foto puoi sceglierne una dalla galleria. Mentre MobileCLIP lavora, sulla foto si vedono come griglia le zone che sta analizzando, una passata dopo l'altra (prima larga, poi fine), con il conteggio delle zone analizzate, e ogni ingrediente compare sulla foto appena viene riconosciuto. Il foglio dei risultati mostra quale motore ha fatto il lavoro; una volta chiuso, si riapre dalla pillola "Rivedi N ingredienti". Le scansioni si sommano: dopo la dispensa il foglio elenca gli ingredienti di tutte e due, e "Aggiungi a frigo e dispensa" li salva tutti.
2. Gli ingredienti riconosciuti finiscono nell'inventario locale (Room). Con MobileCLIP ognuno ha una confidenza e tutto ciò che sta sotto il 22% viene scartato; Gemini Nano non restituisce una confidenza, quindi i suoi risultati mostrano un valore predefinito fisso.
3. **Cosa posso cucinare?** → Gemma scrive 4 ricette con tempo, difficoltà e ingredienti usati, ognuna a schermo appena è scritta; finché la prima non è pronta, una schermata di avanzamento mostra il caricamento del modello e poi la scrittura. La prima lista per l'inventario attuale viene scritta in anticipo in background mentre l'app è aperta, quindi spesso è già pronta, o quasi, quando la chiedi. Gli ingredienti mancanti vengono ricontrollati dall'app confrontandoli con l'inventario (da solo il modello non è affidabile su questo) e le ricette con più ingredienti mancanti finiscono in fondo. Aprendo una ricetta se ne generano su richiesta le quantità, i passaggi e le varianti, anche questi mostrati pezzo per pezzo man mano che vengono scritti.
4. **Altre ricette**, in fondo alla lista, ne aggiunge 4 nuove sotto; **Rigenera** (in alto a destra) sostituisce la lista. Ogni nuovo giro usa due terzi diversi dell'inventario, e i piatti che la lista ha già vengono scartati, anche con un altro nome. Finché l'app è aperta le liste restano in memoria per insieme di ingredienti: cambi l'inventario e ottieni una lista nuova, lo rimetti com'era e torna quella vecchia.

Una pagina di diagnostica nascosta (tocca 6 volte il logo nella home) mostra stato e margine termico, frequenze della CPU e throttling, batteria, memoria, stato del modello e liste di ricette in memoria, che si possono svuotare.

L'app segue la lingua del dispositivo: **italiano se il telefono è in italiano, inglese altrimenti**. Vale per la UI (`res/values-it`), per i prompt di riconoscimento e delle ricette e per i nomi degli ingredienti riconosciuti da MobileCLIP, quindi anche le ricette escono nella stessa lingua. Le chiavi JSON nei prompt restano in inglese: sono il contratto con il parser.

## Architettura

```
CameraX / Photo picker → Image capture
        → Ingredient detection ── AdaptiveIngredientDetector
        │       ├─ Gemini Nano (AICore · ML Kit GenAI) se checkStatus() == AVAILABLE
        │       └─ MobileCLIP-S2 zero-shot (LiteRT) ovunque — e rete di sicurezza se Nano fallisce
        → Room Database
        → Recipe generation ──── Gemma 4 E2B solo testo (LiteRT-LM)
        │                        fase 1: lista ricette · fase 2: istruzioni su richiesta
        → Jetpack Compose UI

flavor play:  Google Play ──(AI pack ×3, fast-follow)──▶ ModelRepository ──assemble──▶ .litertlm
flavor beta:  asset dell'APK ──(chunk ×3 nell'APK)─────▶ ModelRepository ──assemble──▶ .litertlm
MobileCLIP:   asset dell'APK (~140 MB, in gitignore — lo scarica scripts/prepare_clip_assets.py)
```

Ogni feature segue Clean Architecture + MVI (`data / domain / presentation`), DI con Koin:

- `capture/` — CameraX + photo picker; `AdaptiveIngredientDetector` (scelta a runtime), `NanoIngredientDetector` (ML Kit GenAI Prompt API), `ClipZeroShotIngredientDetector` (LiteRT), `LlmVisionIngredientDetector` (riconoscimento con la visione di Gemma: presente nel codice come terza via, fuori dal percorso di default — minuti per scansione quando finisce su CPU)
- `inventory/` — Room, inventario degli ingredienti, banner di stato del modello nella home, risoluzione delle emoji per parola chiave
- `recipes/` — `RecipeRepositoryImpl` (liste per insieme di ingredienti, una generazione alla volta con prima quello che è a schermo, lavoro in anticipo in background, ripresa dopo un'interruzione), `LlmRecipeGenerator` (generazione in due fasi in streaming + tentativi ripetuti), `RecipeTitleRules` (titoli italiani sistemati, abbinamenti strani scartati), `SameDish` (piatti ripetuti), `RecipeJsonParser` tollerante
- `diagnostics/` — la pagina nascosta: calore e throttling, batteria, memoria, modello, cache delle ricette
- `core/data/ai/` — `LlmCatalog`, `ModelSource` (AiPacks | BundledAssets), `ModelRepository` (provisioning automatico), `LlmEngineHolder` (cache dell'engine + politica GPU/CPU)
- `core/domain/` — `AppLanguage` (italiano o inglese, dalla lingua del dispositivo), letto da prompt e detector

## Riconoscimento degli ingredienti

`AdaptiveIngredientDetector` sceglie il motore **a ogni scansione**:

**Gemini Nano** (dispositivi supportati dalla Prompt API di ML Kit GenAI: serie Pixel 9/10/11 esclusi i modelli "a", Galaxy S26 e qualche decina di altri flagship recenti; il Galaxy S25 ha Nano solo per le API GenAI già pronte, non per la Prompt API) — prompt multimodale foto+testo tramite la Prompt API di ML Kit GenAI (`com.google.mlkit:genai-prompt`, ancora in beta). La disponibilità si scopre a runtime: `checkStatus()` a ogni utilizzo; se il modello è scaricabile, il download parte in background e nel frattempo si usa il fallback. Anche quando lo stato è `AVAILABLE`, AICore può rifiutare una richiesta (quota per app, inferenza consentita solo all'app in primo piano), e Nano non è supportato sui dispositivi con bootloader sbloccato né sull'emulatore: qualsiasi eccezione di Nano fa ripiegare silenziosamente su CLIP.

**MobileCLIP-S2 zero-shot** (ovunque, Android 12+, incluso il default) — l'image encoder (~140 MB, LiteRT) calcola gli embedding di ritagli della foto a più scale (una griglia al 50% e al 33% del lato corto, con un pool di interpreter in parallelo) e li confronta con la similarità del coseno con gli embedding testuali di **863 ingredienti + 12 etichette "distrattore"**, precalcolati offline. Il "classificatore" è un file di testo:

- `scripts/ingredient_labels.txt` — un'etichetta per riga; sintassi `etichetta|nome mostrato` per gli alias ("lactose-free milk|milk"), prefisso `~` per i distrattori (assorbono i ritagli senza cibo, e i cibi che non sono ingredienti da ricetta come gli snack, e non vengono mai riportati)
- `scripts/ingredient_names_it.txt` — il nome italiano di ogni nome mostrato (`english|italiano`), usato sui dispositivi in italiano. Il matching resta sulle etichette inglesi, perché il text encoder è addestrato su didascalie in inglese; lo script si ferma se manca una traduzione
- `scripts/prepare_clip_assets.py` — scarica l'encoder da Hugging Face se manca (è in gitignore), calcola gli embedding con open_clip (4 template mediati) e scrive `assets/clip/label_embeddings.json` (~4,5 MB); con `--verify-image` verifica la parità open_clip ↔ TFLite (atteso ≥0,99)

Aggiungere un ingrediente = aggiungere una riga (e il suo nome italiano) e rilanciare lo script. Soglia di confidenza 22%, massimo 15 risultati per scansione. CLIP classifica ma non conta, quindi l'app non mostra quantità: il campo resta nel modello dati (Nano lo compila). Il vocabolario è chiuso: CLIP riconosce solo quello che è elencato nel file.

## L'LLM: Gemma 4 E2B su LiteRT-LM, due canali di distribuzione

**Gemma 4 E2B, multimodale** (formato `.litertlm`, ~2,6 GB — quantizzazione mista 2/4/8 bit) per ogni flavor — mai un download HTTP dall'app:

| Flavor | Canale | Note |
|--------|--------|------|
| `play` | **Play for On-device AI**: 3 AI pack `fast-follow` (~0,85 GB ciascuno), riassemblati al primo avvio, pack rimossi dopo l'assemblaggio | Limiti di Play: 1,5 GB per pack, 4 GB in totale |
| `beta` | **Incorporato nell'APK** come 3 chunk negli asset (AGP non impacchetta asset >2 GB), riassemblati al primo avvio | APK da ~2,9 GB da distribuire ai tester; nessuna azione richiesta |

Al primo avvio l'app mostra "Preparing Gemma 4 E2B…" per qualche secondo o minuto (copia e riassemblaggio dei chunk) e poi è **pronta e offline per sempre**. Il modello va preparato una volta da chi fa la build (i chunk sono in gitignore):

```bash
# Il .litertlm (gemma-4-E2B-it.litertlm, Apache 2.0, non serve login su HF):
#   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
./scripts/prepare_model_packs.sh path/to/gemma-4-E2B-it.litertlm   # → AI pack (flavor play)
./scripts/prepare_beta_model.sh  path/to/gemma-4-E2B-it.litertlm   # → asset dell'APK (flavor beta)

# Encoder MobileCLIP + embedding delle etichette (una volta / quando cambia il vocabolario):
python3 scripts/prepare_clip_assets.py
```

Licenze: Gemma 4 è rilasciato con licenza **Apache 2.0**, quindi per ridistribuirlo (via Play o con APK ai tester) basta includere licenza e attribuzione. MobileCLIP è diverso: il codice è MIT, ma i **pesi sono sotto la Apple Machine Learning Research Model License (solo per ricerca, nessun uso commerciale)**, e questo vale anche per l'export TFLite della community che scarica `prepare_clip_assets.py`. Va bene per questa demo; per un prodotto commerciale va sostituito con un encoder in stile CLIP con licenza permissiva (per esempio SigLIP 2, Apache 2.0): la pipeline resta la stessa, cambiano solo il `.tflite` e gli embedding delle etichette.

### Backend GPU/CPU e resilienza

Il runtime LiteRT-LM parte **dalla GPU**. Una GPU non funzionante si manifesta in due modi: un **blocco** (una chiamata nativa che non si può interrompere) oppure un'**eccezione immediata** alla prima inferenza (per esempio senza OpenCL, dove l'inizializzazione dell'engine riesce comunque). `LlmEngineHolder` gestisce entrambi i casi — un watchdog con timeout su un job separato per i blocchi, la cattura delle eccezioni per il resto — e segna la GPU come guasta **in modo persistente** per quel modello: gli avvii successivi vanno direttamente sulla CPU (fino a 4 thread, per limitare calore e throttling) senza ripagare il tentativo. Una micro-inferenza di prova durante il warm-up attiva il fallback già all'avvio, così la prima generazione dell'utente non paga l'attesa. Le cache XNNPack che LiteRT-LM scrive accanto al modello (i pesi già disposti per la CPU) restano tra un avvio e l'altro: su un Pixel 7 l'engine è pronto in meno di 2 secondi invece di ~13.

La generazione delle ricette riprova automaticamente (fino a 4 tentativi in totale) prima di mostrare un errore: i modelli piccoli a volte producono JSON malformato, e una lista rimasta corta dopo aver scartato piatti strani o ripetuti riceve un altro tentativo che la completa (nel frattempo la UI mostra "Risposta non valida, nuovo tentativo…" o "Cerco ancora qualche idea…"); `RecipeJsonParser` e `DetectionJsonParser` sono tolleranti per costruzione.

## Per iniziare

> ⚠️ **I modelli non sono nella repo** (troppo grandi per git): i passi 2 e 3 sono obbligatori. Se li saltate la build va comunque a buon fine — in fretta, con un APK da ~100 MB invece di ~2,9 GB — ma l'app mostra "AI model unavailable" e, sui telefoni senza Gemini Nano (per esempio un Pixel 7), non riesce nemmeno a riconoscere gli ingredienti.

1. **Strumenti**: JDK 17+, Android SDK 36 (Python 3 solo se usate lo script di preparazione di CLIP).
2. **Encoder MobileCLIP** (in gitignore, ~140 MB). Gli embedding delle etichette sono già nella repo, quindi basta scaricare l'encoder:
   ```bash
   curl -L -o app/src/main/assets/clip/mobileclip_s2_image.tflite https://huggingface.co/plainhub/mobileclip-s2-tflite/resolve/main/mobileclip_s2_image.tflite
   ```
   In alternativa, lo script scarica l'encoder e rigenera anche gli embedding (serve solo quando cambiate il vocabolario):
   ```bash
   pip install torch open_clip_torch ai-edge-litert pillow numpy
   python3 scripts/prepare_clip_assets.py
   ```
3. **Gemma 4 E2B** (~2,6 GB, Apache 2.0, non serve login su HF):
   ```bash
   curl -L -o ~/Downloads/gemma-4-E2B-it.litertlm https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
   ```
   poi scegliete un canale:
   - flavor `beta` (tutto dentro l'APK, il modo più semplice per provarla su un dispositivo). L'APK pesa ~2,9 GB: se l'installazione fallisce con `INSTALL_FAILED_VERIFICATION_FAILURE`, vedete la nota sull'installazione più sotto.
     ```bash
     ./scripts/prepare_beta_model.sh ~/Downloads/gemma-4-E2B-it.litertlm
     ./gradlew assembleBetaDebug
     ```
   - flavor `play` durante lo sviluppo: build e avvio da Android Studio, poi sideload del modello con il nome che l'app si aspetta:
     ```bash
     adb push ~/Downloads/gemma-4-E2B-it.litertlm /data/local/tmp/llm/gemma4-e2b-it.litertlm
     ```
4. Senza Gemma l'app scansiona comunque e costruisce l'inventario (con MobileCLIP, o con Nano dove c'è); solo le ricette richiedono l'LLM. Senza l'encoder MobileCLIP, la scansione funziona solo sui dispositivi con Gemini Nano.

## Build e distribuzione

```bash
# Tester (APK distribuito a mano):
./gradlew assembleBetaDebug     # → app/build/outputs/apk/beta/debug/app-beta-debug.apk (~2,9GB)

# Play Store:
./gradlew bundlePlayRelease     # → AAB con gli AI pack

# Test locale della distribuzione Play senza lo store:
./gradlew bundlePlayDebug
bundletool build-apks --bundle=app/build/outputs/bundle/playDebug/app-play-debug.aab \
  --output=aipantry.apks --local-testing --overwrite
bundletool install-apks --apks=aipantry.apks
```

⚠️ Le build APK del flavor `play` (`assemblePlayDebug`, Run da Android Studio) **non contengono gli AI pack dell'LLM**: per lo sviluppo c'è il sideload `adb push gemma-4-E2B-it.litertlm /data/local/tmp/llm/gemma4-e2b-it.litertlm` (il file deve chiamarsi `gemma4-e2b-it.litertlm`; ha la priorità sul modello installato). Il riconoscimento con CLIP, invece, funziona in ogni build (l'encoder sta negli asset condivisi). Il flavor `beta` funziona completamente anche da Android Studio.

Nota sull'installazione: gli APK con il modello incorporato (~2,9 GB) possono superare il timeout del verificatore di sistema (`INSTALL_FAILED_VERIFICATION_FAILURE`). Sui dispositivi di test: `adb shell settings put global package_verifier_user_consent -1`.

## Requisiti

JDK 17+, Android SDK 36, AGP 8.10+. `minSdk 31`, un dispositivo con fotocamera. Riconoscimento con CLIP: qualsiasi dispositivo (Android 12+). Riconoscimento con Gemini Nano: dispositivi supportati dalla Prompt API di ML Kit GenAI (Pixel 9/10/11 esclusi i modelli "a", Galaxy S26, altri flagship recenti). Ricette con Gemma 4 E2B: 6+ GB di RAM, GPU consigliata. Spazio: ~5,5 GB per la build beta (l'APK da ~2,9 GB tiene i suoi chunk, più il modello riassemblato da ~2,6 GB), ~2,6 GB per quella Play (i pack vengono rimossi dopo l'assemblaggio).

## Stack

Kotlin 2.4 · Jetpack Compose (M3) · CameraX · ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt`, Gemini Nano/AICore) · LiteRT (`com.google.ai.edge.litert`, MobileCLIP-S2) · LiteRT-LM (`com.google.ai.edge.litertlm`, Engine/Conversation) · Play AI Delivery · Room · Koin · Navigation Compose · kotlinx-serialization
