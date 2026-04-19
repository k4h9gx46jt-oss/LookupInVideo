# LookupInVideo - mukodesi attekintes

## 1. Mi ez a projekt?
A LookupInVideo egy Spring Boot alapu webalkalmazas, amellyel videot lehet feltolteni es egyszeru szoveges keresessel jeleneteket talalni.

A jelenlegi allapot egy MVP (minimum viable product):
- mukodik a feltoltes,
- mukodik a talalatlistazas,
- mukodik a videoba ugrasi lehetoseg,
- a keresesi logika harom uzemmodot hasznal (piros, szarvas, altalanos mozgas).

## 2. Technologiai alapok
- Backend: Java 17, Spring Boot 3.3.5
- Web: Spring MVC + Thymeleaf
- Video feldolgozas: JavaCV (FFmpegFrameGrabber)
- Build: Maven

Fo fuggosegek a pom.xml-ben:
- spring-boot-starter-web
- spring-boot-starter-thymeleaf
- spring-boot-starter-validation
- org.bytedeco:javacv-platform

## 3. Hogyan lehet elinditani?

### 3.1 Scriptes inditas (ajanlott)
A projekt gyokerben:

1. Elso alkalommal futtathatova tenni:
   chmod +x run-videolookup-app.sh run-app.sh
2. Inditas:
   ./run-videolookup-app.sh
   vagy
   ./run-app.sh
3. Bongeszo:
   http://localhost:8080

### 3.2 Port modositas
Mas porttal:
PORT=8090 ./run-app.sh

### 3.3 Build-only mod
Ha csak forditani szeretnel futas nelkul:
./run-app.sh --build-only

### 3.4 Kozvetlen Maven inditas
./mvnw spring-boot:run

## 4. End-to-end mukodes (felhasznaloi folyamat)

### 4.1 Egyfajlos kereses (eredeti)
1. A felhasznalo megnyitja a nyito oldalt (/).
2. Feltolt egy video fajlt es beir egy keresesi szoveget.
3. A /search endpoint fogadja a kerdest.
4. A backend elmenti a videot az uploads mappaba, egy egyedi videoId azonositohoz kotve.
5. Elindul a video elemzese frame mintavetellel.
6. A rendszer talalatokat general (idopont + pontszam + indok + elonezeti kep).
7. A results oldalon megjelenik:
   - a video lejatszo,
   - a talalati lista,
   - az egyes talalatokra ugrasi gomb.
8. Ugraskor a lejatszo currentTime erteke a talalat masodpercere all, es indul a lejatszas.

### 4.2 Helyi konyvtar tallozasa
1. A felhasznalo a "Helyi konyvtar" fulon megadja a konyvtar eleresi utjat.
2. A GET /browse endpoint listazza az osszes videofajlt (.mp4, .avi, .mov, stb.).
3. A lista megjelenik a fajlbongeszobe, kivalasztott fajl kiemelessel.
4. A felhasznalo kivalaszt egy fajlt, majd elindíthatja a keresest az adott fajlra (POST /search-local)
   vagy az egesz konyvtarra (POST /search-dir).

### 4.3 Konyvtarszintu aszinkron kereses progress kovetessel
1. A felhasznalo beir egy konyvtarutat es egy keresesi szoveget, majd kattint a
   "Kereses az egesz konyvtarban" gombra.
2. A frontend fetch()-el POST /search-dir-t hiv; a szerver azonnal visszaadja a {"jobId": "..."} valaszt.
3. A backend CompletableFuture-ben vegigmegy az osszes videofajlon sorban.
4. A frontend 400 ms-enkent lekeri a GET /progress/{jobId} endpointot.
5. A progress overlay megjelenik a kepernyon:
   - Aktualis fajl neve + hanyad a sorban (pl. "3 / 10").
   - Aktualis fajlon beluli frame-haladast mutat egy lilaszinu sav.
   - Az osszes feldolgozas aranyat egy zold szazaleksav mutatja szammal.
