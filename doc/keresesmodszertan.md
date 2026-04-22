# LookupInVideo keresesmodszertan (teljes, kodhiven)

Ez a dokumentum a jelenlegi implementacio teljes keresesi logikajat irja le, fuggveny-szinten.
A leiras celja, hogy pontosan kovesse a kodot, es egy helyen mutassa:
- hogyan fut a kereses,
- milyen parameterekkel,
- milyen statisztikakat gyujtunk,
- mit nez a rendszer frame-szinten.

Forras (source of truth):
- `src/main/java/com/gazsik/lookupinvideo/service/VideoSearchService.java`
- `src/main/java/com/gazsik/lookupinvideo/controller/VideoSearchController.java`
- `src/main/java/com/gazsik/lookupinvideo/model/JobProgress.java`
- `src/main/java/com/gazsik/lookupinvideo/model/SearchOutcome.java`
- `src/main/java/com/gazsik/lookupinvideo/model/SceneMatch.java`
- `src/main/resources/application.properties`
- `run_async_video_check.sh`

## 1. Keresesi flow (endpoint -> service)

### 1.1 Egy fajl, feltoltessel
- `POST /search` -> `VideoSearchService.storeAndSearch(video, query)`
- Szinkron feldolgozas, azonnali `SearchOutcome` visszaadas, `results.html` render.

### 1.2 Egy helyi fajl, szinkron
- `POST /search-local` -> `VideoSearchService.searchByPath(path, query)`
- Szinkron feldolgozas, `results.html` render.

### 1.3 Egy fajl, aszinkron
- `POST /search-async` -> `startSingleUploadSearch(video, query)`
- `POST /search-local-async` -> `startSinglePathSearch(path, query)`
- Visszaad: `jobId`
- Polling: `GET /progress/{jobId}`
- Kesz eredmeny: `GET /result-single/{jobId}`

### 1.4 Konyvtar, aszinkron
- `POST /search-dir` -> `startDirectorySearch(dir, query)`
- Visszaad: `jobId`
- Polling: `GET /progress/{jobId}`
- Kesz eredmenyek: `GET /results/{jobId}`

### 1.5 Video stream
- `GET /media/{videoId}` -> `resolveVideoPath(videoId)` alapjan inline stream.

## 2. Adatmodellek es statisztikak

### 2.1 `SceneMatch`
Egy talalat rekord:
- `timestampSeconds`
- `confidence`
- `reason`
- `previewDataUrl` (base64 JPEG)

### 2.2 `SearchOutcome`
Egy video teljes eredmenye:
- `videoId`
- `query`
- `mode`
- `note`
- `durationSeconds`
- `matches` (max 12)
- `fileName`

### 2.3 `JobProgress`
Aszinkron progress/stat allapot:
- `status`: `RUNNING | DONE | ERROR`
- `processed`, `total`
- `matchesFound`
- `currentFile`
- `framePercent` (szekvencialis modban)
- `fileProgress` (parhuzamos modban: fajlnev -> 0..100)
- `threadCount`
- `statusText`
- `error`

`getPercent()` logika:
- parhuzamos mod: `processed * 100 / total`
- szekvencialis mod: `(processed * 100 + framePercent) / total`

Megjegyzes: intra-video szegmentalt futasnal a progress frissites 99%-ra van clampelve feldolgozas kozben,
es csak `done()` allitja 100%-ra.

## 3. Konfiguracio es alap parameterek

## 3.1 `application.properties` (jelenlegi repo ertekek)
- `spring.servlet.multipart.max-file-size=4096MB`
- `spring.servlet.multipart.max-request-size=4096MB`
- `lookup.video.storage-path=uploads`
- `lookup.video.analysis.max-threads=10`
- `lookup.video.analysis.timeout-seconds=900`
- `lookup.video.analysis.gpu-processing=true`
- `lookup.video.analysis.decode-hwaccel=true`
- `lookup.video.analysis.decode-threads=4`
- `lookup.video.analysis.intra-segment-count=7`
- `server.port=8080`

### 3.2 Konstruktor defaultok (`@Value`)
Ha nincs property megadva:
- `storage-path`: `uploads`
- `max-threads`: `0` (auto)
- `timeout-seconds`: `900`
- `gpu-processing`: `true`
- `decode-hwaccel`: `false`
- `decode-threads`: `0`
- `intra-segment-count`: `0` (auto)

### 3.3 Belso normalizalasok
- `analysisTimeoutSeconds = max(60, configured)`
- `analysisThreadCount = normalizeThreadCount(configured)`
  - ha configured > 0: legalabb 1
  - kulonben: `max(2, min(32, availableCores * 2))`
- `segmentExecutor` pool meret:
  - `analysisThreadCount * segsPerVideo`
  - `segsPerVideo = configuredSegmentCount > 0 ? configuredSegmentCount : 1`

## 4. Keresesi modok (query -> QueryIntent) — mit lehet beírni?

