#!/bin/bash
set -e

BASE_BUILD_DIR=""
EXTRA_LIB_DIR=""
PUSH=false

# 🧠 parseo de argumentos
for arg in "$@"; do
  case $arg in
    --push)
      PUSH=true
      shift
      ;;
    *)
      if [ -z "$BASE_BUILD_DIR" ]; then
        BASE_BUILD_DIR=$arg
      elif [ -z "$EXTRA_LIB_DIR" ]; then
        EXTRA_LIB_DIR=$arg
      fi
      ;;
  esac
done

if [ -z "$BASE_BUILD_DIR" ]; then
  echo "Uso: ./build.sh <directorio-build> [directorio-libs-extra] [--push]"
  exit 1
fi

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
CONTENT_DIR="$BASE_DIR/container-content"

# 🔥 extraer versión
DIR_NAME=$(basename "$BASE_BUILD_DIR")
VERSION=$(echo "$DIR_NAME" | sed -E 's/.*-(.*)/\1/')

echo "📌 Versión detectada: $VERSION"

echo "🧹 Limpiando container-content..."
rm -rf "$CONTENT_DIR"
mkdir -p "$CONTENT_DIR/lib" "$CONTENT_DIR/resources" "$CONTENT_DIR/bin"

echo "📦 Copiando project.clj..."
cp "$BASE_BUILD_DIR/project.clj" "$CONTENT_DIR/"

echo "📦 Copiando Dockerfile..."
cp "$BASE_DIR/Dockerfile" "$CONTENT_DIR/"

echo "📦 Copiando JAR principal..."
MAIN_JAR=$(ls "$BASE_BUILD_DIR"/lib/caudal-*-standalone.jar 2>/dev/null | head -n 1)

if [ -z "$MAIN_JAR" ]; then
  echo "❌ No se encontró caudal-*-standalone.jar"
  exit 1
fi

cp "$MAIN_JAR" "$CONTENT_DIR/lib/"

echo "📦 Copiando resources..."
cp -r "$BASE_BUILD_DIR/resources/"* "$CONTENT_DIR/resources/" 2>/dev/null || true

echo "📦 Copiando bin/..."
cp -r "$BASE_DIR/bin/"* "$CONTENT_DIR/bin/"

# libs extra
if [ -n "$EXTRA_LIB_DIR" ]; then
  echo "📦 Copiando jars extra desde $EXTRA_LIB_DIR..."
  find "$EXTRA_LIB_DIR" -name "*.jar" -exec cp {} "$CONTENT_DIR/lib/" \;
fi

# 🔥 nombre de imagen (ajústalo a tu Docker Hub)
IMAGE_NAME="quantumlabs/caudal"

echo "🐳 Construyendo imagen..."

if [ "$PUSH" = true ]; then
  echo "🚀 Modo PUSH activado"
  docker buildx build \
    --platform linux/amd64 \
    -t ${IMAGE_NAME}:${VERSION} \
    -f "$CONTENT_DIR/Dockerfile" \
    --push \
    "$CONTENT_DIR"
else
  docker buildx build \
    --platform linux/amd64 \
    -t ${IMAGE_NAME}:${VERSION} \
    -f "$CONTENT_DIR/Dockerfile" \
    --load \
    "$CONTENT_DIR"
fi

echo "✅ Imagen creada:"
echo "   - ${IMAGE_NAME}:${VERSION}"
