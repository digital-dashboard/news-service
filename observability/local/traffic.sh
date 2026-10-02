#!/usr/bin/env bash
# Generates a little traffic against the local Gate A Argus (127.0.0.1 only).
# Usage: ./traffic.sh [rounds]   (default 20)
set -euo pipefail

BASE_URL="http://127.0.0.1:8080"
ROUNDS="${1:-20}"

hit() {
  curl -s -o /dev/null -w "%{http_code} $1\n" "${BASE_URL}$1"
}

for round in $(seq 1 "${ROUNDS}"); do
  hit "/news/v2/feeds"
  hit "/news/v2/does-not-exist/${round}"
  hit "/news/v2/sources/${round}"
  hit "/actuator/health"
  hit "/actuator/health/liveness"
  hit "/actuator/health/readiness"
  hit "/actuator/prometheus" >/dev/null
  sleep 0.5
done
