#!/bin/bash

set -euo pipefail

# Baja la imagen de caudal y extrae el template de ejecucion que trae adentro (start.sh, bin/,
# config/, README.md -- ver docker/Dockerfile) a un directorio de datos. Un host nuevo solo
# necesita Docker y este script, copiado a mano.
#
# Uso (una instancia por directorio; puede haber varias en el mismo host):
#   mkdir -p /opt/quantum/caudal-<nombre>/data && cd /opt/quantum/caudal-<nombre>/data
#   (copiar aqui bootstrap.sh)
#   ./bootstrap.sh --version <version>
#
# Despues: editar config/instance.env (CONTAINER_NAME y PORTS unicos en el host, usuario, TZ...)
# y config/main.clj, y correr ./start.sh. README.md documenta cada archivo.

IMAGE_NAME=caudal
VERSION=""
FORCE=false
PULL=true

if [ -z "${1:-}" ] || [[ "$1" == -* ]]; then
  DATA=$( cd "$(dirname "${BASH_SOURCE[0]}")" && pwd )
else
  mkdir -p "$1"
  DATA=$( cd "$1" && pwd )
  shift
fi

usage() {
    local exit_code="${1:-1}"
    {
        echo "Usage: $0 [data_dir] --version <ver> [--force | -f] [--no-pull] [--help | -h]"
        echo
        echo "Arguments:"
        echo "  data_dir           Optional. Directory to extract start.sh, bin/ and config/ into."
        echo "                     Defaults to this script's own directory."
        echo "  --version, -v      Required. Image version to pull (quantumlabs/$IMAGE_NAME:<ver>)."
        echo "  --force, -f        Overwrite start.sh, bin/ and README.md if they already exist."
        echo "                     Files in config/ are never overwritten, only added if missing."
        echo "  --no-pull          Use the image already on this host (e.g. loaded with docker load"
        echo "                     on a host without internet) instead of pulling it."
        echo "  --help, -h         Show help."
    } >&2
    exit "$exit_code"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h)
            usage 0
            ;;
        --version|-v)
            VERSION="${2:?Missing value for $1}"
            shift 2
            ;;
        --force|-f)
            FORCE=true
            shift
            ;;
        --no-pull)
            PULL=false
            shift
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage
            ;;
    esac
done

if [ -z "$VERSION" ]; then
    echo "Error: --version is required (the quantumlabs/caudal tag to install)." >&2
    usage
fi

if [ "$FORCE" != true ]; then
    for existing in "$DATA/start.sh" "$DATA/bin"; do
        if [ -e "$existing" ]; then
            echo "Error: $existing already exists. Refusing to overwrite an existing setup." >&2
            echo "Pass --force to update start.sh, bin/ and README.md (config/ is never overwritten)." >&2
            exit 1
        fi
    done
fi

resolve_docker_cmd() {
  case "$( uname -s )" in
    Darwin*)
      echo "docker"
      ;;
    Linux*)
      if groups | grep -q "docker"; then
        echo "docker"
      elif groups | grep -q "sudo"; then
        echo "sudo docker"
      else
        echo "Unable to run docker, $USER must be added to group docker or sudo" >&2
        exit 1
      fi
      ;;
    *)
      echo "Your OS $( uname -s ) is not supported by this script" >&2
      exit 1
      ;;
  esac
}

DOCKER=$( resolve_docker_cmd )

if [ "$PULL" = true ]; then
  echo "Pulling quantumlabs/$IMAGE_NAME:$VERSION ..."
  $DOCKER pull "quantumlabs/$IMAGE_NAME:$VERSION"
fi

CID=$($DOCKER create "quantumlabs/$IMAGE_NAME:$VERSION")
STAGING="$DATA/.template-extract"
cleanup() {
  $DOCKER rm -f "$CID" >/dev/null 2>&1 || true
  rm -rf "$STAGING"
}
trap cleanup EXIT

rm -rf "$STAGING"
mkdir -p "$STAGING"
$DOCKER cp "$CID:/opt/quantumlabs/caudal/execution-template/." "$STAGING"

echo "Extracting execution-template into $DATA ..."
rm -rf "$DATA/bin"
cp -R "$STAGING/bin" "$DATA/bin"
cp "$STAGING/start.sh" "$STAGING/README.md" "$DATA/"
chmod +x "$DATA"/bin/*.sh "$DATA/start.sh"

mkdir -p "$DATA/config"
for f in "$STAGING"/config/*; do
  name=$( basename "$f" )
  if [ -e "$DATA/config/$name" ]; then
    echo "config/$name already present, not touched."
  else
    cp "$f" "$DATA/config/$name"
  fi
done

mkdir -p "$DATA/logs" "$DATA/relevantes" "$DATA/ext"

echo ""
echo "Done. See $DATA/README.md for what each file holds. Next steps:"
echo "  1. Edit $DATA/config/instance.env: CONTAINER_NAME and PORTS must not clash with another"
echo "     caudal on this host, TARGET_USER/TARGET_GROUP must exist and own $DATA."
echo "  2. Put this instance's config in $DATA/config/main.clj (log4j2.xml next to it), and any"
echo "     extra jars in $DATA/ext/."
echo "  3. Run $DATA/start.sh."
