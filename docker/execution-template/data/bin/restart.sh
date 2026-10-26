#!/bin/bash
# Reinicia el contenedor de esta instancia, ej. para que tome cambios en config/main.clj o en
# ext/. Cambios en instance.env (VERSION, PORTS, memoria...) no se aplican asi: para esos hay que
# recrearlo con `docker rm -f <CONTAINER_NAME>` y ./start.sh.

set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/docker-lib.sh"

$DOCKER restart "$CONTAINER_NAME"