`analyzeVideo(...)` elejen:
1. `query` normalizalas: ékezetek eltávolítása + kisbetűsítés (`Normalizer NFD + strip + toLowerCase`)
2. `resolveIntent(normalizedQuery)` — az alábbi sorrendben, az ELSŐ illeszkedő mod aktivál

---

### 4.1 Keresési kulcsszavak táblázata (intent → kulcsszócsoport)

| Intent               | Magyar kulcsszavak                                                                 | Angol kulcsszavak / szinonimák                                                                      | Mit keres?                                                   |
|----------------------|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| **COLOR**            | `piros`, `zold`, `kek`                                                             | `red`, `green`, `blue`                                                                              | Adott szín dominanciája a képen, mozgásboosttal              |
| **WILDLIFE**         | `szarvas`, `vad`, `oz`                                                             | `deer`, `wildlife`, `animal`, `wild animal`, `animal crossing`, `road animal`, `animal on road`, `roadside animal` | Keresztirányú mozgás — szarvas/vad heurisztika  |
| **LANE_CHANGE**      | `savalt`, `savvaltas`, `besorol`, `atmegy masik savba`, `kicsúszik a savbol`       | `lane change`, `lane_change`, `changing lane`, `merge`, `merging`, `drift`, `weaving`               | Sávváltás detektálása                                        |
| **TURN**             | `kanyar`, `balra kanyar`, `jobbra kanyar`, `balra`, `jobbra`                       | `turn`, `turning`, `left turn`, `right turn`, `corner`, `curve`, `bend`                             | Kanyar-esemény                                               |
| **CROSSING_VEHICLE** | `keresztbe`, `keresztbe megy`, `keresztezi`, `athalad keresztben`                  | `crossing`, `cross vehicle`, `crossing vehicle`, `vehicle crossing`, `crosses the road`, `crossing traffic`, `perpendicular motion`, `lateral crossing` | Keresztbe menő jármű |
| **ROAD_OBSTACLE**    | `megall`, `hirtelen megall`, `elakad`, `allo jarmu`, `blokkol`, `akadaly`, `uton akadaly` | `sudden stop`, `stop`, `halt`, `stuck`, `blocking`, `stationary object`, `abrupt stop`, `obstacle`, `road obstacle`, `debris`, `fallen object`, `road hazard` | Megállás / elakadás / útakadály |
| **ANOMALY**          | `anomalia`, `szabalytalan`, `rendellenes`, `veszely`, `veszedelmes`, `hirtelen mozgas` | `anomaly`, `irregularity`, `violation`, `wrong way`, `wrong-way`, `unsafe behavior`, `illegal movement`, `dangerous event`, `sudden movement`, `abrupt motion`, `sudden appearance`, `dash`, `sprint` | Szabálytalanság, veszélyes helyzet, hirtelen mozgás |
| **MOTION**           | *(bármely más szó, pl. `mozgas`, `auto`, `elhalad`, `megeloz`)*                    | *(any other text, incl. `overtake`, `overtaking`, `pass`, `passing vehicle`, `pass-by`)*            | Általános mozgásintenzitás alapú keresés                     |

> **Megjegyzés az előzésről / elhaladásról:** Az `overtake`, `megeloz`, `elhalad`, `pass-by` kulcsszavak
> jelenleg a `MOTION` (általános mozgás) módot aktiválják, mivel dedikált overtake-detektor
> még nincs implementálva.

---

### 4.2 Keresési példák

| Beírt query                  | Aktivált intent      | Miért?                                             |
|------------------------------|----------------------|----------------------------------------------------|
| `szarvas`                    | WILDLIFE             | Direkt egyezés                                     |
| `deer`                       | WILDLIFE             | Direkt egyezés                                     |
| `wild animal`                | WILDLIFE             | Szinonima bővítés                                  |
| `animal crossing`            | WILDLIFE             | Szinonima bővítés                                  |
| `kanyar`                     | TURN                 | Magyar kulcsszó                                    |
| `curve`                      | TURN                 | Szinonima                                          |
| `sávváltás`                  | LANE_CHANGE          | Magyar kulcsszó (ékezet nélkül: `savvaltas`)       |
| `merge`                      | LANE_CHANGE          | Szinonima                                          |
| `keresztbe menő autó`        | CROSSING_VEHICLE     | Részleges egyezés (`keresztbe`)                    |
| `lateral crossing`           | CROSSING_VEHICLE     | Szinonima                                          |
| `wrong way`                  | ANOMALY              | Direkt egyezés                                     |
| `szabálytalan mozgás`        | ANOMALY              | (`szabalytalan` egyezés)                           |
| `hirtelen megáll`            | ROAD_OBSTACLE        | (`hirtelen megall` egyezés)                        |
| `sudden stop`                | ROAD_OBSTACLE        | Szinonima                                          |
| `piros autó`                 | COLOR (piros nyer!)  | Szín mindig elsőbbséget kap                        |
| `piros szarvas`              | COLOR                | Szín > WILDLIFE — a `piros` nyer                  |
| `mozgas`                     | MOTION               | Semmi sem illeszkedett → fallback                  |
| `overtake`                   | MOTION               | Nincs dedikált intent → fallback                   |

