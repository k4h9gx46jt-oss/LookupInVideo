#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PORT="${PORT:-8080}"
BUILD_ONLY=false

if [[ "${1:-}" == "--build-only" ]]; then
  BUILD_ONLY=true
fi

echo "[LookupInVideo] Build indul..."
./mvnw -DskipTests compile

echo "[LookupInVideo] Build kesz."
if [[ "$BUILD_ONLY" == true ]]; then
  echo "[LookupInVideo] Build-only mod aktiv, kilepes."
  exit 0
fi

echo "[LookupInVideo] Alkalmazas indul: http://localhost:${PORT}"
exec ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xms28g -Xmx28g -Dserver.port=${PORT}"