6. Ha egy fajl hibas, a feldolgozas folytatodik a tobbin (hibatureles).
7. Amikor az osszes fajl kesz, a frontend a GET /results/{jobId} oldalra navigal.
8. A results-dir oldalon fajlonkent jelenik meg az osszes talalat, osszesito statisztikakkal;
   minden fajlhoz egy csukhatoan megjelenitheto videolejatszo is tartozik.

## 5. Keresesi logika - pontos algoritmus

Ez a fejezet a jelenlegi implementaciot irja le 1:1-ben, kulon kiemelve a keresztiranyu (deer/szarvas) mozgast.

### 5.0 Ervenyes kulcsszavak (HU/EN) es mit csinalnak

- COLOR mod (szinalapu kereses + mozgas boost):
  - piros / red
  - zold / green
  - kek / blue
  - Ha tobb szin-kulcsszo szerepel, a rendszer ezek kozul a legerosebb szin-dominanciat hasznalja.
- DEER mod (keresztiranyu szarvas-jellegu mozgas):
  - szarvas / deer
  - A rendszer az oldaliranyu atfutast preferalja, es szuri az oncoming vehicles
    (szembol kozeledo jarmuvek) mintakat.
- MOTION mod (altalanos mozgas):
  - minden mas kulcsszo / barmilyen egyeb szoveg.
- Prioritas:
  - Ha a queryben szin-kulcsszo is van, COLOR mod kapcsol be
    (akkor is, ha a szovegben szerepel a szarvas/deer szo).

### 5.1 Elokeszites es mintavetelezes
- A query normalizalasa: kisbetusites + ekezetek eltavolitasa.
- Uzemmod valasztas:
  - COLOR: ha query tartalmazza a "piros/red", "zold/green" vagy "kek/blue" szot.
  - DEER: ha query tartalmazza a "szarvas" vagy "deer" szot.
  - MOTION: minden mas eset.
- Mintaveteli lepeskuzob idoben:
  - DEFAULT_SAMPLE_STEP_US = 1_000_000 us (1.0 s)
  - DEER_SAMPLE_STEP_US = 250_000 us (0.25 s)
- Minden vizsgalt frame max 320 px szelesre van skalazva.
- Pixelbejras ritkitva: x,y tengelyen 2 pixelenkent.

### 5.2 Mozgasmetrikak (DEER mod alapja)

#### 5.2.1 Globalis kameraelmozdulas becslese
A cel: szetvalasztani a "kamera megy elore" jellegu hossziranyu hattermozgast attol, ami lokalis keresztmozgast okoz (pl. szarvas).

- A rendszer dx,dy eltolast keres az elozo es aktualis frame kozott:
  - dx tartomany: [-8, 8]
  - dy tartomany: [-6, 6]
  - minta stride: 4 pixel
- Minden jelolt eltolasra luma-alapu atlagos abszolut hibaatlagot szamol.
- Luma: (299*R + 587*G + 114*B) / 1000.
- Regularizacio: error += 0.15 * sqrt(dx^2 + dy^2), hogy ne valasszon indokolatlanul nagy eltolast.
- A legkisebb hibaju dx,dy lesz a globalShift.

#### 5.2.2 Nyers es kompenzalt mozgaskep
- intensity (nyers): elozo vs aktualis pixelkulonbseg atlag, kompenzacio nelkul.
- residualIntensity (kompenzalt): elozo vs eltolt aktualis frame kulonbseg atlag.
  - Ez mar jobban mutatja a lokalis objektummozgast, mert a hattermozgas egy reszet kivonjuk.

#### 5.2.3 Aktiv mozgaspixelek es ROI
- ROI (ahol aktiv mozgast szamolunk):
  - x: 10% - 90%
  - y: 22% - 95%
- Aktiv pixel feltetel: residual diff >= 0.11.
- activeRatio = aktivPixelek / roiPixelek.
- Mozgas-sulypont (centroid) diff-sulyozottan szamolva:
  - centroidX, centroidY normalizalt [0,1] tartomanyban.
- centerRatio: az aktiv mozgas sulyanak mekkora resze esik a kozepso regioba:
  - center x: 26% - 74%
  - center y: 25% - 90%

