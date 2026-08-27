#!/bin/bash
# Verifica que el contenedor de esta instancia responde en su HTTP_PORT
# (dashboard web de caudal). Se copia junto con data/ a cada instancia via
# create-data-dir.sh -- pensado para cron/monitoreo en el servidor destino.
set -e

DATA=$( cd $( dirname ${BASH_SOURCE[0]} )/.. && pwd )
source "$DATA"/config-*/instance.env

curl -fsS "http://127.0.0.1:${HTTP_PORT}/" > /dev/null && echo "OK: $CONTAINER_NAME responde en :${HTTP_PORT}"
