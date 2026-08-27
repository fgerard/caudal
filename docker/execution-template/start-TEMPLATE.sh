#!/bin/bash
# Corre en el servidor destino. Se autoubica y monta su propio directorio
# (este mismo, data/) como el volumen de datos del contenedor -- copiar
# execution-template/data/ a donde sea en el servidor y correr ./start.sh
# ahi alcanza, sin depender de un path fijo.
set -e

DATA=$( cd $( dirname ${BASH_SOURCE[0]} ) && pwd )

# VERSION, IMAGE_NAME, CONTAINER_NAME, TIMEZONE, MINIMUM_MEMORY,
# MAXIMUM_MEMORY, TCP_PORT, HTTP_PORT y BIND_EXTRA_IPS viven en
# config-plc/instance.env para que este script tenga una sola fuente de
# verdad, editable sin tocar start.sh. create-data-dir.sh reescribe
# "config-plc" -> "config-<sufijo>" al generar este script para una
# instancia real, y sincroniza VERSION/IMAGE_NAME en ese instance.env.
INSTANCE_ENV_PATH="$DATA/config-plc/instance.env"
if [ ! -r "$INSTANCE_ENV_PATH" ]; then
  echo "Error: $INSTANCE_ENV_PATH no encontrado." >&2
  exit 1
fi
source "$INSTANCE_ENV_PATH"

check_os() {
  UNAME=$( uname -s )
  case "${UNAME}" in
    Linux*)     OS=Linux;;
    Darwin*)    OS=Mac;;
    CYGWIN*)    OS=Cygwin;;
    MINGW*)     OS=MinGw;;
    *)          OS="UNKNOWN:${UNAME}"
  esac
  echo $OS
}

OS=$( check_os )
DOCKER=""
if test "$OS" = "Mac"; then
  if ! groups | grep -q "admin"; then
    echo "Unable to run docker, $USER must be added to admin group"
    exit 1
  fi
  DOCKER="docker"
fi
if test "$OS" = "Linux"; then
  if ! groups | grep -q "docker"; then
    if ! groups | grep -q "sudo"; then
      echo "Unable to run docker, $USER must be added to admin docker or sudo"
      exit 1
    else
      DOCKER="sudo docker"
    fi
  else
    DOCKER="docker"
  fi
fi
if test "$DOCKER" = ""; then
  echo "Your OS $OS is not supported by this script"
  exit 1
fi

# Arreglo (no string) para que rutas con espacios no se rompan al expandirse.
# TCP_PORT/HTTP_PORT siempre se publican en loopback; BIND_EXTRA_IPS
# (instance.env) agrega IPs adicionales del host abajo.
DOCKER_ARGS=(
              --name="$CONTAINER_NAME"
              --restart=always
              -e TIMEZONE="$TIMEZONE"
              -e MINIMUM_MEMORY="$MINIMUM_MEMORY"
              -e MAXIMUM_MEMORY="$MAXIMUM_MEMORY"
              -e CAUDAL_CONFIG=/opt/quantumlabs/caudal/data/config-plc/main.clj
              -e CAUDAL_DATA=/opt/quantumlabs/caudal/data
              -v "$DATA:/opt/quantumlabs/caudal/data"
              -p "127.0.0.1:${TCP_PORT}:${TCP_PORT}"
              -p "127.0.0.1:${HTTP_PORT}:${HTTP_PORT}"
)

# El bind por IP es lo unico que de verdad limita quien alcanza la API: los
# puertos publicados por docker se DNATean en PREROUTING y pasan por
# FORWARD, no por INPUT, asi que ufw/iptables -INPUT NO los filtra. Por eso
# 0.0.0.0 aqui (permitido, no validamos) = API abierta a internet.
for BIND_IP in ${BIND_EXTRA_IPS:+${BIND_EXTRA_IPS//,/ }}; do
  echo "Publicando ademas en $BIND_IP:$TCP_PORT y $BIND_IP:$HTTP_PORT"
  DOCKER_ARGS+=( -p "${BIND_IP}:${TCP_PORT}:${TCP_PORT}" -p "${BIND_IP}:${HTTP_PORT}:${HTTP_PORT}" )
done

$DOCKER run -d "${DOCKER_ARGS[@]}" quantumlabs/$IMAGE_NAME:$VERSION