---

### 4.3 Prioritásrend (ha több kulcsszó egyszerre szerepel)

1. `COLOR` — a szín mindig nyer minden más felett
2. `WILDLIFE`
3. `LANE_CHANGE`
4. `TURN`
5. `CROSSING_VEHICLE`
6. `ROAD_OBSTACLE` (ide esik a sudden stop is)
7. `ANOMALY`
8. `MOTION` — fallback, ha semmi sem illeszkedett

---

### 4.4 Megjegyzés a fallback modokról

A `WILDLIFE`, `COLOR`, `TURN`, `LANE_CHANGE`, `CROSSING_VEHICLE`, `ROAD_OBSTACLE` és `ANOMALY`
intentekhez **mindegyikhez** dedikált, matematikai jellel meghajtott pontszámfüggvény tartozik
(részletek a 7.6 szakaszban). A `MOTION` továbbra is a fallback (általános mozgás-intenzitás)
azoknak a szabadszöveges lekérdezéseknek, amelyek egyik kulcsszó-csoportba sem esnek.

## 5. Fo konstansok (algoritmus parameterek)

### 5.1 Mintavetel, meret, limit
- `DEFAULT_SAMPLE_STEP_US = 1_000_000` (1.0 s)
- `DEER_SAMPLE_STEP_US = 150_000` (0.15 s)
- `ANALYSIS_WIDTH_CPU = 320`
- `ANALYSIS_WIDTH_GPU = 512`
- `MAX_MATCHES = 12`
- `SEGMENT_WARMUP_US = 2_000_000` (2.0 s)

### 5.2 Deer temporalis parameterek
- `DEER_TIMESTAMP_LEAD_BASE_SECONDS = 1.1`
- `DEER_TIMESTAMP_LEAD_MAX_SECONDS = 4.7`
- `DEER_CLUSTER_WINDOW_SECONDS = 2.6`
- deer track window: `DEER_TRACK_WINDOW_SECONDS = 1.7`

### 5.3 Global shift parameterek
- `GLOBAL_SHIFT_MAX_DX = 8`
- `GLOBAL_SHIFT_MAX_DY = 6`
- `GLOBAL_SHIFT_SAMPLE_STRIDE = 4`

### 5.4 Jarmu/oncoming/road szuro kuszobok
- `ONCOMING_CENTER_RATIO_MIN = 0.34`
- `ONCOMING_LATERAL_TRAVEL_MAX = 0.020`
- `ONCOMING_ACTIVE_GROWTH_MIN = 0.0025`
- `ONCOMING_TRAVEL_SCORE_MAX = 0.46`
- `WEAK_LATERAL_CROSS_TRAVEL_MAX = 0.013`
- `CENTER_APPROACH_CENTER_RATIO_MIN = 0.42`
- `CENTER_APPROACH_TRAVEL_SCORE_MAX = 0.31`
- `CENTER_APPROACH_CROSS_TRAVEL_MAX = 0.016`
- `DEER_TRACK_LATERAL_MIN = 0.020`
- `DEER_TRACK_LATERAL_STRONG = 0.080`
- `DEER_TRACK_X_MIN = 0.045`
- `DEER_TRACK_X_STRONG = 0.200`
- `ROAD_VEHICLE_COLOR_SIGNAL_MIN = 0.22`
- `ROAD_VEHICLE_NEUTRAL_SIGNAL_MIN = 0.33`
- `VEHICLE_COLOR_DOMINANCE_FILTER = 0.18`
- `NEUTRAL_COLOR_DOMINANCE_FILTER = 0.30`
- `OVERTRACKED_FLOW_CROSS_MIN = 0.75`
- `OVERTRACKED_FLOW_CENTER_MIN = 0.38`
- `OVERTRACKED_FLOW_LATERAL_MIN = 0.45`
- `OVERTRACKED_FLOW_RESIDUAL_MIN = 0.030`

## 6. `analyzeVideo(...)` teljes menete

Input:
- `videoId`, `videoPath`, `query`, `fileName`, optional `JobProgress`

Lepesek:
1. query normalizalas + mod valasztas (`resolveMode`, `resolveColorQuery`).
2. video hossza: `probeVideoDuration(videoPath)`.
3. mintavetel: `FrameSampler.computeSampleStepUs(intent, durationUs)`:
   - `TURN`, `LANE_CHANGE`: `500_000 us`
   - `CROSSING_VEHICLE`: `250_000 us`
   - `ANOMALY`: `350_000 us`
   - `ROAD_OBSTACLE`: `500_000 us`
   - `WILDLIFE` + duration >= 360 s: `350_000 us`
   - `WILDLIFE` + duration >= 180 s: `250_000 us`
   - `WILDLIFE` + rovidebb: `150_000 us`
   - egyebkent (`COLOR`, `MOTION`): `1_000_000 us`
