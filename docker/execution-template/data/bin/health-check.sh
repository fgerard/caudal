#!/bin/bash
# Verifica que el contenedor de esta instancia esta corriendo y, si instance.env trae HTTP_PORT,
# que responde por HTTP en 127.0.0.1. Sale con codigo != 0 si algo falla, para usarlo desde
# cron/monitoreo.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/docker-lib.sh"

if ! STATE=$( $DOCKER container inspect -f '{{.State.Status}} restarts={{.RestartCount}} since={{.State.StartedAt}}' "$CONTAINER_NAME" 2>/dev/null ); then
  echo "FAIL: $CONTAINER_NAME no existe (corre ./start.sh)"
  exit 1
fi
if [[ "$STATE" != running* ]]; then
  echo "FAIL: $CONTAINER_NAME $STATE"
  exit 1
fi
echo "OK: $CONTAINER_NAME $STATE"

if [ -n "${HTTP_PORT:-}" ]; then
  if ! curl -fsS --connect-timeout 10 -o /dev/null "http://127.0.0.1:${HTTP_PORT}/"; then
    echo "FAIL: $CONTAINER_NAME no responde en 127.0.0.1:${HTTP_PORT}"
    exit 1
  fi
  echo "OK: $CONTAINER_NAME responde en 127.0.0.1:${HTTP_PORT}"
fi
