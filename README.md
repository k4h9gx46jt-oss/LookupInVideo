# LookupInVideo

Egyszeru, tovabbfejlesztheto Spring Boot webalkalmazas, amellyel videot lehet feltolteni es szoveges keresessel jeleneteket listazni.

## Mit tud most az alapverzio?

- Video feltoltes webes feluleten.
- Szoveges keresesi mezo.
- Talalati idopontok listazasa es ugrasi lehetoseg a videoban.
- "Piros" keresesi kifejezes eseten szinalapu (piros dominancia) jelenetkereses.
- Egyeb kifejezes eseten demo mozgas-intenzitas alapju jelenetrangsor.

## Gyors inditas

1. Lepj a projekt mappaba:
   ```bash
   cd /Users/SEV0A/java/LookupInVideo
   ```
2. Futtathato script engedelyezese (elso alkalommal):
   ```bash
   chmod +x run-videolookup-app.sh run-app.sh
   ```
3. Inditas scripttel:
   ```bash
   ./run-videolookup-app.sh
   ```
   vagy roviden:
   ```bash
   ./run-app.sh
   ```
4. Nyisd meg bongeszoben:
   - http://localhost:8080

## Port modositas

Ha masik porton szeretned futtatni:

```bash
PORT=8090 ./run-app.sh
```

Ekkor a cim: http://localhost:8090

## Build-only ellenorzes (szerver inditas nelkul)

```bash
./run-app.sh --build-only
```

## Alternativ inditas Maven-nel

Ha script nelkul inditanad:

```bash
./mvnw spring-boot:run
```

Ha nincs `mvnw`, hasznalhatsz telepitett Mavent:

```bash
mvn spring-boot:run
```

## Tovabbfejlesztesi iranyok

- Objektumfelismeres beemelese (pl. YOLO/CLIP) kutya, szarvas, kamion, auto kulcsszavakhoz.
- Talalatok pontozasanak finomitasa es timeline-nezet.
- Aszinkron feldolgozas hosszabb videokhoz.
- S3/Azure Blob tarolas, ha nem lokalis geprol fut.

## GitHub szinkron javaslat

Ha a GitHub felhasznaloneved `GazsikJozsef`, akkor:

```bash
git init
git add .
git commit -m "Initial LookupInVideo MVP"
git branch -M main
git remote add origin https://github.com/GazsikJozsef/LookupInVideo.git
git push -u origin main
```

Ha mar letezik a repo, ez rogton fel tudja tolteni az alapverziot.
# LookUpVideo
# LookUpVideo