4. analizis szelesseg:
   - GPU/OpenCL aktiv: `512`
   - kulonben CPU: `320`
5. timeout hatarido nanos-ban (`analysisDeadlineNanos`).
6. intra-video szegmensek: `computeIntraVideoSegmentCount(durationUs)`:
   - ha `< 5_000_000 us`: 1
   - ha propertyben explicit: azt hasznalja
   - kulonben auto: `min(availableCores, max(1, durationUs / 4_000_000))`
7. segment futtatas:
   - 1 szegmens: `processVideoSegment(...)`
   - tobb szegmens: `CompletableFuture.supplyAsync(...)` a `segmentExecutor` poolon
8. deer modban utoszures: `postProcessDeerMatches(...)`
9. rendezes/limit:
   - confidence desc
   - max 12
   - timestamp asc
10. `SearchOutcome` osszeallitas (`modeLabel`, `note`, `duration`, `matches`, `fileName`).

`note` kiegeszitesben szerepel:
- GPU status (`OpenCL GPU aktiv` vagy fallback/allapot)
- segment darabszam

## 7. `processVideoSegment(...)` frame-ciklus

### 7.1 Grabber setup
- `FFmpegFrameGrabber` + `grabber.setImageWidth(analysisWidth)`
- `startGrabberWithGpuFallback(...)`:
  - decode threads option beallitva
  - hwaccel engedelyezesnel:
    - macOS: `videotoolbox`
    - Windows: `d3d11va`
    - egyeb: `auto`
  - ha hw start fail: fallback `hwaccel=none`

### 7.2 Warmup seek
- szegmens eleje elotti warmup: `max(0, segmentStartUs - 2_000_000)`
- warmup frame-ek frissitik az EMA/axis/track allapotot, de talalatot nem generalnak.

### 7.3 Ciklus stop feltetelek
A ciklus megszakad ha:
- thread interrupted,
- timeout (`System.nanoTime() > analysisDeadlineNanos`),
- szegmens vegere ert (`timestampUs >= segmentEndUs`, ha veges).

### 7.4 Mintavetel es progress
- progress update minden grab frame utan (`updateFileFrame`) a szegmensenkenti akkumulalt us alapjan.
- feldolgozas csak mintaponton: ha `timestampUs < nextSampleUs`, frame skip.
- mintavetel cursor lep: `advanceSampleCursor(...)`.

### 7.5 Frame feature extraction
Minden feldolgozott mintapontban:
1. `scale(Mat, analysisWidth)`
2. `computeColorStats(scaledMat)`
3. `computeMotionMetricsWithBackend(previous, current, prevCentroid, axis)`
4. longitudinal axis update (ha global shift nagysag >= 0.45)
5. burst score + EMA + activeGrowth
6. deer track score (`computeLateralTrackScore`) deer modban

### 7.6 Scoring modonkent
#### COLOR mod
- `colorDominance = colorStats.dominance(colorQuery)`
- `colorMotionBoost = clamp((residualIntensity - 0.010) / 0.070, 0..1)`
- `score = clamp(colorDominance * 0.78 + colorMotionBoost * 0.22, 0..1)`
- reason: szindominancia + mozgas boost szazalekok

#### DEER mod
Eloszuresek:
- `vehicleColorSignal = max(redDominance, blueDominance)`
- `neutralSignal = neutralDominance`
- `strongCrossingCandidate`:
  - `lateralTrackScore >= 0.78`
  - `crossMotionRatio >= 0.48`
  - `residualIntensity >= 0.020`
  - `vehicleColorSignal < 0.20`
- `crossingCore`:
  - `lateralTrackScore >= 0.70`
  - `crossMotionRatio >= 0.40`
- `likelyRoadVehicleByColor`:
  - `vehicleColorSignal >= 0.20`
  - `centerRatio >= 0.30`
  - `residualIntensity >= 0.030`

Drop logika:
- ha nem `crossingCore` -> drop
- ha `likelyRoadVehicleByColor` es nem `strongCrossingCandidate` -> drop
- ha `looksLikeOncomingVehicle(...)` -> drop
- ha `looksLikePseudoLateralGrowth(...)` -> drop
- ha nem `strongCrossingCandidate` es barmelyik road/vehicle profil igaz -> drop

Pontszam:
- `score = computeDeerScore(...)`
- reason: deer heurisztika + fo komponensek (%):
  - keresztmozgas
  - kiemelt mozgas (residual)
  - kozepso regio
  - oldalpalyas kovetes
  - autoszin jel

#### MOTION mod
- `score = motionMetrics.intensity`
- reason: mozgas-intenzitas (%)

