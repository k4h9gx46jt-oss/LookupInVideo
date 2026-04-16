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

## 5. Keresesi logika - pontosan mit csinal?

A szolgaltatas minden mintaponton pontozza a kepkockat, majd kuszob alapjan dont, hogy talalat-e.

### 5.1 Mintavetelezes
- A feldolgozas frame-alapon halad, es idobelyeg szerint mintaz.
- Alapertelmezett mintavetel: 1 masodperc (DEFAULT_SAMPLE_STEP_US = 1_000_000 mikrosec).
- Szarvas/deer modban surubb mintavetel fut: 0.25 masodperc (DEER_SAMPLE_STEP_US = 250_000 mikrosec).
- Minden mintaponton a frame le van skalazva max 320 px szelessegre.
- A pixelekbol ritkitott mintat vesz (x es y iranyban 2 pixelenkent), hogy gyorsabb legyen.

### 5.2 Harom uzemmod
A query normalizalasa utan (kisbetusites + ekezetek elhagyasa) a rendszer eldonti az uzemmodot:

1. Piros mod:
   - Aktiv, ha a query tartalmazza a "piros" vagy "red" szot.
   - Szamol egy redScore-t: a red-dominans pixelek aranyat.
   - Pixel red-dominans, ha:
     - r > 90
     - r > g * 1.25
     - r > b * 1.25
   - Talalat, ha redScore >= 0.12.

2. Szarvas/deer mod:
  - Aktiv, ha a query tartalmazza a "szarvas" vagy "deer" szot.
  - Surubb mintavetellel dolgozik (0.25 mp), hogy a rovid, keresztiranyu mozgast is elkapja.
  - A score tobb jel osszege:
    - mozgas-intenzitas,
    - kozepso savban koncentralt mozgas,
    - oldaliroanyu centroid-elmozdulas,
    - hirtelen mozgas-kitores (burst),
    - lokalizalt (kompakt) mozgas.
  - A talalati idopontot 1.1 mp-cel visszahuzzuk, mert a mozgas-csucs tipikusan kesobb jon, mint amikor az allat belep a kepbe.

3. Mozgas mod (demo alapertelmezett):
  - Minden mas query ide esik.
  - Szamol egy motionScore-t az elozo es aktualis minta kep kulonbsegebol.
  - Talalat, ha motionScore >= 0.18.

### 5.3 Talalatok rendezese
- Eloszor confidence szerint csokkeno sorba rendez.
- Legfeljebb 12 talalatot tart meg.
- Vegul idobelyeg szerint novekvo sorrendben jeleniti meg.

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
- A "piros" query tenylegesen szinalapu.
- A "szarvas/deer" mod egy heurisztikus keresztmozgas-detektor, nem teljes objektumdetektor.
- Minden mas query demo jelleggel mozgas-intenzitas alapu.
- Ujrainditas utan az in-memory videoId->path registry elveszik.
  (A feltoltott fajl marad a diszken, de az elo mapping nem.)

## 9. Fo fajlok es szerepuk
- src/main/java/com/gazsik/lookupinvideo/LookupInVideoApplication.java
  - Spring Boot belepesi pont.
- src/main/java/com/gazsik/lookupinvideo/controller/VideoSearchController.java
  - endpointok: /, /search, /media/{videoId}
- src/main/java/com/gazsik/lookupinvideo/service/VideoSearchService.java
  - tarolas + frame mintavetel + pontozas + talalatkepzes
- src/main/java/com/gazsik/lookupinvideo/model/SceneMatch.java
  - egy talalat adatszerkezete
- src/main/java/com/gazsik/lookupinvideo/model/SearchOutcome.java
  - teljes keresesi eredmeny adatszerkezete
- src/main/resources/templates/index.html
  - feltolto + kereso oldal
- src/main/resources/templates/results.html
  - video + talalati kartyak + ugrasi logika
- src/main/resources/application.properties
  - multipart limitek, tarolasi path, port

## 10. Konfiguracio
application.properties:
- spring.servlet.multipart.max-file-size=1024MB
- spring.servlet.multipart.max-request-size=1024MB
- lookup.video.storage-path=uploads
- server.port=8080

## 11. Tovabbfejlesztesi iranyok
- Valodi objektumfelismeres (pl. auto/kutya/szarvas/kamion) frame vagy clip szinten.
- Jobb indexeles (shot boundary, kulcskocka-kivalasztas).
- Aszinkron feldolgozas hosszabb videokhoz.
- Tartos metadata tarolas (DB), hogy ujrainditas utan is maradjon lookup.
- Talalati timeline es finomabb rangsor.