#### 5.2.4 Hossztengely tanulasa es keresztiranyu arany
- A rendszer fenntart egy becsult longitudinalAxis vektort (hossziranyu kamera-folyas).
- Frissites akkor tortenik, ha |globalShift| >= 0.45.
- Sima frissites:
  - axis = axis*0.88 + shiftUnit*0.12
  - majd normalizalas.
- Objektumelmozdulas a residual centroidokbol:
  - objectShift = currentCentroid - previousCentroid
  - objectSpeed = |objectShift|
- Szetszedes parhuzamos + kereszt komponensre:
  - parallel = |dot(objectShift, axis)|
  - cross = |dot(objectShift, axis_perp)|
  - crossMotionRatio = cross / (cross + parallel + 1e-6)
- travelScore = clamp(objectSpeed * 7.0, 0, 1).

#### 5.2.5 Burst (mozgas-kitores)
- motionEma exponencialis atlag:
  - ema = ema*0.85 + burstBase*0.15
- DEER modban burstBase = residualIntensity.
- burstScore = max(0, burstBase - motionEma).

### 5.3 Deer Score (pontosan)

#### 5.3.1 Reszkomponensek
- compactness = clamp((residualIntensity / activeRatio) / 3.7, 0, 1), ha activeRatio nagyon kicsi, akkor 0.
- foregroundIsolation = 1 - clamp(activeRatio / 0.14, 0, 1).
- residualGate = clamp((residualIntensity - 0.010) / 0.070, 0, 1).
- directionalConfidence = clamp(crossMotionRatio * travelScore * residualGate, 0, 1).
- laneCenterFocus = 1 - clamp(|centroidX - 0.5| / 0.5, 0, 1).

#### 5.3.2 Vegso keplet
deerScore = clamp(
  directionalConfidence * 0.40 +
  residualIntensity * 0.22 +
  centerRatio * 0.11 +
  burstScore * 0.10 +
  foregroundIsolation * 0.06 +
  compactness * 0.07 +
  laneCenterFocus * 0.04,
  0, 1
)

#### 5.3.3 Elso szintu talalati kuszob
- DEER modban egy frame-jelolt talalat, ha deerScore >= 0.09.
- A nyers idopontbol 1.1 mp visszahuzas tortenik:
  - timestamp = max(0, timestamp - 1.1)
  - Ez a belepes kozeli idopontot adja vissza, nem a kesobbi score-csucsot.

### 5.4 Deer utoszures (miert talalja meg mindket iranyt)

Ez a blokk azert van, hogy a rovid keresztmozgast ne nyomjak el kesobbi, eros csucsok.

- 1) Jeloltek idorendbe rendezese.
- 2) Temporalis klaszterezes 2.6 mp ablakkal (DEER_CLUSTER_WINDOW_SECONDS):
  - Minden klaszterbol egy reprezentans marad.
  - A reprezentans confidence-e a klaszter legerosebb pontja.
  - A reprezentans idopontja a klaszter legkorabbi pontja.
  - Ez segit, hogy a crossing eleje maradjon meg.
- 3) Reprezentansok confidence szerint rendezve, dinamikus kuszob:
  - dynamicThreshold = clamp(topScore * 0.50, 0.09, 0.29)
- 4) Kivalasztas:
  - az elso 3 eros jeloltet akkor is megtartja, ha kuszob alatt van,
  - utana kuszob alattiakat eldobja,
  - 0.95 mp-en belul tul kozelieket osszevon.
- 5) Max talalatszam: 12.
- 6) Vegso kimenet idorendben.

### 5.5 Masik ket mod roviden

#### 5.5.1 COLOR mod (piros/zold/kek)
- colorDominance = a keresett szin(ek) dominans pixeleinek aranya.
- Tamogatott kulcsszavak: piros/red, zold/green, kek/blue.
- Vegso score: colorScore = clamp(colorDominance * 0.78 + colorMotionBoost * 0.22, 0, 1).
- colorMotionBoost a kompenzalt mozgasbol (residual mozgas-intenzitas) jon, ez segit a
  hirtelen kepen atmeno nagy szines objektumok megtalalasaban.