#### LANE_CHANGE mod (savvaltas)
- per-frame state: `signedShiftXEma = 0.80*prev + 0.20*globalShiftX` (~2 s ablak)
- `coherence = clamp(|signedShiftXEma| / 2.5, 0..1)` — koherens horizontalis ego-eltolas
- `motionGate = clamp(intensity / 0.04, 0..1)` — auto haladjon
- `notATurn = clamp(1 - lateralSweepScore / 0.55, 0..1)` — ne legyen teljes kanyar
- `notACrossing = clamp(1 - crossMotionRatio / 0.55, 0..1)` — ne legyen kereszt-jarmu
- `cleanScene = clamp(1 - residualIntensity / 0.060, 0..1)` — ne legyen idegen targy
- `score = coherence * motionGate * notATurn * sqrt(notACrossing * cleanScene)`
- reason: `Savvaltas: X% [<- balra | -> jobbra] | shiftCoh, intenz, sweep, cross`

#### CROSSING_VEHICLE mod (keresztezo jarmu)
- per-frame state: `crossingTrack` — ~1.2 s csuszoablak a centroidokkal
- `crossRatioGate = clamp((crossMotionRatio - 0.40) / 0.40, 0..1)`
- `crossTravelGate = clamp((crossTravel - 0.012) / 0.040, 0..1)`
- `residualGate = clamp((residualIntensity - 0.020) / 0.060, 0..1)`
- `speedGate = clamp(travelScore / 0.45, 0..1)`
- `translation = max centroid bbox span` (utolso ~1.2 s)
- `translationGate = clamp((translation - 0.08) / 0.18, 0..1)`
- `score = crossRatioGate * sqrt(crossTravelGate * speedGate) * residualGate * (0.35 + 0.65 * translationGate)`
- reason: `Keresztezo jarmu: X% | crossRatio, crossTravel, travel, transl`

#### ROAD_OBSTACLE mod (megallas / akadaly)
- per-frame state: `intensityLongEma`, `shiftMagLongEma` (~3 s, alpha=0.10)
- (A) hirtelen megallas:
  - `dropRatio = clamp((intensityLongEma - intensity) / max(intensityLongEma, 0.005), 0..1)`
  - `shiftDropRatio = clamp((shiftMagLongEma - shiftMagNow) / shiftMagLongEma, 0..1)`
  - `wasMovingGate = clamp((intensityLongEma - 0.020) / 0.040, 0..1)`
  - `nowQuietGate = clamp((0.018 - intensity) / 0.018, 0..1)`
  - `stopScore = wasMovingGate * nowQuietGate * (0.55 * dropRatio + 0.45 * shiftDropRatio)`
- (B) lingering kozepso akadaly:
  - `centerObstacle = clamp((centerRatio - 0.30) / 0.40) * clamp((0.025 - residualIntensity) / 0.025) * clamp((0.025 - intensity) / 0.025)`
- `score = max(stopScore, 0.85 * centerObstacle)`
- reason: `Megallas/akadaly: X% | stopScore, intDrop, shiftDrop, intEma`

#### ANOMALY mod (rendellenes / hirtelen mozgas)
- `burstGate = clamp((burstScore - 0.025) / 0.060, 0..1)` — burst = `residualIntensity - motionEma`
- `residualGate = clamp((residualIntensity - 0.030) / 0.060, 0..1)`
- `sideSignal = max(crossMotionRatio, lateralSweepScore)`
- `sideGate = clamp((sideSignal - 0.30) / 0.45, 0..1)`
- `activeBoost = clamp((activeRatio - 0.020) / 0.060, 0..1)`
- `score = burstGate * residualGate * (0.50 + 0.50 * sideGate) * (0.65 + 0.35 * activeBoost)`
- reason: `Anomalia: X% | burst, residual, sideMax, active`

### 7.7 Match kapu
`isMatch(intent, score)`:
- COLOR: `score >= 0.14`
- WILDLIFE: `score >= 0.21`
- TURN: `score >= 0.30`
- LANE_CHANGE: `score >= 0.28`
- CROSSING_VEHICLE: `score >= 0.30`
- ROAD_OBSTACLE: `score >= 0.32`
- ANOMALY: `score >= 0.34`
- MOTION (fallback): `score >= 0.18`

A `LANE_CHANGE`, `CROSSING_VEHICLE`, `ROAD_OBSTACLE`, `ANOMALY` es `TURN` modok
post-processzben temporalis dedup-ot kapnak (1.4 s cluster ablak, 0.7 s min. gap),
hogy ne kapjon a felhasznalo egy esemenyrol 5-6 talalatot egymas mellett.

Ha atment:
- preview keszites: `createPreviewDataUrl(...)` (260 px szeles JPEG)
- timestamp:
  - alap: `timestampUs / 1_000_000`
  - deer modban visszahuzas: `computeDeerTimestampLeadSeconds(...)`
- `SceneMatch` push.

