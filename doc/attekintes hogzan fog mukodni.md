Igen — erre már egy **végrehajtási roadmapet** érdemes írni, nem csak ötletlistát. A jó irány az, hogy a mostani Spring Boot MVP-ből egy tisztán szétválasztott, később iPhone-ra is átültethető videókereső platform legyen, ahol a webes backend csak az orchestrációt végzi, a videóelemzés pedig külön, moduláris pipeline-ban fut. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
## Célarchitektúra
A rendszeredet úgy érdemes felépíteni, hogy a UI, az API, az analitika és a tárolás külön réteg legyen. A jelenlegi controller–service–model szerkezet maradhat kiindulásnak, de a service-t szét kell bontani kisebb, egyfelelős komponensekre, mert a hosszú, sokmindent csináló metódusok nehezen tesztelhetők és nehezen fejleszthetők tovább. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
A cél az, hogy a videófeldolgozás később ne csak webre, hanem iPhone / mobileszköz / edge backend / felhős backend kombinációra is átültethető legyen. [blog.devgenius](https://blog.devgenius.io/clean-architecture-with-spring-boot-a-complete-guide-0b0a39695181)
## Fő komponensek
A végső rendszerben ezek a fő objektumok legyenek:

- `VideoIngestionService`, a feltöltés, tárolás, transzformáció és metaadat-nyilvántartás miatt.
- `VideoAnalysisOrchestrator`, amely összehangolja a dekódolást, samplinget, detektálást, trackinget és eseményosztályozást.
- `FrameSampler`, amely a videót stabil, konfigurálható időlépésekben mintavételezi.
- `ObjectDetector`, amely autót, embert, állatot, akadályt stb. ismer fel.
- `Tracker`, amely egyedi track ID-t ad az objektumoknak.
- `LaneAndRoadModel`, amely a sávot, útvonalat, középvonalat vagy haladási tengelyt becsli.
- `EventDetector`, amely a kanyart, keresztbe menést, vadátkelést, oncoming mintát és anomáliát állítja elő.
- `EventRanker`, amely a releváns találatokat rangsorolja.
- `SearchQueryParser`, amely a szöveges lekérdezést esemény-intencióvá alakítja.
- `ResultAssembler`, amely az UI-nak visszaadható `SearchOutcome` jellegű választ épít. [youtube](https://www.youtube.com/watch?v=AirTRCjCYWY)

Ez a felosztás jól illeszkedik a Spring-ös clean architecture / moduláris felépítéshez, ahol az üzleti logika nem függ az API-rétegtől. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
## Fő módszertanok
A rendszerhez nem egyetlen módszertan kell, hanem több együtt:
### 1. Percepciós pipeline
Először a videóból nyers vizuális jelet gyűjtesz. Ide tartozik a frame-decode, az időmintavételezés, a képszűrés, az esetleges stabilizálás és a region-of-interest kezelése. [github](https://github.com/bytedeco/javacv/blob/master/src/main/java/org/bytedeco/javacv/FFmpegFrameGrabber.java)
### 2. Detektálás és követés
A klasszikus és legstabilabb út: detektálás + tracking. A detektor megmondja, mi van a képen, a tracker pedig azt, hogy ugyanaz az objektum hogyan mozog időben. Ez különösen fontos keresztbe menő autók és vadak esetén. [docs.ultralytics](https://docs.ultralytics.com/modes/track/)
### 3. Geometriai eseményazonosítás
A kanyarodás, sávváltás, keresztirányú áthaladás és „helytelen irányú” mozgás nem egyszerű képosztályozási probléma, hanem mozgásgeometriai probléma is. Itt az objektumtrajektória, az út tengelye és a sávgeometria együtt ad döntést. [web.eecs.umich](http://web.eecs.umich.edu/~twenisch/papers/VehiQL_for_ITSC_FINAL.pdf)
### 4. Heurisztika + tanított verifikáció
A mostani score-alapú logika maradhat candidate generálásra, de a végső döntést érdemes tanított modellre vagy legalább egy második szintű classifierre bízni. Ez különösen hasznos dashcam anomaly detection esetén. [arxiv](https://arxiv.org/abs/2004.05261)
## Milestone terv
### Milestone 1: architektúra szétválasztása
Ebben a fázisban a cél nem új funkció, hanem a mostani kód tisztítása. Szét kell szedni a `VideoSearchService` felelősségét kisebb szolgáltatásokra, és definiálni a közös domain objektumokat. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
Itt készüljenek el az új osztályok és interfészek, de az eredeti működés még maradjon meg fallbackként. Ez a lépés csökkenti a későbbi migrációs kockázatot.
### Milestone 2: közös video domain modell
Kialakítandó objektumok:
- `VideoAsset`
- `VideoJob`
- `FrameObservation`
- `TrackedObject`
- `Trajectory`
- `EventCandidate`
- `EventMatch`
- `SearchRequest`
- `SearchContext`
- `AnalysisConfig` [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)

Ezek közül a legfontosabb a `FrameObservation`, mert ebben tárolod a frame-hez tartozó nyers és számolt feature-öket. Így később nem kell újra-dekódolni minden eseményhez ugyanazt a videórészt.
### Milestone 3: detektor és tracker bevezetése
Ez lesz az első valódi minőségi ugrás. A cél, hogy a jelenlegi pixel-szintű heurisztikák mellé bekerüljön egy objektumdetektor és egy többobjektumos tracker. [docs.ultralytics](https://docs.ultralytics.com/modes/track/)
Ekkor már nem csak azt nézed, hogy „valami mozgott”, hanem azt is, hogy melyik objektum, merre, milyen sebességgel és milyen útviszonyban mozgott.
### Milestone 4: út- és sávgeometria
A videókeresésben a kanyar és a keresztmozgás megértéséhez kell egy útirány-referencia. Ehhez lane detection vagy legalább road centerline estimation szükséges. [web.eecs.umich](http://web.eecs.umich.edu/~twenisch/papers/VehiQL_for_ITSC_FINAL.pdf)
Ha ez kész, akkor a rendszered már meg tudja különböztetni a valódi keresztbe menést a kamera-perspektíva okozta látszólagos mozgástól.
### Milestone 5: eseménydetektor réteg
Itt épülnek meg az események:
- turn detection.
- lane change detection.
- crossing vehicle detection.
- animal crossing detection.
- anomaly detection.
- oncoming / wrong-way detection. [web.eecs.umich](http://web.eecs.umich.edu/~twenisch/papers/VehiQL_for_ITSC_FINAL.pdf)

Ezt kétlépcsősen érdemes csinálni: candidate generation és candidate verification. Először sok jelöltet adsz, aztán csak a jókat engeded át.
### Milestone 6: query nyelv és szándékfelismerés
A jelenlegi szín/deer/motion logika helyett alakíts ki egy query parser réteget. A szöveg alapján azonosítsd az intentet, majd abból konfiguráld az elemzési pipeline-t. [blog.devgenius](https://blog.devgenius.io/clean-architecture-with-spring-boot-a-complete-guide-0b0a39695181)
Például a „kanyar”, „balra kanyarodik”, „vad”, „keresztbe megy”, „szabálytalan”, „állat az úton” lekérdezések külön event mappinget kapjanak.
### Milestone 7: tanított verifikátor
Ha az első heurisztikus rendszer már működik, abból készíts tanítóhalmazt. A candidate eseményekből legyen label-elt dataset, amiből egy kisebb temporal classifier vagy event verifier tanulhat. [arxiv](https://arxiv.org/abs/2004.05261)
Ez különösen fontos akkor, amikor majd mobilra vagy iPhone-ra át akarod emelni a logikát, mert ott a számítási költséget jobban kell kontrollálni.
### Milestone 8: mobile/backend split
iPhone-ra két irány van:
- vagy a videófeltöltés és keresés a backendben marad, az iPhone csak kliens,
- vagy a kliens oldalon is fut valamilyen könnyített analitika.  

A gyakorlatban az első a reálisabb kezdés. A mobil app csak feltölt, keres, progress-t mutat, és az eredményt listázza, míg a nehéz videóanalitika a backendben fut. Később lehet edge inference vagy on-device rész, de nem ezt tenném első verzióba. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
## Fejlesztési sorrend
### 1. Kódrefaktor
Először ne új algoritmust írj, hanem bontsd szét a jelenlegi kódot. A cél, hogy a videóolvasás, feature-képzés, scoring és result-építés külön fájlokba kerüljön. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
### 2. Objektum- és trajektória-réteg
A tracking-et tedd központi elemmé. Minden későbbi event ehhez fog kapcsolódni.
### 3. Események bevezetése
Először csak 2-3 eseményre fókuszálj:
- kanyar,
- keresztbe menő jármű,
- vadátkelés.
### 4. Validációs eszközök
Készíts egy mérőszám-rendszert:
- precision,
- recall,
- event-level F1,
- false positive per hour,
- timestamp eltérés. [arxiv](https://arxiv.org/abs/2004.05261)
### 5. Mobilkész backend API
Még a web UI megtartása mellett legyen REST API, ami később iPhone appból is hívható lesz.
### 6. Mobil kliens
A későbbi iPhone app inkább egy React Native / native iOS vagy egyszerű Swift frontend legyen, ami a backend API-t használja. A fő analitika ne az appban legyen.
## Ajánlott Java könyvtárak
A mostani stack mellé ezeket érdemes használnod:
### Videó és kép
- **JavaCV / FFmpegFrameGrabber**: a videó dekódolásra és frame-kezelésre.
- **OpenCV bindingek JavaCV-n keresztül**: mozgás, maszkok, geometric műveletek. [github](https://github.com/bytedeco/javacv/blob/master/src/main/java/org/bytedeco/javacv/FFmpegFrameGrabber.java)
### Aszinkron feldolgozás
- **Spring `@Async` + `CompletableFuture`**: a hosszabb futású keresésekhez és directory scanhez. [oneuptime](https://oneuptime.com/blog/post/2026-01-30-spring-completablefuture-async/view)
- **Spring TaskExecutor / ThreadPoolTaskExecutor**: saját thread poolokhoz.
### API és validáció
- **spring-boot-starter-web**
- **spring-boot-starter-validation**
- **spring-boot-starter-actuator**: monitoringhoz.
- **spring-boot-starter-test**: integrációs és service tesztekhez. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
### Kliens oldali, ha lesz mobil
- iPhone-hoz később REST API-t érdemes kiszolgálni, nem Java libet közvetlenül.
- Ha mobilon is on-device kell analitika, ott külön iOS stack kell majd, nem Java.
## Hogyan működjön az egész
A működés legyen ilyen:

1. A kliens feltölt egy videót vagy kiválaszt egy helyi fájlt.
2. A backend eltárolja és regisztrálja.
3. A `SearchQueryParser` az inputból intentet képez.
4. A `VideoAnalysisOrchestrator` elindítja a releváns pipeline-t.
5. A frame-ekből feature-ök készülnek.
6. Az objektumdetektor és tracker eredményei trajektóriává állnak össze.
7. A `LaneAndRoadModel` kiszámítja az útgeometriát.
8. Az `EventDetector` jelölteket képez.
9. A `EventRanker` kiválasztja a legjobbakat.
10. A frontend időbélyeggel, preview-val és ugrási lehetőséggel megkapja az eredményt. [docs.ultralytics](https://docs.ultralytics.com/modes/track/)
## Technikai elvek
A megoldás akkor lesz jó, ha ezek teljesülnek:

- A videóelemzés idempotens legyen.
- A pipeline konfigurálható legyen.
- Minden esemény külön mérhető legyen.
- A heurisztika és a tanított modell külön réteg legyen.
- Az API maradjon stabil, hogy mobil kliens is rá tudjon csatlakozni.
- Minden hosszú futású folyamat aszinkron legyen. [oneuptime](https://oneuptime.com/blog/post/2026-01-30-spring-completablefuture-async/view)
## Konkrét első 30 napos terv
### 1. hét
- Refaktorálod a service réteget.
- Létrehozod a domain objektumokat.
- Bevezeted a közös event modelleket.
### 2. hét
- Detektor + tracker integráció.
- Egyszerű trajektória-gyűjtés.
### 3. hét
- Lane/road geometry modell.
- Kanyar és keresztmozgás első verziója.
### 4. hét
- Vadátkelés és anomália első verziója.
- Mérőszámok és teszt dataset.
- REST API stabilizálása mobilhoz. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
## Javasolt döntés most
Most ne iPhone-nal kezdd, hanem egy **backend-first, event-driven videókereső motorral**. Ez lesz a tartós alap, és erre később könnyen rá tudsz kötni egy iPhone klienst is. [makariev](https://www.makariev.com/blog/advanced-spring-boot-structure-clean-architecture-modulith/)
A legfontosabb első lépés a kód szétbontása és a tracking-alapú eseménymodell bevezetése, mert ettől lesz a jelenlegi MVP valódi, bővíthető termék.