- Talalat, ha colorScore >= 0.14.

#### 5.5.2 MOTION mod
- Score = intensity (nyers framekulonbseg-atlag).
- Talalat, ha score >= 0.18.

### 5.6 Mit jelent ez a gyakorlatban szarvasnal?
- A kamera hossziranyu haladasat a globalShift + longitudinalAxis modellezi.
- A keresztiranyu atfutas akkor kap magas pontot, ha:
  - a residual (kompenzalt) mozgas valoban jelen van,
  - az objektum elmozdulasa a hossztengelyre meroleges komponensben eros,
  - a jel lokalisan koncentralt, es nem teljes kepes villodas.
- Emiatt ugyanaz a logika kezeli a jobbrol-balra es balrol-jobbra keresztmozgast is.

## 6. Mit lat a felhasznalo a talalatoknal?
Minden talalatnal:
- idopont (formatum: mm:ss vagy hh:mm:ss),
- indoklas (pl. "Piros dominancia: 18.2%" vagy "Mozgas-intenzitas: 24.5%"),
- kep elonezet (base64 JPEG),
- "Ugras a videohoz" gomb.

## 7. Hogyan jon a video stream?
A /media/{videoId} endpoint adja vissza a feltoltott fajlt:
- a videoId alapjan kikeresi a fajl utvonalat,
- beallitja a content type-ot,
- inline modban streameli vissza a bongeszonek.

## 8. Fontos korlatok az aktualis MVP-ben
- A "szoveges kereses" jelenleg nem teljes NLP/objektumfelismeres.
- A "piros/red", "zold/green", "kek/blue" query tenylegesen szinalapu.
- A "szarvas/deer" mod egy heurisztikus keresztmozgas-detektor, nem teljes objektumdetektor.
- Minden mas query demo jelleggel mozgas-intenzitas alapu.
- Ujrainditas utan az in-memory videoId->path registry elveszik.
  (A feltoltott fajl marad a diszken, de az elo mapping nem.)

## 9. Fo fajlok es szerepuk
- src/main/java/com/gazsik/lookupinvideo/LookupInVideoApplication.java
  - Spring Boot belepesi pont.
- src/main/java/com/gazsik/lookupinvideo/controller/VideoSearchController.java
  - Endpointok: /, /browse, /search, /search-local, /search-dir,
    /progress/{jobId}, /results/{jobId}, /media/{videoId}.
- src/main/java/com/gazsik/lookupinvideo/service/VideoSearchService.java
  - Tarolas + frame mintavetel + pontozas + talalatkepzes.
  - Konyvtarszintu aszinkron kereses (startDirectorySearch).
  - Progress- es eredmenyterkep (ConcurrentHashMap).
- src/main/java/com/gazsik/lookupinvideo/model/SceneMatch.java
  - Egy talalat adatszerkezete.
- src/main/java/com/gazsik/lookupinvideo/model/SearchOutcome.java
  - Teljes keresesi eredmeny adatszerkezete (tartalmazza a fileName mezot is).
- src/main/java/com/gazsik/lookupinvideo/model/JobProgress.java
  - Aszinkron job allapota: feldolgozott/osszes fajl, frame-szazalek,
    statusText, befejezetteg-jelzo, hibamezo.
- src/main/resources/templates/index.html
  - Feltolto + kereso oldal, fajlbogeszo tab, progress overlay.
- src/main/resources/templates/results.html
  - Egyfajlos eredmenyoldal: video + talalati kartyak + ugrasi logika.
- src/main/resources/templates/results-dir.html
  - Tobfajlos eredmenyoldal: osszesfoglo statisztika + fajlonkenti
    talalati kartyak csukhatoan megjelenitheto videolejatszoval.
- src/main/resources/application.properties
  - Multipart limitek, tarolasi path, port.

## 10. Konfiguracio
application.properties:
- spring.servlet.multipart.max-file-size=1024MB
- spring.servlet.multipart.max-request-size=1024MB
- lookup.video.storage-path=uploads
- server.port=8080