## 8. Mozgasmetrikak (`computeMotionMetricsOpenCl`)

A fo pipeline ezt hasznalja (`Mat` alapu). Ha OpenCL hiba van, ugyanaz a fuggveny fut `preferGpu=false` modban.

### 8.1 Alapok
- kozos terulet: `width=min(prev.cols,current.cols)`, `height=min(...)`
- global shift: `estimateGlobalShiftOpenCl(prev, curr, width, height)`
- raw intenzitas: `rgbDiffMean01(prev, curr)`

### 8.2 Shiftelt overlap
- overlap a `(dx,dy)` szerint szamitva
- ha overlap nagyon kicsi (`<8x8`): default jellegu metrikak visszaadasa

### 8.3 Residual mask
- `absdiff(prevRoi, currRoi)`
- `residualIntensity = mean(absdiff)/255`
- `activeMask`: gray + threshold (`0.11 * 255`)

### 8.4 ROI-k
- motion ROI:
  - x: 10%..90%
  - y: 22%..95%
- center ROI:
  - x: 26%..74%
  - y: 25%..90%

### 8.5 Metrikak
- `activeRatio = activePixels / roiPixels`
- centroid mask moments-bol (`moments(..., true)`), fallback elozo centroidra
- `centerRatio = centerActive / active`
- object shift centroid kulonbsegbol
- `crossTravel` es `parallelTravel` a longitudinal axisra vetitve
- `crossMotionRatio = cross / (cross + parallel + 1e-6)`
- `travelScore = clamp(objectSpeed * 7.0, 0..1)`
- global shift komponensek: `globalShiftX`, `globalShiftY`

Visszaadott struct: `MotionMetrics`.

## 9. Global shift becsles (`estimateGlobalShiftOpenCl`)

Keresesi ter:
- `dx in [-8, 8]`
- `dy in [-6, 6]`

Minden jeloltnel:
1. overlap vizsgalat (`>=24x16`)
2. grayscale diff atlag (`grayDiffMean`)
3. regularizacio: `error += hypot(dx,dy) * 0.15`
4. legkisebb error -> best `(dx,dy)`

Megjegyzes:
- Ebben a tight loopban CPU-s gray diff fut (explicit komment a kodban), mert itt a GPU upload/download overhead nagy.

## 10. Szinanalitika (`computeColorStats`)

Mintavetel: minden 2. pixel (`x+=2, y+=2`).

Pixelenkenti szin-feltetelek:
- `max`, `min`, `saturation = max-min`
- red dominant:
  - `r > 80`
  - `saturation > 28`
  - `r > g*1.20`
  - `r > b*1.20`
- green dominant:
  - `g > 80`
  - `saturation > 28`
  - `g > r*1.15`
  - `g > b*1.15`
- blue dominant:
  - `b > 80`
  - `saturation > 28`
  - `b > r*1.12`
  - `b > g*1.12`
- neutral dominant:
  - `saturation < 20`
  - `max > 95`

Output aranyok:
- `redDominance`, `greenDominance`, `blueDominance`, `neutralDominance`

`ColorStats.dominance(colorQuery)` a keresett szinek kozul a maximum dominanciat adja.

## 11. Deer track es szuroheurisztikak

### 11.1 `computeLateralTrackScore(...)`
Ha:
- `activeRatio < 0.008` vagy
- `residualIntensity < 0.018`
akkor 0.

Kulonben:
1. track pont hozzaadas (timestamp, centroidX, centroidY)
2. 1.7 s-nal regebbi pontok kidobasa
3. legalabb 2 pont kell
4. `dx,dy` az elso ponttol a mostaniig
5. lateralDistance a hossz-tengelyre meroleges komponensbol
6. score komponensek:
   - `lateralScore = clamp((lateralDistance - 0.020)/(0.080 - 0.020), 0..1)`
   - `horizontalScore = clamp((abs(dx) - 0.045)/(0.200 - 0.045), 0..1)`
7. `blended = sqrt(lateralScore * horizontalScore)`
8. ha `centerRatio >= 0.45` es `horizontalScore < 0.35`, akkor `blended *= 0.55`
9. clamp -> lateralTrackScore.

### 11.2 Oncoming/road drop fuggvenyek (nev szerint)
- `looksLikeOncomingVehicle(motion, activeGrowth)`
- `looksLikePseudoLateralGrowth(motion, activeGrowth, lateralTrackScore)`
- `looksLikeCenteredLowLateralVehicle(motion, lateralTrackScore)`
- `looksLikeLowCrossHighTrackVehicle(motion, lateralTrackScore, vehicleColorSignal)`
- `looksLikeColorfulMidLateralRoadVehicle(motion, lateralTrackScore, vehicleColorSignal)`
- `looksLikeOvertrackedRoadFlow(motion, lateralTrackScore)`
- `looksLikeColoredRoadSweep(motion, lateralTrackScore, vehicleColorSignal)`
- `looksLikeHighLateralRoadSweep(motion, lateralTrackScore, vehicleColorSignal)`
- `looksLikeCenterApproach(motion, lateralTrackScore)`
- `looksLikeVehicleColorBlob(motion, colorStats, lateralTrackScore)`
- `looksLikeRoadVehicleProfile(motion, lateralTrackScore, vehicleColorSignal, neutralSignal)`

