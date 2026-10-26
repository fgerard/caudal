#!/bin/bash
# Comun a los bin/*.sh: carga config/instance.env de esta instancia (el data/ padre de bin/) y
# resuelve como invocar docker en este host. Cada script lo carga con `source`.

DATA="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTANCE_ENV_PATH="$DATA/config/instance.env"
if [ ! -r "$INSTANCE_ENV_PATH" ]; then
  echo "Error: $INSTANCE_ENV_PATH not found." >&2
  exit 1
fi
source "$INSTANCE_ENV_PATH"

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