## 11. Tovabbfejlesztesi iranyok
- Valodi objektumfelismeres (pl. auto/kutya/szarvas/kamion) frame vagy clip szinten.
- Jobb indexeles (shot boundary, kulcskocka-kivalasztas).
- ~~Aszinkron feldolgozas hosszabb videokhoz.~~ (KESZ: konyvtarszintu aszinkron kereses progress-szel)
- Tartos metadata tarolas (DB), hogy ujrainditas utan is maradjon lookup.
- Talalati timeline es finomabb rangsor.

## 12. Konyvtarszintu aszinkron kereses es progress kovetese

### 12.1 Miert kell ez?
Egy vadkamera-konyvtar tobb tucat 200+ MB-os fajlt tartalmazhat. Ha az osszes fajlt
egymast kovetve kell feldolgozni, a szinkron HTTP-valasz keses percekig is tarthat.
Az aszinkron megkozelites es a vizualis progress-kovetese ettol a problemától szabadul meg.

### 12.2 Aszinkron feldolgozas mukodese
1. POST /search-dir vegpont indulaskor visszaad egy jobId-t (UUID) azonnal.
2. A backend CompletableFuture.runAsync()-ban vegigmegy az osszes videofajlon sorban.
3. Minden fajl feldolgozasa elott frissiti a JobProgress objektumot (melyik fajl, hanyad).
4. A frame-ciklusban folyamatosan frissiti a fajlon beluli frame-szazalekot.
5. Ha egy fajl kivetelt dob, a feldolgozas a tobbi fajlon folytatodik (hibatureles).
6. A vegeredmeny (osszes talalat fajlonkent) egy dirResultMap-be kerul.

### 12.3 JobProgress modell mezoi
- processed: hany fajl feldolgozasa fejezoddott be.
- total: osszes fajl a konyvtarban.
- framePercent: aktualis fajlon beluli frame-haladast mutato szazalek (0-100).
- currentFile: az eppen feldolgozas alatt levo fajl neve.
- percent: osszetett szazalek = (processed * 100 + framePercent) / total.
- status: RUNNING / DONE / ERROR.
- statusText: ember-olvashatoe allapotszoveg (pl. "Feldolgozas: fajlnev (3 / 10)").
- error: hiba eseten a hibauzenet.

### 12.4 Frontend progress overlay
- A "Kereses az egesz konyvtarban" gomb fetch()-el hivja a POST /search-dir endpointot.
- A progress overlay azonnal megjelenik (fullscreen, modal).
- 400 ms-enkent lekeri a GET /progress/{jobId} JSON valaszt.
- Ket sav jelzi az elorelEpest:
  - Lilaszinu vekony sav: aktualis fajlon beluli frame-haladast mutatja.
  - Zold szazaleksav (szammal): az osszes fajlra vetitett haladas.
- Fajlszamlalo: "X / Y fajl feldolgozva".
- "Megszakitas" gomb: leallitja a pollozast, bezarja az overlayt
  (a backend feldolgozas a hatterben folytatodik, de az eredmeny nem jelenik meg).
- Ha status == DONE: automatikus navigalas a /results/{jobId} oldalra.
- Ha status == ERROR: hiba-alert megjelenik, overlay bezarul.

### 12.5 Tobfajlos eredmenyoldal (results-dir.html)
- Osszesfoglo statisztika-sor: feldolgozott fajlok szama, talalatot tartalmazo fajlok,
  osszes talalat.
- Csak azok a fajlok jelennek meg, amelyekben volt legalaabb egy talalat.
- Minden fajlhoz:
  - Fejlec: fajlnev, talalatszam, video hossza.
  - "Video megnyitasa" gomb: lazarol/becsukas az inline videolejatszo.
  - Talalati kartyak: elonezeti kep, idopont, indoklas, "Ugras a videohoz" gomb.
  - Ugraskor az adott fajl lejatszoja nyilik meg automatikusan, es a megfelelo idopontra ugrik.