Ezek mind kulon boolean szabalyokkal explicit dropot adnak deer modban.

## 12. Deer score keplet (`computeDeerScore`)

### 12.1 Alap komponensek
- `compactness = activeRatio<=0.0001 ? 0 : clamp((residualIntensity/activeRatio)/3.7, 0..1)`
- `foregroundIsolation = 1 - clamp(activeRatio/0.14, 0..1)`
- `residualGate = clamp((residualIntensity - 0.020)/0.070, 0..1)`
- `lateralTravelGate = clamp((crossTravel - 0.008)/0.032, 0..1)`
- `directionalConfidence = clamp(((crossMotionRatio*0.28) + (lateralTravelGate*0.33) + (lateralTrackScore*0.39)) * travelScore * residualGate, 0..1)`
- `burstGate = clamp(burstScore / 0.070, 0..1)`

### 12.2 Penalty komponensek
- `centerPenalty = clamp((centerRatio - 0.34)/0.45, 0..1)`
- `growthPenalty = clamp((activeGrowth - 0.0025)/0.018, 0..1)`
- `lowCrossPenalty = clamp((0.020 - crossTravel)/0.020, 0..1)`
- `lowTravelPenalty = clamp((0.46 - travelScore)/0.46, 0..1)`
- `oncomingPenalty = (centerPenalty*0.35 + growthPenalty*0.30 + lowCrossPenalty*0.20 + lowTravelPenalty*0.15) * clamp((residualIntensity - 0.035)/0.070, 0..1)`
- `colorPenalty = clamp((vehicleColorSignal - 0.055)/0.20, 0..1) * clamp((centerRatio - 0.32)/0.50, 0..1) * clamp((0.36 - lateralTrackScore)/0.36, 0..1)`
- `neutralPenalty = clamp((neutralDominance - 0.18)/0.30, 0..1) * clamp((centerRatio - 0.30)/0.55, 0..1) * clamp((0.42 - lateralTrackScore)/0.42, 0..1)`
- `overtrackedFlowPenalty = clamp((crossMotionRatio - 0.80)/0.14, 0..1) * clamp((centerRatio - 0.28)/0.20, 0..1) * clamp((lateralTrackScore - 0.50)/0.40, 0..1)`

### 12.3 Vegso score
`combined =`
- `directionalConfidence * 0.63 +`
- `residualGate * 0.10 +`
- `burstGate * 0.15 +`
- `foregroundIsolation * 0.06 +`
- `compactness * 0.06`

Majd levonasok:
- `combined -= oncomingPenalty * 0.42`
- `combined -= colorPenalty * 0.30`
- `combined -= neutralPenalty * 0.20`
- `combined -= overtrackedFlowPenalty * 0.26`

Vegso: `deerScore = clamp(combined, 0..1)`.

## 13. Deer timestamp visszahuzas (`computeDeerTimestampLeadSeconds`)

Lead komponensek:
- `centerLead = clamp((centerRatio - 0.18)/0.30, 0..1) * 2.4`
- `lowTravelLead = clamp((0.54 - travelScore)/0.30, 0..1) * 0.9`
- `stableCrossingLead = clamp((lateralTrackScore - 0.68)/0.32, 0..1) * 0.5`
- `weakConfidenceLead = clamp((0.34 - score)/0.16, 0..1) * 3.5`

`leadSeconds = 1.1 + centerLead + lowTravelLead + stableCrossingLead + weakConfidenceLead`

Vegso clamp: `[1.1, 4.7]`.

## 14. Deer post-process (`postProcessDeerMatches`)

Input: deer mod nyers jeloltek.

Lepesek:
1. idorend rendezes
2. temporalis klaszterezes 2.6 s ablakkal (`selectTemporalRepresentatives`)
   - klaszteren belul:
     - confidence a legerosebb matchbol
     - timestamp a legkorabbi matchbol
3. confidence desc rendezes
4. dinamikus kuszob:
   - `dynamicThreshold = clamp(topScore * 0.60, 0.19, 0.36)`
5. szures:
   - ha confidence kuszob alatt es mar legalabb 3 talalat van -> skip
   - ha 0.95 s-en belul tul kozel van mar kivalasztotthoz -> skip
6. max 12 elem
7. fallback: ha ures lenne, de `topScore >= 0.20`, akkor az elso bent marad

Vegeredmeny: idoben jol szeparalt, limitalt deer talalatok.

## 15. Mi jelenik meg statisztikakent a UI-ban

