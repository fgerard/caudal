#! /bin/bash

set -euo pipefail

OUTPUT="--load"
EXTRA_ARGS=""

print_help() {
  echo "Usage: $0 [OPTIONS]"
  echo ""
  echo "Build quantumlabs/caudal docker image using buildx."
  echo "Compiles the dashboard (shadow-cljs release) and the uberjar first, so the image always"
  echo "carries a jar built from this checkout. The version comes from project.clj."
  echo ""
  echo "Options:"
  echo "  --push              Build linux/amd64 + linux/arm64 and push to Docker Hub."
  echo "                      Without it, builds only for this host's arch and loads it locally."
  echo "  --args '<args>'     Extra arguments to pass to docker buildx build (e.g. '--no-cache')"
  echo "  -h, --help          Show this help message"
  echo ""
  echo "Examples:"
  echo "  $0"
  echo "  $0 --push"
  echo "  $0 --args '--no-cache'"
}

PUSH=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --push)
      PUSH=true
      OUTPUT="--push"
      shift
      ;;
    --args)
      EXTRA_ARGS="$2"
      shift 2
      ;;
    -h|--help)
      print_help
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "" >&2
      print_help >&2
      exit 1
      ;;
  esac
done

# Este script vive en docker/bin, la raiz del proyecto esta dos niveles arriba.
ROOT_PATH=$( cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd )
cd "$ROOT_PATH"

VERSION=$( grep -m1 'defproject' project.clj | awk '{print $3}' | tr -d '"' )
if [ -z "$VERSION" ]; then
  echo "No se pudo leer la version de project.clj" >&2
  exit 1
fi
echo "Version (project.clj): $VERSION"

# Orden importante: el JS tiene que existir en disco antes de lein uberjar, y nada en medio
# debe borrarlo (lein clean solo borra :clean-targets, que ya no incluye js/compiled).
echo "Compilando uberjar..."
lein clean
if [ ! -d node_modules ]; then
  npm ci
fi
npx shadow-cljs release dashboard
lein uberjar

JAR="target/caudal-${VERSION}-standalone.jar"
if [ ! -f "$JAR" ]; then
  echo "No se encontro $JAR" >&2
  exit 1
fi
# Sin grep -q: con pipefail, grep -q cierra el pipe al primer match y unzip muere por SIGPIPE.
if ! unzip -l "$JAR" | grep 'public/js/compiled/dashboard-ui.js' >/dev/null; then
  echo "$JAR no trae public/js/compiled/dashboard-ui.js: el dashboard saldria sin JS" >&2
  exit 1
fi

# El jar no depende de la arquitectura, solo la imagen base: con --push se publican ambas.
# --load no soporta manifests multi-plataforma, asi que en local solo se construye la del host.
PLATFORM_ARGS=()
if [ "$PUSH" = true ]; then
  PLATFORM_ARGS=( --platform linux/amd64,linux/arm64 )
fi

echo "Building Docker image..."
DOCKER_BUILDKIT=1 docker buildx build -t "quantumlabs/caudal:${VERSION}" \
  ${PLATFORM_ARGS[@]+"${PLATFORM_ARGS[@]}"} \
  --build-arg VERSION="$VERSION" \
  $OUTPUT \
  $EXTRA_ARGS \
  -f "$ROOT_PATH/docker/Dockerfile" \
  "$ROOT_PATH"

echo "Imagen: quantumlabs/caudal:${VERSION}"
