Igen, ezt most már érdemes **konkrét osztály- és package-szintű tervként** leírni. Az alábbi terv a jelenlegi Spring Boot webes rendszert tartja meg, API-alapú kereséssel, és nem viszi el mobilapp irányba; a cél egy tiszta, bővíthető backend architektúra. [symflower](https://symflower.com/en/company/blog/2024/spring-boot-folder-structure/)

## Célstruktúra

A javasolt felépítés: **layered + feature-aware** Spring Boot struktúra. A Spring Boot nem ír elő kötött layoutot, de a jó gyakorlat a rétegek és felelősségek szétválasztása: controller, service, domain/model, infrastructure, config, dto. [docs.spring](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)
A jelenlegi `VideoSearchService` túl sok felelősséget visz, ezért szét kell bontani kisebb komponensekre, hogy az API hívásos működés stabil maradjon, de a videólogika fejleszthető legyen. [symflower](https://symflower.com/en/company/blog/2024/spring-boot-folder-structure/)

## Javasolt package struktúra

A gyökér csomag maradhat például:

```text
com.gazsik.lookupinvideo
```

Alatta ezt javaslom:

```text
com.gazsik.lookupinvideo
├─ controller
├─ service
├─ service.impl
├─ domain
│  ├─ model
│  ├─ enum
│  └─ event
├─ dto
├─ config
├─ infrastructure
│  ├─ video
│  ├─ async
│  ├─ storage
│  └─ processing
├─ repository
├─ mapper
├─ exception
└─ util
```

### Mit hova tegyél
- `controller`: REST endpointok, csak request/response és HTTP logika.
- `service`: üzleti interfészek.
- `service.impl`: konkrét implementációk.
- `domain.model`: `SceneMatch`, `SearchOutcome`, `JobProgress`, és az új domain objektumok.
- `domain.event`: eseménytípusok, mint `TURN`, `CROSSING_VEHICLE`, `WILDLIFE`, `ANOMALY`.
- `dto`: API request és response modellek.
- `infrastructure.video`: JavaCV, FFmpegFrameGrabber, frame-decode.
- `infrastructure.processing`: detektálás, tracking, scoring, feature extraction.
- `infrastructure.async`: task executor, job runner.
- `infrastructure.storage`: fájl mentés, visszakeresés, uploads kezelése.
- `config`: Spring konfiguráció, executor, properties binding.
- `repository`: ha később DB vagy cache jön.
- `mapper`: domain ↔ dto átalakítás.
- `exception`: saját hibák.
- `util`: kisebb segédfüggvények. [dev](https://dev.to/jackynote/optimizing-spring-boot-asynchronous-processing-a-comprehensive-guide-147h)

## Osztálylista

### Controller réteg
#### `VideoSearchController`
Feladat: HTTP API fogadása.  
Metódusok:
- `POST /search`
- `POST /search-local`
- `POST /search-dir`
- `POST /search-async`
- `POST /search-local-async`
- `GET /progress/{jobId}`
- `GET /result-single/{jobId}`
- `GET /results/{jobId}`
- `GET /media/{videoId}`

Ez maradhat a web belépési pontja, de csak orchestration szinten.

#### `VideoBrowseController`
Ha a browse külön logikát kap, érdemes leválasztani.  
Metódusok:
- `GET /browse`
- `GET /files`
- `GET /directories`

### Service réteg
#### `VideoSearchService`
Csak magas szintű koordináció.
Metódusok:
- `searchByPath(...)`
- `storeAndSearch(...)`
- `startSingleUploadSearch(...)`
- `startSinglePathSearch(...)`
- `startDirectorySearch(...)`

#### `VideoAnalysisService`
A teljes videóelemzés egy belső orchestrator.
Metódusok:
- `analyzeVideo(...)`
- `analyzeSegment(...)`
- `buildOutcome(...)`
- `postProcessMatches(...)`

#### `VideoStorageService`
Fájlok tárolása és keresése.
Metódusok:
- `storeUploadedVideo(...)`
- `resolveVideoPath(...)`
- `listVideosInDirectory(...)`
- `createVideoId(...)`

#### `QueryInterpretationService`
A szöveges query-ből event intent.
Metódusok:
- `normalizeQuery(...)`
- `resolveIntent(...)`
- `resolveKeywords(...)`

#### `ProgressService`
Job állapot és progress kezelése.
Metódusok:
- `createJob(...)`
- `updateProgress(...)`
- `markDone(...)`
- `markError(...)`
- `getProgress(...)`

#### `ResultService`
Eredmények tárolása és lekérése.
Metódusok:
- `saveSingleResult(...)`
- `saveDirectoryResults(...)`
- `getSingleResult(...)`
- `getDirectoryResults(...)`

### Infrastructure réteg
#### `JavaCvVideoDecoder`
Feladat: videó frame-ek olvasása JavaCV-vel.  
Metódusok:
- `open(...)`
- `readFrame(...)`
- `seek(...)`
- `close(...)`

#### `FrameSampler`
Feladat: mintavételezés idő szerint.  
Metódusok:
- `nextSampleTimeUs(...)`
- `shouldProcessFrame(...)`
- `computeSampleStep(...)`

#### `ObjectDetectionAdapter`
Későbbi detektor integráció.  
Metódusok:
- `detect(...)`
- `detectBatch(...)`

#### `TrackingAdapter`
Track-ek összekötése időben.  
Metódusok:
- `updateTracks(...)`
- `getActiveTracks(...)`
- `expireOldTracks(...)`

#### `LaneGeometryService`
Sáv / középvonal / haladási tengely becslés.  
Metódusok:
- `estimateRoadAxis(...)`
- `detectLaneState(...)`
- `updateLongitudinalAxis(...)`

#### `MotionFeatureExtractor`
Mozgásjelzők előállítása.  
Metódusok:
- `computeGlobalShift(...)`
- `computeResidualMotion(...)`
- `computeCentroid(...)`
- `computeMotionMetrics(...)`

#### `EventScoringService`
Esemény-score számítás.  
Metódusok:
- `scoreTurn(...)`
- `scoreCrossingVehicle(...)`
- `scoreWildlife(...)`
- `scoreAnomaly(...)`

#### `EventPostProcessor`
Találatok összesítése és szűrése.  
Metódusok:
- `clusterMatches(...)`
- `deduplicateNearbyMatches(...)`
- `limitTopMatches(...)`

### Domain objektumok
#### `VideoAsset`
- `videoId`
- `fileName`
- `path`
- `durationUs`

#### `SearchRequest`
- `query`
- `videoId`
- `path`
- `mode`

#### `SearchContext`
- `request`
- `normalizedQuery`
- `intent`
- `sampleStepUs`
- `analysisWidth`
- `timeoutNanos`

#### `FrameObservation`
- `timestampUs`
- `frameIndex`
- `motionMetrics`
- `colorStats`
- `trackedObjects`
- `laneState`

#### `TrackedObject`
- `trackId`
- `label`
- `bbox`
- `confidence`
- `trajectory`

#### `EventCandidate`
- `eventType`
- `timestampUs`
- `score`
- `reason`
- `evidence`

#### `EventMatch`
- `timestampSeconds`
- `confidence`
- `reason`
- `previewDataUrl`
- `eventType`

#### `SearchOutcome`
- `videoId`
- `query`
- `mode`
- `durationSeconds`
- `matches`
- `fileName`

#### `JobProgress`
- `status`
- `processed`
- `total`
- `matchesFound`
- `currentFile`
- `framePercent`
- `fileProgress`
- `threadCount`
- `statusText`
- `error`

### DTO-k
#### `SearchResponseDto`
API válaszokra.

#### `ProgressResponseDto`
Progress JSON-hoz.

#### `ResultResponseDto`
Egyfájlos és dir-szintű eredményekhez.

## Külön domain enumok

```text
QueryIntent
- COLOR
- DEER
- MOTION
- TURN
- CROSSING_VEHICLE
- WILDLIFE
- ANOMALY
- LANE_CHANGE
```

```text
JobStatus
- RUNNING
- DONE
- ERROR
```

```text
EventType
- TURN
- CROSSING_VEHICLE
- WILDLIFE
- ANOMALY
- GENERIC_MOTION
```

## Implementációs sorrend

### 1. lépés: domain és DTO-k
Először az összes adatmodellt és enumot hozd létre.  
Cél: a régi logika ugyanúgy működjön, de már az új osztályokba legyen becsomagolva.

### 2. lépés: service interfészek
Definiáld az interfészeket:
- `VideoStorageService`
- `VideoAnalysisService`
- `QueryInterpretationService`
- `ProgressService`
- `ResultService`

Ezzel leválasztod a webes API-t a konkrét megvalósításról.

### 3. lépés: meglévő logika áthelyezése
A mostani `VideoSearchService`-ből kezdd el átpakolni:
- fájlmentés,
- path resolve,
- query normalizálás,
- videó metadata,
- progress kezelés,
- match post-process.

### 4. lépés: videódekódoló komponens
Készítsd el a `JavaCvVideoDecoder` és `FrameSampler` osztályokat.  
Itt maradhat a JavaCV / FFmpegFrameGrabber alap. [github](https://github.com/bytedeco/javacv/blob/master/src/main/java/org/bytedeco/javacv/FFmpegFrameGrabber.java)

### 5. lépés: analysis pipeline
Bonts le mindent a következő sorrendbe:
1. decode
2. frame sampling
3. feature extraction
4. score
5. post-process
6. outcome build

### 6. lépés: async job rendszer
A directory searchhez és a hosszabb videókhoz külön job runner kell. A Spring async-nál fontos, hogy ne self-invocation-ből hívd az `@Async` metódust, és legyen külön executor. [dev](https://dev.to/realnamehidden1_61/how-does-async-work-internally-in-spring-boot-35h5)
Tehát:
- `AsyncConfig`
- `SearchJobExecutor`
- `DirectorySearchJobService`

### 7. lépés: controller tisztítás
A controller csak kérjen be requestet és adjon vissza response-t. Ne tartalmazzon keresési logikát.

### 8. lépés: tesztek
Külön teszteld:
- query parser,
- progress számítás,
- frame sampling,
- scoring,
- result post-process,
- controller endpointok.

## Javasolt osztályhierarchia

```text
controller
├─ VideoSearchController
├─ VideoBrowseController

service
├─ VideoSearchService
├─ VideoAnalysisService
├─ VideoStorageService
├─ QueryInterpretationService
├─ ProgressService
├─ ResultService

service.impl
├─ DefaultVideoSearchService
├─ DefaultVideoAnalysisService
├─ DefaultVideoStorageService
├─ DefaultQueryInterpretationService
├─ DefaultProgressService
├─ DefaultResultService

infrastructure.video
├─ JavaCvVideoDecoder
├─ VideoMetadataProbe

infrastructure.processing
├─ FrameSampler
├─ MotionFeatureExtractor
├─ LaneGeometryService
├─ EventScoringService
├─ EventPostProcessor

infrastructure.async
├─ AsyncConfig
├─ SearchJobExecutor
├─ DirectorySearchJobRunner

domain.model
├─ VideoAsset
├─ FrameObservation
├─ TrackedObject
├─ Trajectory
├─ EventCandidate
├─ EventMatch
├─ SearchOutcome
├─ JobProgress

domain.enum
├─ JobStatus
├─ QueryIntent
├─ EventType
```

## Konkrét metódustervek

### `QueryInterpretationService`
- `String normalizeQuery(String query)`
- `QueryIntent resolveIntent(String normalizedQuery)`
- `List<String> extractKeywords(String normalizedQuery)`

### `VideoStorageService`
- `String storeUploadedVideo(MultipartFile file)`
- `Path resolveVideoPath(String videoId)`
- `List<Path> listVideoFiles(Path directory)`
- `String createVideoId()`

### `VideoAnalysisService`
- `SearchOutcome analyzeVideo(String videoId, Path path, String query)`
- `List<EventCandidate> analyzeFrames(...)`
- `List<EventMatch> postProcessMatches(...)`
- `byte[] createPreviewFrame(...)`

### `FrameSampler`
- `long computeSampleStepUs(QueryIntent intent, long durationUs)`
- `boolean shouldSample(long timestampUs, long nextSampleUs)`
- `long advanceSampleTime(long currentSampleUs, long stepUs)`

### `MotionFeatureExtractor`
- `MotionMetrics computeMotionMetrics(...)`
- `Point2D computeCentroid(...)`
- `double computeResidualIntensity(...)`

### `EventScoringService`
- `double scoreWildlife(FrameObservation obs)`
- `double scoreCrossingVehicle(FrameObservation obs)`
- `double scoreTurn(FrameObservation obs)`
- `double scoreGenericMotion(FrameObservation obs)`

### `ProgressService`
- `String createJob(int total)`
- `void startFile(String jobId, String fileName)`
- `void updateFrameProgress(String jobId, int percent)`
- `void completeFile(String jobId)`
- `JobProgress getProgress(String jobId)`

## Milyen library-ket használj most

A mostani szakaszban én ezt tartanám optimálisnak:
- `spring-boot-starter-web`
- `spring-boot-starter-thymeleaf`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`
- `org.bytedeco:javacv-platform` [bytedeco](https://bytedeco.org/javacv/apidocs/org/bytedeco/javacv/FFmpegFrameGrabber.html)
- `lombok` opcionálisan, ha szeretnéd csökkenteni a boilerplate-et.
- `spring-boot-starter-test`

Ha később erősebb videó-AI jön, akkor külön integrációs modulba tedd, ne keverd bele a controller/service rétegbe.

## Rövid végrehajtási roadmap

1. Package-struktúra kialakítása.
2. Domain és DTO réteg megírása.
3. Query parser és progress service elkészítése.
4. Storage + video decode komponensek leválasztása.
5. Analysis orchestrator létrehozása.
6. Motion / deer / color logika külön service-ekbe bontása.
7. Async job runner és directory search rendezése.
8. Controller vékonyítása.
9. Tesztek és refaktor.
10. API stabilizálás.

## Fontos döntés

A mostani MVP-t ne próbáld azonnal „AI platformmá” alakítani. Először legyen egy tiszta, API-alapú Spring Boot backend, ahol a videófeldolgozás moduláris, szétválasztott és tesztelhető. Ez később sokkal könnyebben bővíthető, mint egy nagy monolit service. [docs.spring](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)

Ha akarod, a következő válaszban ezt tovább tudom bontani egy **konkrét fájlszintű migrációs tervre**, például:
- melyik jelenlegi osztályból mit kell kiszedni,
- milyen új fájlt kell létrehozni,
- és milyen sorrendben érdemes commitolni.