### 15.1 Egyfajlos eredmeny (`results.html`)
- keresesi query
- mode label
- video hossza (`formattedDuration`)
- talalatok darabszama (`outcome.matches.size()`)
- mode note (algoritmus + GPU status + szegmensszam)

### 15.2 Konyvtaros eredmeny (`results-dir.html`)
- `totalScanned`: osszes feldolgozott fajl (`progress.total` fallback: outcomes.size)
- `outcomes.size`: hany fajlban volt legalabb egy talalat
- `totalMatches`: talalatok osszege az osszes outcome-ban

### 15.3 Progress overlay (`index.html` + `JobProgress`)
- per-file progress barok (`fileProgress`) parhuzamos modban
- overall percent (`percent`)
- `processed / total`
- `threadCount`
- `matchesFound`
- elapsed + becsult ETA (frontend oldali becsles)

## 16. Konyvtarszintu feldolgozas es statisztika gyujtes

`startDirectorySearch(...)`:
1. videofajlok listazasa extension szurovel (`.mp4`, `.avi`, `.mov`, `.mkv`, `.wmv`, `.flv`, `.webm`, `.m4v`, `.mpeg`, `.mpg`)
2. `JobProgress.startParallel(total, min(total, analysisThreadCount))`
3. minden fajl kulon async task az `analysisExecutor` poolban
4. fajlankent:
   - `progress.startFileTracking(fileName)`
   - `analyzeVideo(...)`
   - `fileCompleted(fileName, matchCount)`
   - ha nincs match: `null` outcome (nem kerul be a vegso listaba)
5. `CompletableFuture.allOf(...)` utan:
   - vegso `outcomes` lista (nem-null talalatok)
   - fileName szerint rendezes
   - `dirResultMap.put(jobId, outcomes)`
   - `progress.done(total)`

## 17. Validacios metodika (`run_async_video_check.sh`)

A script celja a deer detector minosegenek gyors merese NEG/POS mintakkal.

Flow:
1. szerver elerhetoseg ellenorzes (`BASE_URL/`)
2. tesztesetenkent `POST /search-local-async`
3. polling `GET /progress/{jobId}`
4. kesz allapotnal `GET /result-single/{jobId}`
5. html parse:
   - `match_count`: `class="result-card"` darabszam
   - `top_score`: reason sorbol kinyerve (`Szarvas-heurisztika: X%`)
6. expected dontes:
   - NEGATIVE fazis: `DROP`
   - POSITIVE fazis: `KEEP`
7. PASS/FAIL tablazat + summary:
   - case szam, pass/fail
   - NEG avg/top score
   - POS avg/min score
   - threshold javaslat, ha nincs score overlap

## 18. Fuggvenyterkep (gyors referencia)

Fobb public service API:
- `storeAndSearch(...)`
- `searchByPath(...)`
- `startSingleUploadSearch(...)`
- `startSinglePathSearch(...)`
- `startDirectorySearch(...)`
- `getProgress(...)`
- `getDirectoryResults(...)`
- `getSingleResult(...)`
- `resolveVideoPath(...)`

Fobb belso pipeline:
- `analyzeVideo(...)`
- `processVideoSegment(...)`
- `probeVideoDuration(...)`
- `computeIntraVideoSegmentCount(...)`
- `resolveMode(...)`
- `resolveColorQuery(...)`
- `sampleStepForMode(...)`
- `isMatch(...)`
- `postProcessDeerMatches(...)`
- `computeDeerScore(...)`
- `computeLateralTrackScore(...)`
- `computeDeerTimestampLeadSeconds(...)`
- `computeColorStats(...)`
- `computeMotionMetricsWithBackend(...)`
- `computeMotionMetricsOpenCl(...)`
- `estimateGlobalShiftOpenCl(...)`

Megjegyzes a kodrol:
- Van egy `BufferedImage` alapu legacy mozgasmetrika (`computeMotionMetrics(BufferedImage, ...)`) is,
  de a fo pipeline jelenleg a `Mat`/OpenCL-capable utat hasznalja.

## 19. Mit nezunk meg, hogyan?

A kereso gyakorlatilag minden mintaponton ezeket vizsgalja:
1. globalis kameramozgas (`globalShift dx/dy`)
2. residual mozgas erossege (`residualIntensity`)
3. aktiv mozgas mennyisege (`activeRatio`)
4. mozgas sulypontja (`centroidX/Y`) es kozepre esese (`centerRatio`)
5. objektum haladasi iranya (`crossTravel`, `parallelTravel`, `crossMotionRatio`)
6. kovetett oldalpalyas tendencia (`lateralTrackScore`)
7. szinprofil (`red/green/blue/neutral dominance`)
8. deer modban explicit road/oncoming mintak kizaraasa
9. modfuggo pontszamolas + kuszob
10. temporalis utoszures (deer), majd vegso top lista.

Ez adja a teljes, jelenlegi LookupInVideo keresesmodszertant.
