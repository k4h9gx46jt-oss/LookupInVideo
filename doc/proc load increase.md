# CPU Load Increase - Phase 0/1/2 Execution Status

Ez a dokumentum a CPU-kihasznaltsag novelesehez kapcsolodo 0., 1. es 2. fazis aktualis allapotat rogzit.

## Aktualis allapot (a kep alapjan)

- 14 magos gepen kb. 400% Java CPU terheles (kb. 4 teljes mag)
- cel: tartos, stabilan magasabb CPU-kihasznaltsag directory search kozben

## Fazis 0 - KPI es meresi keret

Status: IN_PROGRESS

KPI celok:

- `cpu_avg_pct`: directory search teljes ideje alatti atlag Java `%CPU`
- `cpu_max_pct`: benchmark futas kozbeni csucs `%CPU`
- `elapsed_sec`: teljes feldolgozasi ido
- `throughput_files_per_min`: `processed / elapsed_min`
- `matches_found`: talalt esemenyek darabszama (minoseg regresszio figyeleshez)

Elfogadasi celprofil (elso kor):

- 10-14 parhuzamos szalnal legalabb 700-900% atlag CPU
- ugyanazon dataseten rovidebb vagy legalabb nem rosszabb `elapsed_sec`
- `matches_found` ne essen vissza jelentosen

Kapcsolodo fajlok:

- [tools/perf/run_directory_benchmark.sh](tools/perf/run_directory_benchmark.sh)
- [doc/perf-results/README.md](doc/perf-results/README.md)

## Fazis 1 - Konfiguracios matrix (kodmodositas nelkul)

Status: IN_PROGRESS

Letrehozott profile matrix:

- [tools/perf/config_profiles.csv](tools/perf/config_profiles.csv)

Profilok alkalmazasa:

- [tools/perf/apply_config_profile.sh](tools/perf/apply_config_profile.sh)

Teljes sweep futtatasa:

- [tools/perf/run_config_sweep.sh](tools/perf/run_config_sweep.sh)

Megjegyzes:

- a sweep script szandekosan manualis app-ujrainditast ker profile-onkent, hogy kontrollalt legyen a meres

## Fazis 2 - Bottleneck stage profilozas

Status: IN_PROGRESS

Megvalositott stage-level idomeres a pipeline-ban:

- `grabDecode`
- `convertScaleColor`
- `motion`
- `scoring`
- `preview`
- `progressUpdate`

Erintett kod:

- [src/main/java/com/gazsik/lookupinvideo/service/VideoSearchService.java](src/main/java/com/gazsik/lookupinvideo/service/VideoSearchService.java)

Kapcsolo property:

- [src/main/resources/application.properties](src/main/resources/application.properties)
- `lookup.video.analysis.profile-stages=false`

Ha `true`, akkor a logban egy `ANALYSIS_STAGE_PROFILE` sor jelenik meg videonkent, top stage szazalekokkal.

## Mi keszult el konkretan ebben a korben

- perf script es profil matrix toolkit letrehozva
- stage profiling kod bekotve, defaultban kikapcsolva
- compile rendben (`mvn -q -DskipTests compile`)

## Jelenlegi blokkolo tenyezo a baseline mereshez

- a benchmarkhoz elo szerver kell (`http://localhost:8080`), de az ellenorzeskor nem volt listener a 8080 porton

## Kovetkezo azonnali lepesek (manual app start utan)

1. Alap profile benchmark (p01 vagy current config)
2. Stage profiling bekapcsolasa es 1 kor meres (`profile-stages=true`)
3. 3-5 profile gyors sweep (p02-p06)
4. CSV osszehasonlitas es top 3 bottleneck riport