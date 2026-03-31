#!/bin/bash
set -e

BASE_BUILD_DIR=$1
EXTRA_LIB_DIR=$2

if [ -z "$BASE_BUILD_DIR" ]; then
  echo "Uso: ./build.sh <directorio-build> [directorio-libs-extra]"
  exit 1
fi

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
CONTENT_DIR="$BASE_DIR/container-content"

echo "🧹 Limpiando container-content..."
rm -rf "$CONTENT_DIR"
mkdir -p "$CONTENT_DIR/lib"
mkdir -p "$CONTENT_DIR/resources"
mkdir -p "$CONTENT_DIR/bin"

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

echo "📦 Copiando bin/ (script de arranque)..."
cp -r "$BASE_DIR/bin/"* "$CONTENT_DIR/bin/"

# libs extra opcionales
if [ -n "$EXTRA_LIB_DIR" ]; then
  echo "📦 Copiando jars extra desde $EXTRA_LIB_DIR..."
  find "$EXTRA_LIB_DIR" -name "*.jar" -exec cp {} "$CONTENT_DIR/lib/" \;
fi

echo "🐳 Construyendo imagen Docker..."

docker buildx build \
  --platform linux/amd64 \
  -t caudal:latest \
  -f "$CONTENT_DIR/Dockerfile" \
  "$CONTENT_DIR"

echo "✅ Imagen creada: caudal:latest"