#!/bin/bash

set -euo pipefail

if [ -z "${1:-}" ]; then
  DATA=$( cd "$(dirname "${BASH_SOURCE[0]}")" && pwd )
  echo "No data directory specified, using default: $DATA"
else
  DATA=$( cd "$1" && pwd )
  echo "Using data directory: $DATA"
fi

# VERSION, IMAGE_NAME, CONTAINER_NAME, TARGET_USER, TARGET_GROUP, TZ, PORTS, etc. viven en
# instance.env para que este script y los de bin/ tengan una sola fuente de verdad.
INSTANCE_ENV_PATH="$DATA/config/instance.env"
if [ ! -r "$INSTANCE_ENV_PATH" ]; then
  echo "Error: $INSTANCE_ENV_PATH not found. Run bootstrap.sh first." >&2
  exit 1
fi
source "$INSTANCE_ENV_PATH"

# gid de un grupo del host. getent no existe en macOS; ahi se usa dscl.
group_gid() {
  if command -v getent >/dev/null 2>&1; then
    getent group "$1" | cut -d: -f3
  elif command -v dscl >/dev/null 2>&1; then
    dscl . -read "/Groups/$1" PrimaryGroupID 2>/dev/null | awk '{print $2}'
  fi
}

# Corre como TARGET_USER:TARGET_GROUP. Ambos deben existir en el host: el contenedor corre con
# su uid:gid numerico (via -u) para que lo que escribe en data/ (logs, relevantes...) sea de una
# cuenta real del host. Si falta alguno, se falla en lugar de correr como root.
if ! id "$TARGET_USER" >/dev/null 2>&1; then
  echo "Error: user '$TARGET_USER' does not exist on this host. Edit TARGET_USER in $INSTANCE_ENV_PATH (or create the user) before continuing." >&2
  exit 1
fi
TARGET_UID=$(id -u "$TARGET_USER")
TARGET_GID=$(group_gid "$TARGET_GROUP")
if [ -z "$TARGET_GID" ]; then
  echo "Error: group '$TARGET_GROUP' does not exist on this host. Edit TARGET_GROUP in $INSTANCE_ENV_PATH (or create the group) before continuing." >&2
  exit 1
fi
echo "Setting container user to $TARGET_USER:$TARGET_GROUP ($TARGET_UID:$TARGET_GID)"

mkdir -p "$DATA/logs" "$DATA/relevantes" "$DATA/ext"

# Arreglo (no string) para que rutas con espacios no se rompan al expandirse.
DOCKER_ARGS=(
            --name="$CONTAINER_NAME"
            -u "$TARGET_UID:$TARGET_GID"
            --add-host=host.docker.internal:host-gateway
            --restart=always
            --log-opt max-size=10m
            --log-opt max-file=3
            -e TZ="$TZ"
            -e TIMEZONE="$TZ"
            -e MINIMUM_MEMORY="$MINIMUM_MEMORY"
            -e MAXIMUM_MEMORY="$MAXIMUM_MEMORY"
            -v "$DATA:/opt/quantumlabs/caudal/data"
)

if [ -n "${NETWORK:-}" ]; then
  DOCKER_ARGS+=( --network="$NETWORK" )
fi

# Los PORTS siempre se publican en loopback. BIND_EXTRA_IPS (instance.env) agrega IPs del host
# donde tambien se publican; ausente o vacia = solo loopback. Tienen que ser IPs literales:
# docker las pasa por net.ParseIP y rechaza nombres de /etc/hosts.
# El bind por IP es lo unico que de verdad limita quien alcanza los puertos: los puertos
# publicados por docker se DNATean en PREROUTING y pasan por FORWARD, no por INPUT, asi que
# ufw/iptables -INPUT NO los filtra. Por eso 0.0.0.0 aqui (permitido, no validamos) = abiertos
# a internet.
for PORT_SPEC in ${PORTS//,/ }; do
  PORT=${PORT_SPEC%%/*}
  PROTO=""
  if [[ "$PORT_SPEC" == */* ]]; then
    PROTO="/${PORT_SPEC#*/}"
  fi
  DOCKER_ARGS+=( -p "127.0.0.1:${PORT}:${PORT}${PROTO}" )
  for BIND_IP in ${BIND_EXTRA_IPS:+${BIND_EXTRA_IPS//,/ }}; do
    echo "Publicando ademas en $BIND_IP:$PORT_SPEC"
    DOCKER_ARGS+=( -p "${BIND_IP}:${PORT}:${PORT}${PROTO}" )
  done
done

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

check_network() {
  if ! $@ network inspect "$NETWORK" >/dev/null 2>&1; then
    echo "Creating $NETWORK ..."
    $@ network create --driver=bridge "$NETWORK"
  fi
}

run_docker_container() {
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
    if groups | grep -q "docker"; then
      DOCKER="docker"
    elif groups | grep -q "sudo"; then
      DOCKER="sudo docker"
    else
      echo "Unable to run docker, $USER must be added to admin docker or sudo"
      exit 1
    fi
  fi
  if test "$DOCKER" = ""; then
    echo "Your OS $OS is not supported by this script"
    echo "Try to execute the following command to start $CONTAINER_NAME container:"
    echo "docker run -d ${DOCKER_ARGS[*]} quantumlabs/$IMAGE_NAME:$VERSION"
    exit 1
  fi
  if $DOCKER container inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    echo "Error: a container named $CONTAINER_NAME already exists." >&2
    echo "If it is this instance (e.g. after changing VERSION), remove it with: $DOCKER rm -f $CONTAINER_NAME" >&2
    echo "If it is another instance, change CONTAINER_NAME in $INSTANCE_ENV_PATH." >&2
    exit 1
  fi
  if [ -n "${NETWORK:-}" ]; then
    check_network $DOCKER
  fi
  echo "Running command: $DOCKER run -d ${DOCKER_ARGS[*]} quantumlabs/$IMAGE_NAME:$VERSION"
  $DOCKER run -d "${DOCKER_ARGS[@]}" quantumlabs/$IMAGE_NAME:$VERSION
}

run_docker_container
