Igen, erre már érdemes egy **rendszeresen áttervezett, moduláris** tervet csinálni, mert a jelenlegi logikád erős heurisztikákra épül, de nehezen bővíthető, finomhangolható, és az új eseménytípusokhoz sok kézi szabályt kellene hozzáadni. A legjobb irány az, hogy a mostani „szín + mozgás + szabályok” logikát fokozatosan egy **észlelés–követés–események** felépítésű architektúrára cseréld, miközben megtartod a jelenlegi működést mint baseline-t. [real-phd.mtak](https://real-phd.mtak.hu/343/2/SzolgayDaniel_th_book_hun_vglgs.pdf)

## Mi a fő probléma most

A mostani rendszered erőssége, hogy működik és jól mérhető, viszont három gondja van. Egyrészt a query-módok nagyon korlátozottak, mert jelenleg főleg szín- és „deer/motion” heurisztikákra vannak bontva. Másrészt a globális mozgás, a színdominancia és az oldalpálya-score ugyan hasznos, de nem elég általános ahhoz, hogy kanyarodást, sávváltást, keresztbe menő autót vagy vadat egységesen kezeld. Harmadrészt a sok threshold miatt minden új esethez új kivételszabályok kellenek, ami hosszú távon karbantarthatatlan lesz. [arxiv](https://arxiv.org/abs/2004.05261)

## Célarchitektúra

A javasolt új architektúra négy rétegből álljon:

1. **Videó-előkészítés réteg.** Itt legyen frame-decode, stabilizálás, időmintavételezés, metaadat-olvasás, és opcionális scene split.
2. **Percepciós réteg.** Objektumdetektálás, sávdetektálás, mozgásbecslés, tracking.
3. **Eseményréteg.** Kanyar, előzés, sávváltás, keresztbe menő jármű, vadátkelés, veszélyes megjelenés.
4. **Keresési réteg.** A query-ből esemény- és szűrőparamétert csinálsz, és erre fut az indexelt videoanalízis. [docs.ultralytics](https://docs.ultralytics.com/modes/track/)

Ez a felosztás azért jó, mert a detektált objektumok és trajektóriák már újrahasznosíthatók több feladathoz, nem kell minden lekérdezéshez új logikát írni. [web.eecs.umich](http://web.eecs.umich.edu/~twenisch/papers/VehiQL_for_ITSC_FINAL.pdf)

## Mit változtatnék a mostani logikán

Az első nagy módosítás az lenne, hogy a jelenlegi `COLOR / DEER / MOTION` módokat cseréld le egy **intent/event taxonomy** rétegre. Például a query-ből ne csak azt döntsd el, hogy „deer vagy motion”, hanem azt, hogy melyik eseményt keresed: `turn`, `lane_change`, `crossing_vehicle`, `wild_animal`, `road_obstacle`, `anomaly`. Ezután minden eseményhez külön detektálási útvonal tartozzon, de ugyanazon közös videó- és tracking komponenseket használd. [arxiv](https://arxiv.org/html/2502.04244v1)

Második módosításként a jelenlegi kézi score-ok mellé kell egy **tanított eseményosztályozó**. A kézi szabályok maradhatnak első szűrőnek, de a végső döntést inkább egy tanuló modell hozza meg a trajektória-, mozgás- és kontextusfeature-ökből. Dashcam anomália és manőver felismerésnél ez stabilabb, mint a sok kézi küszöb. [arxiv](https://arxiv.org/pdf/2111.09171.pdf)

## Fejlesztési terv szakaszokban

### 1. szakasz: refaktor és közös adatmodell
Először egységesítsd a belső adatstruktúrákat. Legyen külön `FrameObservation`, `TrackedObject`, `LaneState`, `EgoMotion`, `EventCandidate`, `EventMatch` osztályod. A jelenlegi `SceneMatch` jó az eredményre, de kevés az analízis közbeni köztes állapotokhoz. Ettől a keresés sokkal tisztább lesz, és később könnyebb hozzáadni új eseményeket. [real-phd.mtak](https://real-phd.mtak.hu/343/2/SzolgayDaniel_th_book_hun_vglgs.pdf)

Konkrét feladatok:
- Válaszd szét a decode, feature extraction, scoring és post-process lépéseket.
- Minden frame-hez tárold a köztes feature-öket.
- A thresholdokat configba vidd, ne a kód mélyére.
- Készíts közös event result formátumot, amelyben minden esemény ugyanúgy jelenik meg.

### 2. szakasz: objektumdetektálás és tracking
A jelenlegi globális mozgás és color dominance mellé vezess be detektort és trackert. A legpraktikusabb kezdés egy YOLO-alapú detektor és egy multi-object tracker, mert ez valós idejű videóhoz jól használható és egyszerűen integrálható. A tracker minden objektumnak stabil ID-t ad, így később a trajektóriákból tudsz kanyarodást, keresztmozgást vagy vadátkelést felismerni. [docs.ultralytics](https://docs.ultralytics.com/de/modes/track/)

Konkrét feladatok:
- Válassz detektálandó osztályokat: car, truck, bus, person, bicycle, animal.
- Tedd a trackinget frame-enkénti alaprésszé.
- Számold az objektumok trajektóriáját, sebességét, irányát, sávhoz viszonyított helyzetét.
- Mentéskor logold a track history-t, ne csak a végső match-et.

### 3. szakasz: sáv- és útszerkezet
A kanyar és a keresztbe menő jármű detektálása szinte biztosan kell, hogy tudja, mi számít „sávnak” vagy „útirányhoz viszonyított keresztmozgásnak”. Ezért kell lane detection vagy legalább road-centerline becslés. A dashcam eseményértésben a kamera mozgáskompenzáció és a geometriára épülő feldolgozás alaplépés. Ezzel meg tudod különböztetni a valódi keresztbe menést a sima perspektíva- vagy kameraelmozdulástól. [arxiv](https://arxiv.org/abs/2004.05261)

Konkrét feladatok:
- Detektáld a sávvonalakat vagy az útközéppontot.
- Becsüld az ego-jármű haladási tengelyét.
- Határozd meg, mely objektum halad keresztirányban az útpályához képest.
- Mentsd el az útpálya és sáv-geometria időbeli stabilitását.

### 4. szakasz: eseménylogika
Itt jön a tényleges intelligencia. A kanyarodást, lane change-et, keresztbe menő autót és vadat külön eseményként kezeld. Mindegyikhez ugyanaz a közös elv kell: objektumdetektálás + követés + geometriához viszonyított trajektória + időbeli aggregálás. [roadecology.ucdavis](https://roadecology.ucdavis.edu/conference-proceedings-and-papers/rapid-detection-and-identification-roadside-wildlife-using-cctv)

Példák:
- **Kanyar:** az ego-irány vektora tartósan változik, a sávgeometria ívet mutat, a teljes frame mozgásból vagy lane centerből becsült heading változik.
- **Keresztbe menő autó:** egy track rövid idő alatt jelentős keresztirányú mozgást végez, átlépi az útpálya releváns régióját.
- **Vadátkelés:** animal osztályú objektum belép az úttérbe, majd keresztirányban halad, gyakran rövidebb és bizonytalanabb trajektóriával.
- **Szabálytalanság:** wrong-way, hirtelen sávváltás, teljesen váratlan stoppolás vagy útburkolaton kívüli mozgás. [mech.chuo-u.ac](https://www.mech.chuo-u.ac.jp/umedalab/publications/pdf/2021/SII2021hashimoto.pdf)

## Az algoritmus új logikája

A jelenlegi score-alapú rendszert bontsd két részre. Az első rész legyen **candidate generation**, ahol könnyű szabályokkal rengeteg potenciális eseményt találsz. A második rész legyen **event verification**, ahol tanított modell vagy szabályosztályozó dönt a végső találatról. Ez hasonlít a mai video anomaly detection megközelítésekhez is, ahol a mozgásminták és a temporális kontextus együtt számítanak. [mech.chuo-u.ac](https://www.mech.chuo-u.ac.jp/umedalab/publications/pdf/2021/SII2021hashimoto.pdf)

Jó javasolt feature-ek:
- trajektória görbülete,
- object speed,
- lateral velocity,
- sávhoz viszonyított pozíció,
- belépés az útpályára,
- track életideje,
- detektálási confidence,
- kamera ego-mozgás,
- scene context.

## Milyen modellel tervezd meg

Ha az a kérdés, hogy melyik modell a legalkalmasabb az **algoritmus megtervezésére**, akkor én két szintet javasolnék:

- **Tervezési szintre:** egy általános video foundation model vagy multimodális modell, mert jobb közös reprezentációt ad a videós mintákhoz és eseményekhez. [research](https://research.google/blog/videoprism-a-foundational-visual-encoder-for-video-understanding/)
- **Implementációs szintre:** YOLO-alapú detektor + tracker, mert ez praktikus, gyors, és jól dokumentált multi-object trackingre. [docs.ultralytics](https://docs.ultralytics.com/modes/track/)

Ha egyetlen modellt kell mondani a tervezői gondolkodáshoz, akkor a video foundation modell jobb. Ha a konkrét Java rendszeredbe gyorsan be akarod rakni az első működő verziót, akkor YOLO + tracking a jó választás. [research](https://research.google/blog/videoprism-a-foundational-visual-encoder-for-video-understanding/)

## Java-oldali módosítások

A te Java programodnál érdemes ezeket a komponenseket külön szolgáltatásba szedni:
- `VideoDecoderService`
- `FrameSampler`
- `ObjectDetectionService`
- `TrackingService`
- `LaneAnalysisService`
- `EventScoringService`
- `EventPostProcessor`
- `SearchOrchestrator`

Így a jelenlegi `VideoSearchService` túlnőtt felelősségét szét tudod bontani. Java-ban ehhez jó irány a komponens-alapú felépítés és az aszinkron munkafolyamatok megtartása, de szigorúbb felelősségmegosztással. [tutkit](https://www.tutkit.com/hu/szoeveges-oktatoprogramok/20502-beviteli-es-kimeneti-optimalizalas-java-ban)

Konkrét kódátszervezési terv:
- A `analyzeVideo(...)` ne közvetlenül scoringoljon, csak orchestration legyen.
- A frame-ciklusból különítsd ki a feature-extraction lépést.
- A `computeDeerScore(...)` helyett legyen `computeEventScore(eventType, features)`.
- A `postProcessDeerMatches(...)` helyére generikus `postProcessMatches(eventType, matches)`.
- A hardcoded thresholdok helyett config-driven rules legyenek.

## Adat és tanítás

Ha javítani akarod a minőséget, adat kell. Én ezt a sorrendet javaslom:
1. Gyűjts 100–500 rövid videórészletet a fő eseményekre.
2. Kézzel címkézd az eseményeket időintervallumokkal.
3. A jelenlegi heurisztikáddal generálj jelölteket, és ezekből csinálj tanítóhalmazt.
4. Taníts egy kis temporal classifier-t vagy event verifier modellt.
5. Ezt használd a kézi score fölött második védelemnek. [web.eecs.umich](http://web.eecs.umich.edu/~twenisch/papers/VehiQL_for_ITSC_FINAL.pdf)

## Tesztelés és mérés

A jelenlegi async teszt-scripted jó alap, ezt ki kell terjeszteni eseményszintű mérésre. Ne csak azt nézd, hogy „talált-e valamit”, hanem azt is, hogy mennyire pontosan találta el az időt és az esemény típusát. Eseménydetektálásnál fontos a precision, recall, F1, és külön az event-level IoU vagy időablakos egyezés. [roadecology.ucdavis](https://roadecology.ucdavis.edu/conference-proceedings-and-papers/rapid-detection-and-identification-roadside-wildlife-using-cctv)

Javasolt metrikák:
- event precision.
- event recall.
- F1.
- false positives per hour.
- timestamp offset.
- track purity.
- per-class recall.

## Prioritási sorrend

Ha röviden kell priorizálni, akkor ezt tenném:
1. Adatmodell és pipeline-szétbontás.
2. YOLO + tracker integráció.
3. Lane/road geometry.
4. Kanyar és keresztmozgás események.
5. Vadátkelés külön osztályként.
6. Heurisztika + tanult verifikátor kombináció.
7. Eredményminőség-mérés és threshold tuning. [docs.ultralytics](https://docs.ultralytics.com/modes/track/)

## Ajánlott következő lépés

A legjobb következő lépés az lenne, hogy a meglévő `VideoSearchService`-edhez készítsünk egy **konkrét refaktor-tervet fájlszinten és osztályszinten**, például:
- melyik osztály marad,
- melyik megy szét,
- milyen új interfészek lesznek,
- és milyen sorrendben érdemes átírni, hogy közben működő maradjon a rendszer.

Meg tudom csinálni ezt egy **részletes technikai migrációs tervként** is, akár úgy, hogy fejezetenként megadom: mit kell törölni, mit kell megtartani, mit kell újonnan bevezetni.