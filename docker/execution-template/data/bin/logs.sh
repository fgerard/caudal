#!/bin/bash
# Sigue la salida (stdout de caudal, segun config/log4j2.xml) del contenedor de esta instancia.
# Argumentos extra van directo a `docker logs`, ej. ./logs.sh --since 1h

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/docker-lib.sh"

if [ $# -eq 0 ]; then
  set -- -f --tail 200
fi
exec $DOCKER logs "$@" "$CONTAINER_NAME"
